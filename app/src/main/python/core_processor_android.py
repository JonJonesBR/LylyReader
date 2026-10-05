"""
core_processor_android.py - Processamento principal para Android.

Diferenças em relação ao core_processor.py original:
  - Sem Telegram: sem query/context/editar_mensagem_safe/InlineKeyboard
  - Progresso entregue via callback Python (chamado pelo Kotlin)
  - user_id fixo = 0 (app single-user)
  - Formato de saída: apenas MP3 (sem vídeo)
  - cancelar() pode ser chamado do Kotlin a qualquer momento
"""

import asyncio
import hashlib
import json
import os
import shutil
import time
from pathlib import Path
from typing import Callable, Optional

from mutagen.mp3 import MP3 as _MutagenMP3

import config_android as _config
import pronunciation_dict
from config_android import (
    TEMP_DIR, CHUNK_LIMITE, logger, bot_state, CONFIG, TEXTO_PRONTO
)
from utils import (
    formatar_duracao, dividir_em_sentencas, dividir_em_capitulos,
    garantir_temp_dir, ProgressTracker, aplicar_intervalo, ler_texto_pronto,
    extrair_texto_limpo, contar_estatisticas, estimar_duracao,
)
from tts import (
    converter_tts_auto, concatenar_grupo_com_pausa, aplicar_timbre,
    adicionar_metadados_mp3, adicionar_chapter_marks,
    agrupar_blocos_por_limite, obter_duracao_ffprobe, validar_voz_tts,
)
from cover_art import obter_capa
from smart_cleaner import normalize_blacklist
from speaker_segments import build_speaker_chunk_plan

# Tipo do callback de progresso:  callback(pct: int, mensagem: str)
ProgressCallback = Callable[[int, str], None]

# Flag global de cancelamento (setada pelo Kotlin via cancelar())
_cancelar_flag = False

def cancelar():
    """Chamado pelo Kotlin quando o usuário aperta 'Cancelar'."""
    global _cancelar_flag
    _cancelar_flag = True

def _reset_cancelar():
    global _cancelar_flag
    _cancelar_flag = False

def _dica_erro_elevenlabs() -> str:
    """Dica de erro final com o motivo real propagado pela ponte Kotlin — falha de rede não
    pode ser rotulada como chave inválida. Sem motivo disponível, mantém a dica genérica."""
    try:
        from com.jonjonesbr.audiobookgen.tts import ElevenLabsSynthBridge
        motivo = str(ElevenLabsSynthBridge.ultimoMotivo() or "").strip()
        if motivo:
            return f"ElevenLabs: {motivo}"
    except Exception:
        pass
    return "Verifique a chave ElevenLabs em Configurações."


# ── Helpers de progresso/job ──────────────────────────────────────────────────

def _job_hash(texto: str, voz: str, estilo: str, voice_plan: Optional[list] = None) -> str:
    mapping = json.dumps(voice_plan, ensure_ascii=False, separators=(",", ":")) if voice_plan else ""
    # A assinatura do dicionário de pronúncia entra no hash: mudá-lo não reaproveita áudio de um job antigo.
    pronuncias = pronunciation_dict.assinatura()
    return hashlib.sha256(f"{texto[:500]}:{voz}:{estilo}:{mapping}:{pronuncias}".encode()).hexdigest()[:16]

def _job_dir(job_hash: str) -> str:
    return os.path.join(TEMP_DIR, f"job_{job_hash}")

def _carregar_progresso(jh: str) -> set:
    pf = os.path.join(_job_dir(jh), "progress.json")
    if os.path.exists(pf):
        try:
            with open(pf) as f:
                return set(json.load(f).get("completed", []))
        except Exception:
            pass
    return set()

def _salvar_progresso(jh: str, completed: set):
    pf = os.path.join(_job_dir(jh), "progress.json")
    try:
        with open(pf, "w") as f:
            json.dump({"completed": sorted(completed)}, f)
    except Exception:
        pass

def _limpar_job(jh: str):
    d = _job_dir(jh)
    if os.path.exists(d):
        shutil.rmtree(d, ignore_errors=True)


def _chunks_falhos(total: int, completed_valid: set, temp_files: list) -> list:
    """Retorna índices de chunks que ainda precisam ser convertidos."""
    return [
        i for i in range(total)
        if i not in completed_valid
        or not os.path.exists(temp_files[i])
        or os.path.getsize(temp_files[i]) <= 500
    ]


def _titulo_de_capitulo(texto: str, idx: int) -> str:
    """Extrai o título de um capítulo da primeira linha não-vazia do texto."""
    for linha in texto.strip().splitlines():
        linha = linha.strip()
        if linha:
            return linha[:80] if len(linha) <= 80 else linha[:77] + "…"
    return f"Capítulo {idx + 1}"


def _montar_capitulos_info(texto: str, chunk_limite: int):
    """Divide o texto em capítulos e mapeia cada um ao intervalo de chunks que vai gerar.

    Retorna (capitulos_info, limites_chunks, total_chunks_estimado, total_caps) quando há
    mais de um capítulo detectável, ou None caso contrário. Usado para (a) progresso por
    capítulo no callback e (b) chapter marks no MP3 (via adicionar_chapter_marks).
    """
    capitulos = dividir_em_capitulos(texto)
    total_caps = len(capitulos)
    if total_caps <= 1:
        return None

    acumulado = 0
    limites_chunks: list = []
    capitulos_info_final: list = []
    for i, cap_texto in enumerate(capitulos):
        n = max(len(dividir_em_sentencas(cap_texto, limite=chunk_limite)), 1)
        limites_chunks.append((acumulado, acumulado + n))
        capitulos_info_final.append({
            "titulo": _titulo_de_capitulo(cap_texto, i),
            "inicio_chunk": acumulado,
            "fim_chunk": acumulado + n,
        })
        acumulado += n
    return capitulos_info_final, limites_chunks, max(acumulado, 1), total_caps


def _calcular_timestamps_capitulos(
    capitulos_info: list, temp_files: list, arquivos_validos: list, saida_mp3: str
) -> list:
    """
    Calcula os timestamps reais de cada capítulo.

    Lê a duração de cada chunk via mutagen, deriva a pausa efetiva
    (total_final − Σchunks) / (n−1) e mapeia chunk -> posição no áudio final.
    Retorna lista de {"titulo", "inicio_ms", "fim_ms"} pronta para
    adicionar_chapter_marks().
    """
    # Duração de cada chunk válido
    chunk_dur_map: dict = {}
    for f in temp_files:
        if os.path.exists(f) and os.path.getsize(f) > 500:
            try:
                # Detecta WAV pelo magic bytes (Gemini sem FFmpeg salva WAV como .mp3)
                with open(f, 'rb') as _fh:
                    magic = _fh.read(4)
                if magic == b'RIFF':
                    # Duração WAV: (tamanho_data) / (sample_rate * canais * bytes_por_sample)
                    with open(f, 'rb') as _fh:
                        _fh.seek(28)  # ByteRate field offset
                        byte_rate = int.from_bytes(_fh.read(4), 'little')
                    if byte_rate > 0:
                        chunk_dur_map[f] = int(os.path.getsize(f) * 1000 / byte_rate)
                    else:
                        chunk_dur_map[f] = 0
                else:
                    chunk_dur_map[f] = int(_MutagenMP3(f).info.length * 1000)
            except Exception:
                chunk_dur_map[f] = 0

    # Ordem real de concatenação
    concat_order = [(f, chunk_dur_map.get(f, 0)) for f in arquivos_validos]
    if not concat_order:
        return []

    # Duração total do arquivo final (para derivar pausas reais)
    try:
        total_ms = int(_MutagenMP3(saida_mp3).info.length * 1000)
    except Exception:
        total_ms = sum(d for _, d in concat_order)

    sum_dur = sum(d for _, d in concat_order)
    n_gaps = max(len(concat_order) - 1, 1)
    pausa_real_ms = max(0, (total_ms - sum_dur) // n_gaps)

    # Timestamp de início de cada chunk no arquivo final
    chunk_start_times: dict = {}
    t = 0
    for pos, (f, dur) in enumerate(concat_order):
        chunk_start_times[f] = t
        t += dur
        if pos < len(concat_order) - 1:
            t += pausa_real_ms

    caps_com_ts = []
    for cap in capitulos_info:
        cap_chunks = [
            temp_files[i]
            for i in range(cap["inicio_chunk"], cap["fim_chunk"])
            if i < len(temp_files) and temp_files[i] in chunk_start_times
        ]
        if not cap_chunks:
            continue
        inicio_ms = chunk_start_times[cap_chunks[0]]
        ultimo = cap_chunks[-1]
        fim_ms = chunk_start_times[ultimo] + chunk_dur_map.get(ultimo, 0)
        caps_com_ts.append({
            "titulo": cap["titulo"],
            "inicio_ms": inicio_ms,
            "fim_ms": fim_ms,
        })

    if caps_com_ts:
        caps_com_ts[-1]["fim_ms"] = total_ms

    return caps_com_ts


# ── Função principal ──────────────────────────────────────────────────────────

async def processar(
    texto: str,
    voz: str,
    velocidade: str = "+0%",
    tom: str = "+0Hz",
    estilo: str = "padrao",
    titulo: str = "Audiobook",
    saida_mp3: str = "",          # caminho completo do MP3 final; gerado automaticamente se vazio
    progress_cb: Optional[ProgressCallback] = None,
    capitulos_info: Optional[list] = None,  # [{"titulo", "inicio_chunk", "fim_chunk"}]
    blacklist=None,
    speaker_map_path: Optional[str] = None,
) -> dict:
    """
    Converte texto em audiobook MP3.

    Retorna:
        {"ok": True,  "caminho": "/path/to/file.mp3", "duracao": "1h 23min"}
        {"ok": False, "erro": "mensagem de erro"}
    """
    _reset_cancelar()
    garantir_temp_dir()

    blacklist_list = normalize_blacklist(blacklist)

    def _cb(pct: int, msg: str):
        if progress_cb:
            try:
                progress_cb(pct, msg)
            except Exception:
                pass

    # Validar voz Edge antes de começar. Gemini e Android evitam quota e latência extra.
    motor_ativo = _config.CONFIG.get("motor_tts", _config.MOTOR_TTS_PADRAO)
    if motor_ativo == "edge":
        _cb(0, "Testando motor de voz...")
        if not await validar_voz_tts(voz, velocidade, tom):
            return {"ok": False, "erro": f"Voz '{voz}' indisponível. Verifique a conexão."}

    def _transformar_segmento(segmento: str) -> str:
        if blacklist_list:
            import re
            for term in blacklist_list:
                segmento = re.sub(re.escape(term), "", segmento, flags=re.IGNORECASE)
            segmento = re.sub(r"[ \t]{2,}", " ", segmento).strip()
        if estilo and estilo != "padrao":
            try:
                from text_processor import NarrationStyler
                segmento = NarrationStyler.apply(segmento, estilo)
            except Exception as e:
                logger.warning(f"NarrationStyler falhou ({e}), usando texto original.")
        return segmento

    attribution = None
    if speaker_map_path and os.path.isfile(speaker_map_path):
        try:
            with open(speaker_map_path, "r", encoding="utf-8") as speaker_map_file:
                attribution = json.load(speaker_map_file)
        except Exception as e:
            logger.warning(f"Mapa de personagens indisponível; usando a voz do narrador ({e}).")

    speaker_plan = None
    if attribution:
        try:
            speaker_plan = build_speaker_chunk_plan(
                texto,
                attribution,
                voz,
                _config.get_chunk_limite(),
                transform_text=_transformar_segmento
            )
        except Exception as e:
            logger.warning(f"Não foi possível aplicar o mapa de personagens: {e}")

    if attribution and not speaker_plan:
        logger.warning("Mapa de personagens não confere com o texto; o audiobook usará só a voz do narrador.")
        _cb(0, "Vozes dos personagens indisponíveis para este texto; usando só o narrador.")

    if speaker_plan:
        chunks = speaker_plan["chunks"]
        chunk_voices = speaker_plan["voices"]
        chunk_pauses = speaker_plan.get("pauses")
        if speaker_plan["chapters"]:
            capitulos_info = speaker_plan["chapters"]
        if progress_cb and len(speaker_plan["chapters"]) > 1:
            callback_original = progress_cb
            limites = speaker_plan["chapter_limits"]
            total_caps = len(limites)
            total_estimado = max(len(chunks), 1)

            def cb_com_falantes(pct: int, msg: str) -> None:
                chunk_estimado = int(pct / 100 * total_estimado)
                cap_num = total_caps
                for i, (ini, fim) in enumerate(limites):
                    if chunk_estimado < fim:
                        cap_num = i + 1
                        break
                try:
                    callback_original(pct, f"Capítulo {cap_num} de {total_caps}\n{msg}")
                except Exception:
                    pass

            progress_cb = cb_com_falantes
    else:
        texto_processado = texto
        if blacklist_list:
            texto_processado = _transformar_segmento(texto_processado)
        elif estilo and estilo != "padrao":
            texto_processado = _transformar_segmento(texto_processado)
        chunks = dividir_em_sentencas(texto_processado, limite=_config.get_chunk_limite())
        chunk_voices = [voz] * len(chunks)
        chunk_pauses = None

    total  = len(chunks)

    if total == 0:
        return {"ok": False, "erro": "Texto vazio após processamento."}

    # Retomada de progresso
    plan_for_hash = list(zip(chunks, chunk_voices)) if speaker_plan else None
    jh        = _job_hash(texto, voz, estilo, plan_for_hash)
    job_directory = _job_dir(jh)
    os.makedirs(job_directory, exist_ok=True)

    completed_set   = _carregar_progresso(jh)
    temp_files      = [os.path.join(job_directory, f"part_{i}.mp3") for i in range(total)]
    completed_valid = {
        idx for idx in completed_set
        if idx < total  # guard against stale progress from different run
        and os.path.exists(temp_files[idx]) and os.path.getsize(temp_files[idx]) > 500
    }
    pending = [i for i in range(total) if i not in completed_valid]

    ja_prontos = len(completed_valid)
    if ja_prontos > 0:
        _cb(int(ja_prontos * 100 / total), f"Retomando: {ja_prontos}/{total} blocos prontos.")

    inicio   = time.time()
    tracker  = ProgressTracker(total, inicio, ja_prontos)
    sem      = asyncio.Semaphore(_config.get_tts_workers())

    async def _converter_um(idx: int, chunk: str, semaforo: asyncio.Semaphore, *, atualizar_tracker: bool = True):
        if _cancelar_flag:
            return
        async with semaforo:
            if _cancelar_flag:
                return
            ok = await converter_tts_auto(chunk, chunk_voices[idx], temp_files[idx], velocidade, tom)
            if ok:
                # BUG FIX: só marca como concluído se de fato gerou áudio válido
                completed_valid.add(idx)
                _salvar_progresso(jh, completed_valid)
            if atualizar_tracker:
                tracker.registrar(idx, ok)
                if tracker.deve_atualizar():
                    _cb(tracker.pct(), tracker.mensagem_simples())

    # ── Rodada inicial ────────────────────────────────────────────────────────
    await asyncio.gather(*[_converter_um(i, chunks[i], sem) for i in pending])

    if _cancelar_flag:
        return {"ok": False, "erro": "Cancelado pelo usuário."}

    # ── Rodadas de retry: garante que todos os chunks sejam convertidos ───────
    _MAX_RODADAS_RETRY = 3
    for rodada in range(1, _MAX_RODADAS_RETRY + 1):
        falhos = _chunks_falhos(total, completed_valid, temp_files)
        if not falhos:
            break

        espera = 2 ** rodada  # 2, 4, 8 segundos entre rodadas
        logger.warning(f"Retry {rodada}/{_MAX_RODADAS_RETRY}: {len(falhos)} blocos pendentes. Aguardando {espera}s.")
        _cb(
            int(len(completed_valid) * 90 / total),
            f"Reprocessando {len(falhos)} blocos (tentativa {rodada}/{_MAX_RODADAS_RETRY})...",
        )
        await asyncio.sleep(espera)

        # Usa metade dos workers para dar mais folga à API
        workers_retry = max(1, _config.get_tts_workers() // 2)
        sem_retry = asyncio.Semaphore(workers_retry)
        await asyncio.gather(*[
            _converter_um(i, chunks[i], sem_retry, atualizar_tracker=False)
            for i in falhos
        ])

        if _cancelar_flag:
            return {"ok": False, "erro": "Cancelado pelo usuário."}

    # ── Verificação final: impede audiobook incompleto ────────────────────────
    falhos_final = _chunks_falhos(total, completed_valid, temp_files)
    if falhos_final:
        logger.error(f"Conversão incompleta: {len(falhos_final)}/{total} blocos falharam após {_MAX_RODADAS_RETRY} retries.")
        if motor_ativo == "elevenlabs":
            dica = _dica_erro_elevenlabs()
        elif motor_ativo == "gemini":
            dica = "Verifique a chave Gemini em Configurações."
        else:
            dica = "Verifique a conexão com a internet."
        return {
            "ok": False,
            "erro": (
                f"{len(falhos_final)} de {total} blocos não puderam ser convertidos "
                f"após {_MAX_RODADAS_RETRY} tentativas. {dica}"
            ),
        }

    # Concatenar todos os chunks em ordem
    _cb(95, "Juntando áudio...")
    indices_validos = [
        i for i in range(total)
        if os.path.exists(temp_files[i]) and os.path.getsize(temp_files[i]) > 500
    ]
    arquivos_validos = [temp_files[i] for i in indices_validos]
    pausas_validas = [chunk_pauses[i] for i in indices_validos] if chunk_pauses else None

    if not arquivos_validos:
        return {"ok": False, "erro": "Nenhum chunk de áudio foi gerado."}

    pausa_ms = int(CONFIG.get("edge_pausa_entre_blocos_ms", 600))

    if not saida_mp3:
        saida_mp3 = os.path.join(_config.FILES_DIR, f"{titulo}.mp3")

    ok_concat = await concatenar_grupo_com_pausa(arquivos_validos, saida_mp3, pausa_ms, pausas_validas)
    if not ok_concat or not os.path.exists(saida_mp3):
        return {"ok": False, "erro": "Falha na concatenação do áudio."}

    # Timbre escolhido pelo usuário (tom e suavização de agudos). Mantém a duração; se falhar, segue sem.
    tom_meios_tons = int(CONFIG.get("timbre_tom_meios_tons", 0) or 0)
    agudos_db = int(CONFIG.get("timbre_agudos_db", 0) or 0)
    if tom_meios_tons or agudos_db:
        _cb(96, "Ajustando o timbre...")
        try:
            await aplicar_timbre(
                saida_mp3, tom_meios_tons, agudos_db, int(CONFIG.get("audio_bitrate_kbps", 64) or 64)
            )
        except Exception as e:
            logger.warning(f"Ajuste de timbre falhou (não-crítico): {e}")

    # Metadados ID3
    capa_bytes = None
    try:
        capa_bytes = obter_capa(None, titulo)
    except Exception:
        pass

    adicionar_metadados_mp3(
        saida_mp3,
        titulo=titulo,
        artista="LylyReader",
        album=titulo,
        genero="Audiobook",
        capa_bytes=capa_bytes,
    )

    # Chapter marks (EPUB com múltiplos capítulos)
    caps_com_ts: list = []
    if capitulos_info and len(capitulos_info) > 1:
        _cb(97, "Adicionando marcadores de capítulo...")
        try:
            caps_com_ts = _calcular_timestamps_capitulos(
                capitulos_info, temp_files, arquivos_validos, saida_mp3
            )
            if caps_com_ts:
                adicionar_chapter_marks(saida_mp3, caps_com_ts)
                logger.info(f"Chapter marks adicionados: {len(caps_com_ts)} capítulos.")
        except Exception as e:
            logger.warning(f"Chapter marks falhou (não-crítico): {e}")

    # Duração final
    duracao_seg = await obter_duracao_ffprobe(saida_mp3)
    duracao_str = formatar_duracao(duracao_seg)
    tam_mb      = round(os.path.getsize(saida_mp3) / (1024 * 1024), 1)

    _config.adicionar_historico(titulo, "MP3", voz, duracao_str, tam_mb)
    _limpar_job(jh)

    _cb(100, f"Pronto! {duracao_str} | {tam_mb}MB")
    return {
        "ok": True, "caminho": saida_mp3, "duracao": duracao_str, "tamanho_mb": tam_mb,
        "capitulos": caps_com_ts,
    }


async def processar_arquivo(
    caminho_arquivo: str,
    voz: str,
    velocidade: str = "+0%",
    tom: str = "+0Hz",
    estilo: str = "padrao",
    saida_mp3: str = "",
    progress_cb: Optional[ProgressCallback] = None,
    blacklist: Optional[str] = "[]",
) -> dict:
    """
    Wrapper de alto nível: extrai texto do arquivo e chama processar().
    Suporta: .epub, .pdf, .txt, .md, .docx, .doc, .mobi

    Para EPUB/TXT/MD, o progress_cb recebe mensagens com "Capítulo X de N" quando
    o arquivo contém múltiplos capítulos detectáveis (capítulos viram chapter marks
    no MP3, navegáveis no player).
    """
    ext = Path(caminho_arquivo).suffix.lower()
    formatos_suportados = {".epub", ".pdf", ".txt", ".md", ".docx", ".doc", ".mobi"}

    if ext not in formatos_suportados:
        return {"ok": False, "erro": f"Formato '{ext}' não suportado. Use: {', '.join(formatos_suportados)}"}

    try:
        if progress_cb:
            progress_cb(0, f"Extraindo texto de {Path(caminho_arquivo).name}...")
        texto = extrair_texto_limpo(caminho_arquivo, ext)
    except Exception as e:
        return {"ok": False, "erro": f"Erro ao ler arquivo: {e}"}

    if not texto.strip():
        return {"ok": False, "erro": "O arquivo não contém texto extraível."}

    titulo = Path(caminho_arquivo).stem
    stats  = contar_estatisticas(texto)
    estimativa = estimar_duracao(texto, velocidade)

    # ── Progresso por capítulo (EPUB/TXT/MD) ──────────────────────────────────
    cb_final = progress_cb

    capitulos_info_final: Optional[list] = None

    if ext in (".epub", ".txt", ".md"):
        montado = _montar_capitulos_info(texto, _config.get_chunk_limite())

        if montado is not None:
            capitulos_info_final, limites_chunks, total_chunks_estimado, total_caps = montado

            if progress_cb:
                def cb_com_capitulo(pct: int, msg: str) -> None:
                    chunk_estimado = int(pct / 100 * total_chunks_estimado)
                    cap_num = total_caps
                    for i, (ini, fim) in enumerate(limites_chunks):
                        if chunk_estimado < fim:
                            cap_num = i + 1
                            break
                    try:
                        progress_cb(pct, f"Capítulo {cap_num} de {total_caps}\n{msg}")
                    except Exception:
                        pass

                cb_final = cb_com_capitulo

                progress_cb(1, (
                    f"{ext[1:].upper()}: {total_caps} capítulos | {stats['palavras']} palavras | "
                    f"~{stats['paginas_estimadas']} págs | Estimativa: {estimativa}"
                ))
        else:
            if progress_cb:
                progress_cb(1, (
                    f"Texto extraído: {stats['palavras']} palavras | "
                    f"~{stats['paginas_estimadas']} págs | "
                    f"Duração estimada: {estimativa}"
                ))
    else:
        if progress_cb:
            progress_cb(1, (
                f"Texto extraído: {stats['palavras']} palavras | "
                f"~{stats['paginas_estimadas']} págs | "
                f"Duração estimada: {estimativa}"
            ))

    return await processar(
        texto=texto,
        voz=voz,
        velocidade=velocidade,
        tom=tom,
        estilo=estilo,
        titulo=titulo,
        saida_mp3=saida_mp3,
        progress_cb=cb_final,
        capitulos_info=capitulos_info_final,
        blacklist=blacklist,
    )

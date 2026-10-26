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
import contextlib
import hashlib
import json
import math
import os
import re
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
    extrair_texto_limpo, contar_estatisticas, estimar_duracao, sanitizar_trecho_para_tts,
    precisa_sanear, remover_secao_notas_final,
    PCT_FIM_SINTESE, PCT_FIM_JUNCAO, PCT_FIM_TIMBRE, PCT_FIM_METADADOS, PCT_FIM_CAPITULOS,
    fracao_sintese, pct_sintese_global, pct_nao_regride,
)
from tts import (
    converter_tts_auto, motor_da_voz, concatenar_grupo_com_pausa,
    adicionar_metadados_e_capitulos_mp3,
    agrupar_blocos_por_limite, obter_duracao_ffprobe, validar_voz_tts,
    JuncaoCancelada, _CONCAT_BATCH_SIZE, _concatenar_mp3_puro, _is_wav,
    intercalar_silencios, apagar_silencios,
)
from cover_art import obter_capa
from erros_tts import ErroDeChave
from metricas_conversao import MetricasConversao, ms_desde
from orcamento_retry import OrcamentoRetry
import velocidade_motores
from velocidade_motores import motor_medido
from smart_cleaner import normalize_blacklist
from speaker_segments import build_speaker_chunk_plan

# Tipo do callback de progresso:  callback(pct: int, mensagem: str)
ProgressCallback = Callable[[int, str], None]

# Flag global de cancelamento (setada pelo Kotlin via cancelar())
_cancelar_flag = False
# Quando ligada junto com _cancelar_flag, o job dir é apagado ao parar (cancelamento definitivo, não pausa).
_descartar_ao_cancelar = False
# Formato do hash de job gerado por _job_hash (usado por descartar_job para não aceitar caminho).
_JOB_HASH_RE = re.compile(r"[0-9a-f]{16}")
# Retries imediatos de um bloco que falhou (P1.2), antes de ele ir para as rodadas globais.
_RETRIES_IMEDIATOS = 2
_ESPERAS_RETRY_IMEDIATO = (0.5, 1.5)  # segundos antes da 1ª e da 2ª repetição
# Motores que já repetem internamente (Edge em tts.py; Gemini e OpenRouter nos clientes). Para eles
# não há retry imediato extra, para não multiplicar as tentativas (pior caso 3 x 5 x timeout).
_MOTORES_COM_RETRY_INTERNO = frozenset({"edge", "gemini", "openrouter"})
# Motores que rodam no aparelho, pelo roteamento de tts.motor_da_voz (Piper entra como "kokoro").
_MOTORES_LOCAIS = frozenset({"onnx", "android", "kokoro", "pocket"})
# Tempo total somado das rodadas globais de retry (depois da rodada inicial). Esgotado, a conversão
# para de reprocessar e falha (ou tolera) com a mensagem de sempre. Rede boa não chega perto disso.
_ORCAMENTO_RETRY_S = 480


def _registrar_velocidade(pending: list, chunks: list, chunk_voices: list,
                          completed_valid: set, ms_sintese: float) -> None:
    """Grava a velocidade do motor desta execução em velocidade_motores.json. Só mede quando todos
    os blocos pendentes usam o mesmo motor medível. Nunca levanta exceção (não quebra a conversão)."""
    try:
        if not pending or not _config.FILES_DIR:
            return
        motores = {motor_medido(chunk_voices[i], motor_da_voz(chunk_voices[i])) for i in pending}
        if len(motores) != 1 or None in motores:
            return
        motor = motores.pop()
        feitos = [i for i in pending if i in completed_valid]
        chars = sum(len(chunks[i]) for i in feitos)
        falhos = len(pending) - len(feitos)
        velocidade_motores.registrar_execucao(
            _config.FILES_DIR, motor, ms_sintese, chars, len(feitos), falhos,
        )
    except Exception as e:
        logger.warning(f"Medição de velocidade ignorada: {e}")

def cancelar():
    """Chamado pelo Kotlin quando o usuário aperta 'Cancelar'."""
    global _cancelar_flag
    _cancelar_flag = True

def cancelar_e_descartar():
    """Cancela e pede para apagar o job dir ao parar (o próprio Python apaga, depois da síntese)."""
    global _cancelar_flag, _descartar_ao_cancelar
    _cancelar_flag = True
    _descartar_ao_cancelar = True

# Job da conversão em andamento, para a prévia "Ouvir o que já foi gerado". Preenchido em _processar_nucleo.
_job_ativo = {"jh": None, "total": 0}
# Limite de áudio da prévia: 15 min.
_PREVIA_LIMITE_MS = 15 * 60 * 1000


def _apagar_previa() -> None:
    """Remove as prévias (previa*.mp3) e os temporários delas (previa*.tmp) de TEMP_DIR. Nunca levanta exceção.
    Nome único por prévia: o player guarda a posição por arquivo, então reaproveitar o nome faria a prévia nova
    retomar a posição da antiga."""
    try:
        nomes = os.listdir(TEMP_DIR)
    except OSError:
        return
    for nome in nomes:
        if nome.startswith("previa") and nome.endswith((".mp3", ".tmp")):
            with contextlib.suppress(OSError):
                os.remove(os.path.join(TEMP_DIR, nome))


def _zerar_job_ativo() -> None:
    _job_ativo.update(jh=None, total=0)


def _prefixo_contiguo(concluidos, total: int) -> list:
    """Índices 0..k-1 todos concluídos, sem pular nenhum. Para no primeiro bloco que falta."""
    prefixo = []
    for i in range(total):
        if i not in concluidos:
            break
        prefixo.append(i)
    return prefixo


def _limitar_por_duracao(indices: list, duracoes_ms: dict, limite_ms: int) -> list:
    """Corta a sequência antes de passar de `limite_ms`. Bloco sem duração conta 0. O primeiro bloco
    sempre entra, mesmo que sozinho passe do limite."""
    escolhidos = []
    acumulado = 0
    for i in indices:
        duracao = int(duracoes_ms.get(i) or 0)
        if escolhidos and acumulado + duracao > limite_ms:
            break
        escolhidos.append(i)
        acumulado += duracao
    return escolhidos


def gerar_previa() -> str:
    """Junta os blocos já prontos do início do job ativo (sem buraco, até ~60 min) em
    TEMP_DIR/previa.mp3, por arquivo temporário + os.replace. Devolve o caminho, ou "" se não há
    bloco pronto ou algo falhar. Nunca levanta exceção."""
    try:
        jh = _job_ativo.get("jh")
        total = int(_job_ativo.get("total") or 0)
        if not jh or total <= 0:
            return ""
        pasta = _job_dir(jh)
        caminhos = [os.path.join(pasta, f"part_{i}.mp3") for i in range(total)]
        concluidos = {
            i for i in _carregar_progresso(jh)
            if 0 <= i < total and os.path.exists(caminhos[i]) and os.path.getsize(caminhos[i]) > 500
        }
        indices = _prefixo_contiguo(concluidos, total)
        if not indices:
            return ""
        duracoes = _carregar_duracoes(jh)
        for i in indices:
            if not duracoes.get(i):
                lida = _duracao_ms_bloco(caminhos[i])
                if lida:
                    duracoes[i] = lida
        escolhidos = _limitar_por_duracao(indices, duracoes, _PREVIA_LIMITE_MS)
        arquivos = [caminhos[i] for i in escolhidos]
        if not _formato_homogeneo(arquivos):
            return ""
        os.makedirs(TEMP_DIR, exist_ok=True)
        _apagar_previa()  # a anterior sai antes: só uma prévia existe por vez
        saida = os.path.join(TEMP_DIR, f"previa_{time.time_ns()}.mp3")
        tmp = saida + ".tmp"
        try:
            if not _concatenar_mp3_puro(arquivos, tmp):
                return ""
            os.replace(tmp, saida)
        finally:
            if os.path.exists(tmp):
                with contextlib.suppress(OSError):
                    os.remove(tmp)
        return saida
    except Exception as e:
        logger.warning(f"Prévia não gerada: {e}")
        return ""

def _reset_cancelar():
    global _cancelar_flag, _descartar_ao_cancelar
    _cancelar_flag = False
    _descartar_ao_cancelar = False

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

def _job_hash(
    chunks: list, voz: str, estilo: str, voice_plan: Optional[list] = None,
    velocidade: str = "", tom: str = "",
) -> str:
    """Identifica o job pelo conteúdo real dos blocos e pelos ajustes que mudam o áudio.
    Mudar bloco, voz, estilo, velocidade, tom, plano de vozes ou dicionário de pronúncia gera outro job.
    Jobs criados antes desta regra deixam de ser retomados (uma vez só)."""
    conteudo = hashlib.sha256(json.dumps(list(chunks), ensure_ascii=False).encode()).hexdigest()
    mapping = json.dumps(voice_plan, ensure_ascii=False, separators=(",", ":")) if voice_plan else ""
    # A assinatura do dicionário de pronúncia entra no hash: mudá-lo não reaproveita áudio de um job antigo.
    pronuncias = pronunciation_dict.assinatura()
    material = f"{conteudo}:{voz}:{estilo}:{velocidade}:{tom}:{mapping}:{pronuncias}"
    return hashlib.sha256(material.encode()).hexdigest()[:16]

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

def _carregar_duracoes(jh: str) -> dict:
    """Durações (ms) por índice de bloco, gravadas ao concluir cada bloco (P3.3).
    Checkpoint antigo, sem o campo, devolve {}: quem usa cai na leitura do arquivo."""
    pf = os.path.join(_job_dir(jh), "progress.json")
    if os.path.exists(pf):
        try:
            with open(pf) as f:
                bruto = json.load(f).get("duracoes_ms", {})
            return {int(k): int(v) for k, v in bruto.items()}
        except Exception:
            pass
    return {}

def _salvar_progresso(jh: str, completed: set, duracoes: Optional[dict] = None):
    """Grava o progresso por escrita atômica (arquivo temporário + os.replace): uma queda no meio
    não deixa progress.json cortado."""
    pf = os.path.join(_job_dir(jh), "progress.json")
    dados = {"completed": sorted(completed)}
    if duracoes:
        dados["duracoes_ms"] = {str(k): int(v) for k, v in duracoes.items() if k in completed}
    try:
        temporario = pf + ".tmp"
        with open(temporario, "w") as f:
            json.dump(dados, f)
        os.replace(temporario, pf)
    except Exception:
        pass

def _limpar_job(jh: str):
    d = _job_dir(jh)
    if os.path.exists(d):
        shutil.rmtree(d, ignore_errors=True)


# ── Junção incremental (P3.1): lotes derivados de _CONCAT_BATCH_SIZE blocos ───

_PREFIXO_LOTE = "batch_"

def _caminho_lote(jh: str, g: int) -> str:
    return os.path.join(_job_dir(jh), f"{_PREFIXO_LOTE}{g}.mp3")

def _caminho_manifesto_lote(jh: str, g: int) -> str:
    return os.path.join(_job_dir(jh), f"{_PREFIXO_LOTE}{g}.json")

def _assinatura_arquivos(arquivos: list) -> list:
    """Tamanho e data de modificação de cada bloco: detecta bloco trocado depois de o lote ser feito."""
    return [[os.path.getsize(a), os.stat(a).st_mtime_ns] for a in arquivos]

def _formato_homogeneo(arquivos: list) -> bool:
    """True se todos os blocos são do mesmo formato (todos WAV ou todos MP3). A junção mista descarta
    arquivos, então lote só vale quando não há mistura."""
    return len({_is_wav(a) for a in arquivos}) <= 1

def _juncao_incremental_ativa() -> bool:
    """Lotes só são usados sem FFmpeg (Android), onde a junção é binária e as pausas entre blocos são
    silêncios gerados em Python (ver tts.intercalar_silencios)."""
    return shutil.which("ffmpeg") is None

def _lote_valido(jh: str, g: int, arquivos: list, pausas: Optional[list] = None) -> bool:
    """O lote g existe e o manifesto bate com os blocos atuais (mesmos nomes, tamanhos e datas) e com as
    pausas entre eles (`pausas[i]` = ms depois do bloco i; None = sem pausas)."""
    saida = _caminho_lote(jh, g)
    manifesto = _caminho_manifesto_lote(jh, g)
    try:
        if not (os.path.exists(saida) and os.path.getsize(saida) > 500 and os.path.exists(manifesto)):
            return False
        with open(manifesto, encoding="utf-8") as f:
            dados = json.load(f)
        return (
            dados.get("arquivos") == [os.path.basename(a) for a in arquivos]
            and dados.get("assinatura") == _assinatura_arquivos(arquivos)
            and dados.get("pausas") == _pausas_internas(pausas, len(arquivos))
        )
    except Exception:
        return False

def _pausas_internas(pausas: Optional[list], n: int) -> Optional[list]:
    """Pausas entre os blocos de um lote (sem a do último, que fica para a junção final); None sem pausas."""
    if not pausas:
        return None
    internas = [int(p or 0) for p in pausas[:max(0, n - 1)]]
    return internas if any(internas) else None

def _gerar_lote_sync(jh: str, g: int, arquivos: list, pausas: Optional[list] = None) -> bool:
    """Junta os blocos de um grupo cheio num lote derivado (roda em thread), com o silêncio de `pausas`
    entre eles. Reaproveita o lote se ele já bater com os blocos. Ao regravar, apaga o manifesto antes:
    lote sem manifesto correspondente nunca é usado."""
    try:
        if _lote_valido(jh, g, arquivos, pausas):
            return True
        if not _formato_homogeneo(arquivos):
            return False
        saida = _caminho_lote(jh, g)
        manifesto = _caminho_manifesto_lote(jh, g)
        tmp_saida = saida + ".tmp"
        tmp_manifesto = manifesto + ".tmp"
        assinatura = _assinatura_arquivos(arquivos)
        internas = _pausas_internas(pausas, len(arquivos))
        silencios: dict = {}
        try:
            lista = arquivos
            if internas:
                lista = intercalar_silencios(arquivos, internas, _job_dir(jh), silencios)
                if lista is None:
                    return False
            if not _concatenar_mp3_puro(lista, tmp_saida):
                return False
            if os.path.exists(manifesto):
                os.remove(manifesto)
            os.replace(tmp_saida, saida)
            with open(tmp_manifesto, "w", encoding="utf-8") as f:
                json.dump({
                    "arquivos": [os.path.basename(a) for a in arquivos],
                    "assinatura": assinatura,
                    "pausas": internas,
                }, f)
            os.replace(tmp_manifesto, manifesto)
            return True
        finally:
            apagar_silencios(silencios)
            for tmp in (tmp_saida, tmp_manifesto):
                if os.path.exists(tmp):
                    try:
                        os.remove(tmp)
                    except OSError:
                        pass
    except Exception as e:
        logger.warning(f"Lote {g} de junção não foi gerado: {e}")
        return False

def _lista_para_juncao(
    total: int, indices_validos: list, temp_files: list, jh: str, pausas: Optional[list] = None,
) -> tuple:
    """Lista da junção final e a pausa depois de cada item: cada grupo cheio de blocos válidos entra como
    o lote pronto (se o manifesto bater), com as pausas internas já dentro dele; os demais entram bloco a
    bloco. `pausas[i]` é a pausa depois do bloco i. Juntar a lista com essas pausas dá os mesmos bytes
    que juntar todos os blocos em ordem com as pausas de cada um."""
    validos = set(indices_validos)
    lista = []
    pausas_lista = []

    def pausa(i: int) -> int:
        return int(pausas[i] or 0) if pausas and i < len(pausas) else 0

    for g in range(math.ceil(total / _CONCAT_BATCH_SIZE)):
        idxs = list(range(g * _CONCAT_BATCH_SIZE, min(total, (g + 1) * _CONCAT_BATCH_SIZE)))
        if len(idxs) == _CONCAT_BATCH_SIZE and all(i in validos for i in idxs):
            if _lote_valido(jh, g, [temp_files[i] for i in idxs], [pausa(i) for i in idxs] if pausas else None):
                lista.append(_caminho_lote(jh, g))
                pausas_lista.append(pausa(idxs[-1]))
                continue
        for i in idxs:
            if i in validos:
                lista.append(temp_files[i])
                pausas_lista.append(pausa(i))
    return lista, pausas_lista

def _limpar_lotes(jh: str) -> None:
    """Apaga os lotes derivados do job (batch_*). Blocos (part_*) e checkpoint ficam para a retomada."""
    pasta = _job_dir(jh)
    try:
        for nome in os.listdir(pasta):
            if nome.startswith(_PREFIXO_LOTE):
                try:
                    os.remove(os.path.join(pasta, nome))
                except OSError:
                    pass
    except OSError:
        pass

def _duracao_ms_bloco(caminho: str) -> Optional[int]:
    """Duração (ms) de um bloco recém-sintetizado, lida na própria síntese (P3.3). A conta é a mesma
    da leitura final antiga: WAV pelo byte rate do cabeçalho, MP3 pelo mutagen. None se não der para ler."""
    try:
        with open(caminho, 'rb') as fh:
            magic = fh.read(4)
            byte_rate = 0
            if magic == b'RIFF':
                fh.seek(28)  # ByteRate
                byte_rate = int.from_bytes(fh.read(4), 'little')
        if magic == b'RIFF':
            return int(os.path.getsize(caminho) * 1000 / byte_rate) if byte_rate > 0 else 0
        return int(_MutagenMP3(caminho).info.length * 1000)
    except Exception:
        return None


def descartar_job(jh: str) -> bool:
    """Apaga o job dir de um hash (conversão pausada descartada). Só aceita hash no formato de _job_hash;
    qualquer outra coisa (caminho, vazio, texto livre) é recusada sem tocar em nada."""
    if not isinstance(jh, str) or not _JOB_HASH_RE.fullmatch(jh):
        return False
    _limpar_job(jh)
    return True


def _data_mais_recente_do_job(pasta: str) -> float:
    """Maior data de modificação entre o diretório do job e o progress.json (se existir)."""
    recente = os.path.getmtime(pasta)
    pf = os.path.join(pasta, "progress.json")
    if os.path.exists(pf):
        recente = max(recente, os.path.getmtime(pf))
    return recente


def limpar_jobs_antigos(max_dias: int = 30, agora: Optional[float] = None, manter: Optional[str] = None) -> int:
    """Apaga job dirs (job_<hash16>) em TEMP_DIR sem uso há mais de max_dias. Nunca levanta exceção.
    Ignora nomes fora do formato de _job_hash, links simbólicos e o job `manter` (hash atual).
    Devolve quantos diretórios foram apagados."""
    apagados = 0
    try:
        if not os.path.isdir(TEMP_DIR):
            return 0
        limite = (time.time() if agora is None else agora) - max_dias * 86400
        with os.scandir(TEMP_DIR) as it:
            entradas = list(it)
    except Exception as e:
        logger.warning(f"Limpeza de jobs antigos ignorada: {e}")
        return 0

    for entrada in entradas:
        try:
            nome = entrada.name
            if not nome.startswith("job_") or not _JOB_HASH_RE.fullmatch(nome[len("job_"):]):
                continue
            if manter and nome == f"job_{manter}":
                continue
            if entrada.is_symlink() or not entrada.is_dir(follow_symlinks=False):
                continue
            if _data_mais_recente_do_job(entrada.path) >= limite:
                continue
            shutil.rmtree(entrada.path, ignore_errors=True)
            if not os.path.exists(entrada.path):
                apagados += 1
        except Exception as e:
            logger.warning(f"Não foi possível limpar job antigo {entrada.name}: {e}")

    if apagados:
        logger.info(f"Jobs antigos apagados: {apagados}.")
    return apagados


def _retorno_cancelado(jh: str) -> dict:
    """Retorno de cancelamento. Se o cancelamento pediu descarte, apaga o job aqui. Senão apaga só os
    lotes derivados e preserva os blocos e o checkpoint para retomar (P4.2)."""
    if _descartar_ao_cancelar:
        _limpar_job(jh)
    else:
        _limpar_lotes(jh)
    return {"ok": False, "erro": "Cancelado pelo usuário.", "job": jh}


def _chunks_falhos(total: int, completed_valid: set, temp_files: list) -> list:
    """Retorna índices de chunks que ainda precisam ser convertidos."""
    return [
        i for i in range(total)
        if i not in completed_valid
        or not os.path.exists(temp_files[i])
        or os.path.getsize(temp_files[i]) <= 500
    ]


def _limite_falhas_toleradas(total: int) -> int:
    """Quantos blocos podem falhar sem abortar a conversão: 5% do total, entre 1 e 10."""
    return min(10, max(1, math.ceil(total * 0.05)))


def workers_para(vozes, padrao: int, cpu_count: Optional[int] = None) -> int:
    """Workers de síntese para as vozes de um job. Só Piper (todas as vozes) com 4+ CPUs: 2.
    Outros motores locais (Kokoro, Pocket, Supertonic, Android, MMS/BYOM): 1. Qualquer voz de
    nuvem ou sem vozes: `padrao` (o valor de config de sempre)."""
    if cpu_count is None:
        cpu_count = os.cpu_count() or 1
    lista = list(vozes)
    if not lista:
        return padrao
    if not all(motor_da_voz(v) in _MOTORES_LOCAIS for v in lista):
        return padrao
    if all(velocidade_motores.motor_medido(v, motor_da_voz(v)) == "piper" for v in lista) and cpu_count >= 4:
        return 2
    return 1


def orcamento_retry_aplicavel(vozes) -> bool:
    """O orçamento de retry vale quando alguma voz do job é de nuvem ou edge. Job só de motores locais
    (Kokoro, Pocket, Supertonic, Android, Piper, MMS/BYOM) não tem orçamento."""
    return any(motor_da_voz(v) not in _MOTORES_LOCAIS for v in vozes)


def _voz_local(voz: str) -> bool:
    """Vozes que rodam no aparelho (sem internet). O motor carrega o modelo antes do 1º bloco."""
    return voz.startswith(("android::", "supertonic-", "kokoro-", "pocket-")) or "::" in voz


def _iniciar_batimento(_cb, pct, mensagem, intervalo: float = 5.0) -> asyncio.Task:
    """Sinal de vida: tarefa que reenvia _cb(pct(), mensagem()) a cada `intervalo` segundos.
    Quem chama deve cancelá-la ao terminar (ver _parar_batimento)."""
    async def _loop():
        while True:
            await asyncio.sleep(intervalo)
            _cb(pct(), mensagem())
    return asyncio.create_task(_loop())


async def _parar_batimento(tarefa: asyncio.Task) -> None:
    tarefa.cancel()
    with contextlib.suppress(asyncio.CancelledError, Exception):
        await tarefa


async def _com_batimento(awaitable, _cb, pct, mensagem, intervalo: float = 5.0):
    """Aguarda `awaitable` mostrando que segue vivo: a cada `intervalo` s envia
    _cb(pct, "<mensagem> (<tempo decorrido>)"). `pct` e `mensagem` aceitam valor fixo ou
    função sem argumentos. Devolve o resultado do awaitable; o batimento para ao terminar."""
    inicio = time.time()

    def _pct_atual():
        return pct() if callable(pct) else pct

    def _msg_atual():
        texto = mensagem() if callable(mensagem) else mensagem
        return f"{texto} ({formatar_duracao(time.time() - inicio)})"

    tarefa = _iniciar_batimento(_cb, _pct_atual, _msg_atual, intervalo)
    try:
        return await awaitable
    finally:
        await _parar_batimento(tarefa)


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


# Caracteres do começo de cada trecho guardados para a sincronia texto-áudio (o bastante para achar o parágrafo).
_SINCRONIA_TEXTO_MAX = 80


def _inicios_dos_chunks(
    temp_files: list, arquivos_validos: list, saida_mp3: str,
    duracoes_ms: Optional[dict] = None, pausas_apos: Optional[dict] = None,
):
    """
    Posição de cada chunk no áudio final: devolve (inicio_por_arquivo, duracao_por_arquivo, total_ms), ou None
    sem nenhum chunk válido.

    Usa a duração de cada chunk guardada na síntese (`duracoes_ms`, índice -> ms); sem ela, lê via
    mutagen/cabeçalho. Com `pausas_apos` (arquivo -> ms de silêncio depois dele), usa as pausas exatas;
    sem ele, deriva a pausa efetiva (total_final − Σchunks) / (n−1).
    """
    # Duração de cada chunk válido
    chunk_dur_map: dict = {}
    for i, f in enumerate(temp_files):
        if os.path.exists(f) and os.path.getsize(f) > 500:
            if duracoes_ms and i in duracoes_ms:
                chunk_dur_map[f] = duracoes_ms[i]
                continue
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
        return None

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
            t += int(pausas_apos.get(f, 0)) if pausas_apos is not None else pausa_real_ms
    return chunk_start_times, chunk_dur_map, total_ms


def _trechos_para_sincronia(chunks: list, temp_files: list, chunk_start_times: dict) -> list:
    """
    Início (ms) de cada trecho no MP3 final, com o começo do texto dele: o leitor localiza o parágrafo pelo texto e
    destaca o trecho exato que está tocando (sem isso, só dava para estimar pela proporção do tempo).
    """
    trechos = []
    for i, f in enumerate(temp_files):
        if i < len(chunks) and f in chunk_start_times:
            trechos.append({"inicio_ms": int(chunk_start_times[f]), "texto": str(chunks[i])[:_SINCRONIA_TEXTO_MAX]})
    return trechos


def _calcular_timestamps_capitulos(
    capitulos_info: list, temp_files: list, arquivos_validos: list, saida_mp3: str,
    duracoes_ms: Optional[dict] = None, pausas_apos: Optional[dict] = None,
) -> list:
    """
    Calcula os timestamps reais de cada capítulo (posição dos chunks: ver `_inicios_dos_chunks`).
    Retorna lista de {"titulo", "inicio_ms", "fim_ms"} pronta para
    adicionar_chapter_marks().
    """
    inicios = _inicios_dos_chunks(temp_files, arquivos_validos, saida_mp3, duracoes_ms, pausas_apos)
    if inicios is None:
        return []
    chunk_start_times, chunk_dur_map, total_ms = inicios

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

async def _processar_nucleo(
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
    metricas: Optional[MetricasConversao] = None,
    tarefas_lote: Optional[list] = None,
) -> dict:
    """Corpo da conversão (ver `processar`). Registra tempos em `metricas`. Os lotes da junção
    incremental entram em `tarefas_lote` (quem chama espera as que sobrarem)."""
    if metricas is None:
        metricas = MetricasConversao()
    _reset_cancelar()
    garantir_temp_dir()

    blacklist_list = normalize_blacklist(blacklist)

    # Maior percentual já reportado: o progresso nunca volta atrás (ver pct_nao_regride).
    ultimo_pct = [-1]

    def _cb(pct: int, msg: str):
        pct = pct_nao_regride(pct, ultimo_pct[0])
        ultimo_pct[0] = pct
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
                # O capítulo só é informado durante a síntese; depois dela, a mensagem é da etapa.
                if pct < PCT_FIM_SINTESE:
                    chunk_estimado = int(fracao_sintese(pct) * total_estimado)
                    cap_num = total_caps
                    for i, (ini, fim) in enumerate(limites):
                        if chunk_estimado < fim:
                            cap_num = i + 1
                            break
                    msg = f"Capítulo {cap_num} de {total_caps}\n{msg}"
                try:
                    callback_original(pct, msg)
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
    # Rótulo pelo motor real de cada voz (um livro multi-voz junta os nomes); não o motor de configuração.
    rotulo_motores = "+".join(sorted({
        velocidade_motores.rotulo_motor(v, motor_da_voz(v)) for v in set(chunk_voices)
    })) or motor_ativo
    metricas.definir_contexto(rotulo_motores, voz, total,
                              workers_para(set(chunk_voices), padrao=_config.get_tts_workers()))

    # Retomada de progresso
    plan_for_hash = list(zip(chunks, chunk_voices)) if speaker_plan else None
    jh        = _job_hash(chunks, voz, estilo, plan_for_hash, velocidade, tom)
    _job_ativo.update(jh=jh, total=total)
    limpar_jobs_antigos(manter=jh)
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
    # Durações por bloco (P3.3): as já salvas valem só para blocos concluídos e válidos.
    duracoes_ms = {i: d for i, d in _carregar_duracoes(jh).items() if i in completed_valid}

    # Junção incremental (P3.1): grupos cheios de _CONCAT_BATCH_SIZE blocos viram lotes assim que
    # ficam prontos, durante a síntese. Só ativa sem FFmpeg (ver _juncao_incremental_ativa).
    juncao_incremental = _juncao_incremental_ativa()
    if tarefas_lote is None:
        tarefas_lote = []
    lotes_disparados: set = set()
    # Um lote por vez: na retomada, todos os grupos prontos viram lote juntos, e cada lote carrega
    # ~50 WAV na memória. Serializar limita o pico de memória.
    sem_lotes = asyncio.Semaphore(1)

    # Pausa depois de cada bloco: a do mapa de personagens (troca de voz) ou a escolhida em Ajustes.
    pausa_ms = int(CONFIG.get("edge_pausa_entre_blocos_ms", 600))
    pausas_blocos = [
        int(chunk_pauses[i]) if chunk_pauses and i < len(chunk_pauses) and chunk_pauses[i] is not None
        else pausa_ms
        for i in range(total)
    ]

    async def _gerar_lote_serial(g: int, arquivos: list, pausas: list):
        async with sem_lotes:
            return await asyncio.to_thread(_gerar_lote_sync, jh, g, arquivos, pausas)

    # Progresso (P6): grava no máximo ~1 vez por segundo ou a cada 10 blocos. Gravação forçada no fim
    # da síntese, em cada rodada de retry e no cancelamento/pausa, para a retomada não perder blocos.
    ultimo_salvamento = {"t": 0.0, "n": 0}

    def _persistir_progresso(forcado: bool = False) -> None:
        ultimo_salvamento["n"] += 1
        agora = time.monotonic()
        if not forcado and ultimo_salvamento["n"] < 10 and agora - ultimo_salvamento["t"] < 1.0:
            return
        ultimo_salvamento["t"] = agora
        ultimo_salvamento["n"] = 0
        _salvar_progresso(jh, completed_valid, duracoes_ms)

    def _disparar_lote_se_pronto(g: int) -> None:
        if not juncao_incremental or g in lotes_disparados:
            return
        idxs = list(range(g * _CONCAT_BATCH_SIZE, (g + 1) * _CONCAT_BATCH_SIZE))
        if idxs[-1] >= total or not all(i in completed_valid for i in idxs):
            return  # grupo incompleto (ou último, parcial): fica para a junção final, bloco a bloco
        lotes_disparados.add(g)
        arquivos_grupo = [temp_files[i] for i in idxs]
        pausas_grupo = [pausas_blocos[i] for i in idxs]
        tarefas_lote.append(asyncio.create_task(_gerar_lote_serial(g, arquivos_grupo, pausas_grupo)))

    async def _aguardar_lotes() -> None:
        if tarefas_lote:
            await asyncio.gather(*tarefas_lote, return_exceptions=True)

    async def _cancelado() -> dict:
        # Espera os lotes em andamento antes de limpá-los, para nenhum ser gravado depois do cancelamento.
        await _aguardar_lotes()
        _persistir_progresso(forcado=True)  # pausa: a retomada precisa de todos os blocos prontos
        return _retorno_cancelado(jh)

    ja_prontos = len(completed_valid)
    if ja_prontos > 0:
        _cb(ja_prontos * PCT_FIM_SINTESE // total, f"Retomando: {ja_prontos}/{total} blocos prontos.")

    inicio   = time.time()
    tracker  = ProgressTracker(total, inicio, ja_prontos)
    # Edge: limite adaptativo (P1.4), começa em get_tts_workers() e sobe até TTS_WORKERS_TETO.
    # Motores locais e nuvem: semáforo fixo, como antes.
    workers_inicial = workers_para(set(chunk_voices), padrao=_config.get_tts_workers())
    adaptativo = motor_ativo == "edge"
    limitador = _config.LimiteAdaptativo(
        inicial=workers_inicial, minimo=2,
        teto=_config.TTS_WORKERS_TETO if adaptativo else workers_inicial,
        adaptativo=adaptativo,
    )
    # Fase do pipeline: "sintese" (blocos pendentes) ou "retry" (rodadas de reprocessamento).
    fase = ["sintese"]

    def _pct_sintese() -> int:
        return pct_sintese_global(tracker.pct())

    def _pct_concluidos() -> int:
        return len(completed_valid) * PCT_FIM_SINTESE // total

    # Mensagem da chave recusada (ErroDeChave). Preenchida pelo 1º bloco que a recebe; para a conversão.
    chave_recusada = [None]
    # Número de tentativas de síntese por bloco (todas as rodadas), para as métricas.
    tentativas_por_bloco: dict = {}

    async def _converter_um(
        idx: int, chunk: str, semaforo, texto_alt: Optional[str] = None,
        controle: Optional[_config.LimiteAdaptativo] = None,
    ):
        if _cancelar_flag or chave_recusada[0]:
            return
        async with semaforo:
            if _cancelar_flag or chave_recusada[0]:
                return
            texto_envio = chunk
            if texto_alt and texto_alt != chunk:
                logger.warning(f"Bloco {idx + 1}: última tentativa com texto sanitizado (fallback).")
                texto_envio = texto_alt
            elif precisa_sanear(chunk):
                # Bloco com URL, número de processo, símbolos etc.: envia já sanitizado (P1.3).
                texto_envio = sanitizar_trecho_para_tts(chunk)
            ok = False
            retries_extra = 0 if motor_da_voz(chunk_voices[idx]) in _MOTORES_COM_RETRY_INTERNO else _RETRIES_IMEDIATOS
            # Retries imediatos do próprio bloco, com espera curta (P1.2), só nos motores sem retry
            # interno. Chave recusada e cancelamento interrompem sem nova tentativa.
            for tentativa_imediata in range(retries_extra + 1):
                if _cancelar_flag or chave_recusada[0]:
                    break
                if tentativa_imediata:
                    await asyncio.sleep(_ESPERAS_RETRY_IMEDIATO[tentativa_imediata - 1])
                    if _cancelar_flag or chave_recusada[0]:
                        break
                tentativas_por_bloco[idx] = tentativas_por_bloco.get(idx, 0) + 1
                inicio_bloco = time.perf_counter()
                try:
                    ok = await converter_tts_auto(texto_envio, chunk_voices[idx], temp_files[idx], velocidade, tom)
                except ErroDeChave as e:
                    # Repetir o bloco não resolve chave recusada: aborta sem as rodadas de retry.
                    chave_recusada[0] = str(e)
                    logger.error(f"Conversão interrompida: {e}")
                    metricas.registrar_bloco(idx, ms_desde(inicio_bloco), False, tentativas_por_bloco[idx])
                    return
                metricas.registrar_bloco(idx, ms_desde(inicio_bloco), bool(ok), tentativas_por_bloco[idx])
                if ok:
                    break
                if tentativa_imediata < retries_extra:
                    logger.warning(
                        f"Bloco {idx + 1}: falha, nova tentativa imediata "
                        f"({tentativa_imediata + 1}/{retries_extra})."
                    )
            if not ok and (_cancelar_flag or chave_recusada[0]):
                return  # interrompido: não conta como falha para o ajuste de workers
            if controle is not None:
                controle.registrar(bool(ok))
            if ok:
                # BUG FIX: só marca como concluído se de fato gerou áudio válido
                completed_valid.add(idx)
                duracao = await asyncio.to_thread(_duracao_ms_bloco, temp_files[idx])
                if duracao is not None:
                    duracoes_ms[idx] = duracao
                _persistir_progresso()
                _disparar_lote_se_pronto(idx // _CONCAT_BATCH_SIZE)
            tracker.registrar(idx, ok)
            if fase[0] == "sintese" and tracker.deve_atualizar():
                _cb(_pct_sintese(), tracker.mensagem_simples(_pct_sintese()))

    # ── Rodada inicial ────────────────────────────────────────────────────────
    # Sinal de vida: reenvia o andamento a cada 5 s, para o Tempo/ETA andar mesmo sem bloco novo.
    # Motor local: a 1ª síntese demora (carrega o modelo). Avisa já no início; o batimento segue depois.
    if pending and any(_voz_local(v) for v in set(chunk_voices)):
        _cb(_pct_sintese(), "Carregando o modelo de voz…")
    # Grupos que já estavam completos (retomada) viram lote logo no início, sem esperar bloco novo.
    for g in range(math.ceil(total / _CONCAT_BATCH_SIZE)):
        _disparar_lote_se_pronto(g)
    batimento = _iniciar_batimento(_cb, _pct_sintese, lambda: tracker.mensagem_simples(_pct_sintese()))
    inicio_sintese = time.perf_counter()
    tempo_sintese_ms = 0.0  # parede da síntese desta execução (rodada inicial + retries globais)
    try:
        await asyncio.gather(*[_converter_um(i, chunks[i], limitador, controle=limitador) for i in pending])
    finally:
        await _parar_batimento(batimento)
        _persistir_progresso(forcado=True)
        tempo_sintese_ms += ms_desde(inicio_sintese)
        metricas.registrar_etapa("sintese", ms_desde(inicio_sintese))

    if _cancelar_flag:
        return await _cancelado()
    if chave_recusada[0]:
        return {"ok": False, "erro": chave_recusada[0], "job": jh}

    # ── Rodadas de retry: garante que todos os chunks sejam convertidos ───────
    fase[0] = "retry"
    _MAX_RODADAS_RETRY = 3
    # Tempo total das rodadas de retry (somado): ao esgotar, para e segue para a verificação final.
    # Só motores de nuvem/edge têm orçamento; motores locais seguem sem limite (comportamento anterior).
    orcamento_retry = OrcamentoRetry(_ORCAMENTO_RETRY_S) if orcamento_retry_aplicavel(set(chunk_voices)) else None
    if orcamento_retry is not None:
        orcamento_retry.iniciar()
    for rodada in range(1, _MAX_RODADAS_RETRY + 1):
        falhos = _chunks_falhos(total, completed_valid, temp_files)
        if not falhos:
            break
        if orcamento_retry is not None and orcamento_retry.esgotado():
            logger.warning(f"Orçamento de retry esgotado antes da rodada {rodada}: {len(falhos)} bloco(s) sem converter.")
            break

        espera = 2 ** rodada  # 2, 4, 8 segundos entre rodadas
        logger.warning(f"Retry {rodada}/{_MAX_RODADAS_RETRY}: {len(falhos)} blocos pendentes. Aguardando {espera}s.")
        msg_retry = f"Reprocessando {len(falhos)} bloco(s) (tentativa {rodada}/{_MAX_RODADAS_RETRY})…"
        _cb(_pct_concluidos(), msg_retry)

        # Usa metade dos workers para dar mais folga à API
        workers_retry = workers_para(set(chunk_voices), padrao=max(1, _config.get_tts_workers() // 2))
        sem_retry = asyncio.Semaphore(workers_retry)
        ultima_rodada = rodada == _MAX_RODADAS_RETRY

        async def _reprocessar(falhos=falhos, sem_retry=sem_retry, ultima_rodada=ultima_rodada):
            await asyncio.sleep(espera)
            await asyncio.gather(*[
                _converter_um(
                    i, chunks[i], sem_retry,
                    sanitizar_trecho_para_tts(chunks[i]) if ultima_rodada else None,
                )
                for i in falhos
            ])

        inicio_retry = time.perf_counter()
        orcamento_esgotou = False
        try:
            await asyncio.wait_for(
                _com_batimento(_reprocessar(), _cb, _pct_concluidos, msg_retry),
                timeout=orcamento_retry.restante() if orcamento_retry is not None else None,
            )
        except asyncio.TimeoutError:
            # Blocos que não terminaram ficam como falhos; a verificação final decide (tolerância ou erro).
            orcamento_esgotou = True
            logger.warning(f"Orçamento de retry de {_ORCAMENTO_RETRY_S} s esgotado na rodada {rodada}.")
        _persistir_progresso(forcado=True)
        tempo_sintese_ms += ms_desde(inicio_retry)
        metricas.registrar_etapa("sintese", ms_desde(inicio_retry))

        if _cancelar_flag:
            return await _cancelado()
        if chave_recusada[0]:
            return {"ok": False, "erro": chave_recusada[0], "job": jh}
        if orcamento_esgotou:
            break

    # ── Verificação final: impede audiobook incompleto ────────────────────────
    falhos_final = _chunks_falhos(total, completed_valid, temp_files)
    aviso_ignorados = ""  # repetido nas mensagens seguintes, para não ser sobrescrito de imediato
    if falhos_final and len(falhos_final) <= _limite_falhas_toleradas(total):
        logger.warning(
            f"{len(falhos_final)}/{total} blocos ignorados após {_MAX_RODADAS_RETRY} retries "
            "(dentro do limite de tolerância); seguindo com os demais."
        )
        aviso_ignorados = f" | {len(falhos_final)} trecho(s) ignorado(s)"
        _cb(PCT_FIM_SINTESE, f"{len(falhos_final)} trecho(s) não puderam ser convertidos e foram ignorados.")
    elif falhos_final:
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
            "job": jh,
        }

    # Lotes em andamento precisam terminar antes de ler os blocos; cancelamento é checado antes da junção.
    await _aguardar_lotes()
    if _cancelar_flag:
        return _retorno_cancelado(jh)

    _registrar_velocidade(pending, chunks, chunk_voices, completed_valid, tempo_sintese_ms)

    # Concatenar todos os chunks em ordem (junção: faixa PCT_FIM_SINTESE..PCT_FIM_JUNCAO)
    estado_juncao = {"fracao": 0.0}

    def _pct_juncao() -> int:
        return PCT_FIM_SINTESE + int(estado_juncao["fracao"] * (PCT_FIM_JUNCAO - PCT_FIM_SINTESE))

    def _msg_juncao() -> str:
        return f"Juntando áudio… {int(estado_juncao['fracao'] * 100)}%{aviso_ignorados}"

    def _progresso_juncao(fracao: float) -> None:
        estado_juncao["fracao"] = fracao
        _cb(_pct_juncao(), _msg_juncao())

    _cb(PCT_FIM_SINTESE, _msg_juncao())
    indices_validos = [
        i for i in range(total)
        if i in completed_valid and os.path.exists(temp_files[i]) and os.path.getsize(temp_files[i]) > 500
    ]
    arquivos_validos = [temp_files[i] for i in indices_validos]

    if not arquivos_validos:
        return {"ok": False, "erro": "Nenhum chunk de áudio foi gerado.", "job": jh}

    if not saida_mp3:
        saida_mp3 = os.path.join(_config.FILES_DIR, f"{titulo}.mp3")
    # Um MP3 que já existia (conversão anterior com o mesmo título) não é apagado se o cancelamento
    # vier depois da junção; só o arquivo criado por esta execução é descartado.
    saida_existia_antes = os.path.exists(saida_mp3)

    def _cancelado_pos_juncao() -> dict:
        if not saida_existia_antes:
            try:
                if os.path.exists(saida_mp3):
                    os.remove(saida_mp3)  # parcial ou sem ID3: não deixa um MP3 incompleto para trás
            except OSError as e:
                logger.warning(f"Não foi possível apagar o MP3 parcial: {e}")
        return _retorno_cancelado(jh)

    # Com lotes, a lista da junção são os lotes prontos + blocos avulsos (mesmos bytes; ver _lista_para_juncao).
    # Sem lotes, a junção segue a lista de blocos com as pausas, como antes.
    usa_lotes = juncao_incremental and _formato_homogeneo(arquivos_validos)
    if usa_lotes:
        arquivos_juncao, pausas_juncao = _lista_para_juncao(total, indices_validos, temp_files, jh, pausas_blocos)
    else:
        arquivos_juncao, pausas_juncao = arquivos_validos, [pausas_blocos[i] for i in indices_validos]

    inicio_etapa = time.perf_counter()
    try:
        ok_concat = await _com_batimento(
            concatenar_grupo_com_pausa(
                arquivos_juncao, saida_mp3, pausa_ms, pausas_juncao, on_progress=_progresso_juncao,
                cancelado=lambda: _cancelar_flag,
            ),
            _cb, _pct_juncao, _msg_juncao,
        )
    except JuncaoCancelada:
        metricas.registrar_etapa("juncao", ms_desde(inicio_etapa))
        return _cancelado_pos_juncao()
    metricas.registrar_etapa("juncao", ms_desde(inicio_etapa))
    if not ok_concat or not os.path.exists(saida_mp3):
        return {"ok": False, "erro": "Falha na concatenação do áudio.", "job": jh}

    # Tom e agudos não são gravados no arquivo (sem FFmpeg no aparelho): o app guarda o timbre da conversão e o
    # player o aplica ao tocar (TimbreAudiobookStore).
    if _cancelar_flag:
        return _cancelado_pos_juncao()

    # Metadados ID3 e capítulos (faixa PCT_FIM_TIMBRE..PCT_FIM_METADADOS). Os capítulos são calculados
    # antes e gravados junto com os metadados, numa única passada de tags (P3.4).
    if _cancelar_flag:
        return _cancelado_pos_juncao()
    capa_bytes = None
    try:
        capa_bytes = obter_capa(None, titulo)
    except Exception:
        pass

    tem_capitulos = bool(capitulos_info and len(capitulos_info) > 1)
    caps_com_ts: list = []
    msg_metadados = f"Adicionando metadados…{aviso_ignorados}"
    _cb(PCT_FIM_TIMBRE, msg_metadados)
    if tem_capitulos:
        inicio_etapa = time.perf_counter()
        try:
            caps_com_ts = await _com_batimento(
                asyncio.to_thread(
                    _calcular_timestamps_capitulos,
                    capitulos_info, temp_files, arquivos_validos, saida_mp3, duracoes_ms,
                    {temp_files[i]: pausas_blocos[i] for i in indices_validos},
                ),
                _cb, PCT_FIM_TIMBRE, msg_metadados,
            )
        except Exception as e:
            logger.warning(f"Chapter marks falhou (não-crítico): {e}")
            caps_com_ts = []
        metricas.registrar_etapa("capitulos", ms_desde(inicio_etapa))

    # Início de cada trecho no MP3, para o leitor destacar o trecho exato que está tocando (não-crítico).
    # Antes de _limpar_job: sem os blocos no disco, as durações lidas deles somem.
    trechos: list = []
    try:
        inicios = await _com_batimento(
            asyncio.to_thread(
                _inicios_dos_chunks,
                temp_files, arquivos_validos, saida_mp3, duracoes_ms,
                {temp_files[i]: pausas_blocos[i] for i in indices_validos},
            ),
            _cb, PCT_FIM_TIMBRE, msg_metadados,
        )
        if inicios is not None:
            trechos = _trechos_para_sincronia(chunks, temp_files, inicios[0])
    except Exception as e:
        logger.warning(f"Sincronia texto-áudio falhou (não-crítico): {e}")

    inicio_etapa = time.perf_counter()
    await _com_batimento(
        asyncio.to_thread(
            adicionar_metadados_e_capitulos_mp3,
            saida_mp3,
            titulo=titulo,
            artista="LylyReader",
            album=titulo,
            genero="Audiobook",
            capa_bytes=capa_bytes,
            capitulos=caps_com_ts,
        ),
        _cb, PCT_FIM_TIMBRE, msg_metadados,
    )
    metricas.registrar_etapa("id3", ms_desde(inicio_etapa))
    if caps_com_ts:
        logger.info(f"Chapter marks adicionados: {len(caps_com_ts)} capítulos.")

    # Faixa PCT_FIM_METADADOS..PCT_FIM_CAPITULOS
    _cb(PCT_FIM_METADADOS, f"Finalizando…{aviso_ignorados}")

    # Duração final (faixa PCT_FIM_CAPITULOS)
    msg_duracao = f"Calculando a duração…{aviso_ignorados}"
    _cb(PCT_FIM_CAPITULOS, msg_duracao)
    inicio_etapa = time.perf_counter()
    duracao_seg = await _com_batimento(
        obter_duracao_ffprobe(saida_mp3), _cb, PCT_FIM_CAPITULOS, msg_duracao,
    )
    metricas.registrar_etapa("ffprobe", ms_desde(inicio_etapa))
    duracao_str = formatar_duracao(duracao_seg)
    tam_mb      = round(os.path.getsize(saida_mp3) / (1024 * 1024), 1)
    metricas.definir_tamanho(os.path.getsize(saida_mp3))

    _config.adicionar_historico(titulo, "MP3", voz, duracao_str, tam_mb)
    _limpar_job(jh)

    _cb(100, f"Pronto! {duracao_str} | {tam_mb}MB")
    return {
        "ok": True, "caminho": saida_mp3, "duracao": duracao_str, "tamanho_mb": tam_mb,
        "capitulos": caps_com_ts,
        # JSON numa string só: milhares de trechos atravessam a ponte Python→Kotlin de uma vez.
        "trechos_json": json.dumps(trechos, ensure_ascii=False),
    }


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

    Em ambos os casos o dict traz "metricas" (resumo de tempos, ver MetricasConversao) e o
    resumo é logado em INFO como "[METRICAS] ...", mesmo quando a conversão falha.
    """
    metricas = MetricasConversao()
    lotes: list = []
    # Prévia e job ativo são da conversão que começa agora: nada sobra de uma anterior.
    _apagar_previa()
    _zerar_job_ativo()
    # Vocabulário do livro: diz ao normalizador que "CASA" num título é palavra e "OAB" é sigla.
    try:
        from text_normalizer import registrar_vocabulario
        registrar_vocabulario("conversao", texto)
    except Exception as e:
        logger.warning(f"Vocabulário do livro não registrado: {e}")
    try:
        resultado = await _processar_nucleo(
            texto, voz, velocidade, tom, estilo, titulo, saida_mp3, progress_cb,
            capitulos_info, blacklist, speaker_map_path, metricas=metricas, tarefas_lote=lotes,
        )
    finally:
        # Lotes da junção incremental ainda em voo não podem gravar depois que a conversão terminou
        # (erro ou retorno antecipado): espera o que sobrou antes de sair.
        if lotes:
            await asyncio.gather(*lotes, return_exceptions=True)
        # Fim da conversão (sucesso, erro ou pausa): a prévia só vale durante a geração.
        _zerar_job_ativo()
        _apagar_previa()
        try:
            logger.info(f"[METRICAS] {metricas.como_texto()}")
        except Exception:
            pass
    if isinstance(resultado, dict):
        resultado["metricas"] = metricas.resumo()
    return resultado


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

    tamanho_antes_notas = len(texto)
    texto = remover_secao_notas_final(texto)
    if len(texto) < tamanho_antes_notas:
        logger.info(f"Seção de notas finais removida: {tamanho_antes_notas - len(texto)} caracteres.")

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
                    # O capítulo só é informado durante a síntese; depois dela, a mensagem é da etapa.
                    if pct < PCT_FIM_SINTESE:
                        chunk_estimado = int(fracao_sintese(pct) * total_chunks_estimado)
                        cap_num = total_caps
                        for i, (ini, fim) in enumerate(limites_chunks):
                            if chunk_estimado < fim:
                                cap_num = i + 1
                                break
                        msg = f"Capítulo {cap_num} de {total_caps}\n{msg}"
                    try:
                        progress_cb(pct, msg)
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

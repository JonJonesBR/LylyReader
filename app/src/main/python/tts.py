"""
tts.py - Funções de síntese de voz e pós-processamento de áudio.
Adaptado para Android/Chaquopy: importa config_android em vez de config.
Removido: gerar_video_de_audio (irrelevante no Android), referências ao Telegram.
"""

import asyncio
import os
import random
import shutil
import struct
import time
from datetime import datetime
from pathlib import Path
from typing import List

import edge_tts
from mutagen.mp3 import MP3
from mutagen.id3 import (
    ID3, TIT2, TPE1, TALB, TCON, TDRC, COMM, APIC, ID3NoHeaderError,
    CTOC, CHAP, CTOCFlags,
)

from audio_cache import CACHE
import config_android as _config
from config_android import TTS_TIMEOUT, logger
from text_normalizer import normalizar as _normalizar_texto
import pronunciation_dict as _pronuncias

_MAX_RETRIES = 5
_CONCAT_BATCH_SIZE = 50  # Máx. de entradas por operação de concat
_VOICE_VALIDATION_TTL_SECONDS = 10 * 60
_validated_voices = {}


def _voice_validation_key(voz: str, velocidade: str, tom: str) -> tuple:
    return voz, velocidade, tom


def _mark_voice_valid(voz: str, velocidade: str, tom: str) -> None:
    _validated_voices[_voice_validation_key(voz, velocidade, tom)] = time.monotonic()


def _is_voice_recently_valid(voz: str, velocidade: str, tom: str) -> bool:
    validated_at = _validated_voices.get(_voice_validation_key(voz, velocidade, tom))
    return validated_at is not None and time.monotonic() - validated_at < _VOICE_VALIDATION_TTL_SECONDS


# ── Concatenação MP3 em Python puro (sem FFmpeg) ──────────────────────────────

def _mp3_audio_offset(path: str) -> int:
    """
    Retorna o byte onde os frames MP3 começam, pulando o header ID3v2
    (incluindo padding opcional após o payload).
    Necessário para concatenação binária correta (sem duplicar headers).
    """
    try:
        with open(path, 'rb') as f:
            header = f.read(10)
            if header[:3] == b'ID3':
                # ID3v2: tamanho codificado em 4 bytes syncsafe
                size = (
                    (header[6] & 0x7F) << 21 |
                    (header[7] & 0x7F) << 14 |
                    (header[8] & 0x7F) << 7  |
                    (header[9] & 0x7F)
                )
                base_offset = 10 + size
                # Pula padding (bytes 0x00) após o payload ID3 até o primeiro sync MP3 (0xFF 0xE* ou 0xFF 0xF*)
                f.seek(base_offset)
                padding_data = f.read(4096)  # lê no máximo 4KB de padding
                for i in range(len(padding_data) - 1):
                    b = padding_data[i]
                    if b == 0xFF and (padding_data[i + 1] & 0xE0) == 0xE0:
                        return base_offset + i
                return base_offset
    except Exception:
        pass
    return 0


def _is_wav(path: str) -> bool:
    """Verifica se um arquivo é WAV pelo magic bytes RIFF."""
    try:
        with open(path, 'rb') as f:
            return f.read(4) == b'RIFF'
    except Exception:
        return False


def _concatenar_wav_puro(arquivos: list, saida: str) -> bool:
    """
    Concatena arquivos WAV (PCM 24000Hz, mono, 16-bit) sem FFmpeg.
    Lê apenas o chunk 'data' de cada WAV e gera um único WAV válido.
    """
    pcm_chunks = []
    # Formato lido do primeiro WAV válido (24000 p/ Edge, 44100 p/ Supertonic etc.).
    fmt = None  # (sr, ch, bps)
    for arq in arquivos:
        if not os.path.exists(arq):
            continue
        try:
            with open(arq, 'rb') as f:
                data = f.read()
            if data[:4] != b'RIFF':
                continue
            i = 12  # Pula RIFF header (4 bytes ID + 4 bytes size + 4 bytes "WAVE")
            while i + 8 <= len(data):
                chunk_id = data[i:i+4]
                chunk_size = struct.unpack('<I', data[i+4:i+8])[0]
                if chunk_id == b'fmt ' and fmt is None and chunk_size >= 16:
                    # fmt: audioFormat(H) channels(H) sampleRate(I) byteRate(I) blockAlign(H) bits(H)
                    ch_f = struct.unpack('<H', data[i+10:i+12])[0]
                    sr_f = struct.unpack('<I', data[i+12:i+16])[0]
                    bps_f = struct.unpack('<H', data[i+22:i+24])[0]
                    if sr_f > 0 and ch_f > 0 and bps_f > 0:
                        fmt = (sr_f, ch_f, bps_f)
                if chunk_id == b'data':
                    pcm_chunks.append(data[i+8:i+8+chunk_size])
                    break
                i += 8 + chunk_size + (chunk_size % 2)  # chunks RIFF são word-aligned
        except Exception as e:
            logger.warning(f"Erro ao ler WAV {arq}: {e}")

    if not pcm_chunks:
        return False

    pcm_total = b''.join(pcm_chunks)
    sr, ch, bps = fmt if fmt is not None else (24000, 1, 16)
    size = len(pcm_total)
    hdr = bytearray()
    hdr += b"RIFF" + struct.pack("<I", 36 + size) + b"WAVE"
    hdr += b"fmt " + struct.pack("<IHHIIHH", 16, 1, ch, sr,
                                  sr * ch * bps // 8, ch * bps // 8, bps)
    hdr += b"data" + struct.pack("<I", size)
    try:
        with open(saida, 'wb') as f:
            f.write(bytes(hdr))
            f.write(pcm_total)
        return os.path.exists(saida) and os.path.getsize(saida) > 500
    except Exception as e:
        logger.error(f"Erro ao escrever WAV concatenado: {e}")
        return False


def _concatenar_mp3_puro(arquivos: list, saida: str) -> bool:
    """
    Concatena arquivos MP3 via I/O binário direto - sem FFmpeg.
    Mantém o header ID3v2 apenas do primeiro arquivo válido; pula os demais.

    IMPORTANTE: Se qualquer arquivo for WAV (magic bytes RIFF), redireciona
    tudo para _concatenar_wav_puro para evitar que bytes WAV corrompam o
    stream MP3 (causaria silêncio súbito no MediaPlayer do Android).
    """
    # Verifica o formato de TODOS os arquivos válidos
    # Se houver qualquer WAV na lista, usa concatenação WAV para todos
    arquivos_validos = [arq for arq in arquivos if os.path.exists(arq) and os.path.getsize(arq) > 0]
    if not arquivos_validos:
        return False

    tem_wav = any(_is_wav(arq) for arq in arquivos_validos)
    if tem_wav:
        # Verifica se há mistura de WAV e MP3 (situação problemática)
        tem_mp3 = any(not _is_wav(arq) for arq in arquivos_validos)
        if tem_mp3:
            logger.warning(
                "Mistura de WAV e MP3 detectada na lista de chunks. "
                "Usando apenas arquivos WAV para evitar corrupção do stream MP3."
            )
            arquivos_wav = [arq for arq in arquivos_validos if _is_wav(arq)]
            return _concatenar_wav_puro(arquivos_wav, saida)
        return _concatenar_wav_puro(arquivos_validos, saida)

    # Concatenação MP3 padrão - todos os arquivos são MP3
    try:
        primeiro_escrito = False
        with open(saida, 'wb') as out:
            for arq in arquivos_validos:
                if not primeiro_escrito:
                    # Primeiro arquivo: copia inteiro (inclui header ID3v2)
                    with open(arq, 'rb') as f:
                        shutil.copyfileobj(f, out)
                    primeiro_escrito = True
                else:
                    # Demais: pula o header ID3v2, copia só os frames MP3
                    offset = _mp3_audio_offset(arq)
                    with open(arq, 'rb') as f:
                        if offset > 0:
                            f.seek(offset)
                        shutil.copyfileobj(f, out)
        return os.path.exists(saida) and os.path.getsize(saida) > 500
    except Exception as e:
        logger.error(f"Erro na concatenação MP3 puro: {e}")
        return False


async def converter_tts_bloco(
    texto: str, voz: str, arquivo_saida: str,
    velocidade: str = "+0%", tom: str = "+0Hz", tentativas: int = _MAX_RETRIES
) -> bool:
    """Sintetiza um bloco de texto com Edge TTS. Usa cache automático."""
    # Garante o diretório-pai (o job dir pode ser removido entre rodadas/processos → evita
    # "[Errno 2] No such file or directory" em livros grandes).
    try:
        _d = os.path.dirname(arquivo_saida)
        if _d:
            os.makedirs(_d, exist_ok=True)
    except Exception:
        pass
    async with _config.get_semaforo():
        engine_key = f"edge:{velocidade}:{tom}"
        cached = CACHE.get(texto, voz, engine_key)
        if cached:
            try:
                Path(arquivo_saida).write_bytes(cached)
                _mark_voice_valid(voz, velocidade, tom)
                return True
            except Exception:
                pass

        for tentativa in range(tentativas):
            try:
                await _config.wait_for_tts_slot("edge")
                # Recria o diretório-pai imediatamente antes de escrever (fecha a janela de
                # corrida caso algo tenha removido o job dir após a espera/semáforo).
                _d = os.path.dirname(arquivo_saida)
                if _d:
                    os.makedirs(_d, exist_ok=True)
                communicate = edge_tts.Communicate(texto, voz, rate=velocidade, pitch=tom)
                await asyncio.wait_for(
                    communicate.save(arquivo_saida),
                    timeout=TTS_TIMEOUT,
                )
                if os.path.exists(arquivo_saida) and os.path.getsize(arquivo_saida) > 500:
                    try:
                        CACHE.put(texto, voz, engine_key, Path(arquivo_saida).read_bytes())
                    except Exception:
                        pass
                    _mark_voice_valid(voz, velocidade, tom)
                return True
            except asyncio.TimeoutError:
                logger.warning(f"Timeout bloco {arquivo_saida} ({tentativa + 1}/{tentativas})")
                delay = (2 ** tentativa) + random.uniform(0, 1)
            except Exception as e:
                err = str(e).lower()
                is_rate_limit = "429" in err or "too many" in err or "rate" in err
                if is_rate_limit:
                    delay = min(5.0 * (2 ** tentativa), 60.0) + random.uniform(0, 3)
                    logger.warning(
                        f"Rate-limit (429) bloco {arquivo_saida} "
                        f"({tentativa + 1}/{tentativas}), aguardando {delay:.0f}s"
                    )
                else:
                    delay = (2 ** tentativa) + random.uniform(0, 1)
                    logger.warning(f"Bloco {arquivo_saida} tentativa {tentativa + 1}/{tentativas}: {e}")

            if tentativa < tentativas - 1:
                await asyncio.sleep(delay)

        return False


_OPENROUTER_VOZES_PT = frozenset({
    "coral", "nova", "sage", "rachel",
})

# Vozes pt-BR do Kokoro (bundle sherpa-onnx v1.0). RN-1 do PLANO_MELHORIAS_V6: os ids
# "kokoro-pf-dora/pm-alex/pm-santa" NÃO carregam "-pt-" nem sufixo "-pt" — a heurística de
# string abaixo não os detectaria e a normalização cairia em "en" (números/datas lidos em
# inglês). Lista explícita = mesma regra do lang explícito por voz no lado Kotlin.
_KOKORO_VOZES_PT = frozenset({
    "kokoro-pf-dora", "kokoro-pm-alex", "kokoro-pm-santa",
})
_POCKET_VOZES_PT = frozenset({"pocket-ptbr-rafael"})
_POCKET_VOZES_ES = frozenset({"pocket-es-lola"})

def _qualquer_voz_pt(voz: str) -> bool:
    """Detecta se a voz é PT-BR pelo identificador."""
    if voz in _KOKORO_VOZES_PT:
        return True
    if voz in _POCKET_VOZES_PT:
        return True
    if voz.startswith("openrouter-"):
        voz_name = voz.split("-", 1)[1] if "-" in voz else ""
        return voz_name in _OPENROUTER_VOZES_PT
    if voz.startswith("android::") and voz.count("::") >= 2:
        # ID composto "android::<pkg>::<nome>" — o nome real do motor (ex.: "pt-BR-language")
        # é quem carrega a informação de idioma, então aplica a heurística sobre ele.
        voz = voz.split("::", 2)[-1]
    elif "::" in voz:
        # ID de voz de pacote local ("<packId>::<vozId>" — BYOM da Parte B ou pacote de 1ª
        # classe no mesmo formato, como o MMS-TTS "mms-por" da Parte A): o BYOMManager (Kotlin)
        # sufixa vozId com "-pt" quando o manifesto declara idioma pt-*, então a heurística de
        # sufixo abaixo já detecta sem precisar de lista nova aqui.
        voz = voz.split("::", 1)[-1]
    # Supertonic, Edge voices with explicit -pt- or -pt suffix (vozes Android costumam
    # começar com o locale, ex.: "pt-BR-x-ptd-local")
    lower = voz.lower()
    return "-pt-" in lower or lower.endswith("-pt") or lower.startswith("pt-")


def _idioma_para_voz(voz: str) -> str:
    """Retorna o idioma de normalização para as vozes Pocket com locale explícito."""
    if voz in _POCKET_VOZES_PT:
        return "pt"
    if voz in _POCKET_VOZES_ES:
        return "es"
    if voz == "pocket-en-alba":
        return "en"
    return "pt" if _qualquer_voz_pt(voz) else "en"


async def converter_tts_auto(
    texto: str, voz: str, arquivo_saida: str,
    velocidade: str = "+0%", tom: str = "+0Hz"
) -> bool:
    """Dispatcher Edge/Gemini/ONNX conforme a voz selecionada (Auto-Routing)."""
    # Garante o diretório-pai do arquivo de saída (job dir pode ter sumido entre rodadas).
    try:
        _d = os.path.dirname(arquivo_saida)
        if _d:
            os.makedirs(_d, exist_ok=True)
    except Exception:
        pass
    texto = _pronuncias.aplicar(texto)  # dicionário de pronúncia do usuário, antes de normalizar
    texto = _normalizar_texto(
        texto,
        idioma=_idioma_para_voz(voz),
        # Só o Pocket precisa de números soltos por extenso (o tokenizador dele não lê dígitos).
        numeros_soltos=voz.startswith("pocket-"),
        # Travessão/hífen no início abre o áudio com vazio + som avulso no Pocket e no Piper.
        sem_travessoes=voz.startswith(("pocket-", "piper-")),
    )
    if voz.startswith("android::"):
        motor = "android"
    elif voz.startswith("supertonic-"):
        motor = "onnx"
    elif voz.startswith("elevenlabs-"):
        motor = "elevenlabs"
    elif voz.startswith("pocket-"):
        motor = "pocket"
    elif voz.startswith("kokoro-"):
        motor = "kokoro"
    elif "::" in voz:
        # Voz de pacote local (BYOM da V6 Parte B, "byom-*", OU um pacote de 1ª classe que
        # reaproveita o mesmo formato, como o MMS-TTS da Parte A, "mms-por") — mesma ponte
        # Kotlin do kokoro (KokoroSynthBridge já é genérica por voiceId; o motor Kotlin resolve
        # o pacote/arquitetura internamente). "android::" já foi tratado acima, então qualquer
        # "::" que sobra até aqui é sempre um pacote local, nunca um motor de nuvem.
        motor = "kokoro"
    elif voz.startswith("openrouter-"):
        motor = "openrouter"
    elif voz in {v for cat in _config.GEMINI_VOICES.values() for v in cat.values()}:
        motor = "gemini"
    else:
        motor = "edge"

    if motor == "android":
        # A síntese Android (voz nativa instalada no aparelho) é delegada ao motor Kotlin via
        # AndroidSynthBridge — o TextToSpeech do Android precisa rodar numa thread com Looper,
        # que o Chaquopy não tem; o lado Kotlin cuida disso internamente.
        try:
            from com.jonjonesbr.audiobookgen.tts import AndroidSynthBridge
            ok = bool(AndroidSynthBridge.synthesize(texto, voz, arquivo_saida))
            if not ok:
                logger.warning(f"[TTS] Motor Android (Kotlin) falhou para voz '{voz}'.")
            return ok
        except Exception as e:
            logger.error(f"Erro na síntese Android via ponte Kotlin: {e}")
            return False

    if motor == "onnx":
        # A síntese ONNX (Supertonic) é delegada ao motor Kotlin via OnnxSynthBridge.
        # O caminho Python (local_tts) dependia do módulo `onnxruntime`, indisponível no
        # Chaquopy → falhava ("No module named 'onnxruntime'"). O motor Kotlin já funciona
        # e roda o Supertonic no processo isolado. Escreve um WAV.
        try:
            from com.jonjonesbr.audiobookgen.tts import OnnxSynthBridge
            ok = bool(OnnxSynthBridge.synthesize(texto, voz, arquivo_saida))
            if not ok:
                logger.warning(
                    f"[TTS] Motor ONNX (Kotlin) falhou para voz '{voz}'. "
                    f"Verifique se o modelo foi baixado em Configurações → Vozes Offline."
                )
            return ok
        except Exception as e:
            logger.error(f"Erro na síntese ONNX via ponte Kotlin: {e}")
            return False

    if motor == "kokoro":
        # A síntese Kokoro (Sherpa-ONNX local, processo isolado :sherpa) é delegada ao motor
        # Kotlin via KokoroSynthBridge — mesmo fluxo do Supertonic (onnx): o motor Kotlin roda
        # a síntese no processo isolado e escreve um WAV por chunk.
        try:
            from com.jonjonesbr.audiobookgen.tts import KokoroSynthBridge
            ok = bool(KokoroSynthBridge.synthesize(texto, voz, arquivo_saida))
            if not ok:
                logger.warning(
                    f"[TTS] Motor Kokoro (Kotlin) falhou para voz '{voz}'. "
                    f"Verifique se o modelo foi baixado em Configurações → Vozes Offline."
                )
            return ok
        except Exception as e:
            logger.error(f"Erro na síntese Kokoro via ponte Kotlin: {e}")
            return False

    if motor == "pocket":
        # A conversão de audiobook usa a mesma sessão Pocket isolada e o cache Kotlin da
        # leitura guiada, sem carregar os pesos ONNX pelo Chaquopy/Python.
        try:
            from com.jonjonesbr.audiobookgen.tts import PocketSynthBridge
            ok = bool(PocketSynthBridge.synthesize(texto, voz, arquivo_saida))
            if not ok:
                logger.warning(
                    f"[TTS] Pocket TTS falhou para '{voz}'. "
                    "Verifique se o pacote do idioma foi baixado em Vozes Offline."
                )
            return ok
        except Exception as e:
            logger.error(f"Erro na síntese Pocket via ponte Kotlin: {e}")
            return False

    if motor == "elevenlabs":
        # A síntese ElevenLabs (API oficial, chave própria do usuário) é delegada ao motor
        # Kotlin via ElevenLabsSynthBridge — chamada HTTP simples, sem depender de nenhum
        # pacote pip novo no Chaquopy. Escreve um MP3.
        try:
            from com.jonjonesbr.audiobookgen.tts import ElevenLabsSynthBridge
            ok = bool(ElevenLabsSynthBridge.synthesize(texto, voz, arquivo_saida))
            if not ok:
                motivo = ElevenLabsSynthBridge.ultimoMotivo() or "motivo desconhecido"
                logger.warning(
                    f"[TTS] Motor ElevenLabs (Kotlin) falhou para voz '{voz}': {motivo}"
                )
            return ok
        except Exception as e:
            logger.error(f"Erro na síntese ElevenLabs via ponte Kotlin: {e}")
            return False

    if motor == "gemini":
        from gemini_tts import KeyManager, GeminiTTSClient
        km = KeyManager(list(_config.GEMINI_API_KEYS))
        if not km.has_keys:
            logger.warning("Gemini sem chaves. Usando Edge TTS como fallback.")
            return await converter_tts_bloco(texto, voz, arquivo_saida, velocidade, tom)

        # synth_chunk sempre salva WAV (PCM 24000Hz, mono, 16-bit) em wav_saida.
        # Tenta converter para MP3 via FFmpeg; se indisponível, move WAV como-está
        # (Android MediaPlayer detecta WAV pelo magic bytes "RIFF" independente da extensão).
        voz_gemini     = voz if "-" not in voz else _config.CONFIG.get("voz_gemini", "Enceladus")
        speaking_rate  = _config.CONFIG.get("gemini_speaking_rate", 0.85)
        wav_saida      = arquivo_saida.rsplit(".", 1)[0] + "_gem.wav"

        async with _config.get_semaforo():
            async with GeminiTTSClient(
                km, _config.MODELOS_GEMINI,
                speaking_rate=speaking_rate, enable_ssml=False,
            ) as client:
                await _config.wait_for_tts_slot("gemini")
                ok = await client.synth_chunk(texto, voz_gemini, wav_saida)
        if not ok:
            return False

        # Tenta FFmpeg WAV->MP3
        sucesso = await executar_ffmpeg(
            "-y", "-i", wav_saida,
            "-c:a", "libmp3lame", "-q:a", "2", arquivo_saida,
        )
        if sucesso:
            try:
                os.remove(wav_saida)
            except OSError:
                pass
            return True

        # FFmpeg indisponível: move WAV como-está (MediaPlayer lê pelo magic bytes)
        logger.warning("FFmpeg indisponível: arquivo Gemini será WAV salvo como .mp3.")
        try:
            shutil.move(wav_saida, arquivo_saida)
            return True
        except Exception as _e:
            logger.error(f"Erro ao mover áudio Gemini: {_e}")
            return False

    if motor == "openrouter":
        from openrouter_tts import KeyManager, OpenRouterTTSClient, extrair_modelo_voz
        km = KeyManager(list(_config.OPENROUTER_API_KEYS))
        if not km.has_keys:
            logger.warning("OpenRouter sem chaves. Usando Edge TTS como fallback.")
            return await converter_tts_bloco(texto, voz, arquivo_saida, velocidade, tom)

        model, voice_name = extrair_modelo_voz(voz)
        speed_val = _config.CONFIG.get("openrouter_speed", 1.0)
        http_referer = _config.CONFIG.get("openrouter_referer", "")
        x_title = _config.CONFIG.get("openrouter_title", "LylyReader")

        wav_saida = arquivo_saida.rsplit(".", 1)[0] + "_or.wav"

        async with _config.get_semaforo():
            async with OpenRouterTTSClient(
                km, model=model, voice=voice_name,
                speed=speed_val, http_referer=http_referer, x_title=x_title,
            ) as client:
                await _config.wait_for_tts_slot("openrouter")
                ok = await client.synth_chunk(texto, wav_saida)
        if not ok:
            return False

        sucesso = await executar_ffmpeg(
            "-y", "-i", wav_saida,
            "-c:a", "libmp3lame", "-q:a", "2", arquivo_saida,
        )
        if sucesso:
            try:
                os.remove(wav_saida)
            except OSError:
                pass
            return True

        logger.warning("FFmpeg indisponível: arquivo OpenRouter será WAV salvo como .mp3.")
        try:
            shutil.move(wav_saida, arquivo_saida)
            return True
        except Exception as _e:
            logger.error(f"Erro ao mover áudio OpenRouter: {_e}")
            return False

    return await converter_tts_bloco(texto, voz, arquivo_saida, velocidade, tom)


async def validar_voz_tts(voz: str, velocidade: str = "+0%", tom: str = "+0Hz") -> bool:
    # A validação prévia às vezes causava falsos positivos ou lentidão excessiva.
    # Retornamos True direto para deixar a síntese real tratar a conexão e evitar
    # que o app fique travado no "Testando motor de voz".
    return True


async def executar_ffmpeg(*args) -> bool:
    try:
        proc = await asyncio.create_subprocess_exec(
            "ffmpeg", *args,
            stdout=asyncio.subprocess.PIPE,
            stderr=asyncio.subprocess.PIPE,
        )
        _, stderr = await proc.communicate()
        if proc.returncode != 0:
            logger.error(f"ffmpeg erro: {stderr.decode(errors='ignore')[-500:]}")
            return False
        return True
    except FileNotFoundError:
        logger.error("ffmpeg não encontrado no PATH do dispositivo.")
        return False
    except Exception as e:
        logger.error(f"Erro ffmpeg: {e}")
        return False


async def obter_duracao_ffprobe(caminho: str) -> float:
    try:
        proc = await asyncio.create_subprocess_exec(
            "ffprobe", "-v", "quiet",
            "-show_entries", "format=duration",
            "-of", "default=noprint_wrappers=1:nokey=1",
            caminho,
            stdout=asyncio.subprocess.PIPE,
            stderr=asyncio.subprocess.PIPE,
        )
        stdout, _ = await proc.communicate()
        return float(stdout.decode().strip())
    except Exception as e:
        # No Android não há ffprobe: o tempo do audiobook aparecia sempre como "0s". Plano B: mutagen (já embutido).
        logger.warning(f"ffprobe indisponivel ({e}); usando mutagen")
    try:
        return float(MP3(caminho).info.length)
    except Exception as e:
        logger.error(f"duracao via mutagen falhou: {e}")
        return 0.0


async def agrupar_blocos_por_limite(
    arquivos: list, limite_bytes: int = None, limite_segundos: float = None
) -> list:
    grupos = [[]]
    acumulado = 0.0
    for arq in arquivos:
        if not os.path.exists(arq):
            continue
        if limite_bytes:
            valor = os.path.getsize(arq)
            limite = limite_bytes
        elif limite_segundos:
            valor = await obter_duracao_ffprobe(arq)
            limite = limite_segundos
        else:
            grupos[0].append(arq)
            continue
        if acumulado + valor > limite and grupos[-1]:
            grupos.append([])
            acumulado = 0
        grupos[-1].append(arq)
        acumulado += valor
    return [g for g in grupos if g]


def _escapar_caminho_ffmpeg(caminho: str) -> str:
    """Escapa o caminho para o formato 'file' do concat demuxer do FFmpeg."""
    # No Windows, converte barras invertidas e escapa aspas simples
    return caminho.replace("\\", "/").replace("'", "\\'")


async def _concatenar_lista(arquivos: list, saida: str) -> bool:
    """
    Tenta concatenar via FFmpeg (preferencial); cai para Python puro se
    FFmpeg não estiver disponível (caso do Android sem FFmpeg instalado).
    """
    list_file = saida + ".list.txt"
    ffmpeg_tentado = False
    try:
        with open(list_file, "w", encoding="utf-8") as f:
            for arq in arquivos:
                caminho = _escapar_caminho_ffmpeg(os.path.abspath(arq))
                f.write(f"file '{caminho}'\n")

        ffmpeg_tentado = True
        ok = await executar_ffmpeg(
            "-y", "-f", "concat", "-safe", "0",
            "-i", list_file, "-c", "copy", saida,
        )
        if ok:
            return True

        logger.warning("concat -c copy falhou; tentando com re-encoding...")
        ok = await executar_ffmpeg(
            "-y", "-f", "concat", "-safe", "0",
            "-i", list_file,
            "-c:a", "libmp3lame", "-q:a", "2", saida,
        )
        if ok:
            return True

    except Exception as e:
        logger.warning(f"FFmpeg indisponível ou com erro: {e}")
    finally:
        if os.path.exists(list_file):
            try:
                os.remove(list_file)
            except OSError:
                pass

    # Fallback final: concatenação MP3 em Python puro (funciona no Android)
    if ffmpeg_tentado:
        logger.info("Usando concatenação MP3 Python puro (FFmpeg indisponível).")
    return _concatenar_mp3_puro(arquivos, saida)


async def concatenar_grupo(arquivos: list, saida: str) -> bool:
    if not arquivos:
        return False
    if len(arquivos) == 1:
        # copyfile (não copy2): o Android bloqueia copiar xattr/metadados (SELinux) no
        # filesDir do app → copy2 lançava PermissionError em _copyxattr.
        shutil.copyfile(arquivos[0], saida)
        return True

    # Para listas pequenas, concatena diretamente
    if len(arquivos) <= _CONCAT_BATCH_SIZE:
        return await _concatenar_lista(arquivos, saida)

    # Para listas grandes (ebooks longos), concatenação hierárquica em lotes.
    # Divide a lista em lotes, concatena cada lote num arquivo temporário,
    # depois concatena os temporários - processo se repete recursivamente
    # até caber num único lote.
    lotes = [
        arquivos[i:i + _CONCAT_BATCH_SIZE]
        for i in range(0, len(arquivos), _CONCAT_BATCH_SIZE)
    ]
    temp_dir = str(Path(saida).parent)
    nome_base = Path(saida).name
    lote_files: List[str] = []
    try:
        for idx, lote in enumerate(lotes):
            f_lote = os.path.join(temp_dir, f"_lote{idx}_{nome_base}")
            ok = await _concatenar_lista(lote, f_lote)
            if not ok:
                return False
            lote_files.append(f_lote)
        # Recursão: permite listas com qualquer número de arquivos
        return await concatenar_grupo(lote_files, saida)
    finally:
        for f in lote_files:
            if os.path.exists(f):
                try:
                    os.remove(f)
                except OSError:
                    pass


async def concatenar_grupo_com_pausa(
    arquivos: list, saida: str, pausa_ms: int = 600, pausas_ms: list = None
) -> bool:
    """Junta os arquivos com silêncio entre eles. `pausas_ms[i]` (opcional) é a pausa depois do
    arquivo i; None ou ausente = `pausa_ms`. Usado pelo modo multi-personagem (trocas de voz curtas)."""
    if not arquivos:
        return False
    if len(arquivos) == 1:
        # copyfile (não copy2): evita PermissionError de xattr no Android (ver concatenar_grupo).
        shutil.copyfile(arquivos[0], saida)
        return True

    pasta = str(Path(saida).parent)
    silencios = {}  # duração (ms) -> arquivo de silêncio

    async def _silencio(ms: int):
        if ms not in silencios:
            caminho = os.path.join(pasta, f"_silence_{ms}.mp3")
            ok = await executar_ffmpeg(
                "-y", "-f", "lavfi",
                "-i", "anullsrc=channel_layout=mono:sample_rate=24000",
                "-t", str(ms / 1000.0),
                "-c:a", "libmp3lame", "-q:a", "9",
                caminho,
            )
            silencios[ms] = caminho if ok else None
        return silencios[ms]

    try:
        intercalados = []
        for i, arq in enumerate(arquivos):
            intercalados.append(arq)
            if i < len(arquivos) - 1:
                ms = pausa_ms
                if pausas_ms and i < len(pausas_ms) and pausas_ms[i] is not None:
                    ms = int(pausas_ms[i])
                arquivo_silencio = await _silencio(ms)
                if arquivo_silencio is None:
                    # sem ffmpeg para gerar o silêncio: mantém o comportamento antigo (sem pausas)
                    return await concatenar_grupo(arquivos, saida)
                intercalados.append(arquivo_silencio)
        return await concatenar_grupo(intercalados, saida)
    finally:
        for caminho in silencios.values():
            if caminho and os.path.exists(caminho):
                try:
                    os.remove(caminho)
                except OSError:
                    pass


def montar_filtro_timbre(tom_meios_tons: int, agudos_db: int) -> str:
    """Filtro ffmpeg do timbre: tom em meios-tons de semitom (sem mudar a duração) e prateleira de
    agudos. Devolve "" quando nada precisa ser feito."""
    partes = []
    if tom_meios_tons:
        fator = 2.0 ** (tom_meios_tons / 24.0)
        taxa = 24000
        # reamostra para a taxa fixa, muda a taxa "aparente" (sobe/desce o tom) e compensa a duração
        partes.append(
            f"aformat=sample_rates={taxa}:channel_layouts=mono,asetrate={int(round(taxa * fator))},"
            f"aresample={taxa},atempo={1.0 / fator:.6f}"
        )
    if agudos_db:
        partes.append(f"treble=g=-{int(agudos_db)}:f=6000:t=s")
    return ",".join(partes)


async def aplicar_timbre(arquivo: str, tom_meios_tons: int, agudos_db: int, bitrate_kbps: int = 64) -> bool:
    """Reescreve o MP3 final com o timbre escolhido. Se o ffmpeg falhar, o arquivo original é mantido."""
    filtro = montar_filtro_timbre(tom_meios_tons, agudos_db)
    if not filtro or not os.path.exists(arquivo):
        return False
    temporario = arquivo + ".timbre.mp3"
    ok = await executar_ffmpeg(
        "-y", "-i", arquivo, "-af", filtro,
        "-c:a", "libmp3lame", "-b:a", f"{int(bitrate_kbps)}k", temporario,
    )
    if ok and os.path.exists(temporario) and os.path.getsize(temporario) > 500:
        os.replace(temporario, arquivo)
        return True
    if os.path.exists(temporario):
        try:
            os.remove(temporario)
        except OSError:
            pass
    return False


def adicionar_metadados_mp3(
    filepath: str, titulo: str = "", artista: str = "LylyReader",
    album: str = "Audiobook", genero: str = "Audiobook", comentario: str = "",
    capa_bytes: bytes = None,
):
    try:
        # WAV disfarçado de .mp3 (Gemini sem FFmpeg): ID3 corromperia o RIFF header
        with open(filepath, 'rb') as _f:
            if _f.read(4) == b'RIFF':
                return False
        try:
            tags = ID3(filepath)
        except ID3NoHeaderError:
            tags = ID3()
        if titulo:
            tags.add(TIT2(encoding=3, text=titulo))
        if artista:
            tags.add(TPE1(encoding=3, text=artista))
        if album:
            tags.add(TALB(encoding=3, text=album))
        if genero:
            tags.add(TCON(encoding=3, text=genero))
        tags.add(TDRC(encoding=3, text=str(datetime.now().year)))
        if comentario:
            tags.add(COMM(encoding=3, lang='por', desc='', text=comentario))
        if capa_bytes and len(capa_bytes) > 1000:
            tags.add(APIC(encoding=3, mime="image/jpeg", type=3, desc="Cover", data=capa_bytes))
        tags.save(filepath)
        return True
    except Exception as e:
        logger.warning(f"Falha ao adicionar metadados: {e}")
        return False


def adicionar_chapter_marks(filepath: str, capitulos: list) -> bool:
    if not capitulos:
        return False
    try:
        # WAV disfarçado de .mp3: pula chapter marks (ID3 corromperia o RIFF header)
        with open(filepath, 'rb') as _f:
            if _f.read(4) == b'RIFF':
                return False
        try:
            tags = ID3(filepath)
        except ID3NoHeaderError:
            tags = ID3()
        for key in list(tags.keys()):
            if key.startswith("CHAP:") or key.startswith("CTOC:"):
                del tags[key]
        ids = [f"ch{i}" for i in range(len(capitulos))]
        tags.add(CTOC(
            element_id="toc",
            flags=CTOCFlags.TOP_LEVEL | CTOCFlags.ORDERED,
            child_element_ids=ids,
            sub_frames=[TIT2(encoding=3, text="Índice")],
        ))
        for i, cap in enumerate(capitulos):
            tags.add(CHAP(
                element_id=ids[i],
                start_time=cap["inicio_ms"],
                end_time=cap["fim_ms"],
                start_offset=0xFFFFFFFF,
                end_offset=0xFFFFFFFF,
                sub_frames=[TIT2(encoding=3, text=cap["titulo"])],
            ))
        tags.save(filepath)
        return True
    except Exception as e:
        logger.warning(f"Falha ao adicionar chapter marks: {e}")
        return False

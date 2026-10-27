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
from typing import Callable, List, Optional

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
from erros_tts import ErroDeChave

_MAX_RETRIES = 5
_CONCAT_BATCH_SIZE = 50  # Máx. de entradas por operação de concat
_VOICE_VALIDATION_TTL_SECONDS = 10 * 60
_COPIA_BUFFER_BYTES = 4 * 1024 * 1024  # buffer da cópia binária da junção (P3.2)
_ID3_PADDING_MINIMO = 8192  # padding reservado ao gravar tags, para updates futuros sem reescrever
_validated_voices = {}


class JuncaoCancelada(Exception):
    """Levantada pela junção quando o cancelamento é pedido entre dois lotes (P4.2)."""


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


def _duracao_wav(caminho: str) -> float:
    """
    Duração de um WAV pelo cabeçalho, sem decodificar o áudio. Cobre o WAV disfarçado de .mp3
    (motor sem FFmpeg), que o mutagen não lê. Chunk 'data' com tamanho 0xFFFFFFFF ou maior que o
    arquivo usa o tamanho real do arquivo. Devolve 0.0 se o cabeçalho não tiver fmt/data válidos.
    """
    try:
        with open(caminho, 'rb') as f:
            data = f.read()
    except Exception:
        return 0.0
    if data[:4] != b'RIFF' or data[8:12] != b'WAVE':
        return 0.0
    byte_rate = 0
    tamanho_data = None
    i = 12  # mesmo percurso de chunks de _concatenar_wav_puro
    while i + 8 <= len(data):
        chunk_id = data[i:i+4]
        chunk_size = struct.unpack('<I', data[i+4:i+8])[0]
        if chunk_id == b'fmt ' and chunk_size >= 16 and i + 20 <= len(data):
            # fmt: audioFormat(H) channels(H) sampleRate(I) byteRate(I) ...
            byte_rate = struct.unpack('<I', data[i+16:i+20])[0]
        if chunk_id == b'data':
            disponivel = len(data) - (i + 8)
            tamanho_data = disponivel if chunk_size == 0xFFFFFFFF or chunk_size > disponivel else chunk_size
            break
        i += 8 + chunk_size + (chunk_size % 2)  # chunks RIFF são word-aligned
    if not byte_rate or tamanho_data is None:
        return 0.0
    return tamanho_data / byte_rate


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
                        shutil.copyfileobj(f, out, _COPIA_BUFFER_BYTES)
                    primeiro_escrito = True
                else:
                    # Demais: pula o header ID3v2, copia só os frames MP3
                    offset = _mp3_audio_offset(arq)
                    with open(arq, 'rb') as f:
                        if offset > 0:
                            f.seek(offset)
                        shutil.copyfileobj(f, out, _COPIA_BUFFER_BYTES)
        return os.path.exists(saida) and os.path.getsize(saida) > 500
    except Exception as e:
        logger.error(f"Erro na concatenação MP3 puro: {e}")
        return False


# ── Silêncio sem FFmpeg (pausas entre blocos) ────────────────────────────────

# Layer III: bitrates (kbps) por índice e taxas de amostragem por versão do MPEG.
_MP3_BITRATES_V1 = (0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320)
_MP3_BITRATES_V2 = (0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160)
_MP3_TAXAS = {3: (44100, 48000, 32000), 2: (22050, 24000, 16000), 0: (11025, 12000, 8000)}


def _formato_wav(caminho: str) -> Optional[tuple]:
    """(taxa, canais, bits) do chunk fmt de um WAV; None se não for WAV legível."""
    try:
        with open(caminho, "rb") as f:
            dados = f.read(4096)
    except OSError:
        return None
    if dados[:4] != b"RIFF" or dados[8:12] != b"WAVE":
        return None
    i = 12
    while i + 8 <= len(dados):
        tamanho = struct.unpack("<I", dados[i + 4:i + 8])[0]
        if dados[i:i + 4] == b"fmt " and tamanho >= 16 and i + 24 <= len(dados):
            canais, taxa = struct.unpack("<HI", dados[i + 10:i + 16])
            bits = struct.unpack("<H", dados[i + 22:i + 24])[0]
            return (taxa, canais, bits) if taxa > 0 and canais > 0 and bits > 0 else None
        i += 8 + tamanho + (tamanho % 2)
    return None


def _quadro_mp3_silencioso(caminho: str) -> Optional[tuple]:
    """Quadro MP3 (Layer III) mudo no mesmo formato do primeiro quadro de `caminho`, e quantos ms ele dura.

    Cabeçalho igual ao do arquivo (sem CRC e sem padding) seguido de zeros: com a informação lateral
    zerada o quadro não tem dados de áudio, e o decodificador toca silêncio. Assim o silêncio entra
    na junção binária dos MP3 sem reencodar nada."""
    try:
        with open(caminho, "rb") as f:
            f.seek(_mp3_audio_offset(caminho))
            cab = f.read(4)
    except OSError:
        return None
    if len(cab) < 4 or cab[0] != 0xFF or (cab[1] & 0xE0) != 0xE0:
        return None
    versao = (cab[1] >> 3) & 0x03
    camada = (cab[1] >> 1) & 0x03
    indice_bitrate = cab[2] >> 4
    indice_taxa = (cab[2] >> 2) & 0x03
    if versao == 1 or camada != 1 or indice_taxa == 3 or indice_bitrate in (0, 15):
        return None  # reservado, livre ou não é Layer III
    mpeg1 = versao == 3
    bitrate = (_MP3_BITRATES_V1 if mpeg1 else _MP3_BITRATES_V2)[indice_bitrate] * 1000
    taxa = _MP3_TAXAS[versao][indice_taxa]
    tamanho = (144 if mpeg1 else 72) * bitrate // taxa
    amostras = 1152 if mpeg1 else 576
    cabecalho = bytes([cab[0], cab[1] | 0x01, cab[2] & ~0x02 & 0xFF, cab[3]])
    return cabecalho + bytes(tamanho - 4), amostras * 1000.0 / taxa


def _quadros_silencio(ms: int, ms_por_quadro: float) -> int:
    """Quadros MP3 mudos que cobrem `ms` (no mínimo 1). Mesma conta na escrita e em duracao_real_silencio_ms."""
    return max(1, int(round(ms / ms_por_quadro)))


def _amostras_silencio(ms: int, taxa: int) -> int:
    """Amostras WAV de um silêncio de `ms` (arredondado para amostra inteira). Mesma conta na escrita e na duração real."""
    return int(round(taxa * ms / 1000.0))


def duracao_real_silencio_ms(modelo: str, ms: int) -> float:
    """Duração (ms) do silêncio que `gerar_silencio_como(modelo, ms, ...)` grava de fato: MP3 em quadros inteiros,
    WAV em amostras inteiras. `ms` <= 0 não grava silêncio (0.0). Formato desconhecido também dá 0.0: sem silêncio
    possível, concatenar_grupo_com_pausa junta os blocos sem pausa nenhuma."""
    ms = max(0, int(ms))
    if ms == 0:
        return 0.0
    try:
        formato = _formato_wav(modelo) if _is_wav(modelo) else None
        if formato:
            taxa = formato[0]
            return _amostras_silencio(ms, taxa) * 1000.0 / taxa
        if _is_wav(modelo):
            return 0.0
        quadro = _quadro_mp3_silencioso(modelo)
        if quadro is None:
            return 0.0
        return _quadros_silencio(ms, quadro[1]) * quadro[1]
    except Exception:
        return 0.0


def gerar_silencio_como(modelo: str, ms: int, saida: str) -> bool:
    """Grava em `saida` `ms` de silêncio no mesmo formato de `modelo` (WAV PCM ou MP3 Layer III), sem FFmpeg.
    Devolve False se o formato não for reconhecido. A duração gravada é a de duracao_real_silencio_ms."""
    ms = max(0, int(ms))
    try:
        formato = _formato_wav(modelo) if _is_wav(modelo) else None
        if formato:
            taxa, canais, bits = formato
            bloco = canais * bits // 8
            pcm = bytes(_amostras_silencio(ms, taxa) * bloco)
            cab = (
                b"RIFF" + struct.pack("<I", 36 + len(pcm)) + b"WAVE"
                + b"fmt " + struct.pack("<IHHIIHH", 16, 1, canais, taxa, taxa * bloco, bloco, bits)
                + b"data" + struct.pack("<I", len(pcm))
            )
            with open(saida, "wb") as f:
                f.write(cab + pcm)
            return True
        if _is_wav(modelo):
            return False
        quadro = _quadro_mp3_silencioso(modelo)
        if quadro is None:
            return False
        dados, ms_por_quadro = quadro
        with open(saida, "wb") as f:
            f.write(dados * _quadros_silencio(ms, ms_por_quadro))
        return True
    except Exception as e:
        logger.warning(f"Silêncio da junção não foi gerado: {e}")
        return False


def intercalar_silencios(arquivos: list, pausas_ms: list, pasta: str, silencios: dict) -> Optional[list]:
    """Lista de junção com um arquivo de silêncio depois de cada arquivo (menos o último). `pausas_ms[i]` é a
    pausa depois do arquivo i (0 = nenhuma). Os silêncios são criados em `pasta`, no formato do primeiro
    arquivo, e guardados em `silencios` (ms → caminho) para reaproveitar. None se o silêncio não puder ser
    gerado."""
    if not arquivos:
        return []
    modelo = arquivos[0]
    tipo = "wav" if _is_wav(modelo) else "mp3"
    intercalados = []
    for i, arq in enumerate(arquivos):
        intercalados.append(arq)
        ms = int(pausas_ms[i]) if i < len(pausas_ms) and pausas_ms[i] else 0
        if i == len(arquivos) - 1 or ms <= 0:
            continue
        chave = (tipo, ms)
        if chave not in silencios:
            caminho = os.path.join(pasta, f"_silence_{ms}_{tipo}.mp3")
            silencios[chave] = caminho if gerar_silencio_como(modelo, ms, caminho) else None
        if silencios[chave] is None:
            return None
        intercalados.append(silencios[chave])
    return intercalados


def apagar_silencios(silencios: dict) -> None:
    for caminho in silencios.values():
        if caminho and os.path.exists(caminho):
            try:
                os.remove(caminho)
            except OSError:
                pass


def timeout_para_texto(texto: str) -> float:
    """Timeout (s) de uma chamada Edge para um bloco: 30 s + 1 s a cada 20 caracteres, entre 30 e 90 s.
    Folga generosa para redes lentas; blocos curtos falham um pouco mais cedo e blocos longos mantêm o teto de 90 s."""
    return min(90.0, max(30.0, 30.0 + len(texto or "") / 20.0))


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
                    timeout=timeout_para_texto(texto),
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


def _motivo_eh_chave_elevenlabs(motivo: str) -> bool:
    """Motivo da ponte ElevenLabs que indica chave recusada (HTTP 401/403) ou ausente.
    Cota esgotada também volta como 401, mas não é problema de chave: fica de fora (segue o retry)."""
    m = (motivo or "").lower()
    if "quota" in m:
        return False
    return "http 401" in m or "http 403" in m or "chave elevenlabs ausente" in m


def motor_da_voz(voz: str) -> str:
    """Motor que converter_tts_auto usa para a voz: android, onnx, elevenlabs, pocket, kokoro,
    openrouter, gemini ou edge (default). Única fonte da classificação por voz."""
    if voz.startswith("android::"):
        return "android"
    if voz.startswith("supertonic-"):
        return "onnx"
    if voz.startswith("elevenlabs-"):
        return "elevenlabs"
    if voz.startswith("pocket-"):
        return "pocket"
    if voz.startswith("kokoro-"):
        return "kokoro"
    if "::" in voz:
        # Voz de pacote local (BYOM da V6 Parte B, "byom-*", OU um pacote de 1ª classe que
        # reaproveita o mesmo formato, como o MMS-TTS da Parte A, "mms-por") — mesma ponte
        # Kotlin do kokoro (KokoroSynthBridge já é genérica por voiceId; o motor Kotlin resolve
        # o pacote/arquitetura internamente). "android::" já foi tratado acima, então qualquer
        # "::" que sobra até aqui é sempre um pacote local, nunca um motor de nuvem.
        return "kokoro"
    if voz.startswith("openrouter-"):
        return "openrouter"
    if voz in {v for cat in _config.GEMINI_VOICES.values() for v in cat.values()}:
        return "gemini"
    return "edge"


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
    motor = motor_da_voz(voz)

    if motor == "android":
        # A síntese Android (voz nativa instalada no aparelho) é delegada ao motor Kotlin via
        # AndroidSynthBridge — o TextToSpeech do Android precisa rodar numa thread com Looper,
        # que o Chaquopy não tem; o lado Kotlin cuida disso internamente.
        # As pontes Kotlin são síncronas (bloqueiam até o fim da síntese). Para não travar o event
        # loop (batimento e "Tempo" congelados), rodam numa thread de worker via to_thread.
        try:
            from com.jonjonesbr.audiobookgen.tts import AndroidSynthBridge
            ok = bool(await asyncio.to_thread(AndroidSynthBridge.synthesize, texto, voz, arquivo_saida))
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
            ok = bool(await asyncio.to_thread(OnnxSynthBridge.synthesize, texto, voz, arquivo_saida))
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
            ok = bool(await asyncio.to_thread(KokoroSynthBridge.synthesize, texto, voz, arquivo_saida))
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
            ok = bool(await asyncio.to_thread(PocketSynthBridge.synthesize, texto, voz, arquivo_saida))
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
            ok = bool(await asyncio.to_thread(ElevenLabsSynthBridge.synthesize, texto, voz, arquivo_saida))
            if not ok:
                motivo = ElevenLabsSynthBridge.ultimoMotivo() or "motivo desconhecido"
                logger.warning(
                    f"[TTS] Motor ElevenLabs (Kotlin) falhou para voz '{voz}': {motivo}"
                )
                if _motivo_eh_chave_elevenlabs(motivo):
                    raise ErroDeChave(
                        f"ElevenLabs: chave recusada ou ausente ({motivo[:200]}). "
                        "Revise a chave salva nas configurações."
                    )
            return ok
        except ErroDeChave:
            raise
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
        logger.warning(f"ffprobe indisponível ({e}); usando mutagen")
    # WAV disfarçado de .mp3 (motor local/Gemini sem FFmpeg): o mutagen não lê, então usa o cabeçalho.
    if _is_wav(caminho):
        return _duracao_wav(caminho)
    try:
        return float(MP3(caminho).info.length)
    except Exception as e:
        logger.error(f"duração via mutagen falhou: {e}")
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


async def concatenar_grupo(
    arquivos: list, saida: str, on_progress: Optional[Callable[[float], None]] = None,
    cancelado: Optional[Callable[[], bool]] = None,
) -> bool:
    """`on_progress(fracao)` (0..1) informa o andamento. Em listas grandes, os lotes ocupam 0..0.9
    e a junção final o restante; em listas pequenas só há o 1.0 ao fim.
    `cancelado()` é consultado antes de cada lote: se devolver True, levanta JuncaoCancelada."""
    if cancelado and cancelado():
        raise JuncaoCancelada()
    if not arquivos:
        return False
    if len(arquivos) == 1:
        # copyfile (não copy2): o Android bloqueia copiar xattr/metadados (SELinux) no
        # filesDir do app → copy2 lançava PermissionError em _copyxattr.
        shutil.copyfile(arquivos[0], saida)
        if on_progress:
            on_progress(1.0)
        return True

    # Para listas pequenas, concatena diretamente
    if len(arquivos) <= _CONCAT_BATCH_SIZE:
        ok = await _concatenar_lista(arquivos, saida)
        if ok and on_progress:
            on_progress(1.0)
        return ok

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
    fracao_lotes = 0.9  # o restante (10%) é a junção final dos lotes
    try:
        for idx, lote in enumerate(lotes):
            if cancelado and cancelado():
                raise JuncaoCancelada()
            f_lote = os.path.join(temp_dir, f"_lote{idx}_{nome_base}")
            ok = await _concatenar_lista(lote, f_lote)
            if not ok:
                return False
            lote_files.append(f_lote)
            if on_progress:
                on_progress(fracao_lotes * (idx + 1) / len(lotes))
        # Recursão: permite listas com qualquer número de arquivos. Seu progresso é
        # reescalado para a faixa final (0.9..1.0).
        sub_progresso = (
            (lambda f: on_progress(fracao_lotes + (1.0 - fracao_lotes) * f)) if on_progress else None
        )
        return await concatenar_grupo(lote_files, saida, sub_progresso, cancelado)
    finally:
        for f in lote_files:
            if os.path.exists(f):
                try:
                    os.remove(f)
                except OSError:
                    pass


async def concatenar_grupo_com_pausa(
    arquivos: list, saida: str, pausa_ms: int = 600, pausas_ms: list = None,
    on_progress: Optional[Callable[[float], None]] = None,
    cancelado: Optional[Callable[[], bool]] = None,
) -> bool:
    """Junta os arquivos com silêncio entre eles. `pausas_ms[i]` (opcional) é a pausa depois do
    arquivo i; None ou ausente = `pausa_ms`. Usado pelo modo multi-personagem (trocas de voz curtas).
    `on_progress` (opcional) recebe a fração 0..1 da junção (ver concatenar_grupo).
    `cancelado` (opcional) é repassado à concatenação (ver concatenar_grupo)."""
    if not arquivos:
        return False
    if len(arquivos) == 1:
        # copyfile (não copy2): evita PermissionError de xattr no Android (ver concatenar_grupo).
        shutil.copyfile(arquivos[0], saida)
        if on_progress:
            on_progress(1.0)
        return True

    pasta = str(Path(saida).parent)
    pausas = [
        pausas_ms[i] if pausas_ms and i < len(pausas_ms) and pausas_ms[i] is not None else pausa_ms
        for i in range(len(arquivos))
    ]
    silencios: dict = {}
    try:
        # Silêncio gerado em Python puro, no formato dos blocos (o Android não tem FFmpeg).
        intercalados = intercalar_silencios(arquivos, pausas, pasta, silencios)
        if intercalados is None:
            logger.warning("Formato de áudio sem silêncio conhecido: junção sem pausas entre blocos.")
            return await concatenar_grupo(arquivos, saida, on_progress, cancelado)
        return await concatenar_grupo(intercalados, saida, on_progress, cancelado)
    finally:
        apagar_silencios(silencios)


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


def _carregar_tags_mp3(filepath: str) -> ID3:
    try:
        return ID3(filepath)
    except ID3NoHeaderError:
        return ID3()


def _aplicar_metadados(
    tags: ID3, titulo: str, artista: str, album: str, genero: str, comentario: str, capa_bytes: bytes,
) -> None:
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


def _aplicar_capitulos(tags: ID3, capitulos: list) -> None:
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


def _padding_id3(info) -> int:
    """Padding do ID3 ao salvar: o mínimo de _ID3_PADDING_MINIMO, ou o padrão do mutagen se for maior."""
    return max(info.get_default_padding(), _ID3_PADDING_MINIMO)


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
        tags = _carregar_tags_mp3(filepath)
        _aplicar_metadados(tags, titulo, artista, album, genero, comentario, capa_bytes)
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
        tags = _carregar_tags_mp3(filepath)
        _aplicar_capitulos(tags, capitulos)
        tags.save(filepath)
        return True
    except Exception as e:
        logger.warning(f"Falha ao adicionar chapter marks: {e}")
        return False


def adicionar_metadados_e_capitulos_mp3(
    filepath: str, titulo: str = "", artista: str = "LylyReader",
    album: str = "Audiobook", genero: str = "Audiobook", comentario: str = "",
    capa_bytes: bytes = None, capitulos: list = None,
) -> bool:
    """Grava metadados e chapter marks numa única passada de tags (P3.4): o arquivo inteiro é
    reescrito uma vez, e não duas, como ocorria com adicionar_metadados_mp3 + adicionar_chapter_marks.
    Sem capítulos, grava só os metadados. Reserva padding (_padding_id3) para updates futuros.
    WAV disfarçado de .mp3 é ignorado (não recebe ID3)."""
    try:
        # WAV disfarçado de .mp3: ID3 corromperia o RIFF header
        with open(filepath, 'rb') as _f:
            if _f.read(4) == b'RIFF':
                return False
        tags = _carregar_tags_mp3(filepath)
        _aplicar_metadados(tags, titulo, artista, album, genero, comentario, capa_bytes)
        if capitulos:
            try:
                _aplicar_capitulos(tags, capitulos)
            except Exception as e:
                # Capítulos são opcionais: sem eles, metadados e capa seguem gravados.
                logger.warning(f"Capítulos não gravados (metadados e capa seguem): {e}")
                for key in list(tags.keys()):
                    if key.startswith("CHAP:") or key.startswith("CTOC:"):
                        del tags[key]
        tags.save(filepath, padding=_padding_id3)
        return True
    except Exception as e:
        logger.warning(f"Falha ao gravar metadados e capítulos: {e}")
        return False

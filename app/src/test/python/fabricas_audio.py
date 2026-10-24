"""Arquivos de áudio falsos para os testes de junção e ID3. Não são áudio de verdade: MP3 com
cabeçalho de quadro e WAV PCM 24 kHz mono 16 bits, com bytes determinísticos e sem 0xFF (sem sync falso)."""

import struct

_QUADRO_MP3 = bytes([0xFF, 0xFB, 0x90, 0x00])


_TAMANHO_QUADRO = 417  # 128 kbps, 44,1 kHz, sem padding: o mutagen consegue sincronizar e ler a duração


def mp3_falso(tamanho: int, com_id3: bool = False, semente: int = 0) -> bytes:
    """MP3 falso de ~`tamanho` bytes, feito de quadros de 417 bytes (o mutagen lê a duração).
    Com `com_id3`, começa por um cabeçalho ID3v2 com padding de zeros."""
    quadros = max(1, -(-tamanho // _TAMANHO_QUADRO))
    corpo = bytearray()
    for q in range(quadros):
        corpo += _QUADRO_MP3
        corpo += bytes(((semente + q * 13 + j) % 251) + 1 for j in range(_TAMANHO_QUADRO - 4))
    corpo = bytes(corpo)
    if not com_id3:
        return corpo
    payload = 64
    tamanho_syncsafe = bytes([(payload >> 21) & 0x7F, (payload >> 14) & 0x7F,
                              (payload >> 7) & 0x7F, payload & 0x7F])
    return b"ID3\x03\x00\x00" + tamanho_syncsafe + bytes(payload) + corpo


def wav_falso(n_amostras: int, semente: int = 0) -> bytes:
    """WAV falso PCM 24000 Hz, mono, 16 bits, com `n_amostras` amostras."""
    pcm = bytes(((semente + j) % 251) + 1 for j in range(n_amostras * 2))
    fmt = struct.pack("<HHIIHH", 1, 1, 24000, 48000, 2, 16)
    cabecalho = (
        b"RIFF" + struct.pack("<I", 4 + 8 + len(fmt) + 8 + len(pcm)) + b"WAVE"
        + b"fmt " + struct.pack("<I", len(fmt)) + fmt
        + b"data" + struct.pack("<I", len(pcm))
    )
    return cabecalho + pcm


def gravar(caminho: str, dados: bytes) -> str:
    with open(caminho, "wb") as f:
        f.write(dados)
    return caminho

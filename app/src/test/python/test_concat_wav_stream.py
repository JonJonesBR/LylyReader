"""
S6: a junção WAV em duas passagens (streaming) tem que gerar exatamente os mesmos bytes
que a versão antiga, que lia todos os WAVs para a memória. A versão antiga fica aqui congelada
como referência.
"""
import asyncio
import logging
import os
import random
import struct
import sys
import threading
from pathlib import Path
from unittest.mock import AsyncMock

PYTHON_SRC = Path(__file__).parents[2] / "main" / "python"
sys.path.insert(0, str(PYTHON_SRC))

import tts  # noqa: E402

logger = logging.getLogger("concat_wav_antigo")


def _concatenar_wav_antigo(arquivos: list, saida: str) -> bool:
    """Cópia congelada da versão anterior de tts._concatenar_wav_puro (referência)."""
    pcm_chunks = []
    fmt = None  # (sr, ch, bps)
    for arq in arquivos:
        if not Path(arq).exists():
            continue
        try:
            with open(arq, 'rb') as f:
                data = f.read()
            if data[:4] != b'RIFF':
                continue
            i = 12
            while i + 8 <= len(data):
                chunk_id = data[i:i+4]
                chunk_size = struct.unpack('<I', data[i+4:i+8])[0]
                if chunk_id == b'fmt ' and fmt is None and chunk_size >= 16:
                    ch_f = struct.unpack('<H', data[i+10:i+12])[0]
                    sr_f = struct.unpack('<I', data[i+12:i+16])[0]
                    bps_f = struct.unpack('<H', data[i+22:i+24])[0]
                    if sr_f > 0 and ch_f > 0 and bps_f > 0:
                        fmt = (sr_f, ch_f, bps_f)
                if chunk_id == b'data':
                    pcm_chunks.append(data[i+8:i+8+chunk_size])
                    break
                i += 8 + chunk_size + (chunk_size % 2)
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



def _monta_wav(pcm: bytes, sr: int = 24000, ch: int = 1, bps: int = 16,
               meio: bytes = b"", tam_data=None) -> bytes:
    """WAV PCM: RIFF, fmt, chunks extras (meio) e data. tam_data sobrescreve o tamanho declarado."""
    fmt = b"fmt " + struct.pack("<IHHIIHH", 16, 1, ch, sr, sr * ch * bps // 8, ch * bps // 8, bps)
    data_cs = len(pcm) if tam_data is None else tam_data
    corpo = b"WAVE" + fmt + meio + b"data" + struct.pack("<I", data_cs) + pcm
    return b"RIFF" + struct.pack("<I", len(corpo)) + corpo


def _pcm(n: int, semente: int) -> bytes:
    return random.Random(semente).randbytes(n)


def _grava(pasta: Path, nome: str, conteudo: bytes) -> str:
    caminho = pasta / nome
    caminho.write_bytes(conteudo)
    return str(caminho)


def _executa(func, arquivos: list, pasta: Path, nome: str):
    saida = pasta / nome
    ret = func(arquivos, str(saida))
    dados = saida.read_bytes() if saida.exists() else None
    return ret, dados


def _compara(tmp_path: Path, arquivos: list):
    antigo = _executa(_concatenar_wav_antigo, arquivos, tmp_path, "antigo.wav")
    novo = _executa(tts._concatenar_wav_puro, arquivos, tmp_path, "novo.wav")
    assert novo[0] == antigo[0]
    assert novo[1] == antigo[1]
    return antigo


def test_taxas_misturadas_usa_o_primeiro_fmt_valido(tmp_path):
    arquivos = [
        _grava(tmp_path, "a.wav", _monta_wav(_pcm(3000, 1), sr=24000)),
        _grava(tmp_path, "b.wav", _monta_wav(_pcm(1001, 2), sr=44100)),
        _grava(tmp_path, "c.wav", _monta_wav(_pcm(2222, 3), sr=24000)),
    ]
    ret, dados = _compara(tmp_path, arquivos)
    assert ret is True
    assert dados[24:28] == struct.pack("<I", 24000)


def test_chunk_list_antes_do_data(tmp_path):
    meio = b"LIST" + struct.pack("<I", 10) + b"INFOabcdef"
    arquivos = [_grava(tmp_path, "a.wav", _monta_wav(_pcm(2000, 4), meio=meio))]
    ret, _ = _compara(tmp_path, arquivos)
    assert ret is True


def test_chunk_impar_antes_do_data_recebe_padding(tmp_path):
    meio = b"LIST" + struct.pack("<I", 5) + b"abcde" + b"\x00"
    arquivos = [
        _grava(tmp_path, "a.wav", _monta_wav(_pcm(1500, 5), meio=meio)),
        _grava(tmp_path, "b.wav", _monta_wav(_pcm(1500, 6), meio=b"junk" + struct.pack("<I", 3) + b"xyz\x00")),
    ]
    ret, _ = _compara(tmp_path, arquivos)
    assert ret is True


def test_data_com_tamanho_0xffffffff(tmp_path):
    arquivos = [
        _grava(tmp_path, "a.wav", _monta_wav(_pcm(4000, 7), tam_data=0xFFFFFFFF)),
        _grava(tmp_path, "b.wav", _monta_wav(_pcm(900, 8))),
    ]
    ret, dados = _compara(tmp_path, arquivos)
    assert ret is True
    assert len(dados) == 44 + 4000 + 900


def test_arquivo_truncado_em_7_bytes(tmp_path):
    completo = _monta_wav(_pcm(3000, 9))
    arquivos = [
        _grava(tmp_path, "a.wav", completo[:-7]),
        _grava(tmp_path, "b.wav", _monta_wav(_pcm(2000, 10))),
    ]
    ret, dados = _compara(tmp_path, arquivos)
    assert ret is True
    assert len(dados) == 44 + (3000 - 7) + 2000


def test_arquivo_que_nao_e_riff_e_ignorado(tmp_path):
    arquivos = [
        _grava(tmp_path, "id3.mp3", b"ID3" + _pcm(3000, 11)),
        _grava(tmp_path, "b.wav", _monta_wav(_pcm(2500, 12))),
    ]
    ret, dados = _compara(tmp_path, arquivos)
    assert ret is True
    assert len(dados) == 44 + 2500


def test_arquivo_faltando_e_ignorado(tmp_path):
    arquivos = [
        str(tmp_path / "nao_existe.wav"),
        _grava(tmp_path, "b.wav", _monta_wav(_pcm(2500, 13))),
    ]
    ret, _ = _compara(tmp_path, arquivos)
    assert ret is True


def test_lista_vazia_retorna_false_sem_gravar(tmp_path):
    ret, dados = _compara(tmp_path, [])
    assert ret is False
    assert dados is None


def test_so_arquivos_invalidos_retorna_false(tmp_path):
    arquivos = [
        _grava(tmp_path, "x.bin", _pcm(800, 14)),
        str(tmp_path / "faltando.wav"),
    ]
    ret, _ = _compara(tmp_path, arquivos)
    assert ret is False


def test_fmt_truncado_pula_o_arquivo_inteiro(tmp_path):
    truncado = b"RIFF" + struct.pack("<I", 30) + b"WAVE" + b"fmt " + struct.pack("<I", 16) + b"\x01\x00"
    arquivos = [
        _grava(tmp_path, "ruim.wav", truncado),
        _grava(tmp_path, "b.wav", _monta_wav(_pcm(2500, 15), sr=44100)),
    ]
    ret, dados = _compara(tmp_path, arquivos)
    assert ret is True
    assert dados[24:28] == struct.pack("<I", 44100)


def test_fmt_com_taxa_zero_e_ignorado_ate_o_proximo(tmp_path):
    arquivos = [
        _grava(tmp_path, "zero.wav", _monta_wav(_pcm(2000, 16), sr=0)),
        _grava(tmp_path, "b.wav", _monta_wav(_pcm(2000, 17), sr=32000)),
    ]
    ret, dados = _compara(tmp_path, arquivos)
    assert ret is True
    assert dados[24:28] == struct.pack("<I", 32000)


def test_arquivo_sem_chunk_data_e_ignorado(tmp_path):
    so_fmt = b"RIFF" + struct.pack("<I", 28) + b"WAVE" + b"fmt " + struct.pack("<IHHIIHH", 16, 1, 1, 24000,
                                                                                  48000, 2, 16)
    arquivos = [
        _grava(tmp_path, "sem_data.wav", so_fmt),
        _grava(tmp_path, "b.wav", _monta_wav(_pcm(2500, 18))),
    ]
    ret, dados = _compara(tmp_path, arquivos)
    assert ret is True
    assert len(dados) == 44 + 2500


def test_data_vazio_e_bloco_grande_mais_de_um_mebibyte(tmp_path):
    arquivos = [
        _grava(tmp_path, "vazio.wav", _monta_wav(b"")),
        _grava(tmp_path, "grande.wav", _monta_wav(_pcm(2_500_000, 19))),
        _grava(tmp_path, "pequeno.wav", _monta_wav(_pcm(1234, 20))),
    ]
    ret, dados = _compara(tmp_path, arquivos)
    assert ret is True
    assert len(dados) == 44 + 2_500_000 + 1234


def test_concatenar_lista_sem_ffmpeg_gera_os_mesmos_bytes_em_thread(tmp_path, monkeypatch):
    arquivos = [
        _grava(tmp_path, "a.wav", _monta_wav(_pcm(3000, 21), sr=24000)),
        _grava(tmp_path, "b.wav", _monta_wav(_pcm(1500, 22), sr=44100, meio=b"LIST" + struct.pack("<I", 2) + b"ok")),
    ]
    esperado_ret, esperado = _executa(_concatenar_wav_antigo, arquivos, tmp_path, "esperado.wav")
    assert esperado_ret is True

    # Android sem FFmpeg: a junção cai na concatenação Python pura.
    monkeypatch.setattr(tts, "executar_ffmpeg", AsyncMock(return_value=False))
    threads = []
    original = tts._concatenar_mp3_puro

    def registrar(arqs, saida):
        threads.append(threading.current_thread())
        return original(arqs, saida)

    monkeypatch.setattr(tts, "_concatenar_mp3_puro", registrar)

    saida = tmp_path / "junta.wav"
    ok = asyncio.run(tts._concatenar_lista(arquivos, str(saida)))

    assert ok is True
    assert saida.read_bytes() == esperado
    assert len(threads) == 1
    assert threads[0] is not threading.main_thread()


def test_concatenar_lista_vazia_retorna_false(tmp_path, monkeypatch):
    monkeypatch.setattr(tts, "executar_ffmpeg", AsyncMock(return_value=False))
    ok = asyncio.run(tts._concatenar_lista([], str(tmp_path / "vazia.wav")))
    assert ok is False

import asyncio
import struct
import sys
import tempfile
import unittest
import wave
from pathlib import Path
from unittest import mock

PYTHON_SRC = Path(__file__).parents[2] / "main" / "python"
sys.path.insert(0, str(PYTHON_SRC))

try:
    import tts
except ImportError:  # dependências de conversão ausentes no ambiente de teste
    tts = None


def _gravar_wav_silencio(caminho: Path, segundos: float = 2.0, taxa: int = 24000) -> None:
    with wave.open(str(caminho), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(taxa)
        w.writeframes(b"\x00\x00" * int(taxa * segundos))


def _gravar_wav_com_data(caminho: Path, tamanho_declarado: int, segundos: float = 2.0, taxa: int = 24000) -> None:
    """WAV escrito à mão com o campo de tamanho do chunk 'data' controlado (ex.: 0xFFFFFFFF)."""
    pcm = b"\x00\x00" * int(taxa * segundos)
    hdr = bytearray()
    hdr += b"RIFF" + struct.pack("<I", 36 + len(pcm)) + b"WAVE"
    hdr += b"fmt " + struct.pack("<IHHIIHH", 16, 1, 1, taxa, taxa * 2, 2, 16)
    hdr += b"data" + struct.pack("<I", tamanho_declarado)
    caminho.write_bytes(bytes(hdr) + pcm)


@unittest.skipIf(tts is None, "módulo tts indisponível")
class DuracaoWavDisfarcadoTest(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.dir = Path(self._tmp.name)
        # Simula o Android sem ffprobe: o create_subprocess_exec falha e cai no plano B.
        patcher = mock.patch("tts.asyncio.create_subprocess_exec", side_effect=FileNotFoundError("ffprobe"))
        patcher.start()
        self.addCleanup(patcher.stop)

    def tearDown(self):
        self._tmp.cleanup()

    def _duracao(self, caminho: Path) -> float:
        return asyncio.run(tts.obter_duracao_ffprobe(str(caminho)))

    def test_wav_disfarcado_de_mp3_devolve_duracao_do_cabecalho(self):
        arq = self.dir / "capitulo.mp3"
        _gravar_wav_silencio(arq, segundos=2.0)
        self.assertAlmostEqual(2.0, self._duracao(arq), places=2)

    def test_duracao_considera_taxa_de_amostragem_do_arquivo(self):
        arq = self.dir / "taxa44.mp3"
        _gravar_wav_silencio(arq, segundos=3.0, taxa=44100)
        self.assertAlmostEqual(3.0, self._duracao(arq), places=2)

    def test_chunk_data_com_tamanho_0xFFFFFFFF_usa_tamanho_real_do_arquivo(self):
        arq = self.dir / "streaming.mp3"
        _gravar_wav_com_data(arq, tamanho_declarado=0xFFFFFFFF, segundos=2.0)
        self.assertAlmostEqual(2.0, self._duracao(arq), places=2)

    def test_chunk_data_maior_que_o_arquivo_usa_tamanho_real(self):
        arq = self.dir / "inconsistente.mp3"
        _gravar_wav_com_data(arq, tamanho_declarado=10_000_000, segundos=2.0)
        self.assertAlmostEqual(2.0, self._duracao(arq), places=2)

    def test_arquivo_que_nao_e_wav_nem_mp3_devolve_zero(self):
        arq = self.dir / "lixo.mp3"
        arq.write_bytes(b"\x01" * 200)
        self.assertEqual(0.0, self._duracao(arq))


if __name__ == "__main__":
    unittest.main()

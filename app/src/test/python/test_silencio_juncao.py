"""Silêncio da junção gerado em Python puro (o Android não tem FFmpeg): mesmo formato dos blocos."""

import os
import struct
import sys
import tempfile
import unittest
from pathlib import Path

PYTHON_SRC = Path(__file__).parents[2] / "main" / "python"
sys.path.insert(0, str(PYTHON_SRC))
sys.path.insert(0, str(Path(__file__).parent))

import tts  # noqa: E402
from fabricas_audio import gravar, wav_falso  # noqa: E402
from mutagen.mp3 import MP3  # noqa: E402

# Formato do Edge TTS: MPEG-2 Layer III, 24 kHz, 48 kbps, mono (quadro de 144 bytes, 24 ms).
_QUADRO_EDGE = bytes([0xFF, 0xF3, 0x64, 0xC4]) + bytes(140)


class SilencioJuncaoTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.pasta = self.tmp.name

    def tearDown(self):
        self.tmp.cleanup()

    def _caminho(self, nome: str) -> str:
        return os.path.join(self.pasta, nome)

    def test_wav_tem_o_formato_do_modelo_e_a_duracao_pedida(self):
        modelo = gravar(self._caminho("bloco.mp3"), wav_falso(1000))
        saida = self._caminho("sil.mp3")
        self.assertTrue(tts.gerar_silencio_como(modelo, 500, saida))
        self.assertEqual((24000, 1, 16), tts._formato_wav(saida))
        self.assertAlmostEqual(0.5, tts._duracao_wav(saida), places=3)
        with open(saida, "rb") as f:
            self.assertEqual(bytes(24000), f.read()[44:])

    def test_mp3_do_edge_vira_quadros_mudos_do_mesmo_formato(self):
        modelo = gravar(self._caminho("bloco.mp3"), _QUADRO_EDGE * 10)
        saida = self._caminho("sil.mp3")
        self.assertTrue(tts.gerar_silencio_como(modelo, 600, saida))
        with open(saida, "rb") as f:
            dados = f.read()
        self.assertEqual(25 * 144, len(dados))  # 600 ms / 24 ms por quadro
        self.assertEqual(bytes([0xFF, 0xF3, 0x64, 0xC4]), dados[:4])
        self.assertEqual(bytes(140), dados[4:144])
        self.assertAlmostEqual(0.6, MP3(saida).info.length, delta=0.03)

    def test_mp3_com_id3_e_crc_sai_sem_crc_e_sem_padding(self):
        quadro_crc_padding = bytes([0xFF, 0xFA, 0x92, 0x00]) + bytes(414)  # MPEG-1, 128 kbps, 44,1 kHz
        id3 = b"ID3\x03\x00\x00\x00\x00\x00\x10" + bytes(16)
        modelo = gravar(self._caminho("bloco.mp3"), id3 + quadro_crc_padding)
        saida = self._caminho("sil.mp3")
        self.assertTrue(tts.gerar_silencio_como(modelo, 100, saida))
        with open(saida, "rb") as f:
            dados = f.read()
        self.assertEqual(bytes([0xFF, 0xFB, 0x90, 0x00]), dados[:4])
        self.assertEqual(0, len(dados) % 417)

    def test_formato_desconhecido_nao_gera_silencio(self):
        modelo = gravar(self._caminho("bloco.mp3"), b"OggS" + bytes(100))
        self.assertFalse(tts.gerar_silencio_como(modelo, 300, self._caminho("sil.mp3")))

    def test_intercala_e_reaproveita_um_arquivo_por_duracao(self):
        blocos = [gravar(self._caminho(f"b{i}.mp3"), wav_falso(100, semente=i)) for i in range(4)]
        silencios = {}
        lista = tts.intercalar_silencios(blocos, [600, 160, 600, 600], self.pasta, silencios)
        self.assertEqual(7, len(lista))
        self.assertEqual(lista[1], lista[5])
        self.assertNotEqual(lista[1], lista[3])
        self.assertEqual(blocos, lista[::2])
        tts.apagar_silencios(silencios)
        self.assertFalse(any(os.path.exists(p) for p in silencios.values()))

    def test_pausa_zero_nao_intercala(self):
        blocos = [gravar(self._caminho(f"b{i}.mp3"), wav_falso(100, semente=i)) for i in range(3)]
        self.assertEqual(blocos, tts.intercalar_silencios(blocos, [0, 0, 0], self.pasta, {}))


if __name__ == "__main__":
    unittest.main()

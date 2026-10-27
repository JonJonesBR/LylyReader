"""Silêncio da junção gerado em Python puro (o Android não tem FFmpeg): mesmo formato dos blocos."""

import os
import struct
import sys
import tempfile
import unittest
import wave
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

    def test_duracao_real_mp3_fica_em_quadros_inteiros(self):
        modelo = gravar(self._caminho("bloco.mp3"), _QUADRO_EDGE * 3)
        self.assertEqual(288.0, tts.duracao_real_silencio_ms(modelo, 300))  # 12 quadros de 24 ms
        self.assertEqual(1488.0, tts.duracao_real_silencio_ms(modelo, 1500))  # 62 quadros
        self.assertEqual(600.0, tts.duracao_real_silencio_ms(modelo, 600))  # 25 quadros
        self.assertEqual(24.0, tts.duracao_real_silencio_ms(modelo, 1))  # no mínimo 1 quadro

    def test_duracao_real_bate_com_o_silencio_gravado(self):
        modelo_mp3 = gravar(self._caminho("bloco.mp3"), _QUADRO_EDGE * 3)
        modelo_wav = self._wav_com_taxa("bloco_44k.wav", 44100)
        for ms in (1, 50, 300, 600, 777, 1500):
            saida = self._caminho(f"sil_{ms}.mp3")
            self.assertTrue(tts.gerar_silencio_como(modelo_mp3, ms, saida))
            # MP3 Edge: 144 bytes e 24 ms por quadro
            self.assertAlmostEqual(
                tts.duracao_real_silencio_ms(modelo_mp3, ms), os.path.getsize(saida) / 144 * 24, places=6,
            )
            saida_wav = self._caminho(f"sil_{ms}_44k.wav")
            self.assertTrue(tts.gerar_silencio_como(modelo_wav, ms, saida_wav))
            self.assertAlmostEqual(
                tts.duracao_real_silencio_ms(modelo_wav, ms), tts._duracao_wav(saida_wav) * 1000, places=6,
            )

    def test_duracao_real_wav_arredonda_para_amostra_inteira(self):
        modelo = self._wav_com_taxa("bloco_44k.wav", 44100)
        self.assertAlmostEqual(44 * 1000 / 44100, tts.duracao_real_silencio_ms(modelo, 1), places=9)
        modelo_24k = gravar(self._caminho("bloco_24k.mp3"), wav_falso(1000))
        self.assertEqual(1.0, tts.duracao_real_silencio_ms(modelo_24k, 1))  # 24 amostras, exato

    def test_duracao_real_sem_silencio_ou_formato_desconhecido(self):
        modelo = gravar(self._caminho("bloco.mp3"), _QUADRO_EDGE * 3)
        self.assertEqual(0.0, tts.duracao_real_silencio_ms(modelo, 0))
        ogg = gravar(self._caminho("ogg.mp3"), b"OggS" + bytes(100))
        # Sem silêncio possível a junção sai sem pausas: a linha do tempo não pode contar nenhuma.
        self.assertEqual(0.0, tts.duracao_real_silencio_ms(ogg, 300))

    def _wav_com_taxa(self, nome: str, taxa: int) -> str:
        caminho = self._caminho(nome)
        with wave.open(caminho, "wb") as w:
            w.setnchannels(1)
            w.setsampwidth(2)
            w.setframerate(taxa)
            w.writeframes(b"\x00\x00" * taxa)
        return caminho


if __name__ == "__main__":
    unittest.main()

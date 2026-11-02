import os
import sys
import tempfile
import unittest
from pathlib import Path

PYTHON_SRC = Path(__file__).parents[2] / "main" / "python"
sys.path.insert(0, str(PYTHON_SRC))
sys.path.insert(0, str(Path(__file__).parent))

import tts  # noqa: E402
from mutagen.id3 import ID3  # noqa: E402
from fabricas_audio import gravar, mp3_falso, wav_falso  # noqa: E402

try:
    import core_processor_android as cpa
except ImportError:  # dependências de conversão ausentes no ambiente de teste
    cpa = None


def _texto(tags: ID3, quadro: str) -> list:
    return [str(t) for t in tags[quadro].text]


@unittest.skipIf(cpa is None, "módulos de conversão indisponíveis")
class EtiquetasDoMp3Test(unittest.TestCase):
    def test_sem_etiquetas_ou_titulo_vazio_grava_como_antes(self):
        for etiquetas in (None, {}, {"titulo": "", "autor": "Machado", "album": "X"}, {"titulo": "   "}):
            with self.subTest(etiquetas=etiquetas):
                self.assertEqual(
                    {"titulo": "Um_Apologo", "artista": "LylyReader", "album": "Um_Apologo", "marcador": ""},
                    cpa.etiquetas_do_mp3("Um_Apologo", etiquetas),
                )

    def test_com_titulo_e_autor_grava_os_dois_e_o_marcador(self):
        etiquetas = {"titulo": " Um Apólogo ", "autor": "Machado de Assis", "album": "Um Apólogo"}
        self.assertEqual(
            {"titulo": "Um Apólogo", "artista": "Machado de Assis", "album": "Um Apólogo", "marcador": "LylyReader"},
            cpa.etiquetas_do_mp3("interno", etiquetas),
        )

    def test_autor_vazio_vira_lylyreader_com_marcador(self):
        etiquetas = {"titulo": "Um Apólogo", "autor": "  ", "album": "Um Apólogo"}
        resultado = cpa.etiquetas_do_mp3("interno", etiquetas)
        self.assertEqual("LylyReader", resultado["artista"])
        self.assertEqual("LylyReader", resultado["marcador"])

    def test_album_vazio_vira_o_proprio_titulo(self):
        etiquetas = {"titulo": "Um Apólogo - Capítulo 1", "autor": "Machado", "album": ""}
        self.assertEqual("Um Apólogo - Capítulo 1", cpa.etiquetas_do_mp3("interno", etiquetas)["album"])


class GravacaoDeEtiquetasTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.dados = mp3_falso(3000, com_id3=True, semente=5)

    def tearDown(self):
        self.tmp.cleanup()

    def _arquivo(self, nome: str, dados: bytes = None) -> str:
        return gravar(os.path.join(self.tmp.name, nome), dados or self.dados)

    def test_titulo_autor_album_e_marcador_ficam_gravados_com_acentos(self):
        f = self._arquivo("com_marcador.mp3")
        self.assertTrue(tts.adicionar_metadados_e_capitulos_mp3(
            f, titulo="Um Apólogo", artista="Machado de Assis", album="Um Apólogo", marcador="LylyReader",
        ))
        tags = ID3(f)
        self.assertEqual(["Um Apólogo"], _texto(tags, "TIT2"))
        self.assertEqual(["Machado de Assis"], _texto(tags, "TPE1"))
        self.assertEqual(["Um Apólogo"], _texto(tags, "TALB"))
        self.assertEqual(["LylyReader"], _texto(tags, "TCOM"))

    def test_sem_marcador_nao_grava_compositor(self):
        f = self._arquivo("sem_marcador.mp3")
        self.assertTrue(tts.adicionar_metadados_e_capitulos_mp3(f, titulo="Livro", album="Livro"))
        self.assertNotIn("TCOM", ID3(f))

    def test_marcador_vazio_nao_grava_compositor(self):
        f = self._arquivo("marcador_vazio.mp3")
        self.assertTrue(tts.adicionar_metadados_mp3(f, titulo="Livro", marcador=""))
        self.assertNotIn("TCOM", ID3(f))

    def test_wav_disfarcado_devolve_false_e_nao_recebe_tags(self):
        f = self._arquivo("wav.mp3", wav_falso(2000))
        self.assertFalse(tts.adicionar_metadados_e_capitulos_mp3(f, titulo="Um Apólogo", marcador="LylyReader"))
        with open(f, "rb") as h:
            self.assertEqual(b"RIFF", h.read(4))


if __name__ == "__main__":
    unittest.main()

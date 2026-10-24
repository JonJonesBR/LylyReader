import os
import shutil
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

PYTHON_SRC = Path(__file__).parents[2] / "main" / "python"
sys.path.insert(0, str(PYTHON_SRC))
sys.path.insert(0, str(Path(__file__).parent))

import tts  # noqa: E402
from mutagen.id3 import ID3, TALB  # noqa: E402
from fabricas_audio import gravar, mp3_falso, wav_falso  # noqa: E402

CAPITULOS = [
    {"titulo": "Um", "inicio_ms": 0, "fim_ms": 1000},
    {"titulo": "Dois", "inicio_ms": 1000, "fim_ms": 2500},
]


def _resumo_tags(caminho: str) -> dict:
    tags = ID3(caminho)
    resumo = {}
    for chave in sorted(tags.keys()):
        frame = tags[chave]
        if chave.startswith("CHAP:"):
            resumo[chave] = (frame.element_id, frame.start_time, frame.end_time,
                             frame.sub_frames["TIT2"].text[0])
        elif chave.startswith("CTOC:"):
            resumo[chave] = (frame.element_id, list(frame.child_element_ids))
        elif chave.startswith("APIC"):
            resumo[chave] = len(frame.data)
        else:
            resumo[chave] = [str(t) for t in frame.text]
    return resumo


class AdicionarMetadadosECapitulosTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.pasta = self.tmp.name
        self.dados = mp3_falso(3000, com_id3=True, semente=3)

    def tearDown(self):
        patch.stopall()
        self.tmp.cleanup()

    def _arquivo(self, nome: str) -> str:
        return gravar(os.path.join(self.pasta, nome), self.dados)

    def test_gravacao_unica_produz_as_mesmas_tags_que_as_duas_funcoes_antigas(self):
        a = self._arquivo("separado.mp3")
        b = self._arquivo("combinado.mp3")
        self.assertTrue(tts.adicionar_metadados_mp3(a, titulo="Livro", album="Livro"))
        self.assertTrue(tts.adicionar_chapter_marks(a, CAPITULOS))
        self.assertTrue(tts.adicionar_metadados_e_capitulos_mp3(
            b, titulo="Livro", album="Livro", capitulos=CAPITULOS,
        ))
        self.assertEqual(_resumo_tags(a), _resumo_tags(b))
        self.assertIn("CTOC:toc", _resumo_tags(b))
        self.assertIn("CHAP:ch1", _resumo_tags(b))

    def test_combinada_grava_o_arquivo_uma_vez_e_as_antigas_duas(self):
        a = self._arquivo("separado.mp3")
        b = self._arquivo("combinado.mp3")
        with patch.object(ID3, "save", autospec=True, side_effect=ID3.save) as salvar:
            tts.adicionar_metadados_mp3(a, titulo="Livro")
            tts.adicionar_chapter_marks(a, CAPITULOS)
            self.assertEqual(2, salvar.call_count)
        with patch.object(ID3, "save", autospec=True, side_effect=ID3.save) as salvar:
            tts.adicionar_metadados_e_capitulos_mp3(b, titulo="Livro", capitulos=CAPITULOS)
            self.assertEqual(1, salvar.call_count)

    def test_padding_reservado_permite_novo_update_sem_mudar_o_tamanho(self):
        b = self._arquivo("combinado.mp3")
        tts.adicionar_metadados_e_capitulos_mp3(b, titulo="Livro", capitulos=CAPITULOS)
        tamanho = os.path.getsize(b)
        tags = ID3(b)
        tags.add(TALB(encoding=3, text="Outro álbum"))
        tags.save(b)
        self.assertEqual(tamanho, os.path.getsize(b))

    def test_sem_capitulos_nao_grava_marcadores(self):
        b = self._arquivo("sem_capitulos.mp3")
        self.assertTrue(tts.adicionar_metadados_e_capitulos_mp3(b, titulo="Livro", capitulos=None))
        resumo = _resumo_tags(b)
        self.assertFalse(any(k.startswith(("CHAP:", "CTOC:")) for k in resumo))
        self.assertIn("TIT2", resumo)

    def test_regravar_capitulos_substitui_os_antigos(self):
        b = self._arquivo("regravado.mp3")
        tts.adicionar_metadados_e_capitulos_mp3(b, titulo="Livro", capitulos=CAPITULOS)
        tts.adicionar_metadados_e_capitulos_mp3(b, titulo="Livro", capitulos=CAPITULOS[:1])
        resumo = _resumo_tags(b)
        self.assertEqual(["CHAP:ch0", "CTOC:toc"], sorted(k for k in resumo if k.startswith(("CHAP:", "CTOC:"))))

    def test_wav_disfarcado_de_mp3_nao_recebe_id3(self):
        wav = gravar(os.path.join(self.pasta, "disfarcado.mp3"), wav_falso(600))
        antes = Path(wav).read_bytes()
        self.assertFalse(tts.adicionar_metadados_e_capitulos_mp3(wav, titulo="Livro", capitulos=CAPITULOS))
        self.assertEqual(antes, Path(wav).read_bytes())


if __name__ == "__main__":
    unittest.main()

import sys
import unittest
from pathlib import Path


PYTHON_SRC = Path(__file__).parents[2] / "main" / "python"
sys.path.insert(0, str(PYTHON_SRC))

from core_processor_android import _montar_capitulos_info


def _texto_com_capitulos(*titulos, corpo="Texto longo o bastante para passar no filtro de 100 chars. " * 4):
    """Monta um texto com os títulos dados seguidos de um corpo repetido."""
    return "\n\n".join(t + "\n" + corpo for t in titulos)


class MontarCapitulosInfoTest(unittest.TestCase):
    def test_txt_com_capitulos_gera_info_com_titulos_e_intervalos(self):
        texto = _texto_com_capitulos("Capítulo 1", "Capítulo 2", "Capítulo 3")
        montado = _montar_capitulos_info(texto, chunk_limite=5000)
        self.assertIsNotNone(montado)
        capitulos_info, limites_chunks, total_chunks, total_caps = montado
        self.assertEqual(3, total_caps)
        self.assertEqual(["Capítulo 1", "Capítulo 2", "Capítulo 3"],
                         [c["titulo"] for c in capitulos_info])
        # Intervalos de chunks contíguos e monotônicos
        self.assertEqual(0, capitulos_info[0]["inicio_chunk"])
        for prev, cur in zip(capitulos_info, capitulos_info[1:]):
            self.assertEqual(prev["fim_chunk"], cur["inicio_chunk"])
        self.assertEqual(total_chunks, capitulos_info[-1]["fim_chunk"])
        self.assertEqual(len(limites_chunks), total_caps)

    def test_txt_sem_titulos_de_capitulo_retorna_none(self):
        texto = "Texto corrido sem nenhum marcador de capítulo. " * 30
        self.assertIsNone(_montar_capitulos_info(texto, chunk_limite=5000))

    def test_txt_com_um_unico_capitulo_retorna_none(self):
        texto = "Capítulo 1\n" + "Conteúdo. " * 300
        self.assertIsNone(_montar_capitulos_info(texto, chunk_limite=5000))

    def test_md_com_headings_detecta_capitulos(self):
        texto = _texto_com_capitulos("# Introdução", "## Capítulo 2", "## Conclusão")
        montado = _montar_capitulos_info(texto, chunk_limite=5000)
        self.assertIsNotNone(montado)
        capitulos_info, _, _, total_caps = montado
        self.assertEqual(3, total_caps)
        self.assertEqual("# Introdução", capitulos_info[0]["titulo"])

    def test_titulo_longo_e_truncado(self):
        titulo_longo = "Capítulo 1 " + "x" * 90
        texto = _texto_com_capitulos(titulo_longo, "Capítulo 2")
        montado = _montar_capitulos_info(texto, chunk_limite=5000)
        self.assertIsNotNone(montado)
        capitulos_info, _, _, _ = montado
        titulo = capitulos_info[0]["titulo"]
        self.assertLessEqual(len(titulo), 80)
        self.assertTrue(titulo.endswith("…"))

    def test_partes_em_romano_sao_detectadas(self):
        texto = _texto_com_capitulos("Parte I", "Parte II")
        montado = _montar_capitulos_info(texto, chunk_limite=5000)
        self.assertIsNotNone(montado)
        capitulos_info, _, _, total_caps = montado
        self.assertEqual(2, total_caps)
        self.assertEqual("Parte I", capitulos_info[0]["titulo"])

    def test_chunk_limite_reduz_estima_de_chunks(self):
        texto = _texto_com_capitulos("Capítulo 1", "Capítulo 2")
        _, _, total_chunks_pequeno, _ = _montar_capitulos_info(texto, chunk_limite=5000)
        _, _, total_chunks_grande, _ = _montar_capitulos_info(texto, chunk_limite=50000)
        self.assertLessEqual(total_chunks_pequeno, total_chunks_grande)

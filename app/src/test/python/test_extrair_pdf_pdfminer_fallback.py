import sys
import types
import unittest
from pathlib import Path


PYTHON_SRC = Path(__file__).parents[2] / "main" / "python"
sys.path.insert(0, str(PYTHON_SRC))


class FakeLTTextBox:
    def __init__(self, texto: str):
        self._texto = texto

    def get_text(self) -> str:
        return self._texto


def _instalar_pdfminer_fake(paginas: list) -> None:
    """Injeta um pdfminer falso em sys.modules — real lib não está instalada no
    ambiente de teste local (só existe no APK via requirements do Chaquopy)."""
    fake_layout = types.ModuleType("pdfminer.layout")
    fake_layout.LTTextBox = FakeLTTextBox

    fake_high_level = types.ModuleType("pdfminer.high_level")
    fake_high_level.extract_pages = lambda caminho: paginas

    fake_pdfminer = types.ModuleType("pdfminer")
    fake_pdfminer.high_level = fake_high_level
    fake_pdfminer.layout = fake_layout

    sys.modules["pdfminer"] = fake_pdfminer
    sys.modules["pdfminer.high_level"] = fake_high_level
    sys.modules["pdfminer.layout"] = fake_layout


class ExtrairPdfPdfminerFallbackTest(unittest.TestCase):
    """`_extrair_pdf` cai no caminho pdfminer só quando o pypdf (`_extrair_pdf_fallback`)
    falha — aqui simulado com um caminho de arquivo inexistente, que faz o pypdf real (se
    instalado) ou o ImportError (se não) retornarem ok=False de qualquer forma."""

    CAMINHO_INEXISTENTE = "/caminho/que/nao/existe.pdf"

    def setUp(self):
        for nome in ("pdfminer", "pdfminer.high_level", "pdfminer.layout"):
            sys.modules.pop(nome, None)
        for nome in list(sys.modules):
            if nome == "audiobook_android":
                del sys.modules[nome]

    def test_usa_titulos_reais_quando_detectaveis_em_vez_de_pagina_por_capitulo(self):
        paginas = [
            [FakeLTTextBox("Capítulo 1"), FakeLTTextBox("Primeiro parágrafo bem longo o bastante.")],
            [FakeLTTextBox("Capítulo 2"), FakeLTTextBox("Segundo parágrafo bem longo o bastante.")],
        ]
        _instalar_pdfminer_fake(paginas)
        from audiobook_android import _extrair_pdf

        resultado = _extrair_pdf(self.CAMINHO_INEXISTENTE)
        self.assertTrue(resultado["ok"])
        titulos = [c["titulo"] for c in resultado["capitulos"]]
        self.assertEqual(["Capítulo 1", "Capítulo 2"], titulos)
        self.assertNotIn("Página 1", titulos)

    def test_cai_em_pagina_por_capitulo_quando_nenhum_titulo_real_e_detectavel(self):
        paginas = [
            [FakeLTTextBox("Texto corrido da página um, sem nenhum marcador de capítulo.")],
            [FakeLTTextBox("Texto corrido da página dois, sem nenhum marcador de capítulo.")],
        ]
        _instalar_pdfminer_fake(paginas)
        from audiobook_android import _extrair_pdf

        resultado = _extrair_pdf(self.CAMINHO_INEXISTENTE)
        self.assertTrue(resultado["ok"])
        titulos = [c["titulo"] for c in resultado["capitulos"]]
        self.assertEqual(["Página 1", "Página 2"], titulos)


if __name__ == "__main__":
    unittest.main()

import sys
import tempfile
import unittest
import zipfile
from pathlib import Path


PYTHON_SRC = Path(__file__).parents[2] / "main" / "python"
sys.path.insert(0, str(PYTHON_SRC))

from audiobook_android import _extrair_epub  # noqa: E402


def _construir_epub_ncx_degenerado_com_titulos_em_texto(caminho: Path) -> None:
    """EPUB convertido de PDF (comum em livros antigos/digitalizados): o toc.ncx só tem
    UM navpoint genérico ("Start", apontando pro primeiro arquivo do spine — o que calibre
    gera quando o original não tinha marcação de capítulo real) e os capítulos de verdade
    são só texto puro ("CAPÍTULO N") dentro de <p> comuns, sem heading nem estilo distinto.
    Também injeta um separador decorativo ("6 9 6", artefato real de conversão PDF→HTML)
    entre parágrafos, pra provar que ele não vira narração nem quebra a contagem."""
    opf = """<?xml version="1.0"?>
<package xmlns="http://www.idpf.org/2007/opf" version="2.0">
  <manifest>
    <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
    <item id="c1" href="cap1.html" media-type="application/xhtml+xml"/>
  </manifest>
  <spine toc="ncx">
    <itemref idref="c1"/>
  </spine>
</package>"""
    ncx = """<?xml version="1.0"?>
<ncx xmlns="http://www.daisy.org/z3986/2005/ncx/">
  <navMap>
    <navPoint id="n1" playOrder="1">
      <navLabel><text>Start</text></navLabel>
      <content src="cap1.html"/>
    </navPoint>
  </navMap>
</ncx>"""
    cap1 = """<html><body>
<p>CAPITULO 1</p>
<p>Primeiro paragrafo do capitulo um, longo o bastante para nao ser descartado.</p>
<p>6 9 6</p>
<p>Segundo paragrafo do capitulo um, tambem longo o bastante.</p>
<p>Ele exclamou, <b>6 9 6</b> elevando a voz e batendo com o caneco vazio no bar.</p>
<p>CAPITULO 2</p>
<p>Primeiro paragrafo do capitulo dois, longo o bastante para nao ser descartado.</p>
</body></html>"""
    with zipfile.ZipFile(caminho, "w") as zf:
        zf.writestr("mimetype", "application/epub+zip")
        zf.writestr("content.opf", opf)
        zf.writestr("toc.ncx", ncx)
        zf.writestr("cap1.html", cap1)


class ExtrairEpubCapitulosTextoPuroTest(unittest.TestCase):
    def test_ncx_degenerado_usa_marcadores_de_capitulo_em_texto_puro(self):
        with tempfile.TemporaryDirectory() as td:
            caminho = Path(td) / "livro.epub"
            _construir_epub_ncx_degenerado_com_titulos_em_texto(caminho)
            resultado = _extrair_epub(str(caminho))

        self.assertTrue(resultado["ok"])
        titulos = [c["titulo"] for c in resultado["capitulos"]]
        self.assertEqual(["CAPITULO 1", "CAPITULO 2"], titulos)

    def test_separador_decorativo_numerico_nao_vira_paragrafo(self):
        with tempfile.TemporaryDirectory() as td:
            caminho = Path(td) / "livro.epub"
            _construir_epub_ncx_degenerado_com_titulos_em_texto(caminho)
            resultado = _extrair_epub(str(caminho))

        textos = [p["texto"] for p in resultado["paragrafos"]]
        self.assertNotIn("6 9 6", textos)
        # As duas frases narrativas ao redor do separador continuam presentes.
        self.assertTrue(any("Primeiro paragrafo do capitulo um" in t for t in textos))
        self.assertTrue(any("Segundo paragrafo do capitulo um" in t for t in textos))

    def test_separador_decorativo_grudado_em_prosa_real_e_removido_sem_perder_a_frase(self):
        with tempfile.TemporaryDirectory() as td:
            caminho = Path(td) / "livro.epub"
            _construir_epub_ncx_degenerado_com_titulos_em_texto(caminho)
            resultado = _extrair_epub(str(caminho))

        textos = [p["texto"] for p in resultado["paragrafos"]]
        alvo = next((t for t in textos if "exclamou" in t), None)
        self.assertIsNotNone(alvo)
        self.assertNotIn("6 9 6", alvo)
        self.assertEqual(
            "Ele exclamou, elevando a voz e batendo com o caneco vazio no bar.", alvo
        )


if __name__ == "__main__":
    unittest.main()

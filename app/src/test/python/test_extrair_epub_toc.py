import sys
import tempfile
import unittest
import zipfile
from pathlib import Path


PYTHON_SRC = Path(__file__).parents[2] / "main" / "python"
sys.path.insert(0, str(PYTHON_SRC))

from audiobook_android import _extrair_epub  # noqa: E402


def _construir_epub_epub3_sem_ncx(caminho: Path) -> None:
    """EPUB3 minimalista SEM toc.ncx (comum em livros gerados por ferramentas modernas, ex.:
    Pandoc) — só o Navigation Document (`<nav epub:type="toc">`), exigido pela spec EPUB3.
    Os capítulos NÃO usam h1/h2/h3 (título é um <p class="titulo">), pra provar que a detecção
    veio do nav.xhtml e não da heurística de headings."""
    opf = """<?xml version="1.0"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0">
  <manifest>
    <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
    <item id="c1" href="cap1.xhtml" media-type="application/xhtml+xml"/>
    <item id="c2" href="cap2.xhtml" media-type="application/xhtml+xml"/>
  </manifest>
  <spine>
    <itemref idref="c1"/>
    <itemref idref="c2"/>
  </spine>
</package>"""
    nav = """<?xml version="1.0"?>
<html xmlns:epub="http://www.idpf.org/2007/ops">
<body>
  <nav epub:type="toc">
    <ol>
      <li><a href="cap1.xhtml">Capitulo Um</a></li>
      <li><a href="cap2.xhtml">Capitulo Dois</a></li>
    </ol>
  </nav>
</body>
</html>"""
    cap1 = """<html><body><p class="titulo">Capitulo Um</p>
<p>Primeiro paragrafo do capitulo um, longo o bastante para nao ser descartado.</p>
</body></html>"""
    cap2 = """<html><body><p class="titulo">Capitulo Dois</p>
<p>Primeiro paragrafo do capitulo dois, longo o bastante para nao ser descartado.</p>
</body></html>"""
    with zipfile.ZipFile(caminho, "w") as zf:
        zf.writestr("mimetype", "application/epub+zip")
        zf.writestr("content.opf", opf)
        zf.writestr("nav.xhtml", nav)
        zf.writestr("cap1.xhtml", cap1)
        zf.writestr("cap2.xhtml", cap2)


class ExtrairEpubTocTest(unittest.TestCase):
    def test_epub3_sem_ncx_usa_nav_document_para_capitulos(self):
        with tempfile.TemporaryDirectory() as td:
            caminho = Path(td) / "livro.epub"
            _construir_epub_epub3_sem_ncx(caminho)
            resultado = _extrair_epub(str(caminho))

        self.assertTrue(resultado["ok"])
        titulos = [c["titulo"] for c in resultado["capitulos"]]
        self.assertEqual(["Capitulo Um", "Capitulo Dois"], titulos)


if __name__ == "__main__":
    unittest.main()

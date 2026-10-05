import sys
import tempfile
import unittest
import zipfile
from pathlib import Path


PYTHON_SRC = Path(__file__).parents[2] / "main" / "python"
sys.path.insert(0, str(PYTHON_SRC))

from audiobook_android import _extrair_epub  # noqa: E402


def _construir_epub_ancora_no_body_com_pagina_de_anuncio(caminho: Path) -> None:
    """Reproduz um padrão real (calibre/Amazon) que quebrava a detecção de capítulos:
    1) o NCX aponta a âncora de cada capítulo pro id da própria tag <body> (não vazio, não
       dentro de um <p>/<a>) — sem casar isso, NENHUM capítulo do TOC era encontrado;
    2) o livro tem uma página de "outros títulos" no fim, fora do spine do TOC, com um <h2>
       por título anunciado — sem tratamento, cada um virava um capítulo falso."""
    opf = """<?xml version="1.0"?>
<package xmlns="http://www.idpf.org/2007/opf" version="2.0">
  <manifest>
    <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
    <item id="c1" href="cap1.xhtml" media-type="application/xhtml+xml"/>
    <item id="c2" href="cap2.xhtml" media-type="application/xhtml+xml"/>
    <item id="ad" href="anuncio.xhtml" media-type="application/xhtml+xml"/>
  </manifest>
  <spine toc="ncx">
    <itemref idref="c1"/>
    <itemref idref="c2"/>
    <itemref idref="ad"/>
  </spine>
</package>"""
    ncx = """<?xml version="1.0"?>
<ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
  <navMap>
    <navPoint id="n1"><navLabel><text>Capitulo Um</text></navLabel>
      <content src="cap1.xhtml#corpo-um"/></navPoint>
    <navPoint id="n2"><navLabel><text>Capitulo Dois</text></navLabel>
      <content src="cap2.xhtml#corpo-dois"/></navPoint>
  </navMap>
</ncx>"""
    cap1 = """<html><body id="corpo-um">
<p>Primeiro paragrafo do capitulo um, longo o bastante para nao ser descartado.</p>
</body></html>"""
    cap2 = """<html><body id="corpo-dois">
<p>Primeiro paragrafo do capitulo dois, longo o bastante para nao ser descartado.</p>
</body></html>"""
    anuncio = """<html><body>
<h2>Outro Livro Qualquer</h2>
<p>Descricao do livro anunciado, sem relacao nenhuma com este.</p>
</body></html>"""
    with zipfile.ZipFile(caminho, "w") as zf:
        zf.writestr("mimetype", "application/epub+zip")
        zf.writestr("content.opf", opf)
        zf.writestr("toc.ncx", ncx)
        zf.writestr("cap1.xhtml", cap1)
        zf.writestr("cap2.xhtml", cap2)
        zf.writestr("anuncio.xhtml", anuncio)


class ExtrairEpubNcxBodyAnchorTest(unittest.TestCase):
    def test_ancora_no_id_do_body_e_reconhecida_como_inicio_do_capitulo(self):
        with tempfile.TemporaryDirectory() as td:
            caminho = Path(td) / "livro.epub"
            _construir_epub_ancora_no_body_com_pagina_de_anuncio(caminho)
            resultado = _extrair_epub(str(caminho))

        self.assertTrue(resultado["ok"])
        titulos = [c["titulo"] for c in resultado["capitulos"]]
        self.assertEqual(["Capitulo Um", "Capitulo Dois"], titulos)

    def test_pagina_fora_do_toc_nao_vira_capitulo_falso(self):
        with tempfile.TemporaryDirectory() as td:
            caminho = Path(td) / "livro.epub"
            _construir_epub_ancora_no_body_com_pagina_de_anuncio(caminho)
            resultado = _extrair_epub(str(caminho))

        titulos = [c["titulo"] for c in resultado["capitulos"]]
        self.assertNotIn("Outro Livro Qualquer", titulos)
        # O texto da pagina de anuncio ainda entra como paragrafo (nao filtramos conteudo,
        # so evitamos que vire um capitulo falso) — anexado ao ultimo capitulo real.
        textos = [p["texto"] for p in resultado["paragrafos"]]
        self.assertTrue(any("livro anunciado" in t for t in textos))


if __name__ == "__main__":
    unittest.main()

import io
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path
from unittest import mock


PYTHON_SRC = Path(__file__).parents[2] / "main" / "python"
sys.path.insert(0, str(PYTHON_SRC))

import audiobook_android  # noqa: E402
from audiobook_android import (  # noqa: E402
    ArquivoSuspeitoError,
    _LeitorZipSeguro,
    _extrair_epub,
    extrair_texto_estruturado,
)


W_NS = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"


def _capitulo(titulo: str, texto: str, cabecalho: str = "") -> str:
    return (
        f'{cabecalho}<html xmlns="http://www.w3.org/1999/xhtml"><body>'
        f"<h1>{titulo}</h1><p>{texto}</p></body></html>"
    )


def _gravar_epub(caminho: Path, capitulos: dict) -> None:
    """EPUB3 mínimo sem NCX: `capitulos` = {nome_arquivo: xhtml}, lidos na ordem do dicionário."""
    itens = "".join(
        f'<item id="c{i}" href="{nome}" media-type="application/xhtml+xml"/>'
        for i, nome in enumerate(capitulos)
    )
    refs = "".join(f'<itemref idref="c{i}"/>' for i in range(len(capitulos)))
    opf = (
        '<?xml version="1.0"?>\n'
        '<package xmlns="http://www.idpf.org/2007/opf" version="3.0">'
        f"<manifest>{itens}</manifest><spine>{refs}</spine></package>"
    )
    with zipfile.ZipFile(caminho, "w") as zf:
        zf.writestr("mimetype", "application/epub+zip")
        zf.writestr("content.opf", opf)
        for nome, xhtml in capitulos.items():
            zf.writestr(nome, xhtml)


def _gravar_docx(caminho: Path, corpo_xml: str, prefixo: str = "") -> None:
    documento = (
        f'{prefixo}<w:document xmlns:w="{W_NS}"><w:body>'
        '<w:p><w:pPr><w:pStyle w:val="Heading1"/></w:pPr><w:r><w:t>Capitulo Um</w:t></w:r></w:p>'
        f"{corpo_xml}"
        "</w:body></w:document>"
    )
    with zipfile.ZipFile(caminho, "w") as zf:
        zf.writestr("[Content_Types].xml", "<Types/>")
        zf.writestr("word/document.xml", documento)


PARAGRAFO_DOCX = (
    "<w:p><w:r><w:t>Paragrafo longo o bastante para ser aceito pelo extrator.</w:t></w:r></w:p>"
)


class TetosDeLeituraTest(unittest.TestCase):
    def test_teto_por_entrada_e_total_sao_50_e_200_mb(self):
        self.assertEqual(50 * 1024 * 1024, _LeitorZipSeguro.LIMITE_ENTRADA)
        self.assertEqual(200 * 1024 * 1024, _LeitorZipSeguro.LIMITE_TOTAL)

    def test_entrada_acima_do_teto_levanta_erro(self):
        with tempfile.TemporaryDirectory() as td:
            caminho = Path(td) / "a.zip"
            with zipfile.ZipFile(caminho, "w") as zf:
                zf.writestr("grande.txt", "x" * 2000)
            with zipfile.ZipFile(caminho) as zf:
                leitor = _LeitorZipSeguro(zf)
                with mock.patch.object(_LeitorZipSeguro, "LIMITE_ENTRADA", 1000):
                    with self.assertRaises(ArquivoSuspeitoError):
                        leitor.ler("grande.txt")

    def test_total_acima_do_teto_recusa_a_entrada_que_estoura(self):
        with tempfile.TemporaryDirectory() as td:
            caminho = Path(td) / "a.zip"
            with zipfile.ZipFile(caminho, "w") as zf:
                zf.writestr("a.txt", "x" * 600)
                zf.writestr("b.txt", "y" * 600)
            with zipfile.ZipFile(caminho) as zf:
                leitor = _LeitorZipSeguro(zf)
                with mock.patch.object(_LeitorZipSeguro, "LIMITE_TOTAL", 1000):
                    self.assertEqual(b"x" * 600, leitor.ler("a.txt"))
                    with self.assertRaises(ArquivoSuspeitoError):
                        leitor.ler("b.txt")

    def test_cabecalho_que_mente_sobre_o_tamanho_ainda_e_limitado(self):
        # Cabeçalho declara 1 byte, mas o conteúdo real tem 100: a leitura tem que parar.
        class ZipMentiroso:
            def getinfo(self, nome):
                return mock.Mock(file_size=1)

            def open(self, info):
                return io.BytesIO(b"z" * 100)

        with mock.patch.object(_LeitorZipSeguro, "LIMITE_ENTRADA", 10):
            with self.assertRaises(ArquivoSuspeitoError):
                _LeitorZipSeguro(ZipMentiroso()).ler("x")

    def test_xml_com_entity_e_recusado_pelo_leitor(self):
        with tempfile.TemporaryDirectory() as td:
            caminho = Path(td) / "a.zip"
            with zipfile.ZipFile(caminho, "w") as zf:
                zf.writestr("x.xml", '<!DOCTYPE r [<!ENTITY e "y">]><r>&e;</r>')
            with zipfile.ZipFile(caminho) as zf:
                with self.assertRaises(ArquivoSuspeitoError):
                    _LeitorZipSeguro(zf).ler("x.xml")


class EpubSeguroTest(unittest.TestCase):
    def test_epub_valido_continua_funcionando_com_doctype_simples(self):
        with tempfile.TemporaryDirectory() as td:
            caminho = Path(td) / "livro.epub"
            _gravar_epub(caminho, {
                "cap1.xhtml": _capitulo(
                    "Capitulo Um",
                    "Texto do primeiro capitulo, longo o bastante para ser mantido.",
                    cabecalho="<!DOCTYPE html>",
                ),
            })
            resultado = _extrair_epub(str(caminho))

        self.assertTrue(resultado["ok"], resultado)
        self.assertEqual(["Capitulo Um"], [c["titulo"] for c in resultado["capitulos"]])
        self.assertIn(
            "Texto do primeiro capitulo, longo o bastante para ser mantido.",
            [p["texto"] for p in resultado["paragrafos"]],
        )

    def test_capitulo_acima_do_teto_e_ignorado_e_o_resto_sai(self):
        with tempfile.TemporaryDirectory() as td:
            caminho = Path(td) / "livro.epub"
            _gravar_epub(caminho, {
                "grande.xhtml": _capitulo("Gigante", "g" * 3000),
                "pequeno.xhtml": _capitulo(
                    "Pequeno", "Texto do capitulo pequeno, longo o bastante para ser mantido."
                ),
            })
            # Teto baixo para o teste; OPF e capítulo pequeno cabem nele.
            with mock.patch.object(_LeitorZipSeguro, "LIMITE_ENTRADA", 1000):
                resultado = _extrair_epub(str(caminho))

        self.assertTrue(resultado["ok"], resultado)
        textos = [p["texto"] for p in resultado["paragrafos"]]
        self.assertFalse(any(t.startswith("ggg") for t in textos))
        self.assertIn(
            "Texto do capitulo pequeno, longo o bastante para ser mantido.", textos
        )

    def test_capitulo_com_entity_e_ignorado_e_o_resto_sai(self):
        malicioso = (
            '<?xml version="1.0"?>\n'
            '<!DOCTYPE html [<!ENTITY e "Entidade maliciosa expandida">]>'
            '<html xmlns="http://www.w3.org/1999/xhtml"><body>'
            "<h1>Malicioso</h1><p>&e;</p></body></html>"
        )
        with tempfile.TemporaryDirectory() as td:
            caminho = Path(td) / "livro.epub"
            _gravar_epub(caminho, {
                "mal.xhtml": malicioso,
                "bom.xhtml": _capitulo(
                    "Bom", "Texto do capitulo bom, longo o bastante para ser mantido."
                ),
            })
            resultado = _extrair_epub(str(caminho))

        self.assertTrue(resultado["ok"], resultado)
        textos = [p["texto"] for p in resultado["paragrafos"]]
        self.assertFalse(any("Entidade maliciosa" in t for t in textos))
        self.assertIn("Texto do capitulo bom, longo o bastante para ser mantido.", textos)

    def test_capitulo_com_dtd_interno_e_ignorado(self):
        dtd_interno = (
            '<?xml version="1.0"?>\n'
            "<!DOCTYPE html [<!ELEMENT html ANY>]>"
            '<html xmlns="http://www.w3.org/1999/xhtml"><body>'
            "<h1>Com DTD</h1><p>Texto que nao deve sair do arquivo recusado.</p></body></html>"
        )
        with tempfile.TemporaryDirectory() as td:
            caminho = Path(td) / "livro.epub"
            _gravar_epub(caminho, {
                "dtd.xhtml": dtd_interno,
                "bom.xhtml": _capitulo(
                    "Bom", "Texto do capitulo bom, longo o bastante para ser mantido."
                ),
            })
            resultado = _extrair_epub(str(caminho))

        self.assertTrue(resultado["ok"], resultado)
        textos = [p["texto"] for p in resultado["paragrafos"]]
        self.assertFalse(any("nao deve sair" in t for t in textos))


class DocxSeguroTest(unittest.TestCase):
    def test_docx_valido_continua_funcionando(self):
        with tempfile.TemporaryDirectory() as td:
            caminho = Path(td) / "doc.docx"
            _gravar_docx(caminho, PARAGRAFO_DOCX)
            resultado = extrair_texto_estruturado(str(caminho))

        self.assertTrue(resultado["ok"], resultado)
        self.assertEqual(["Capitulo Um"], [c["titulo"] for c in resultado["capitulos"]])
        self.assertEqual(
            ["Paragrafo longo o bastante para ser aceito pelo extrator."],
            [p["texto"] for p in resultado["paragrafos"]],
        )

    def test_docx_com_doctype_e_recusado(self):
        with tempfile.TemporaryDirectory() as td:
            caminho = Path(td) / "doc.docx"
            _gravar_docx(caminho, PARAGRAFO_DOCX, prefixo="<!DOCTYPE w:document>")
            resultado = extrair_texto_estruturado(str(caminho))

        self.assertFalse(resultado["ok"])
        self.assertIn("recusado", resultado["erro"])

    def test_docx_com_entidades_expansivas_e_recusado(self):
        prefixo = (
            '<?xml version="1.0"?>\n'
            '<!DOCTYPE lol [<!ENTITY a "aaaaaaaaaa"><!ENTITY b "&a;&a;&a;&a;&a;&a;&a;&a;&a;&a;">]>'
        )
        corpo = (
            "<w:p><w:r><w:t>&b;&b;&b;&b;&b;&b;&b;&b;&b;&b;</w:t></w:r></w:p>"
        )
        with tempfile.TemporaryDirectory() as td:
            caminho = Path(td) / "doc.docx"
            _gravar_docx(caminho, corpo, prefixo=prefixo)
            resultado = extrair_texto_estruturado(str(caminho))

        self.assertFalse(resultado["ok"])
        self.assertIn("recusado", resultado["erro"])

    def test_docx_acima_do_teto_e_recusado_sem_derrubar(self):
        with tempfile.TemporaryDirectory() as td:
            caminho = Path(td) / "doc.docx"
            _gravar_docx(caminho, PARAGRAFO_DOCX)
            with mock.patch.object(_LeitorZipSeguro, "LIMITE_ENTRADA", 100):
                resultado = extrair_texto_estruturado(str(caminho))

        self.assertFalse(resultado["ok"])
        self.assertIn("grande demais", resultado["erro"])


if __name__ == "__main__":
    unittest.main()

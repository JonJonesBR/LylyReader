import importlib.util
import shutil
import sys
import tempfile
import types
import unittest
from pathlib import Path
from unittest import mock


PYTHON_SRC = Path(__file__).parents[2] / "main" / "python"
sys.path.insert(0, str(PYTHON_SRC))

from mobi_extract import MobiParseError, desempacotar  # noqa: E402


def _modulo_mobi_falso(extract):
    modulo = types.ModuleType("mobi")
    modulo.extract = extract
    return modulo


class MobiExtractEntradaInvalidaTest(unittest.TestCase):
    def test_falha_do_pacote_vira_mobi_parse_error(self):
        falso = _modulo_mobi_falso(mock.Mock(side_effect=ValueError("cabecalho ruim")))
        with mock.patch.dict(sys.modules, {"mobi": falso}):
            with self.assertRaises(MobiParseError) as ctx:
                desempacotar("entrada.mobi")
        self.assertIn("invalido", str(ctx.exception))

    def test_formato_de_saida_inesperado_limpa_o_tempdir(self):
        td = tempfile.mkdtemp()
        saida = Path(td) / "saida.txt"
        saida.write_text("nada", encoding="utf-8")
        falso = _modulo_mobi_falso(mock.Mock(return_value=(td, str(saida))))
        with mock.patch.dict(sys.modules, {"mobi": falso}):
            with self.assertRaises(MobiParseError):
                desempacotar("entrada.mobi")
        self.assertFalse(Path(td).exists())

    def test_epub_de_saida_e_reconhecido(self):
        td = tempfile.mkdtemp()
        try:
            falso = _modulo_mobi_falso(
                mock.Mock(return_value=(td, str(Path(td) / "livro.epub")))
            )
            with mock.patch.dict(sys.modules, {"mobi": falso}):
                kind, path, tempdir = desempacotar("entrada.mobi")
            self.assertEqual("epub", kind)
            self.assertEqual(td, tempdir)
        finally:
            shutil.rmtree(td, ignore_errors=True)

    @unittest.skipUnless(importlib.util.find_spec("mobi"), "pacote mobi não instalado")
    def test_arquivo_mobi_invalido_de_verdade_vira_mobi_parse_error(self):
        with tempfile.TemporaryDirectory() as td:
            caminho = Path(td) / "lixo.mobi"
            caminho.write_bytes(b"isto nao e um mobi" * 10)
            with self.assertRaises(MobiParseError):
                desempacotar(str(caminho))


if __name__ == "__main__":
    unittest.main()

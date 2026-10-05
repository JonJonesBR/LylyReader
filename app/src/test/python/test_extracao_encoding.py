import sys
import tempfile
import unittest
from pathlib import Path


PYTHON_SRC = Path(__file__).parents[2] / "main" / "python"
sys.path.insert(0, str(PYTHON_SRC))

from utils import extrair_texto_limpo  # noqa: E402


class ExtracaoEncodingTest(unittest.TestCase):
    """Extração de .txt/.md deve preservar acentos independente do encoding do
    arquivo (UTF-8, UTF-8 com BOM, UTF-16, cp1252/ANSI)."""

    def _extrair(self, nome: str, dados: bytes) -> str:
        with tempfile.TemporaryDirectory() as td:
            p = Path(td) / nome
            p.write_bytes(dados)
            return extrair_texto_limpo(str(p), p.suffix.lower())

    def test_txt_utf8_mantem_acentos(self):
        texto = "Água, não, ação, coração, você."
        out = self._extrair("ok.txt", texto.encode("utf-8"))
        self.assertEqual("Água, não, ação, coração, você.", out)

    def test_txt_utf8_com_bom_nao_deixa_bom_residual(self):
        texto = "Início com BOM."
        out = self._extrair("bom.txt", b"\xef\xbb\xbf" + texto.encode("utf-8"))
        self.assertNotIn("\ufeff", out)
        self.assertIn("Início", out)

    def test_txt_cp1252_preserva_acentos(self):
        # Arquivo ANSI antigo (Windows): ler como UTF-8 apagava os acentos.
        texto = "Não é possível. Coração, você, ação, nação."
        out = self._extrair("ansi.txt", texto.encode("cp1252"))
        self.assertIn("Não é possível", out)
        self.assertIn("Coração", out)
        self.assertIn("ação", out)

    def test_txt_latin1_preserva_acentos(self):
        # latin-1 puro: acentos na faixa 0xA0-0xFF mapeiam iguais no cp1252.
        texto = "São João, não, mãe, coração."
        out = self._extrair("l1.txt", texto.encode("latin-1"))
        self.assertIn("São João", out)
        self.assertIn("mãe", out)

    def test_txt_utf16_preserva_acentos(self):
        texto = "Olá mundo. Coração e não."
        out = self._extrair("u16.txt", texto.encode("utf-16"))
        self.assertIn("Olá", out)
        self.assertIn("Coração", out)
        self.assertIn("não", out)

    def test_md_cp1252_preserva_acentos(self):
        md = "# Capítulo Um\n\nAção e coração."
        out = self._extrair("livro.md", md.encode("cp1252"))
        self.assertIn("Capítulo Um", out)
        self.assertIn("Ação", out)

    def test_txt_vazio_retorna_vazio(self):
        self.assertEqual("", self._extrair("vazio.txt", b""))

    def test_txt_sem_acentos_utf8_inalterado(self):
        texto = "Texto simples sem acentos."
        out = self._extrair("simples.txt", texto.encode("utf-8"))
        self.assertEqual(texto, out)


if __name__ == "__main__":
    unittest.main()

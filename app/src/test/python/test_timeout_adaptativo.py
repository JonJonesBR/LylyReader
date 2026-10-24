import sys
import unittest
from pathlib import Path

PYTHON_SRC = Path(__file__).parents[2] / "main" / "python"
sys.path.insert(0, str(PYTHON_SRC))

try:
    from tts import timeout_para_texto
except ImportError:  # dependências de síntese ausentes no ambiente de teste
    timeout_para_texto = None


@unittest.skipIf(timeout_para_texto is None, "módulo tts indisponível")
class TimeoutParaTextoTest(unittest.TestCase):
    def test_bloco_vazio_usa_o_minimo_de_30_segundos(self):
        self.assertEqual(30.0, timeout_para_texto(""))
        self.assertEqual(30.0, timeout_para_texto(None))

    def test_cresce_com_o_tamanho(self):
        # 30 + 400/20 = 50
        self.assertEqual(50.0, timeout_para_texto("x" * 400))

    def test_bloco_de_1900_caracteres(self):
        # 30 + 1900/20 = 125 -> teto de 90
        self.assertEqual(90.0, timeout_para_texto("x" * 1900))

    def test_teto_de_90_segundos(self):
        self.assertEqual(90.0, timeout_para_texto("x" * 5000))
        self.assertEqual(90.0, timeout_para_texto("x" * 100000))

    def test_nunca_diminui_com_o_tamanho(self):
        tamanhos = [0, 50, 500, 1000, 2000, 2500, 4000]
        valores = [timeout_para_texto("x" * n) for n in tamanhos]
        self.assertEqual(sorted(valores), valores)


if __name__ == "__main__":
    unittest.main()

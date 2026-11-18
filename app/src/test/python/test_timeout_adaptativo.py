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
    def test_bloco_vazio_usa_o_minimo_de_60_segundos(self):
        self.assertEqual(60.0, timeout_para_texto(""))
        self.assertEqual(60.0, timeout_para_texto(None))

    def test_cresce_com_o_tamanho(self):
        # 60 + 400/10 = 100
        self.assertEqual(100.0, timeout_para_texto("x" * 400))

    def test_bloco_de_2500_caracteres_chega_ao_teto(self):
        # 60 + 2500/10 = 310 -> teto de 300
        self.assertEqual(300.0, timeout_para_texto("x" * 2500))

    def test_teto_de_300_segundos(self):
        self.assertEqual(300.0, timeout_para_texto("x" * 5000))
        self.assertEqual(300.0, timeout_para_texto("x" * 100000))

    def test_nunca_diminui_com_o_tamanho(self):
        tamanhos = [0, 50, 500, 1000, 2000, 2500, 4000]
        valores = [timeout_para_texto("x" * n) for n in tamanhos]
        self.assertEqual(sorted(valores), valores)


if __name__ == "__main__":
    unittest.main()

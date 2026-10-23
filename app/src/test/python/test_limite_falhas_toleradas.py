import sys
import unittest
from pathlib import Path

PYTHON_SRC = Path(__file__).parents[2] / "main" / "python"
sys.path.insert(0, str(PYTHON_SRC))

try:
    from core_processor_android import _limite_falhas_toleradas
except ImportError:  # dependências de conversão ausentes no ambiente de teste
    _limite_falhas_toleradas = None


@unittest.skipIf(_limite_falhas_toleradas is None, "módulo core_processor_android indisponível")
class LimiteFalhasToleradasTest(unittest.TestCase):
    def test_livro_pequeno_tolera_uma_falha(self):
        self.assertEqual(1, _limite_falhas_toleradas(1))
        self.assertEqual(1, _limite_falhas_toleradas(15))

    def test_cinco_por_cento_arredondado_para_cima(self):
        self.assertEqual(2, _limite_falhas_toleradas(21))  # ceil(1.05) = 2
        self.assertEqual(3, _limite_falhas_toleradas(41))  # ceil(2.05) = 3

    def test_livro_de_201_blocos_tolera_10(self):
        # 5% de 201 = 10,05 -> 11, limitado ao teto de 10
        self.assertEqual(10, _limite_falhas_toleradas(201))

    def test_teto_de_10_em_livros_grandes(self):
        self.assertEqual(10, _limite_falhas_toleradas(1000))


if __name__ == "__main__":
    unittest.main()

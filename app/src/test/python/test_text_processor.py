import sys
import unittest
from pathlib import Path


PYTHON_SRC = Path(__file__).parents[2] / "main" / "python"
sys.path.insert(0, str(PYTHON_SRC))

from text_processor import TextProcessor, preparar_texto


class TextProcessorTest(unittest.TestCase):
    def test_split_chunks_respeita_limite_em_frases_normais(self):
        chunks = TextProcessor.split_chunks("Frase um. " * 2000, max_chars=5000)
        self.assertTrue(all(len(c) <= 5000 for c in chunks))
        self.assertGreater(len(chunks), 1)

    def test_split_chunks_divide_frase_unica_sem_pontuacao(self):
        # Texto longo sem pontuacao de frase (ex.: extração de PDF que perdeu o
        # ponto final): o chunk nao pode ficar ilimitado.
        chunks = TextProcessor.split_chunks("x" * 10000, max_chars=5000)
        self.assertEqual([5000, 5000], [len(c) for c in chunks])

    def test_split_chunks_divide_palavras_sem_ponto_final(self):
        chunks = TextProcessor.split_chunks("palavra " * 9000, max_chars=5000)
        self.assertTrue(all(len(c) <= 5000 for c in chunks))
        self.assertGreater(len(chunks), 1)

    def test_split_chunks_divide_por_paragrafos(self):
        chunks = TextProcessor.split_chunks("Paragrafo um.\n\n" * 1000, max_chars=5000)
        self.assertTrue(all(len(c) <= 5000 for c in chunks))
        self.assertGreater(len(chunks), 1)

    def test_split_chunks_preserva_conteudo(self):
        texto = "palavra palavra " * 2000
        chunks = TextProcessor.split_chunks(texto, max_chars=5000)
        junto = " ".join(c for c in chunks)
        self.assertIn("palavra", junto)
        self.assertEqual(4000, junto.count("palavra"))

    def test_preparar_texto_vazio(self):
        self.assertEqual([], preparar_texto(""))

    def test_preparar_texto_curto(self):
        self.assertEqual(["Olá mundo."], preparar_texto("Olá mundo."))


if __name__ == "__main__":
    unittest.main()

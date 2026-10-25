import sys
import unittest
from pathlib import Path


PYTHON_SRC = Path(__file__).parents[2] / "main" / "python"
sys.path.insert(0, str(PYTHON_SRC))

from text_normalizer import normalizar


class CaixaAltaTest(unittest.TestCase):
    """Títulos em CAIXA ALTA são lidos como palavras, não soletrados letra a letra."""

    def test_titulo_em_caixa_alta_nao_e_soletrado(self):
        self.assertEqual(normalizar("O DIA EM QUE TUDO MUDOU", "pt-BR"), "O dia em que tudo mudou")

    def test_titulo_de_uma_palavra_curta(self):
        self.assertEqual(normalizar("FIM", "pt-BR"), "Fim")

    def test_titulo_com_numero_de_capitulo(self):
        self.assertEqual(normalizar("CAPÍTULO 1 - A CASA", "pt-BR", True, True), "Capítulo um, a casa")
        self.assertEqual(normalizar("PARTE II: O FIM", "pt-BR"), "Parte dois: o fim")

    def test_palavra_longa_gritada_no_meio_da_frase(self):
        self.assertEqual(normalizar("Ele gritou: SOCORRO!", "pt-BR"), "Ele gritou: Socorro!")

    def test_titulo_em_ingles(self):
        self.assertEqual(normalizar("THE END OF DAYS", "en"), "The end of days")

    def test_siglas_continuam_tratadas(self):
        self.assertEqual(normalizar("A OAB decidiu.", "pt-BR"), "A ó á bê decidiu.")
        self.assertEqual(normalizar("Meu CPF aqui.", "pt-BR"), "Meu cê pê efe aqui.")
        self.assertEqual(normalizar("O INSS paga.", "pt-BR"), "O inss paga.")

    def test_sigla_conhecida_dentro_de_titulo(self):
        self.assertEqual(normalizar("EU E A ONU", "pt-BR"), "Eu e a onu")


if __name__ == "__main__":
    unittest.main()

import sys
import unittest
from pathlib import Path


PYTHON_SRC = Path(__file__).parents[2] / "main" / "python"
sys.path.insert(0, str(PYTHON_SRC))

from text_normalizer import normalizar


class DecimaisTest(unittest.TestCase):
    """A parte decimal é lida como aparece no texto ('3,5' → cinco, não cinquenta)."""

    def test_porcentagem_decimal(self):
        self.assertEqual(normalizar("Subiu 3,5%.", "pt-BR"), "Subiu três vírgula cinco por cento.")

    def test_unidade_decimal(self):
        self.assertEqual(normalizar("Pesa 2,5 kg.", "pt-BR"), "Pesa dois vírgula cinco quilos.")

    def test_decimal_com_zero_a_esquerda_e_com_duas_casas(self):
        self.assertEqual(normalizar("Deu 2,05%.", "pt-BR"), "Deu dois vírgula zero cinco por cento.")
        self.assertEqual(normalizar("Deu 3,50%.", "pt-BR"), "Deu três vírgula cinquenta por cento.")

    def test_decimal_inteiro_nao_le_virgula(self):
        self.assertEqual(normalizar("Deu 5,0%.", "pt-BR"), "Deu cinco por cento.")

    def test_milhar_com_ponto_e_lido_como_inteiro(self):
        self.assertEqual(normalizar("Pesa 1.234 kg.", "pt-BR"), "Pesa mil duzentos e trinta e quatro quilos.")

    def test_moeda_real_continua_igual(self):
        self.assertIn("vinte e cinco reais e cinquenta centavos", normalizar("Custa R$ 25,50.", "pt-BR"))


class AbreviacaoCapTest(unittest.TestCase):
    def test_cap_seguido_de_numero_ou_romano_e_capitulo(self):
        self.assertEqual(normalizar("Veja o Cap. 7.", "pt-BR"), "Veja o capítulo sete.")
        self.assertEqual(normalizar("Veja o cap. 12.", "pt-BR"), "Veja o capítulo doze.")
        self.assertEqual(normalizar("Veja o Cap. IV.", "pt-BR"), "Veja o capítulo quatro.")

    def test_cap_seguido_de_nome_continua_capitao(self):
        self.assertEqual(normalizar("O Cap. Nemo chegou.", "pt-BR"), "O capitão Nemo chegou.")


class SaoEMoedasTest(unittest.TestCase):
    def test_s_ponto_diante_de_nome_proprio_e_sao(self):
        self.assertEqual(normalizar("Nasceu em S. Paulo.", "pt-BR"), "Nasceu em são Paulo.")
        self.assertEqual(normalizar("Sta. Ana e S. João.", "pt-BR"), "santa Ana e são João.")

    def test_s_minusculo_solto_nao_vira_sul(self):
        self.assertEqual(normalizar("Ele disse s. e foi.", "pt-BR"), "Ele disse s. e foi.")

    def test_euro_depois_do_valor(self):
        self.assertEqual(
            normalizar("Custou 1.234,56 €.", "pt-BR"),
            "Custou mil duzentos e trinta e quatro euros e cinquenta e seis centavos.",
        )
        self.assertEqual(normalizar("Pagou 50 € hoje.", "pt-BR"), "Pagou cinquenta euros hoje.")

    def test_dolar_com_prefixo(self):
        self.assertEqual(normalizar("US$ 10,50", "pt-BR"), "dez dólares e cinquenta centavos")


if __name__ == "__main__":
    unittest.main()

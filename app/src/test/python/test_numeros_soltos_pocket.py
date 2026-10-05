import sys
import unittest
from pathlib import Path


PYTHON_SRC = Path(__file__).parents[2] / "main" / "python"
sys.path.insert(0, str(PYTHON_SRC))

from text_normalizer import normalizar


class NumerosSoltosPocketTest(unittest.TestCase):
    """O tokenizador do Pocket não lê dígitos: 'numeros_soltos=True' expande inteiros e decimais
    soltos por extenso. Sem a opção, o comportamento dos demais motores não muda."""

    def _pt(self, texto):
        return normalizar(texto, "pt-BR", numeros_soltos=True)

    def test_inteiros_soltos_viram_extenso(self):
        self.assertEqual(self._pt("Tem 99 anos."), "Tem noventa e nove anos.")
        self.assertEqual(self._pt("Eram 500 homens."), "Eram quinhentos homens.")
        self.assertEqual(self._pt("Eram 100 homens."), "Eram cem homens.")

    def test_anos_e_milhares(self):
        self.assertEqual(self._pt("Em 1990 tudo mudou."), "Em mil novecentos e noventa tudo mudou.")
        self.assertEqual(self._pt("Em 2024."), "Em dois mil e vinte e quatro.")
        self.assertEqual(self._pt("Foram 1.234 pessoas."), "Foram mil duzentas e trinta e quatro pessoas.")

    def test_decimais(self):
        self.assertEqual(self._pt("Mede 3,5 metros."), "Mede três vírgula cinco metros.")
        self.assertEqual(self._pt("Vale 2,05."), "Vale dois vírgula zero cinco.")

    def test_numeros_longos_e_com_zero_a_esquerda_sao_lidos_digito_a_digito(self):
        self.assertEqual(self._pt("Código 007."), "Código zero zero sete.")
        self.assertIn("um dois três quatro cinco seis sete", self._pt("Ligue 1234567."))

    def test_digitos_colados_a_letras_ficam_intactos(self):
        self.assertIn("A4", self._pt("Papel A4."))
        self.assertIn("mp3", self._pt("Arquivo mp3."))

    def test_expansoes_anteriores_continuam_valendo(self):
        self.assertIn("vinte e cinco reais", self._pt("Custa R$ 25,00."))
        self.assertIn("cinquenta por cento", self._pt("Subiu 50%."))

    def test_sem_a_opcao_numeros_soltos_ficam_como_antes(self):
        self.assertEqual(normalizar("Tem 99 anos.", "pt-BR"), "Tem 99 anos.")
        self.assertEqual(normalizar("Em 1990.", "pt-BR"), "Em 1990.")

    def test_ingles_e_espanhol_tambem_expandem(self):
        self.assertEqual(normalizar("I have 12 apples.", "en-US", numeros_soltos=True), "I have twelve apples.")
        self.assertEqual(normalizar("Tengo 12 años.", "es-ES", numeros_soltos=True), "Tengo doce años.")


if __name__ == "__main__":
    unittest.main()

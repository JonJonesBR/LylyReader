import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parents[2] / "main" / "python"))

from text_normalizer import normalizar


def pt(texto):
    return normalizar(texto, "pt-BR", numeros_soltos=True, sem_travessoes=True)


class NumerosPtTest(unittest.TestCase):
    def test_mil_e_centenas(self):
        self.assertEqual(pt("Custou 1.500 reais."), "Custou mil e quinhentos reais.")
        self.assertEqual(pt("Eram 1.100."), "Eram mil e cem.")
        self.assertEqual(pt("Eram 1.234."), "Eram mil duzentos e trinta e quatro.")
        self.assertEqual(pt("Eram 2.020."), "Eram dois mil e vinte.")

    def test_milhoes(self):
        self.assertEqual(pt("Vendeu 1.000.000 de cópias."), "Vendeu um milhão de cópias.")
        self.assertEqual(pt("Eram 3.500.000 pessoas."), "Eram três milhões e quinhentas mil pessoas.")
        self.assertEqual(pt("Eram 1.234.567 pessoas."),
                         "Eram um milhão duzentas e trinta e quatro mil quinhentas e sessenta e sete pessoas.")

    def test_numero_longo_sem_pontos_continua_codigo(self):
        self.assertEqual(pt("Código 1234567."), "Código um dois três quatro cinco seis sete.")

    def test_horas(self):
        self.assertEqual(pt("Às 10h30 saímos."), "Às dez horas e trinta minutos saímos.")
        self.assertEqual(pt("Chegou às 7:45."), "Chegou às sete horas e quarenta e cinco minutos.")
        self.assertEqual(pt("Volto à 1h."), "Volto à uma hora.")
        self.assertEqual(pt("Às 2h15."), "Às duas horas e quinze minutos.")

    def test_romanos_depois_de_seculo(self):
        self.assertEqual(pt("No século XXI."), "No século vinte e um.")
        self.assertEqual(pt("Tomo IV."), "Tomo quatro.")

    def test_negativos(self):
        self.assertEqual(pt("De -5 graus."), "De menos cinco graus.")
        self.assertEqual(pt("Páginas 10-20."), "Páginas dez a vinte.")
        self.assertEqual(pt("De 1984-1990."), "De mil novecentos e oitenta e quatro a mil novecentos e noventa.")

    def test_telefone_dito_digito_a_digito(self):
        self.assertEqual(pt("Ligue 99999-9999."), "Ligue nove nove nove nove nove, nove nove nove nove.")

    def test_milhar_com_espaco(self):
        self.assertEqual(pt("Eram 12 000 pessoas."), "Eram doze mil pessoas.")

    def test_ordinal_com_ponto(self):
        self.assertEqual(pt("No 1.º de maio."), "No primeiro de maio.")
        self.assertEqual(pt("A 2.ª vez."), "A segunda vez.")


class NumerosInglesEEspanholTest(unittest.TestCase):
    def test_ingles(self):
        self.assertEqual(normalizar("He was 99 years old.", "en-US", numeros_soltos=True), "He was ninety-nine years old.")
        self.assertEqual(normalizar("There were 1,500 men.", "en-US", numeros_soltos=True), "There were one thousand five hundred men.")
        self.assertEqual(normalizar("In 1984 it began.", "en-US", numeros_soltos=True), "In nineteen eighty-four it began.")
        self.assertEqual(normalizar("In 2024 we met.", "en-US", numeros_soltos=True), "In twenty twenty-four we met.")
        self.assertEqual(normalizar("About 3.5 meters, 50%.", "en-US", numeros_soltos=True),
                         "About three point five meters, fifty percent.")

    def test_espanhol(self):
        self.assertEqual(normalizar("Tenía 99 años.", "es-ES", numeros_soltos=True), "Tenía noventa y nueve años.")
        self.assertEqual(normalizar("Eran 1.500 hombres.", "es-ES", numeros_soltos=True), "Eran mil quinientos hombres.")
        self.assertEqual(normalizar("Tenía 21 años y 31 hijos.", "es-ES", numeros_soltos=True),
                         "Tenía veintiuno años y treinta y uno hijos.")
        self.assertEqual(normalizar("Medía 1,80 metros.", "es-ES", numeros_soltos=True), "Medía uno coma ocho cero metros.")

    def test_sem_a_opcao_nao_muda(self):
        self.assertEqual(normalizar("He was 99 years old.", "en-US"), "He was 99 years old.")


class OrdinaisESimbolosTest(unittest.TestCase):
    def test_ordinais_grandes(self):
        self.assertEqual(normalizar("O 63\u00ba batalhão.", "pt-BR", numeros_soltos=True), "O sexagésimo terceiro batalhão.")
        self.assertEqual(normalizar("A 233\u00aa vez.", "pt-BR", numeros_soltos=True), "A ducentésima trigésima terceira vez.")
        self.assertEqual(normalizar("O 20\u00ba.", "pt-BR", numeros_soltos=True), "O vigésimo.")

    def test_simbolos_de_marca_somem(self):
        self.assertNotIn("\u2122", normalizar("Cérebro\u2122 falou.", "pt-BR", numeros_soltos=True))

    def test_graus(self):
        self.assertEqual(normalizar("Girou 360\u00b0 depressa.", "pt-BR", numeros_soltos=True), "Girou trezentos e sessenta graus depressa.")


class ConcordanciaDeGeneroTest(unittest.TestCase):
    def test_feminino_depois_de_numero(self):
        self.assertEqual(normalizar("Havia 2 casas e 21 pessoas.", "pt-BR", numeros_soltos=True), "Havia duas casas e vinte e uma pessoas.")
        self.assertEqual(normalizar("Eram 200 páginas.", "pt-BR", numeros_soltos=True), "Eram duzentas páginas.")
        self.assertEqual(normalizar("Viu 1 mulher.", "pt-BR", numeros_soltos=True), "Viu uma mulher.")
        self.assertEqual(normalizar("Tinha 101 casas.", "pt-BR", numeros_soltos=True), "Tinha cento e uma casas.")
        self.assertEqual(normalizar("Eram 2000 casas.", "pt-BR", numeros_soltos=True), "Eram duas mil casas.")

    def test_masculino_continua(self):
        self.assertEqual(normalizar("Tinha 2 homens e 200 soldados.", "pt-BR", numeros_soltos=True), "Tinha dois homens e duzentos soldados.")
        self.assertEqual(normalizar("Eram 1 dia e 1 noite.", "pt-BR", numeros_soltos=True), "Eram um dia e uma noite.")

    def test_sufixos_femininos(self):
        self.assertEqual(normalizar("Tinha 2 gerações.", "pt-BR", numeros_soltos=True), "Tinha duas gerações.")

    def test_o_de_ocr_vira_zero(self):
        self.assertEqual(normalizar("Gastou 420O reais.", "pt-BR", numeros_soltos=True), "Gastou quatro mil e duzentos reais.")

    def test_graus_ou_ordinal_conforme_o_substantivo(self):
        self.assertEqual(normalizar("Girou 360º depressa.", "pt-BR", numeros_soltos=True), "Girou trezentos e sessenta graus depressa.")
        self.assertEqual(normalizar("O 233º batalhão.", "pt-BR", numeros_soltos=True), "O ducentésimo trigésimo terceiro batalhão.")


class SiglasTest(unittest.TestCase):
    def test_sigla_soletrada_com_nomes_das_letras(self):
        self.assertEqual(normalizar("Os corpos da FCD chegaram.", "pt-BR"), "Os corpos da efe cê dê chegaram.")
        self.assertEqual(normalizar("Assistiu à TV.", "pt-BR"), "Assistiu à tê vê.")


if __name__ == "__main__":
    unittest.main()

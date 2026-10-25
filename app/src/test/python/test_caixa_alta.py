import sys
import unittest
from pathlib import Path


PYTHON_SRC = Path(__file__).parents[2] / "main" / "python"
sys.path.insert(0, str(PYTHON_SRC))

from text_normalizer import esquecer_vocabularios, normalizar, registrar_vocabulario


class CaixaAltaTest(unittest.TestCase):
    """Títulos em CAIXA ALTA são lidos como palavras, não soletrados letra a letra."""

    def setUp(self):
        esquecer_vocabularios()

    def tearDown(self):
        esquecer_vocabularios()

    def test_titulo_em_caixa_alta_nao_e_soletrado(self):
        self.assertEqual(normalizar("O DIA EM QUE TUDO MUDOU", "pt-BR"), "O dia em que tudo mudou")
        self.assertEqual(normalizar("EU VI O MAR", "pt-BR"), "Eu vi o mar")

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
        self.assertEqual(normalizar("O STF julgou.", "pt-BR"), "O esse tê efe julgou.")

    def test_sigla_conhecida_dentro_de_titulo(self):
        self.assertEqual(normalizar("EU E A ONU", "pt-BR"), "Eu e a onu")

    def test_sigla_soletrada_com_letras_do_idioma(self):
        self.assertEqual(normalizar("The FBI came.", "en"), "The ef bee eye came.")


class VocabularioDoLivroTest(unittest.TestCase):
    """Com o vocabulário do livro, palavra curta em caixa alta é palavra se o livro a usa em minúsculas."""

    def setUp(self):
        esquecer_vocabularios()
        registrar_vocabulario("livro", "A casa era velha. Para ti, meu amor. Era o lobo.")

    def tearDown(self):
        esquecer_vocabularios()

    def test_palavra_curta_isolada_do_livro(self):
        self.assertEqual(normalizar("Ele viu A CASA.", "pt-BR"), "Ele viu A casa.")

    def test_sem_vocabulario_continua_soletrando(self):
        esquecer_vocabularios()
        self.assertEqual(normalizar("Ele viu A CASA.", "pt-BR"), "Ele viu A cê á esse á.")

    def test_sigla_que_o_livro_nao_usa_em_minusculas(self):
        self.assertEqual(normalizar("A OAB decidiu.", "pt-BR"), "A ó á bê decidiu.")
        self.assertEqual(normalizar("A HISTÓRIA DA OAB", "pt-BR"), "A história da ó á bê")

    def test_vocabulario_vence_lista_de_siglas_no_titulo(self):
        self.assertEqual(normalizar("CAPÍTULO IV: PARA TI", "es"), "Capítulo cuatro: para ti")

    def test_registro_conta_so_palavras_com_minusculas(self):
        self.assertEqual(registrar_vocabulario("outro", "O STF e a casa"), 3)  # e, a, casa ("O" e "STF" são maiúsculas)


class RomanosTest(unittest.TestCase):
    def test_romano_sozinho_e_no_inicio_do_titulo(self):
        self.assertEqual(normalizar("XII", "pt-BR"), "doze")
        self.assertEqual(normalizar("XII. O RETORNO", "pt-BR"), "doze. o retorno")
        self.assertEqual(normalizar("IV.", "en"), "four.")

    def test_romano_depois_de_secao_em_ingles_e_espanhol(self):
        self.assertEqual(normalizar("CHAPTER XII", "en"), "Chapter twelve")
        self.assertEqual(normalizar("Capítulo IV", "es"), "Capítulo cuatro")

    def test_romano_depois_de_nome(self):
        self.assertEqual(normalizar("LUÍS XIV, O REI SOL", "pt-BR"), "Luís quatorze, o rei sol")
        self.assertEqual(normalizar("Dom Pedro I e Isabel II.", "pt-BR"), "Dom Pedro primeiro e Isabel segunda.")
        self.assertEqual(normalizar("Henry VIII and World War II.", "en"), "Henry the eighth and World War two.")
        self.assertEqual(normalizar("Felipe II y Alfonso XIII.", "es"), "Felipe segundo y Alfonso trece.")

    def test_pronome_i_em_ingles_nao_vira_numero(self):
        self.assertEqual(normalizar("Then I went home.", "en"), "Then I went home.")


if __name__ == "__main__":
    unittest.main()

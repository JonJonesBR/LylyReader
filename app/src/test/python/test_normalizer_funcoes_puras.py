import sys
import unittest
from pathlib import Path


PYTHON_SRC = Path(__file__).parents[2] / "main" / "python"
sys.path.insert(0, str(PYTHON_SRC))

from text_normalizer import (
    _expandir_abreviacoes_en,
    _expandir_abreviacoes_pt,
    _expandir_numeros_pt,
    _expandir_siglas,
    _expandir_simbolos,
    _parenteses_por_pausa,
)


class AbreviacoesPtTest(unittest.TestCase):
    def test_tratamento_masculino_e_feminino(self):
        self.assertEqual(
            _expandir_abreviacoes_pt("Sr. Silva e Dra. Ana foram ao Prof. Carlos."),
            "senhor Silva e doutora Ana foram ao professor Carlos.",
        )

    def test_tratamentos_restantes(self):
        self.assertEqual(_expandir_abreviacoes_pt("Sra. Lima"), "senhora Lima")
        self.assertEqual(_expandir_abreviacoes_pt("Srta. Rita"), "senhorita Rita")
        self.assertEqual(_expandir_abreviacoes_pt("Profa. Ana"), "professora Ana")

    def test_abreviacao_em_caixa_alta_tambem_expande(self):
        self.assertEqual(_expandir_abreviacoes_pt("SR. JOSE"), "senhor JOSE")

    def test_avenida_e_rua(self):
        self.assertEqual(_expandir_abreviacoes_pt("Av. Brasil"), "avenida Brasil")
        self.assertEqual(_expandir_abreviacoes_pt("R. Ouvidor 10"), "rua Ouvidor 10")

    def test_cap_com_numero_ou_romano_vira_capitulo(self):
        self.assertEqual(_expandir_abreviacoes_pt("Cap. 7"), "capítulo 7")
        self.assertEqual(_expandir_abreviacoes_pt("Cap. IV"), "capítulo IV")

    def test_cap_seguido_de_nome_vira_capitao(self):
        self.assertEqual(_expandir_abreviacoes_pt("Cap. Nemo"), "capitão Nemo")

    def test_s_ponto_diante_de_nome_proprio_vira_sao(self):
        self.assertEqual(_expandir_abreviacoes_pt("Nasceu em S. Paulo."), "Nasceu em são Paulo.")
        self.assertEqual(_expandir_abreviacoes_pt("Sta. Ana e S. João."), "santa Ana e são João.")

    def test_s_minusculo_solto_nao_vira_sao(self):
        self.assertEqual(_expandir_abreviacoes_pt("Ele disse s. e foi."), "Ele disse s. e foi.")

    def test_etc_e_pagina(self):
        self.assertEqual(_expandir_abreviacoes_pt("Etc. e Pág. 3"), "etcetera e página 3")

    def test_sem_abreviacao_fica_igual(self):
        self.assertEqual(_expandir_abreviacoes_pt("Uma frase simples."), "Uma frase simples.")

    def test_texto_vazio(self):
        self.assertEqual(_expandir_abreviacoes_pt(""), "")

    def test_idempotente(self):
        uma_vez = _expandir_abreviacoes_pt("Sr. Silva e Dra. Ana.")
        self.assertEqual(_expandir_abreviacoes_pt(uma_vez), uma_vez)

    def test_texto_longo_expande_todas_as_ocorrencias(self):
        longo = "Sr. Silva disse oi. " * 200
        resultado = _expandir_abreviacoes_pt(longo)
        self.assertEqual(resultado.count("senhor Silva"), 200)
        self.assertNotIn("Sr.", resultado)


class AbreviacoesEnTest(unittest.TestCase):
    def test_tratamentos_em_ingles(self):
        self.assertEqual(
            _expandir_abreviacoes_en("Mr. Smith and Mrs. Jones"),
            "mister Smith and missus Jones",
        )

    def test_vs_e_eg(self):
        self.assertEqual(_expandir_abreviacoes_en("vs. the e.g. case"), "versus the for example case")

    def test_st_inc_ltd(self):
        self.assertEqual(
            _expandir_abreviacoes_en("St. Louis Inc. ltd"),
            "saint Louis incorporated ltd",
        )

    def test_sem_abreviacao_fica_igual(self):
        self.assertEqual(_expandir_abreviacoes_en("Plain sentence."), "Plain sentence.")

    def test_texto_vazio(self):
        self.assertEqual(_expandir_abreviacoes_en(""), "")

    def test_idempotente(self):
        uma_vez = _expandir_abreviacoes_en("Mr. Smith vs. Jones.")
        self.assertEqual(_expandir_abreviacoes_en(uma_vez), uma_vez)


class SimbolosTest(unittest.TestCase):
    def test_e_comercial_vira_e(self):
        self.assertEqual(_expandir_simbolos("Tom & Jerry"), "Tom e Jerry")

    def test_e_comercial_colado_nao_cola_palavras(self):
        self.assertEqual(_expandir_simbolos("A&B"), "A e B")

    def test_arroba_vira_arroba_por_extenso(self):
        self.assertEqual(_expandir_simbolos("a@b.com"), "a arroba b.com")

    def test_mais_e_igual(self):
        self.assertEqual(_expandir_simbolos("1+1=2"), "1 mais 1 igual 2")

    def test_jogo_da_velha_vira_numero(self):
        self.assertEqual(_expandir_simbolos("x # y"), "x número y")

    def test_texto_sem_simbolo_fica_igual(self):
        self.assertEqual(_expandir_simbolos("Nada a expandir aqui."), "Nada a expandir aqui.")

    def test_texto_vazio(self):
        self.assertEqual(_expandir_simbolos(""), "")

    def test_texto_longo_troca_todos_os_e_comerciais(self):
        longo = "Tom & Jerry. " * 150
        resultado = _expandir_simbolos(longo)
        self.assertNotIn("&", resultado)
        self.assertEqual(resultado.count(" e "), 150)
        self.assertNotIn("  ", resultado)


class ParentesesPorPausaTest(unittest.TestCase):
    def test_parenteses_simples_viram_pausa(self):
        self.assertEqual(_parenteses_por_pausa("Oi (aparte) fim."), "Oi, aparte, fim.")

    def test_colchetes_viram_pausa(self):
        self.assertEqual(_parenteses_por_pausa("Texto [nota] final"), "Texto, nota, final")

    def test_aninhados(self):
        self.assertEqual(_parenteses_por_pausa("a (b (c) d) e"), "a, b, c, d, e")

    def test_parenteses_antes_de_ponto_nao_deixam_virgula_solta(self):
        self.assertEqual(_parenteses_por_pausa("Frase (fim)."), "Frase, fim.")

    def test_parenteses_no_inicio_do_texto(self):
        self.assertEqual(_parenteses_por_pausa("(só) agora"), "só, agora")

    def test_parenteses_vazios_sobram_so_virgula_que_some(self):
        self.assertEqual(_parenteses_por_pausa("( ), x"), "x")

    def test_sem_parenteses_fica_igual(self):
        self.assertEqual(_parenteses_por_pausa("Sem parenteses nenhum."), "Sem parenteses nenhum.")

    def test_texto_vazio(self):
        self.assertEqual(_parenteses_por_pausa(""), "")

    def test_quebra_de_linha_preservada(self):
        self.assertEqual(
            _parenteses_por_pausa("Linha (um)\nlinha dois (dois)\n"),
            "Linha, um\nlinha dois, dois",
        )

    def test_texto_longo_sem_sobrar_parenteses(self):
        longo = "Frase (x) " * 300
        resultado = _parenteses_por_pausa(longo)
        self.assertNotIn("(", resultado)
        self.assertNotIn(")", resultado)
        self.assertTrue(resultado.startswith("Frase, x"))


class SiglasTest(unittest.TestCase):
    def test_sigla_lida_como_palavra(self):
        self.assertEqual(_expandir_siglas("A ONU aprovou."), "A onu aprovou.")

    def test_inss_lida_como_palavra(self):
        self.assertIn("inss", _expandir_siglas("O INSS paga."))

    def test_sigla_soletrada(self):
        self.assertEqual(_expandir_siglas("Use USB e HD."), "Use u esse bê e agá dê.")

    def test_cpf_e_rg_sao_soletrados_e_nao_lidos_como_palavra(self):
        self.assertEqual(_expandir_siglas("Meu CPF aqui."), "Meu cê pê efe aqui.")
        self.assertEqual(_expandir_siglas("O RG do cliente."), "O erre gê do cliente.")

    def test_sigla_com_ponto_nao_e_tocada(self):
        self.assertEqual(_expandir_siglas("Empresa Foo S.A. aqui."), "Empresa Foo S.A. aqui.")

    def test_texto_vazio(self):
        self.assertEqual(_expandir_siglas(""), "")


class MoedasTest(unittest.TestCase):
    def test_real_com_centavos(self):
        self.assertEqual(_expandir_numeros_pt("R$ 1,50"), "um real e cinquenta centavos")

    def test_dolar_com_prefixo(self):
        self.assertEqual(_expandir_numeros_pt("US$ 10"), "dez dólares")

    def test_unidade_comum(self):
        self.assertEqual(_expandir_numeros_pt("Pesa 2 kg."), "Pesa dois quilos.")

    def test_texto_vazio(self):
        self.assertEqual(_expandir_numeros_pt(""), "")


if __name__ == "__main__":
    unittest.main()

import sys
import unittest
from pathlib import Path


PYTHON_SRC = Path(__file__).parents[2] / "main" / "python"
sys.path.insert(0, str(PYTHON_SRC))

from text_normalizer import normalizar, relatorio_trocas


class RelatorioTrocasTest(unittest.TestCase):
    """relatorio_trocas: lista, só para conferência, o que normalizar() troca (antes → depois).
    Não pode mudar a saída de normalizar()."""

    def _pt(self, texto, numeros_soltos=False):
        return relatorio_trocas(texto, "pt-BR", numeros_soltos=numeros_soltos)

    def test_abreviacao_pt(self):
        self.assertEqual(self._pt("O Dr. Silva disse."), [{"antes": "Dr", "depois": "doutor"}])

    def test_ordinais_pt(self):
        self.assertEqual(
            self._pt("O 2º lugar e a 3ª vez."),
            [{"antes": "2º", "depois": "segundo"}, {"antes": "3ª", "depois": "terceira"}],
        )

    def test_horas_pt(self):
        self.assertEqual(
            self._pt("Chegou às 10h30."),
            [{"antes": "10h30", "depois": "dez horas e trinta minutos"}],
        )

    def test_moeda_pt_junta_trechos_separados_por_palavra_igual(self):
        self.assertEqual(
            self._pt("Custa R$ 45,90 e US$ 10."),
            [{
                "antes": "R$ 45,90 e US$ 10",
                "depois": "quarenta e cinco reais e noventa centavos e dez dólares",
            }],
        )

    def test_moeda_pt_simples(self):
        self.assertEqual(
            self._pt("Pagou R$ 45,90 ontem."),
            [{"antes": "R$ 45,90", "depois": "quarenta e cinco reais e noventa centavos"}],
        )

    def test_sigla_pt(self):
        self.assertEqual(self._pt("O PIB cresceu."), [{"antes": "PIB", "depois": "pê i bê"}])

    def test_numeros_soltos_desligado_nao_troca_inteiro_solto(self):
        self.assertEqual(self._pt("Eram 3 homens."), [])

    def test_numeros_soltos_ligado_troca_inteiros_soltos(self):
        self.assertEqual(
            self._pt("Eram 3 homens e 2 mulheres.", numeros_soltos=True),
            [{"antes": "3", "depois": "três"}, {"antes": "2", "depois": "duas"}],
        )
        self.assertEqual(
            self._pt("Tem 99 anos.", numeros_soltos=True),
            [{"antes": "99", "depois": "noventa e nove"}],
        )

    def test_ano_com_numeros_soltos(self):
        self.assertEqual(
            self._pt("Em 1990 tudo mudou.", numeros_soltos=True),
            [{"antes": "1990", "depois": "mil novecentos e noventa"}],
        )

    def test_en_abreviacao_e_numero_com_flag(self):
        self.assertEqual(
            relatorio_trocas("Dr. Smith came.", "en"),
            [{"antes": "Dr", "depois": "doctor"}],
        )
        self.assertEqual(
            relatorio_trocas("Paid 3 dollars.", "en", numeros_soltos=True),
            [{"antes": "3", "depois": "three"}],
        )
        self.assertEqual(relatorio_trocas("Paid 3 dollars.", "en"), [])

    def test_es_numero_com_flag(self):
        self.assertEqual(
            relatorio_trocas("Tem 99 años y 3 perros.", "es", numeros_soltos=True),
            [{"antes": "99", "depois": "noventa y nueve"}, {"antes": "3", "depois": "tres"}],
        )

    def test_sem_travessoes_remove_travessao_inicial_nao_e_reportado(self):
        # Remover o travessão só mexe em pontuação: a troca é descartada.
        self.assertEqual(relatorio_trocas("— Olá, disse ele.", "pt-BR", sem_travessoes=True), [])
        self.assertEqual(relatorio_trocas("— Olá, disse ele.", "pt-BR"), [])

    def test_texto_sem_trocas_devolve_lista_vazia(self):
        self.assertEqual(self._pt("Olá, mundo."), [])
        self.assertEqual(relatorio_trocas("Olá, disse ele.", "en"), [])

    def test_texto_vazio_devolve_lista_vazia(self):
        self.assertEqual(self._pt(""), [])
        self.assertEqual(self._pt("   \n  "), [])

    def test_varias_linhas_sao_conferidas_uma_a_uma(self):
        trocas = self._pt("Primeiro, Dr. Silva.\n\nSegundo, 2º lugar.")
        self.assertEqual(
            trocas,
            [{"antes": "Dr", "depois": "doutor"}, {"antes": "2º", "depois": "segundo"}],
        )

    def test_nao_altera_saida_de_normalizar(self):
        texto = "O Dr. Silva chegou às 10h30 com R$ 45,90 e 3 cães, o 2º lugar."
        antes = normalizar(texto, "pt-BR")
        relatorio_trocas(texto, "pt-BR")
        relatorio_trocas(texto, "pt-BR", numeros_soltos=True, sem_travessoes=True)
        self.assertEqual(normalizar(texto, "pt-BR"), antes)
        self.assertEqual(texto, "O Dr. Silva chegou às 10h30 com R$ 45,90 e 3 cães, o 2º lugar.")


class RelatorioComDicionarioTest(unittest.TestCase):
    """Ponte relatorio_normalizacao_json: primeiro as trocas do dicionário de pronúncia, depois as do
    normalizador sobre o texto já com o dicionário."""

    def setUp(self):
        import json
        import pronunciation_dict
        import audiobook_android
        self._pron = pronunciation_dict
        self._ponte = audiobook_android.relatorio_normalizacao_json
        self._json = json

    def tearDown(self):
        self._pron.configurar("[]")

    def _trocas(self, texto, voz="pt-BR-ThalitaMultilingualNeural"):
        return self._json.loads(self._ponte(texto, voz))["trocas"]

    def test_entrada_do_dicionario_aparece_como_troca(self):
        self._pron.configurar(self._json.dumps([{"word": "coso", "spoken": "cozo"}]))
        self.assertEqual(
            self._trocas("Porque coso."),
            [{"antes": "coso", "depois": "cozo"}],
        )

    def test_dicionario_e_numero_no_mesmo_texto_geram_as_duas_trocas(self):
        self._pron.configurar(self._json.dumps([{"word": "coso", "spoken": "cozo"}]))
        self.assertEqual(
            self._trocas("Porque coso.\nEram 3 homens.", voz="pocket-pt-x"),
            [{"antes": "coso", "depois": "cozo"}, {"antes": "3", "depois": "três"}],
        )

    def test_coso_com_virgula_e_coso_com_ponto_viram_o_mesmo_par(self):
        self._pron.configurar(self._json.dumps([{"word": "coso", "spoken": "cozo"}]))
        trocas = self._trocas("Digo coso, e depois coso.")
        self.assertEqual(len(trocas), 2)
        self.assertEqual(trocas[0], trocas[1])
        self.assertEqual(trocas[0], {"antes": "coso", "depois": "cozo"})

    def test_sem_dicionario_resultado_igual_ao_relatorio_puro(self):
        texto = "Dr. Silva chegou às 10h30.\nEram 3 homens e 2 mulheres."
        self._pron.configurar("[]")
        esperado = relatorio_trocas(texto, "pt", numeros_soltos=True, sem_travessoes=True)
        self.assertEqual(self._trocas(texto, voz="pocket-pt-x"), esperado)

    def test_comparar_textos_igual_devolve_vazio(self):
        from text_normalizer import comparar_textos
        self.assertEqual(comparar_textos("Olá, mundo.", "Olá, mundo."), [])

    def test_comparar_textos_so_pontuacao_mudou_devolve_vazio(self):
        from text_normalizer import comparar_textos
        self.assertEqual(comparar_textos("Ele disse, sim.", "Ele disse sim!"), [])
        self.assertEqual(comparar_textos("Oi — tudo bem", "Oi tudo bem"), [])

    def test_comparar_textos_tira_pontuacao_das_bordas_do_par(self):
        from text_normalizer import comparar_textos
        self.assertEqual(
            comparar_textos("Porque coso, sim.", "Porque cozo, sim."),
            [{"antes": "coso", "depois": "cozo"}],
        )


if __name__ == "__main__":
    unittest.main()

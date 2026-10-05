import sys
import unittest
from pathlib import Path


PYTHON_SRC = Path(__file__).parents[2] / "main" / "python"
sys.path.insert(0, str(PYTHON_SRC))

from text_normalizer import normalizar


class TravessoesPocketTest(unittest.TestCase):
    """O Pocket lê travessões como um ruído estranho: com sem_travessoes=True eles somem
    (marcador de fala) ou viram pausa (vírgula). Sem a opção, nada muda para os outros motores."""

    def _pt(self, texto):
        return normalizar(texto, "pt-BR", sem_travessoes=True)

    def test_marcador_de_fala_no_inicio_some(self):
        self.assertEqual(self._pt("— Onde você vai?"), "Onde você vai?")

    def test_travessoes_de_atribuicao_viram_virgula(self):
        self.assertEqual(self._pt("— Não sei — disse Maria."), "Não sei, disse Maria.")
        self.assertEqual(self._pt("— Vamos embora — disse ele — agora."), "Vamos embora, disse ele, agora.")

    def test_travessao_no_meio_da_frase_vira_pausa(self):
        self.assertEqual(self._pt("Ele chegou — cansado — e dormiu."), "Ele chegou, cansado, e dormiu.")

    def test_travessao_no_fim_some(self):
        self.assertEqual(self._pt("Ele hesitou —"), "Ele hesitou")

    def test_meia_risca_entre_numeros_vira_a(self):
        self.assertIn(" a ", self._pt("Páginas 10–20."))

    def test_multiplas_linhas_de_dialogo(self):
        entrada = "— Oi.\n— Olá — respondeu Ana."
        saida = self._pt(entrada)
        self.assertNotIn("—", saida)
        self.assertIn("Oi.", saida)
        self.assertIn("Olá, respondeu Ana.", saida)

    def test_hifen_no_inicio_tambem_some(self):
        # Medido no aparelho: "- ", "-- " e "— " no começo geram ~0,6 s de vazio antes da fala.
        self.assertEqual(self._pt("- Ele chegou tarde."), "Ele chegou tarde.")
        self.assertEqual(self._pt("-- Ele chegou tarde."), "Ele chegou tarde.")

    def test_marcadores_e_reticencias_no_inicio_somem(self):
        self.assertEqual(self._pt("• Ele chegou."), "Ele chegou.")
        self.assertEqual(self._pt("… Ele chegou."), "Ele chegou.")
        self.assertEqual(self._pt("# Ele chegou."), "Ele chegou.")

    def test_quebra_de_cena_e_so_simbolos_viram_vazio(self):
        for texto in ("***", "* * *", "---", "___", "•"):
            self.assertEqual(self._pt(texto), "", texto)

    def test_quebra_de_cena_no_meio_do_texto_some(self):
        self.assertEqual(self._pt("Fim.\n***\nComeço."), "Fim. Começo.")

    def test_hifen_depois_de_dois_pontos_nao_deixa_virgula_solta(self):
        self.assertEqual(self._pt("Ela disse: - Vamos."), "Ela disse: Vamos.")

    def test_aspas_no_inicio_sao_preservadas(self):
        self.assertTrue(self._pt('"Ele chegou tarde."').startswith('"Ele'))

    def test_barra_horizontal_e_outros_marcadores_de_dialogo(self):
        # O Nome do Vento usa U+2015; outros livros usam meia-risca (U+2013) e minus (U+2212).
        self.assertEqual(self._pt("― Isso mesmo ― disse Cob."), "Isso mesmo, disse Cob.")
        self.assertEqual(self._pt("– Vou embora – disse Ana."), "Vou embora, disse Ana.")
        self.assertEqual(self._pt("− Vou embora − disse Ana."), "Vou embora, disse Ana.")

    def test_parenteses_viram_pausa(self):
        # O Pocket perdia palavras e soltava ruído com texto entre parênteses (medido no aparelho).
        self.assertEqual(self._pt("(Ele abriu a porta.)"), "Ele abriu a porta.")
        self.assertEqual(
            self._pt("Ele abriu a porta (e olhou para dentro) da sala."),
            "Ele abriu a porta, e olhou para dentro, da sala.",
        )
        self.assertEqual(self._pt("Texto [nota] fim."), "Texto, nota, fim.")
        self.assertEqual(self._pt("Fim (aqui)."), "Fim, aqui.")

    def test_sem_a_opcao_parenteses_sao_preservados(self):
        self.assertIn("(", normalizar("Fim (aqui).", "pt-BR"))

    def test_sem_a_opcao_travessoes_sao_preservados(self):
        self.assertIn("—", normalizar("— Onde você vai?", "pt-BR"))


if __name__ == "__main__":
    unittest.main()

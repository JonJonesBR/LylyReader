import json
import sys
import unittest
from pathlib import Path


PYTHON_SRC = Path(__file__).parents[2] / "main" / "python"
sys.path.insert(0, str(PYTHON_SRC))

import pronunciation_dict as pd


def _configurar(*pares):
    pd.configurar(json.dumps([{"word": w, "spoken": s} for w, s in pares]))


class PronunciationDictTest(unittest.TestCase):
    """Espelho do PronunciationDictionaryTest.kt: o audiobook e a leitura guiada precisam trocar igual."""

    def tearDown(self):
        pd.configurar("[]")

    def test_troca_a_palavra_pela_pronuncia(self):
        _configurar(("Hermione", "Hermáione"))
        self.assertEqual(pd.aplicar("Ela chamou Hermione."), "Ela chamou Hermáione.")

    def test_nao_diferencia_caixa_e_nao_troca_no_meio_de_outra_palavra(self):
        _configurar(("Ana", "Âna"))
        self.assertEqual(pd.aplicar("Ana e ana"), "Âna e Âna")
        self.assertEqual(pd.aplicar("banana e Anatólia"), "banana e Anatólia")

    def test_pontuacao_ao_redor(self):
        _configurar(("Nyx", "Níks"))
        self.assertEqual(pd.aplicar("“Nyx”, disse (Nyx)."), "“Níks”, disse (Níks).")

    def test_expressao_mais_longa_vence_e_espacos_flexiveis(self):
        _configurar(("Gandalf", "Gândalf"), ("Gandalf o Cinzento", "Gândalf, o Cinzento"))
        self.assertEqual(
            pd.aplicar("Gandalf  o Cinzento chegou; Gandalf ficou."),
            "Gândalf, o Cinzento chegou; Gândalf ficou.",
        )

    def test_uma_unica_passada(self):
        _configurar(("a", "b"), ("b", "c"))
        self.assertEqual(pd.aplicar("a b"), "b c")

    def test_caracteres_especiais_sao_literais(self):
        _configurar(("C++", "cê mais mais"), ("R2-D2", "érre dois dê dois"))
        self.assertEqual(pd.aplicar("C++ e R2-D2"), "cê mais mais e érre dois dê dois")

    def test_ultima_definicao_vence_e_entradas_vazias_sao_ignoradas(self):
        _configurar(("Ana", "Âna"), ("ana", "Ãna"), ("", "x"), ("Zé", "  "))
        self.assertEqual(pd.aplicar("Ana"), "Ãna")

    def test_json_invalido_ou_vazio_zera(self):
        _configurar(("Ana", "Âna"))
        pd.configurar("{não é json")
        self.assertEqual(pd.aplicar("Ana"), "Ana")
        self.assertEqual(pd.assinatura(), "")
        pd.configurar("")
        self.assertEqual(pd.aplicar("Ana"), "Ana")

    def test_assinatura_muda_com_o_conteudo(self):
        self.assertEqual(pd.assinatura(), "")
        _configurar(("Ana", "Âna"))
        a = pd.assinatura()
        _configurar(("Ana", "Ãna"))
        self.assertNotEqual(a, pd.assinatura())
        self.assertNotEqual(a, "")

    def test_converter_tts_auto_aplica_o_dicionario_antes_de_normalizar(self):
        # A mesma troca do Kotlin acontece no início de converter_tts_auto (audiobook).
        source = (PYTHON_SRC / "tts.py").read_text(encoding="utf-8")
        self.assertLess(source.index("_pronuncias.aplicar(texto)"), source.index("texto = _normalizar_texto("))


if __name__ == "__main__":
    unittest.main()

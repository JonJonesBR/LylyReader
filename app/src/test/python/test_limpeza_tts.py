import json
import sys
import unittest
from pathlib import Path

PYTHON_SRC = Path(__file__).parents[2] / "main" / "python"
sys.path.insert(0, str(PYTHON_SRC))

from narration_normalizer import sanitize_text_noise
from smart_cleaner import _classificar_linha, smart_clean_paragraphs_json


class LimpezaParaTtsTest(unittest.TestCase):
    def test_ligaduras_viram_letras(self):
        self.assertEqual("O fim do fluxo", sanitize_text_noise("O ﬁm do ﬂuxo"))

    def test_hifen_invisivel_some(self):
        self.assertEqual("extraordinário", sanitize_text_noise("extra­ordinário"))

    def test_chamadas_de_nota_somem(self):
        self.assertEqual("Ele disse que sim.", sanitize_text_noise("Ele disse¹ que sim²³."))
        self.assertEqual("Foi o que ele disse.", sanitize_text_noise("Foi o que ele disse[12]."))

    def test_colchete_solto_e_expoente_de_unidade_ficam(self):
        self.assertEqual("item [12] lista", sanitize_text_noise("item [12] lista"))
        self.assertEqual("10 m² e 5 cm³", sanitize_text_noise("10 m² e 5 cm³"))

    def test_titulo_numerado_nao_e_nota(self):
        self.assertIsNone(_classificar_linha("1. A partida"))
        self.assertIsNone(_classificar_linha("12) O retorno"))
        self.assertEqual("nota_rodape", _classificar_linha("1. Ver Platão, A República, p. 42."))
        self.assertEqual("nota_rodape", _classificar_linha("[3] Nota do tradutor"))

    def test_limpeza_em_json(self):
        saida = json.loads(smart_clean_paragraphs_json(json.dumps(["O ﬁm.", "42"]), "epub", "pt", "[]"))
        self.assertEqual(["O fim.", ""], saida["paragrafos"])
        self.assertEqual(1, saida["removidas"])


if __name__ == "__main__":
    unittest.main()

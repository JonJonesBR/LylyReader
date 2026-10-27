import sys
import unicodedata
import unittest
from pathlib import Path


PYTHON_SRC = Path(__file__).parents[2] / "main" / "python"
sys.path.insert(0, str(PYTHON_SRC))

from text_normalizer import comparar_textos, normalizar


class InvisiveisSupertonicTest(unittest.TestCase):
    """Caracteres invisíveis de EPUB (hífen opcional, marcas de direção) chegavam ao Supertonic no meio
    das palavras, onde viram um token desconhecido. A normalização (a mesma da síntese) os tira."""

    PARAGRAFO = (
        "— Acabamos de sofrer outro ataque. Alguém tentou explodir alguns componentes críticos. "
        "Eles erraram o alvo, mas quatro dos meus funcionários foram mortos. Vamos para um centro "
        "de operações secundário. Como está sua leitura?"
    )

    def _supertonic(self, texto):
        # Mesmas flags de OnnxTtsSynthesizer: números soltos por extenso, travessões mantidos.
        return normalizar(texto, "pt-BR", True, False)

    def test_paragrafo_relatado_chega_intacto_ao_motor(self):
        resultado = self._supertonic(self.PARAGRAFO)
        self.assertEqual(resultado, self.PARAGRAFO)
        self.assertTrue(unicodedata.is_normalized("NFC", resultado))

    def test_hifen_opcional_some_de_dentro_da_palavra(self):
        self.assertEqual(
            self._supertonic("Como es­tá sua lei­tu­ra? Va­mos pa­ra um centro."),
            "Como está sua leitura? Vamos para um centro.",
        )

    def test_marcas_de_direcao_e_larguras_zero_somem(self):
        self.assertEqual(
            self._supertonic("﻿Vamos‎ para‏ um⁦ centro⁩ de‪ ope‬rações."),
            "Vamos para um centro de operações.",
        )

    def test_texto_decomposto_volta_a_nfc(self):
        self.assertEqual(self._supertonic(unicodedata.normalize("NFD", "Como está?")), "Como está?")

    def test_relatorio_nao_lista_invisivel_como_troca(self):
        self.assertEqual(comparar_textos("Como es­tá sua leitura?", "Como está sua leitura?"), [])


if __name__ == "__main__":
    unittest.main()

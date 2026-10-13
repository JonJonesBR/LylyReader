import sys
import unittest
from pathlib import Path

PYTHON_SRC = Path(__file__).parents[2] / "main" / "python"
sys.path.insert(0, str(PYTHON_SRC))

try:
    from utils import sanitizar_trecho_para_tts
except ImportError:  # dependências de extração ausentes no ambiente de teste
    sanitizar_trecho_para_tts = None


@unittest.skipIf(sanitizar_trecho_para_tts is None, "módulo utils indisponível")
class SanitizarTrechoParaTtsTest(unittest.TestCase):
    def test_texto_normal_fica_inalterado(self):
        texto = "Olá, mundo! Você está bem? Sim: é um teste; até logo."
        self.assertEqual(texto, sanitizar_trecho_para_tts(texto))

    def test_emoji_e_removido(self):
        self.assertEqual("Parabéns pelo livro!", sanitizar_trecho_para_tts("Parabéns 🎉 pelo livro! 👍🏽"))

    def test_emoji_com_variation_selector_e_zwj_e_removido(self):
        self.assertEqual("Coração feliz", sanitizar_trecho_para_tts("Coração ❤️ feliz 👨‍👩‍👧"))

    def test_zero_width_e_removido(self):
        self.assertEqual("palavrainteira", sanitizar_trecho_para_tts("palavra​inteira‍⁠﻿"))

    def test_controle_e_removido(self):
        self.assertEqual("ab cd", sanitizar_trecho_para_tts("ab\x00\x07 cd"))

    def test_aspas_tipograficas_viram_simples(self):
        self.assertEqual('Ele disse "oi" e\'s', sanitizar_trecho_para_tts("Ele disse “oi” e’s"))
        self.assertEqual("\"citação\" 'x'", sanitizar_trecho_para_tts("«citação» ‘x’"))

    def test_travessoes_viram_hifen(self):
        self.assertEqual("a-b-c", sanitizar_trecho_para_tts("a–b—c"))

    def test_acentos_sao_preservados(self):
        self.assertEqual("ação, coração, é", sanitizar_trecho_para_tts("ação, coração, é"))

    def test_nfkc_normaliza_compatibilidade(self):
        # Ligadura e largura total viram forma simples após NFKC
        self.assertEqual("final fi", sanitizar_trecho_para_tts("ﬁnal ｆｉ"))

    def test_espacos_sao_colapsados(self):
        self.assertEqual("um dois três", sanitizar_trecho_para_tts("um   \t dois \n\n três  "))

    def test_so_simbolos_resulta_vazio(self):
        self.assertEqual("", sanitizar_trecho_para_tts("★ ✨ 🎉 → ©"))

    def test_preserva_moeda(self):
        self.assertEqual("R$ 10", sanitizar_trecho_para_tts("R$ 10 🎉"))

    def test_texto_com_letras_nunca_fica_vazio(self):
        resultado = sanitizar_trecho_para_tts("🎉 a")
        self.assertEqual("a", resultado)
        self.assertTrue(resultado)

    def test_digitos_sao_preservados(self):
        self.assertEqual("Capítulo 12, página 3.", sanitizar_trecho_para_tts("Capítulo 12, página 3. 📖"))


if __name__ == "__main__":
    unittest.main()

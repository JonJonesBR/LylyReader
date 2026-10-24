import sys
import unittest
from pathlib import Path

PYTHON_SRC = Path(__file__).parents[2] / "main" / "python"
sys.path.insert(0, str(PYTHON_SRC))

try:
    from utils import precisa_sanear
except ImportError:  # dependências de extração ausentes no ambiente de teste
    precisa_sanear = None


@unittest.skipIf(precisa_sanear is None, "módulo utils indisponível")
class PrecisaSanearTest(unittest.TestCase):
    def test_texto_comum_nao_precisa(self):
        self.assertFalse(precisa_sanear("Era uma vez um menino que gostava de ler."))
        self.assertFalse(precisa_sanear(""))
        self.assertFalse(precisa_sanear(None))

    def test_url_precisa(self):
        self.assertTrue(precisa_sanear("Veja em https://exemplo.com.br/pagina agora."))
        self.assertTrue(precisa_sanear("Acesse WWW.exemplo.com"))

    def test_email_precisa(self):
        self.assertTrue(precisa_sanear("Escreva para fulano@dominio.com.br."))

    def test_numero_de_processo_precisa(self):
        self.assertTrue(precisa_sanear("Processo 0001234-56.2023.8.19.0001 em andamento."))

    def test_numero_de_processo_parcial_nao_dispara_por_si(self):
        self.assertFalse(precisa_sanear("Capítulo 12, página 3."))

    def test_sequencia_longa_de_digitos_precisa(self):
        self.assertTrue(precisa_sanear("Código 123456789 do pedido."))

    def test_oito_digitos_nao_dispara(self):
        self.assertFalse(precisa_sanear("Ano 12345678 apenas."))

    def test_sequencia_longa_de_simbolos_precisa(self):
        self.assertTrue(precisa_sanear("Antes ==== depois"))

    def test_tres_simbolos_nao_disparam(self):
        self.assertFalse(precisa_sanear("Fim... ou talvez !?"))

    def test_disponivel_em_precisa_sem_distinguir_caixa(self):
        self.assertTrue(precisa_sanear("Disponível em: biblioteca"))
        self.assertTrue(precisa_sanear("disponível em: outro lugar"))


if __name__ == "__main__":
    unittest.main()

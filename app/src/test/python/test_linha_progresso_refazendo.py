import sys
import unittest
from pathlib import Path

PYTHON_SRC = Path(__file__).parents[2] / "main" / "python"
sys.path.insert(0, str(PYTHON_SRC))

from utils import ProgressTracker, conexao_lenta, linha_de_progresso  # noqa: E402


class ConexaoLentaTest(unittest.TestCase):

    def test_motor_local_nunca_e_lento(self):
        self.assertFalse(conexao_lenta([30_000] * 5, motor_de_rede=False))

    def test_mediana_de_20_s_ou_mais_e_lenta(self):
        self.assertTrue(conexao_lenta([20_000, 20_000, 20_000], motor_de_rede=True))
        self.assertTrue(conexao_lenta([5_000, 25_000, 30_000, 22_000, 21_000], motor_de_rede=True))

    def test_mediana_abaixo_de_20_s_nao_e_lenta(self):
        self.assertFalse(conexao_lenta([19_999, 19_999, 19_999], motor_de_rede=True))
        self.assertFalse(conexao_lenta([60_000, 60_000, 2_000, 2_000, 3_000], motor_de_rede=True))

    def test_um_bloco_lento_isolado_nao_basta(self):
        self.assertFalse(conexao_lenta([90_000], motor_de_rede=True))
        self.assertFalse(conexao_lenta([90_000, 90_000], motor_de_rede=True))

    def test_so_olha_os_ultimos_5_blocos(self):
        # Os 3 primeiros foram lentos, mas os 5 últimos são rápidos: não é lenta.
        self.assertFalse(conexao_lenta([50_000, 50_000, 50_000, 1_000, 1_000, 1_000, 1_000, 1_000],
                                       motor_de_rede=True))
        # Os 5 últimos são lentos mesmo com início rápido.
        self.assertTrue(conexao_lenta([1_000, 1_000, 1_000, 25_000, 25_000, 25_000, 25_000, 25_000],
                                      motor_de_rede=True))

    def test_sem_amostras_nao_e_lenta(self):
        self.assertFalse(conexao_lenta([], motor_de_rede=True))


class LinhaDeProgressoTest(unittest.TestCase):

    def test_linha_comum_sem_campos_novos(self):
        self.assertEqual("@progresso pct=18 eta_s=168", linha_de_progresso(18, eta_s=168))
        self.assertEqual("@progresso pct=18 eta_s=168 falhas=2",
                         linha_de_progresso(18, eta_s=168, falhas=2))

    def test_lenta_falso_ou_zero_nao_aparece(self):
        self.assertNotIn("lenta", linha_de_progresso(10, eta_s=5, lenta=False))
        self.assertNotIn("refazendo", linha_de_progresso(10, eta_s=5, refazendo=0, rodada=2, rodadas=3))

    def test_lenta_verdadeiro_vira_lenta_1(self):
        self.assertEqual("@progresso pct=10 eta_s=5 lenta=1", linha_de_progresso(10, eta_s=5, lenta=True))

    def test_rodada_de_retry_traz_refazendo_sem_eta_nem_falhas(self):
        self.assertEqual(
            "@progresso pct=60 refazendo=2 rodada=1 rodadas=3",
            linha_de_progresso(60, eta_s=100, falhas=2, refazendo=2, rodada=1, rodadas=3),
        )

    def test_rodada_de_retry_com_conexao_lenta(self):
        self.assertEqual(
            "@progresso pct=60 refazendo=2 rodada=2 rodadas=3 lenta=1",
            linha_de_progresso(60, refazendo=2, rodada=2, rodadas=3, lenta=True),
        )


class MensagemSimplesTest(unittest.TestCase):

    def test_mensagem_simples_com_lenta(self):
        t = ProgressTracker(4, inicio=0.0)
        self.assertEqual("@progresso pct=10 eta_s=-1 lenta=1", t.mensagem_simples(10, lenta=True))

    def test_mensagem_simples_sem_lenta_fica_igual(self):
        t = ProgressTracker(4, inicio=0.0)
        self.assertEqual("@progresso pct=10 eta_s=-1", t.mensagem_simples(10))


if __name__ == "__main__":
    unittest.main()

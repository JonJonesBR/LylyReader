import sys
import unittest
from pathlib import Path
from unittest.mock import patch

PYTHON_SRC = Path(__file__).parents[2] / "main" / "python"
sys.path.insert(0, str(PYTHON_SRC))

try:
    from utils import ProgressTracker
except ImportError:  # dependências de extração ausentes no ambiente de teste
    ProgressTracker = None


@unittest.skipIf(ProgressTracker is None, "módulo utils indisponível")
class ProgressTrackerTest(unittest.TestCase):
    def test_falha_nao_conta_como_concluido(self):
        with patch("utils.time.time", return_value=1.0):
            t = ProgressTracker(3, inicio=0.0)
            t.registrar(0, False)
        self.assertEqual(0, t.concluidos)
        self.assertEqual([0], t.falhas)
        self.assertEqual(0, t.pct())

    def test_retry_bem_sucedido_remove_da_lista_de_falhas(self):
        with patch("utils.time.time", return_value=1.0):
            t = ProgressTracker(3, inicio=0.0)
            t.registrar(0, False)
            t.registrar(0, True)
        self.assertEqual([], t.falhas)
        self.assertEqual(1, t.concluidos)

    def test_falha_repetida_nao_duplica_na_lista(self):
        with patch("utils.time.time", return_value=1.0):
            t = ProgressTracker(3, inicio=0.0)
            t.registrar(1, False)
            t.registrar(1, False)
        self.assertEqual([1], t.falhas)

    def test_mensagem_mostra_falhas_pendentes_e_some_quando_zeram(self):
        with patch("utils.time.time", return_value=1.0):
            t = ProgressTracker(3, inicio=0.0)
            t.registrar(0, False)
            self.assertIn("| 1 falhas", t.mensagem_simples())
            t.registrar(0, True)
            self.assertNotIn("falhas", t.mensagem_simples())

    def test_eta_nunca_zero_com_blocos_restantes(self):
        with patch("utils.time.time") as relogio:
            relogio.return_value = 0.1
            t = ProgressTracker(2, inicio=0.0)
            relogio.return_value = 0.1
            t.registrar(0, True)
            eta = t._eta()
        self.assertEqual("1s", eta)

    def test_eta_usa_media_da_execucao_nao_so_ultimas_cinco(self):
        # 1º bloco leva 10s; os seguintes saem a cada 1s (16s no total, 7 blocos).
        # Só as últimas 5 dariam 13s; a média da execução dá 16/7 * 13 = 29s.
        with patch("utils.time.time") as relogio:
            relogio.return_value = 0.0
            t = ProgressTracker(20, inicio=0.0)
            relogio.return_value = 10.0
            t.registrar(0, True)
            for i in range(1, 7):
                relogio.return_value = 10.0 + i
                t.registrar(i, True)
            eta = t._eta()
        self.assertEqual("29s", eta)

    def test_retomada_nao_zera_nem_infla_eta(self):
        with patch("utils.time.time") as relogio:
            relogio.return_value = 0.0
            t = ProgressTracker(10, inicio=0.0, concluidos_iniciais=6)
            # Sem nenhum bloco produzido nesta execução ainda não há base.
            self.assertEqual("calculando...", t._eta())
            relogio.return_value = 2.0
            t.registrar(6, True)
            eta = t._eta()
        self.assertEqual(7, t.concluidos)
        # 2s por bloco produzido x 3 restantes = 6s (não 2/7*3, que daria 0s).
        self.assertEqual("6s", eta)

    def test_percentual_so_chega_a_100_com_todos_ok(self):
        with patch("utils.time.time", return_value=1.0):
            t = ProgressTracker(3, inicio=0.0)
            t.registrar(0, True)
            t.registrar(1, True)
            t.registrar(2, False)
            self.assertEqual(66, t.pct())
            self.assertNotEqual(100, t.pct())
            t.registrar(2, True)
        self.assertEqual(100, t.pct())
        self.assertEqual([], t.falhas)


if __name__ == "__main__":
    unittest.main()

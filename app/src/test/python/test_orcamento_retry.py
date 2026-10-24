import asyncio
import os
import sys
import tempfile
import time
import unittest
from pathlib import Path
from unittest import mock

PYTHON_SRC = Path(__file__).parents[2] / "main" / "python"
sys.path.insert(0, str(PYTHON_SRC))

from orcamento_retry import OrcamentoRetry  # noqa: E402

try:
    import core_processor_android as cpa
except ImportError:  # dependências de conversão ausentes no ambiente de teste
    cpa = None


class Relogio:
    """Relógio falso: o teste avança o tempo sem dormir."""

    def __init__(self):
        self.agora = 1000.0

    def __call__(self):
        return self.agora

    def avancar(self, segundos):
        self.agora += segundos


class OrcamentoRetryTest(unittest.TestCase):
    def test_antes_de_iniciar_nada_foi_gasto(self):
        relogio = Relogio()
        orcamento = OrcamentoRetry(480, relogio)
        relogio.avancar(900)
        self.assertEqual(0.0, orcamento.gasto())
        self.assertEqual(480.0, orcamento.restante())
        self.assertFalse(orcamento.esgotado())

    def test_restante_diminui_com_o_tempo(self):
        relogio = Relogio()
        orcamento = OrcamentoRetry(480, relogio)
        orcamento.iniciar()
        relogio.avancar(300)
        self.assertEqual(300.0, orcamento.gasto())
        self.assertEqual(180.0, orcamento.restante())
        self.assertFalse(orcamento.esgotado())

    def test_esgota_ao_chegar_no_total(self):
        relogio = Relogio()
        orcamento = OrcamentoRetry(480, relogio)
        orcamento.iniciar()
        relogio.avancar(480)
        self.assertEqual(0.0, orcamento.restante())
        self.assertTrue(orcamento.esgotado())

    def test_restante_nunca_fica_negativo(self):
        relogio = Relogio()
        orcamento = OrcamentoRetry(480, relogio)
        orcamento.iniciar()
        relogio.avancar(2000)
        self.assertEqual(0.0, orcamento.restante())
        self.assertTrue(orcamento.esgotado())

    def test_iniciar_duas_vezes_nao_reinicia_a_contagem(self):
        relogio = Relogio()
        orcamento = OrcamentoRetry(480, relogio)
        orcamento.iniciar()
        relogio.avancar(400)
        orcamento.iniciar()
        self.assertEqual(80.0, orcamento.restante())

    def test_orcamento_zero_ou_negativo_ja_esta_esgotado(self):
        relogio = Relogio()
        for total in (0, -5):
            with self.subTest(total=total):
                orcamento = OrcamentoRetry(total, relogio)
                orcamento.iniciar()
                self.assertTrue(orcamento.esgotado())

    def test_usa_monotonic_por_padrao(self):
        orcamento = OrcamentoRetry(480)
        orcamento.iniciar()
        self.assertFalse(orcamento.esgotado())
        self.assertLessEqual(orcamento.gasto(), time.monotonic())


@unittest.skipIf(cpa is None, "módulos de conversão indisponíveis")
class OrcamentoNasRodadasDeRetryTest(unittest.TestCase):
    """O processador para de reprocessar quando o orçamento acaba e segue o caminho de falha existente."""

    def _rodar(self, tmp, converter, orcamento_s):
        with mock.patch.object(cpa, "TEMP_DIR", tmp), \
                mock.patch.object(cpa, "garantir_temp_dir", lambda: None), \
                mock.patch.object(cpa, "converter_tts_auto", converter), \
                mock.patch.object(cpa, "obter_capa", lambda *_a, **_k: None), \
                mock.patch.object(cpa._config, "adicionar_historico", lambda *_a, **_k: None), \
                mock.patch.object(cpa, "_ORCAMENTO_RETRY_S", orcamento_s), \
                mock.patch.dict(cpa._config.CONFIG, {"motor_tts": "edge"}):
            return asyncio.run(cpa.processar(
                "Texto curto de teste para o orçamento.", "pt-BR-FranciscaNeural",
                saida_mp3=os.path.join(tmp, "saida.mp3"),
            ))

    def test_orcamento_zero_nao_faz_rodada_de_retry(self):
        chamadas = []

        async def converter(texto, voz, arquivo, *_a, **_k):
            chamadas.append(texto)
            return False

        with tempfile.TemporaryDirectory() as tmp:
            resultado = self._rodar(tmp, converter, orcamento_s=0)
        self.assertFalse(resultado["ok"])
        self.assertEqual(1, len(chamadas))  # só a rodada inicial, sem reprocessar

    def test_estouro_durante_a_rodada_encerra_sem_travar(self):
        chamadas = []

        async def converter(texto, voz, arquivo, *_a, **_k):
            chamadas.append(texto)
            if len(chamadas) == 1:
                return False  # falha na rodada inicial
            await asyncio.sleep(30)  # rodada de retry que não termina
            return False

        inicio = time.monotonic()
        with tempfile.TemporaryDirectory() as tmp:
            resultado = self._rodar(tmp, converter, orcamento_s=0.05)
        self.assertLess(time.monotonic() - inicio, 10)
        self.assertFalse(resultado["ok"])
        # A rodada de retry espera 2 s antes de chamar o motor; o orçamento de 0,05 s cancela antes disso.
        self.assertEqual(1, len(chamadas))


if __name__ == "__main__":
    unittest.main()

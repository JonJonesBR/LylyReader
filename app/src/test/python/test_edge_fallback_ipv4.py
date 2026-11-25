import asyncio
import os
import socket
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

PYTHON_SRC = Path(__file__).parents[2] / "main" / "python"
sys.path.insert(0, str(PYTHON_SRC))
sys.path.insert(0, str(Path(__file__).parent))

try:
    import config_android as _config
    import tts
except ImportError:  # dependências de síntese ausentes no ambiente de teste
    tts = None


@unittest.skipIf(tts is None, "módulo tts indisponível")
class EdgeFallbackIpv4Test(unittest.TestCase):
    def setUp(self):
        tts._limpar_edge_ipv4()
        tts.zerar_diagnostico_edge()

    def tearDown(self):
        tts._limpar_edge_ipv4()
        tts.zerar_diagnostico_edge()

    def _run_bloco(self, fabrica_communicate, tentativas=2):
        with tempfile.TemporaryDirectory() as tmp:
            saida = os.path.join(tmp, "bloco.mp3")
            with mock.patch.object(tts.edge_tts, "Communicate", fabrica_communicate), \
                    mock.patch.object(tts.CACHE, "get", return_value=None), \
                    mock.patch.object(tts.CACHE, "put"), \
                    mock.patch.object(_config, "wait_for_tts_slot", new=mock.AsyncMock()), \
                    mock.patch.object(tts.asyncio, "sleep", new=mock.AsyncMock()):
                return asyncio.run(
                    tts.converter_tts_bloco(
                        "Texto de teste para o bloco.",
                        "pt-BR-FranciscaNeural",
                        saida,
                        tentativas=tentativas,
                    )
                )

    def test_sem_erro_previo_chama_communicate_sem_connector(self):
        chamadas = []

        class _CommunicateOk:
            def __init__(self, texto, voz, **kwargs):
                chamadas.append(kwargs)

            async def stream(self):
                yield {"type": "audio", "data": b"x" * 1024}

        ok = self._run_bloco(_CommunicateOk, tentativas=1)
        self.assertTrue(ok)
        self.assertEqual(len(chamadas), 1)
        self.assertNotIn("connector", chamadas[0])

    def test_timeout_ativa_fallback_ipv4_na_tentativa_seguinte_e_mantem_para_proximo_bloco(self):
        chamadas = []

        class _CommunicateTimeoutDepoisOk:
            def __init__(self, texto, voz, **kwargs):
                chamadas.append(kwargs)
                self._idx = len(chamadas)

            async def stream(self):
                if self._idx == 1:
                    raise asyncio.TimeoutError()
                yield {"type": "audio", "data": b"x" * 1024}

        ok_1 = self._run_bloco(_CommunicateTimeoutDepoisOk, tentativas=2)
        self.assertTrue(ok_1)
        self.assertEqual(len(chamadas), 2)
        self.assertNotIn("connector", chamadas[0])
        self.assertIn("connector", chamadas[1])
        self.assertEqual(chamadas[1]["connector"]._family, socket.AF_INET)

        # Próximo bloco continua usando IPv4 (instância nova de connector)
        ok_2 = self._run_bloco(_CommunicateTimeoutDepoisOk, tentativas=1)
        self.assertTrue(ok_2)
        self.assertEqual(len(chamadas), 3)
        self.assertIn("connector", chamadas[2])
        self.assertIsNot(chamadas[1]["connector"], chamadas[2]["connector"])

        diag = tts.obter_diagnostico_edge()
        self.assertEqual(diag["timeouts"], 1)
        self.assertEqual(diag["tentativas_ipv4"], 2)
        self.assertEqual(diag["sucessos_ipv4"], 2)
        self.assertTrue(diag["fallback_ipv4_ativo"])

    def test_erro_conexao_ativa_fallback_ipv4(self):
        chamadas = []

        class _CommunicateErroDepoisOk:
            def __init__(self, texto, voz, **kwargs):
                chamadas.append(kwargs)
                self._idx = len(chamadas)

            async def stream(self):
                if self._idx == 1:
                    raise OSError("network unreachable")
                yield {"type": "audio", "data": b"x" * 1024}

        ok = self._run_bloco(_CommunicateErroDepoisOk, tentativas=2)
        self.assertTrue(ok)
        self.assertNotIn("connector", chamadas[0])
        self.assertIn("connector", chamadas[1])
        diag = tts.obter_diagnostico_edge()
        self.assertEqual(diag["erros_conexao"], 1)
        self.assertEqual(diag["sucessos_ipv4"], 1)

    def test_rate_limit_429_nao_ativa_fallback_ipv4(self):
        chamadas = []

        class _Communicate429DepoisOk:
            def __init__(self, texto, voz, **kwargs):
                chamadas.append(kwargs)
                self._idx = len(chamadas)

            async def stream(self):
                if self._idx == 1:
                    raise RuntimeError("429 Too Many Requests")
                yield {"type": "audio", "data": b"x" * 1024}

        ok = self._run_bloco(_Communicate429DepoisOk, tentativas=2)
        self.assertTrue(ok)
        self.assertNotIn("connector", chamadas[0])
        self.assertNotIn("connector", chamadas[1])
        diag = tts.obter_diagnostico_edge()
        self.assertEqual(diag["rate_limits_429"], 1)
        self.assertFalse(diag["fallback_ipv4_ativo"])

    def test_falha_no_ipv4_volta_para_padrao_do_sistema(self):
        tts._ativar_edge_ipv4()
        chamadas = []

        class _CommunicateFalhaIpv4DepoisOk:
            def __init__(self, texto, voz, **kwargs):
                chamadas.append(kwargs)
                self._idx = len(chamadas)

            async def stream(self):
                if self._idx == 1:
                    raise OSError("ipv4 unreachable")
                yield {"type": "audio", "data": b"x" * 1024}

        ok = self._run_bloco(_CommunicateFalhaIpv4DepoisOk, tentativas=2)
        self.assertTrue(ok)
        self.assertIn("connector", chamadas[0])
        self.assertNotIn("connector", chamadas[1])

    def test_zerar_diagnostico_limpa_contadores(self):
        tts._diagnostico_edge["timeouts"] = 4
        tts._diagnostico_edge["ultimo_erro"] = "TimeoutError"
        tts.zerar_diagnostico_edge()
        diag = tts.obter_diagnostico_edge()
        self.assertEqual(diag["timeouts"], 0)
        self.assertEqual(diag["ultimo_erro"], "")


if __name__ == "__main__":
    unittest.main()

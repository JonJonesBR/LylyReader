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
sys.path.insert(0, str(Path(__file__).parent))

try:
    import config_android as _config
    import tts
except ImportError:  # dependências de síntese ausentes no ambiente de teste
    tts = None


def _communicate_que_streama(tamanho: int, registro: list):
    """Falso do edge_tts.Communicate: stream() rende um item de WordBoundary (deve ser ignorado)
    e depois um pedaço de áudio de `tamanho` bytes."""

    class _CommunicateFalso:
        def __init__(self, texto, voz, rate="+0%", pitch="+0Hz", **_k):
            registro.append((texto, voz))

        async def stream(self):
            yield {"type": "WordBoundary", "offset": 0, "duration": 1, "text": "x"}
            yield {"type": "audio", "data": b"x" * tamanho}

    return _CommunicateFalso


@unittest.skipIf(tts is None, "módulo tts indisponível")
class ConverterTtsBlocoArquivoInvalidoTest(unittest.TestCase):
    def _converter(self, tamanho: int):
        registro = []
        with tempfile.TemporaryDirectory() as tmp:
            saida = os.path.join(tmp, "bloco.mp3")
            with mock.patch.object(tts.edge_tts, "Communicate",
                                   _communicate_que_streama(tamanho, registro)), \
                    mock.patch.object(tts.CACHE, "get", return_value=None), \
                    mock.patch.object(tts.CACHE, "put"), \
                    mock.patch.object(_config, "wait_for_tts_slot", new=mock.AsyncMock()):
                resultado = asyncio.run(
                    tts.converter_tts_bloco("Texto de teste para o bloco.", "pt-BR-FranciscaNeural",
                                            saida, tentativas=1)
                )
        return resultado

    def test_arquivo_pequeno_demais_nao_conta_como_sucesso(self):
        self.assertFalse(self._converter(100))

    def test_arquivo_valido_conta_como_sucesso(self):
        self.assertTrue(self._converter(2000))


@unittest.skipIf(tts is None, "módulo tts indisponível")
class VigiaSemDadosTest(unittest.TestCase):
    def test_stream_parado_levanta_timeout_rapido(self):
        class _ComunicateParado:
            async def stream(self):
                yield {"type": "audio", "data": b"x" * 10}
                await asyncio.sleep(5)
                yield {"type": "audio", "data": b"y" * 10}

        with tempfile.TemporaryDirectory() as tmp:
            saida = os.path.join(tmp, "parado.mp3")
            inicio = time.monotonic()
            with self.assertRaises(asyncio.TimeoutError):
                asyncio.run(tts._salvar_edge_com_vigia(_ComunicateParado(), saida, 0.2))
            self.assertLess(time.monotonic() - inicio, 2.0)


if __name__ == "__main__":
    unittest.main()

import asyncio
import base64
import sys
import tempfile
import time
import unittest
from pathlib import Path
from unittest import mock

PYTHON_SRC = Path(__file__).parents[2] / "main" / "python"
sys.path.insert(0, str(PYTHON_SRC))

try:
    import core_processor_android as cpa
    import gemini_tts
    import openrouter_tts
    from erros_tts import ErroDeChave
except ImportError:  # dependências de conversão ausentes no ambiente de teste
    cpa = None


class _RespFalsa:
    def __init__(self, status, corpo="", dados=None):
        self.status = status
        self._corpo = corpo
        self._dados = dados or {}

    async def text(self):
        return self._corpo

    async def json(self):
        return self._dados

    async def read(self):
        return self._corpo.encode()

    async def __aenter__(self):
        return self

    async def __aexit__(self, *_):
        return False


class _SessFalsa:
    """Sessão aiohttp falsa: a resposta depende da URL/cabeçalhos chamados."""

    def __init__(self, responder):
        self._responder = responder
        self.chamadas = 0
        self.ultimo_headers = {}

    def post(self, url, headers=None, **_kwargs):
        self.chamadas += 1
        self.ultimo_headers = dict(headers or {})
        return self._responder(url)


def _cache_vazio():
    cache = mock.MagicMock()
    cache.get.return_value = None
    return cache


def _audio_gemini_ok():
    pcm = b"\x00\x00" * 2400
    corpo = {"candidates": [{"content": {"parts": [{"inlineData": {"data": base64.b64encode(pcm).decode()}}]}}]}
    return _RespFalsa(200, dados=corpo)


@unittest.skipIf(cpa is None, "módulos de conversão indisponíveis")
class ProcessarAbortaComChaveRecusadaTest(unittest.TestCase):
    def test_processar_aborta_no_primeiro_erro_de_chave_sem_retries(self):
        async def converter_recusado(*_args, **_kwargs):
            raise ErroDeChave("Gemini: chave recusada (HTTP 403). Verifique a chave Gemini em Configurações.")

        texto = "Frase número de teste do livro. " * 400  # muitos blocos
        with tempfile.TemporaryDirectory() as tmp, \
                mock.patch.object(cpa, "TEMP_DIR", tmp), \
                mock.patch.object(cpa, "garantir_temp_dir", lambda: None), \
                mock.patch.object(cpa, "converter_tts_auto", side_effect=converter_recusado) as conv, \
                mock.patch.object(cpa._config, "get_tts_workers", return_value=1), \
                mock.patch.dict(cpa._config.CONFIG, {"motor_tts": "gemini"}):
            inicio = time.monotonic()
            resultado = asyncio.run(cpa.processar(
                texto, "Enceladus", saida_mp3=str(Path(tmp) / "saida.mp3"),
            ))
            decorrido = time.monotonic() - inicio

        self.assertFalse(resultado["ok"])
        self.assertIn("chave", resultado["erro"])
        self.assertIn("Gemini", resultado["erro"])
        self.assertEqual(1, conv.call_count)  # os demais blocos não são tentados
        self.assertLess(decorrido, 2.0)  # sem os backoffs de retry (2 s, 4 s, 8 s...)


@unittest.skipIf(cpa is None, "módulos de conversão indisponíveis")
class GeminiChaveRecusadaTest(unittest.TestCase):
    def setUp(self):
        patcher = mock.patch.object(gemini_tts, "CACHE", _cache_vazio())
        patcher.start()
        self.addCleanup(patcher.stop)

    def _cliente(self, chaves, responder):
        km = gemini_tts.KeyManager(chaves)
        client = gemini_tts.GeminiTTSClient(km, ["gemini-modelo-a"], enable_ssml=False)
        client._sess = _SessFalsa(responder)
        return client

    def test_403_com_uma_chave_levanta_erro_de_chave_sem_retry(self):
        client = self._cliente(["AAA"], lambda url: _RespFalsa(403, "forbidden"))
        with tempfile.TemporaryDirectory() as tmp, self.assertRaises(ErroDeChave) as ctx:
            asyncio.run(client.synth_chunk("texto de teste um", "Enceladus", str(Path(tmp) / "a.wav")))
        self.assertEqual(1, client._sess.chamadas)
        self.assertIn("Gemini", str(ctx.exception))

    def test_400_api_key_invalid_levanta_erro_de_chave(self):
        corpo = '{"error": {"reason": "API_KEY_INVALID", "message": "API key not valid"}}'
        client = self._cliente(["AAA"], lambda url: _RespFalsa(400, corpo))
        with tempfile.TemporaryDirectory() as tmp, self.assertRaises(ErroDeChave):
            asyncio.run(client.synth_chunk("texto de teste dois", "Enceladus", str(Path(tmp) / "b.wav")))
        self.assertEqual(1, client._sess.chamadas)

    def test_duas_chaves_com_uma_recusada_rotaciona_e_segue(self):
        client = self._cliente(["AAA", "BBB"], None)

        def responder(url):
            # A chave segue no cabeçalho, nunca na URL.
            self.assertNotIn("key=", url)
            chave = client._sess.ultimo_headers.get("x-goog-api-key")
            return _RespFalsa(403, "forbidden") if chave == "AAA" else _audio_gemini_ok()

        client._sess._responder = responder
        with tempfile.TemporaryDirectory() as tmp, \
                mock.patch.object(gemini_tts.asyncio, "sleep", new=_sem_espera):
            saida = str(Path(tmp) / "c.wav")
            ok = asyncio.run(client.synth_chunk("texto de teste tres", "Enceladus", saida))
            self.assertTrue(ok)
            self.assertTrue(Path(saida).exists())

    def test_duas_chaves_ambas_recusadas_levanta_erro_de_chave(self):
        client = self._cliente(["AAA", "BBB"], lambda url: _RespFalsa(401, "unauthorized"))
        with tempfile.TemporaryDirectory() as tmp, \
                mock.patch.object(gemini_tts.asyncio, "sleep", new=_sem_espera), \
                self.assertRaises(ErroDeChave):
            asyncio.run(client.synth_chunk("texto de teste quatro", "Enceladus", str(Path(tmp) / "d.wav")))
        self.assertEqual(2, client._sess.chamadas)


@unittest.skipIf(cpa is None, "módulos de conversão indisponíveis")
class OpenRouterChaveRecusadaTest(unittest.TestCase):
    def setUp(self):
        patcher = mock.patch.object(openrouter_tts, "CACHE", _cache_vazio())
        patcher.start()
        self.addCleanup(patcher.stop)

    def test_401_com_uma_chave_levanta_erro_de_chave_sem_retry(self):
        km = openrouter_tts.KeyManager(["K1"])
        client = openrouter_tts.OpenRouterTTSClient(km)
        client._sess = _SessFalsa(lambda url: _RespFalsa(401, "unauthorized"))
        with tempfile.TemporaryDirectory() as tmp, self.assertRaises(ErroDeChave) as ctx:
            asyncio.run(client.synth_chunk("texto de teste cinco", str(Path(tmp) / "e.wav")))
        self.assertEqual(1, client._sess.chamadas)
        self.assertIn("OpenRouter", str(ctx.exception))


async def _sem_espera(*_args, **_kwargs):
    return None


if __name__ == "__main__":
    unittest.main()

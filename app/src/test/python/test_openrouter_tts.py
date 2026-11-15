import asyncio
import sys
import tempfile
import types
import unittest
from pathlib import Path
from unittest import mock

PYTHON_SRC = Path(__file__).parents[2] / "main" / "python"
sys.path.insert(0, str(PYTHON_SRC))

try:
    import aiohttp  # noqa: F401
except ModuleNotFoundError:
    _aiohttp_stub = types.ModuleType("aiohttp")
    _aiohttp_stub.ClientTimeout = object
    _aiohttp_stub.ClientSession = object
    _aiohttp_stub.ClientConnectorError = Exception
    _aiohttp_stub.ClientError = Exception
    sys.modules["aiohttp"] = _aiohttp_stub

import audio_cache  # noqa: E402
import openrouter_tts  # noqa: E402


class _FakeResponse:
    def __init__(self, status: int, body: bytes):
        self.status = status
        self._body = body

    async def read(self) -> bytes:
        return self._body

    async def text(self) -> str:
        return self._body.decode("utf-8", errors="replace")

    async def __aenter__(self):
        return self

    async def __aexit__(self, *_):
        return False


class _FakeSession:
    def __init__(self, payload_factory):
        self._payload_factory = payload_factory
        self.calls = []

    def post(self, url, headers=None, json=None):
        self.calls.append({"url": url, "headers": headers, "json": json})
        return _FakeResponse(200, self._payload_factory(json))


class ExtrairModeloVozTest(unittest.TestCase):
    def test_todas_as_vozes_do_catalogo_mapeiam_para_modelo_e_voz_validos(self):
        catalogo = openrouter_tts.montar_catalogo_vozes()
        self.assertGreater(len(catalogo), 0)
        for item in catalogo:
            modelo, voz = openrouter_tts.extrair_modelo_voz(item["id"])
            self.assertEqual((item["model"], item["voice"]), (modelo, voz))
            self.assertIn(modelo, openrouter_tts.MODEL_VOICES)
            self.assertIn(voz, openrouter_tts.MODEL_VOICES[modelo])

    def test_casos_especificos_do_catalogo(self):
        esperados = {
            "openrouter-nova_en": ("openai/tts-1", "nova"),
            "openrouter-rachel_en": ("elevenlabs/eleven-turbo-v2", "Rachel"),
            "openrouter-rachel": ("elevenlabs/eleven-turbo-v2", "Rachel"),
            "openrouter-echo": ("openai/tts-1", "echo"),
            "openrouter-coral": ("openai/gpt-4o-mini-tts", "coral"),
        }
        for voice_id, esperado in esperados.items():
            self.assertEqual(esperado, openrouter_tts.extrair_modelo_voz(voice_id))

    def test_prefixos_explicitos_e_capitalizacao(self):
        casos = {
            "openrouter-openai-tts-nova": ("openai/tts-1", "nova"),
            "openrouter-openai-tts-hd-shimmer": ("openai/tts-1-hd", "shimmer"),
            "openrouter-gpt4o-tts-coral": ("openai/gpt-4o-mini-tts", "coral"),
            "openrouter-elevenlabs-rachel": ("elevenlabs/eleven-turbo-v2", "Rachel"),
            "openrouter-elevenlabs-domi": ("elevenlabs/eleven-turbo-v2", "Domi"),
            "openrouter-Domi": ("elevenlabs/eleven-turbo-v2", "Domi"),
        }
        for voice_id, esperado in casos.items():
            self.assertEqual(esperado, openrouter_tts.extrair_modelo_voz(voice_id))

    def test_ids_invalidos_ou_vazios(self):
        for voice_id in ("", "tts-pt-BR", "openrouter-", "openrouter-openai-tts-"):
            self.assertEqual(("", ""), openrouter_tts.extrair_modelo_voz(voice_id))


class KeyManagerTest(unittest.TestCase):
    def test_ignora_vazias_e_rotaciona_com_wraparound(self):
        km = openrouter_tts.KeyManager(["  k1 ", "", "   ", "k2"])
        self.assertTrue(km.has_keys)
        self.assertEqual(2, km.count)
        self.assertEqual("k1", asyncio.run(km.current()))
        asyncio.run(km.rotate())
        self.assertEqual("k2", asyncio.run(km.current()))
        asyncio.run(km.rotate())
        self.assertEqual("k1", asyncio.run(km.current()))

    def test_sem_chaves_nao_falha_ao_rotacionar(self):
        km = openrouter_tts.KeyManager(["", "   "])
        self.assertFalse(km.has_keys)
        self.assertEqual(0, km.count)
        self.assertIsNone(asyncio.run(km.current()))
        asyncio.run(km.rotate())
        self.assertIsNone(asyncio.run(km.current()))


class OpenRouterClientCacheESpeedTest(unittest.TestCase):
    def test_speed_e_limitado_entre_meio_e_dois(self):
        km = openrouter_tts.KeyManager(["k1"])
        self.assertEqual(0.5, openrouter_tts.OpenRouterTTSClient(km, speed=0.1).speed)
        self.assertEqual(2.0, openrouter_tts.OpenRouterTTSClient(km, speed=3.5).speed)
        self.assertEqual(1.25, openrouter_tts.OpenRouterTTSClient(km, speed=1.25).speed)

    def test_chave_do_cache_inclui_speed(self):
        km = openrouter_tts.KeyManager(["k1"])
        c1 = openrouter_tts.OpenRouterTTSClient(km, model="openai/tts-1", voice="echo", speed=1.0)
        c2 = openrouter_tts.OpenRouterTTSClient(km, model="openai/tts-1", voice="echo", speed=1.25)
        self.assertNotEqual(c1._cache_voice_key(), c2._cache_voice_key())

        with tempfile.TemporaryDirectory() as tmp:
            cache_dir = Path(tmp) / "cache"
            cache_teste = audio_cache.AudioCache()
            cache_teste.DIR = cache_dir

            def _gerar_audio(payload):
                tag = f"speed={payload['speed']:.2f}".encode()
                return b"RIFF" + tag.ljust(300, b"\x00")

            sess = _FakeSession(_gerar_audio)
            c1._sess = sess
            c2._sess = sess

            out_1a = Path(tmp) / "out1a.wav"
            out_2 = Path(tmp) / "out2.wav"
            out_1b = Path(tmp) / "out1b.wav"

            with mock.patch.object(openrouter_tts, "CACHE", cache_teste):
                self.assertTrue(asyncio.run(c1.synth_chunk("Texto de exemplo", str(out_1a))))
                # Speed diferente nao reutiliza cache do speed 1.0 e faz nova requisicao
                self.assertTrue(asyncio.run(c2.synth_chunk("Texto de exemplo", str(out_2))))
                # Mesmo speed reaproveita cache sem nova chamada HTTP
                self.assertTrue(asyncio.run(c1.synth_chunk("Texto de exemplo", str(out_1b))))

            self.assertEqual(2, len(sess.calls))
            self.assertIn(b"speed=1.00", out_1a.read_bytes())
            self.assertIn(b"speed=1.25", out_2.read_bytes())
            self.assertEqual(out_1a.read_bytes(), out_1b.read_bytes())


if __name__ == "__main__":
    unittest.main()

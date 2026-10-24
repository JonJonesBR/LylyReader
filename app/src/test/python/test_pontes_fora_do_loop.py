import asyncio
import sys
import tempfile
import threading
import time
import types
import unittest
from pathlib import Path
from unittest import mock

PYTHON_SRC = Path(__file__).parents[2] / "main" / "python"
sys.path.insert(0, str(PYTHON_SRC))

try:
    import tts
    from erros_tts import ErroDeChave
except ImportError:  # dependências de conversão ausentes no ambiente de teste
    tts = None


class _PonteLenta:
    """Imita uma ponte Kotlin síncrona: bloqueia a thread que a chama durante a síntese."""

    registro = {}
    resultado = True
    motivo = None

    @classmethod
    def synthesize(cls, texto, voz, saida):
        cls.registro["thread"] = threading.get_ident()
        time.sleep(0.4)
        Path(saida).write_bytes(b"x" * 600)
        return cls.resultado

    @classmethod
    def ultimoMotivo(cls):
        return cls.motivo


def _modulos_falsos_kotlin():
    """Modulos com o caminho com.jonjonesbr.audiobookgen.tts usado pelo `from ... import` do tts.py."""
    modulos = {}
    for nome in ("com", "com.jonjonesbr", "com.jonjonesbr.audiobookgen"):
        modulos[nome] = types.ModuleType(nome)
    ponte = types.ModuleType("com.jonjonesbr.audiobookgen.tts")
    for classe in ("AndroidSynthBridge", "OnnxSynthBridge", "KokoroSynthBridge",
                   "PocketSynthBridge", "ElevenLabsSynthBridge"):
        setattr(ponte, classe, _PonteLenta)
    modulos["com.jonjonesbr.audiobookgen.tts"] = ponte
    return modulos


@unittest.skipIf(tts is None, "módulo tts indisponível")
class PontesForaDoLoopTest(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self._tmp.cleanup)
        _PonteLenta.registro = {}
        _PonteLenta.resultado = True
        _PonteLenta.motivo = None
        patcher = mock.patch.dict(sys.modules, _modulos_falsos_kotlin())
        patcher.start()
        self.addCleanup(patcher.stop)

    def _saida(self) -> str:
        return str(Path(self._tmp.name) / "bloco.mp3")

    def test_ponte_android_roda_em_thread_de_worker_e_loop_segue_batendo(self):
        async def cenario():
            batidas = 0

            async def batimento():
                nonlocal batidas
                while True:
                    await asyncio.sleep(0.05)
                    batidas += 1

            tarefa = asyncio.create_task(batimento())
            ok = await tts.converter_tts_auto("Olá mundo.", "android::pacote::voz", self._saida())
            tarefa.cancel()
            return ok, batidas

        ok, batidas = asyncio.run(cenario())
        self.assertTrue(ok)
        self.assertNotEqual(threading.get_ident(), _PonteLenta.registro["thread"])
        # Com a ponte bloqueando 0.4 s, o batimento do loop precisa ter rodado várias vezes.
        self.assertGreaterEqual(batidas, 3)

    def test_ponte_elevenlabs_com_motivo_de_chave_levanta_erro_de_chave(self):
        _PonteLenta.resultado = False
        _PonteLenta.motivo = "elevenlabs http 401: {\"detail\":{\"status\":\"invalid_api_key\"}}"
        with self.assertRaises(ErroDeChave) as ctx:
            asyncio.run(tts.converter_tts_auto("Olá mundo.", "elevenlabs-rachel", self._saida()))
        self.assertIn("ElevenLabs", str(ctx.exception))
        self.assertIn("chave", str(ctx.exception))

    def test_ponte_elevenlabs_com_falha_de_rede_nao_levanta_erro_de_chave(self):
        _PonteLenta.resultado = False
        _PonteLenta.motivo = "Unable to resolve host api.elevenlabs.io"
        ok = asyncio.run(tts.converter_tts_auto("Olá mundo.", "elevenlabs-rachel", self._saida()))
        self.assertFalse(ok)


@unittest.skipIf(tts is None, "módulo tts indisponível")
class MotivoChaveElevenLabsTest(unittest.TestCase):
    def test_http_401_e_403_e_chave_ausente_sao_chave(self):
        self.assertTrue(tts._motivo_eh_chave_elevenlabs("elevenlabs http 401: invalid_api_key"))
        self.assertTrue(tts._motivo_eh_chave_elevenlabs("elevenlabs http 403: forbidden"))
        self.assertTrue(tts._motivo_eh_chave_elevenlabs("chave elevenlabs ausente"))

    def test_cota_esgotada_com_401_nao_e_chave(self):
        self.assertFalse(tts._motivo_eh_chave_elevenlabs("elevenlabs http 401: quota_exceeded"))

    def test_outros_erros_e_motivo_vazio_nao_sao_chave(self):
        self.assertFalse(tts._motivo_eh_chave_elevenlabs("elevenlabs http 500: erro interno"))
        self.assertFalse(tts._motivo_eh_chave_elevenlabs(""))
        self.assertFalse(tts._motivo_eh_chave_elevenlabs(None))


if __name__ == "__main__":
    unittest.main()

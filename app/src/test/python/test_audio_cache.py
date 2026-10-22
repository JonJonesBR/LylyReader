import sys
import tempfile
import unittest
from pathlib import Path


PYTHON_SRC = Path(__file__).parents[2] / "main" / "python"
sys.path.insert(0, str(PYTHON_SRC))

from audio_cache import AudioCache  # noqa: E402


class AudioCacheCorrupcaoTest(unittest.TestCase):
    def setUp(self):
        self._td = tempfile.TemporaryDirectory()
        self.cache = AudioCache()
        self.cache.DIR = Path(self._td.name)

    def tearDown(self):
        self._td.cleanup()

    def test_put_recusa_dados_vazios_ou_menores_que_o_minimo(self):
        self.assertFalse(self.cache.put("t", "v", "e", b""))
        self.assertFalse(self.cache.put("t", "v", "e", b"x" * (AudioCache.MIN_SIZE - 1)))
        self.assertEqual([], list(self.cache.DIR.glob("*.bin")))

    def test_get_ignora_arquivo_truncado_abaixo_do_minimo(self):
        chave = self.cache._key("texto", "voz", "motor")
        (self.cache.DIR / f"{chave}.bin").write_bytes(b"x" * 10)
        self.assertIsNone(self.cache.get("texto", "voz", "motor"))

    def test_get_sem_arquivo_retorna_none(self):
        self.assertIsNone(self.cache.get("nada", "voz", "motor"))

    def test_put_e_get_de_dados_validos_preservam_os_bytes(self):
        dados = bytes(range(256)) * 2
        self.assertTrue(self.cache.put("texto", "voz", "motor", dados))
        self.assertEqual(dados, self.cache.get("texto", "voz", "motor"))

    def test_clear_old_tolera_arquivo_truncado(self):
        chave = self.cache._key("texto", "voz", "motor")
        (self.cache.DIR / f"{chave}.bin").write_bytes(b"")
        self.assertEqual(1, self.cache.clear_old(days=-1))


if __name__ == "__main__":
    unittest.main()

import sys
import unittest
from pathlib import Path

PYTHON_SRC = Path(__file__).parents[2] / "main" / "python"
sys.path.insert(0, str(PYTHON_SRC))

try:
    from tts import montar_filtro_timbre
except ImportError:  # dependências de áudio ausentes no ambiente de teste
    montar_filtro_timbre = None


@unittest.skipIf(montar_filtro_timbre is None, "módulo tts indisponível")
class FiltroTimbreTest(unittest.TestCase):
    def test_padrao_nao_gera_filtro(self):
        self.assertEqual("", montar_filtro_timbre(0, 0))

    def test_so_agudos(self):
        self.assertEqual("treble=g=-4:f=6000:t=s", montar_filtro_timbre(0, 4))

    def test_tom_mantem_duracao(self):
        filtro = montar_filtro_timbre(2, 0)  # +1 semitom
        self.assertIn("asetrate=25427", filtro)
        self.assertIn("atempo=0.943874", filtro)

    def test_tom_negativo(self):
        self.assertIn("atempo=1.059463", montar_filtro_timbre(-2, 0))


if __name__ == "__main__":
    unittest.main()

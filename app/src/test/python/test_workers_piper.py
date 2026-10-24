import sys
import unittest
from pathlib import Path
from unittest import mock

PYTHON_SRC = Path(__file__).parents[2] / "main" / "python"
sys.path.insert(0, str(PYTHON_SRC))

try:
    import core_processor_android as cpa
except ImportError:  # dependências de conversão ausentes no ambiente de teste
    cpa = None

PIPER = "piper-ptbr-cadu::main-pt"
PIPER_2 = "piper-ptbr-faber::main-pt"


@unittest.skipIf(cpa is None, "módulo core_processor_android indisponível")
class WorkersParaTest(unittest.TestCase):
    def test_piper_so_com_4_ou_mais_cpus_usa_2_workers(self):
        self.assertEqual(2, cpa.workers_para({PIPER, PIPER_2}, padrao=1, cpu_count=4))
        self.assertEqual(2, cpa.workers_para({PIPER}, padrao=1, cpu_count=8))

    def test_piper_com_menos_de_4_cpus_usa_1_worker(self):
        self.assertEqual(1, cpa.workers_para({PIPER}, padrao=1, cpu_count=3))
        self.assertEqual(1, cpa.workers_para({PIPER}, padrao=1, cpu_count=1))

    def test_piper_misturado_com_outro_motor_local_usa_1(self):
        self.assertEqual(1, cpa.workers_para({PIPER, "kokoro-pf-dora"}, padrao=1, cpu_count=8))

    def test_outros_motores_locais_usam_1(self):
        for voz in ("kokoro-pf-dora", "pocket-ptbr-rafael", "supertonic-m1",
                    "android::pkg::voz", "mms-por::main-pt", "pacote::byom-voz"):
            with self.subTest(voz=voz):
                self.assertEqual(1, cpa.workers_para({voz}, padrao=1, cpu_count=8))

    def test_nuvem_devolve_o_padrao(self):
        self.assertEqual(10, cpa.workers_para({"pt-BR-FranciscaNeural"}, padrao=10, cpu_count=8))
        self.assertEqual(4, cpa.workers_para({"elevenlabs-x"}, padrao=4, cpu_count=8))
        self.assertEqual(3, cpa.workers_para({"Aoede"}, padrao=3, cpu_count=8))

    def test_sem_vozes_devolve_o_padrao(self):
        self.assertEqual(7, cpa.workers_para(set(), padrao=7, cpu_count=8))

    def test_sem_cpu_informada_usa_os_cpu_count(self):
        with mock.patch.object(cpa.os, "cpu_count", return_value=6):
            self.assertEqual(2, cpa.workers_para({PIPER}, padrao=1))
        with mock.patch.object(cpa.os, "cpu_count", return_value=None):
            self.assertEqual(1, cpa.workers_para({PIPER}, padrao=1))


if __name__ == "__main__":
    unittest.main()

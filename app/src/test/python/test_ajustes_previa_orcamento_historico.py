import os
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

PYTHON_SRC = Path(__file__).parents[2] / "main" / "python"
sys.path.insert(0, str(PYTHON_SRC))

try:
    import core_processor_android as cpa
    import config_android
except ImportError:  # dependências de conversão ausentes no ambiente de teste
    cpa = None
    config_android = None


@unittest.skipIf(cpa is None, "módulo core_processor_android indisponível")
class LimitePreviaTest(unittest.TestCase):
    def test_limite_da_previa_e_15_minutos(self):
        self.assertEqual(15 * 60 * 1000, cpa._PREVIA_LIMITE_MS)

    def test_apagar_previa_remove_arquivo_e_temporario(self):
        with tempfile.TemporaryDirectory() as tmp, mock.patch.object(cpa, "TEMP_DIR", tmp):
            for nome in ("previa.mp3", "previa.mp3.tmp"):
                open(os.path.join(tmp, nome), "wb").close()
            cpa._apagar_previa()
            self.assertFalse(os.path.exists(os.path.join(tmp, "previa.mp3")))
            self.assertFalse(os.path.exists(os.path.join(tmp, "previa.mp3.tmp")))

    def test_apagar_previa_sem_arquivos_nao_levanta(self):
        with tempfile.TemporaryDirectory() as tmp, mock.patch.object(cpa, "TEMP_DIR", tmp):
            cpa._apagar_previa()

    def test_zerar_job_ativo(self):
        with mock.patch.dict(cpa._job_ativo, {"jh": "0123456789abcdef", "total": 4}):
            cpa._zerar_job_ativo()
            self.assertIsNone(cpa._job_ativo["jh"])
            self.assertEqual(0, cpa._job_ativo["total"])


@unittest.skipIf(cpa is None, "módulo core_processor_android indisponível")
class OrcamentoPorMotorTest(unittest.TestCase):
    def test_so_motores_locais_nao_tem_orcamento(self):
        locais = {"piper-ptbr-cadu::main-pt", "kokoro-pf-dora", "pocket-ptbr-rafael", "supertonic-m1",
                  "android::pkg::voz", "mms-por::main-pt"}
        self.assertFalse(cpa.orcamento_retry_aplicavel(locais))

    def test_edge_e_nuvem_tem_orcamento(self):
        self.assertTrue(cpa.orcamento_retry_aplicavel({"pt-BR-FranciscaNeural"}))
        self.assertTrue(cpa.orcamento_retry_aplicavel({"elevenlabs-x"}))
        self.assertTrue(cpa.orcamento_retry_aplicavel({"Aoede"}))

    def test_mistura_com_voz_de_nuvem_tem_orcamento(self):
        self.assertTrue(cpa.orcamento_retry_aplicavel({"kokoro-pf-dora", "pt-BR-FranciscaNeural"}))

    def test_sem_vozes_nao_tem_orcamento(self):
        self.assertFalse(cpa.orcamento_retry_aplicavel(set()))


@unittest.skipIf(config_android is None, "módulo config_android indisponível")
class TrocarTamanhoDoPrimeiroTest(unittest.TestCase):
    def test_troca_quando_a_entrada_ainda_tem_o_tamanho_do_wav(self):
        historico = [{"titulo": "A", "tamanho_mb": 12.0}, {"titulo": "B", "tamanho_mb": 3.0}]
        self.assertTrue(config_android._trocar_tamanho_do_primeiro(historico, "12.0", "4.1"))
        self.assertEqual(4.1, historico[0]["tamanho_mb"])
        self.assertEqual(3.0, historico[1]["tamanho_mb"])

    def test_nao_troca_se_o_tamanho_ja_mudou(self):
        historico = [{"titulo": "A", "tamanho_mb": 9.9}]
        self.assertFalse(config_android._trocar_tamanho_do_primeiro(historico, "12.0", "4.1"))
        self.assertEqual(9.9, historico[0]["tamanho_mb"])

    def test_historico_vazio_ou_valor_invalido(self):
        self.assertFalse(config_android._trocar_tamanho_do_primeiro([], "1.0", "0.5"))
        historico = [{"titulo": "A", "tamanho_mb": "x"}]
        self.assertFalse(config_android._trocar_tamanho_do_primeiro(historico, "1.0", "0.5"))


if __name__ == "__main__":
    unittest.main()

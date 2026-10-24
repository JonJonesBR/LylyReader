import json
import os
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

PYTHON_SRC = Path(__file__).parents[2] / "main" / "python"
sys.path.insert(0, str(PYTHON_SRC))

import velocidade_motores as vm  # noqa: E402

try:
    import core_processor_android as cpa
    import tts
except ImportError:  # dependências de conversão ausentes no ambiente de teste
    cpa = None
    tts = None


class MotorMedidoTest(unittest.TestCase):
    def test_motores_locais_e_edge(self):
        self.assertEqual("edge", vm.motor_medido("pt-BR-FranciscaNeural", "edge"))
        self.assertEqual("android", vm.motor_medido("android::pkg::voz", "android"))
        self.assertEqual("onnx", vm.motor_medido("supertonic-m1", "onnx"))
        self.assertEqual("pocket", vm.motor_medido("pocket-ptbr-rafael", "pocket"))
        self.assertEqual("kokoro", vm.motor_medido("kokoro-pf-dora", "kokoro"))

    def test_pacote_local_comum_e_piper(self):
        self.assertEqual("piper", vm.motor_medido("pacote42::pt_BR-faber", "kokoro"))

    def test_pacote_mms_ou_byom_e_kokoro(self):
        self.assertEqual("kokoro", vm.motor_medido("mms-por", "kokoro"))
        self.assertEqual("kokoro", vm.motor_medido("pacote::byom-voz", "kokoro"))

    def test_nuvem_nao_entra_na_medicao(self):
        self.assertIsNone(vm.motor_medido("elevenlabs-x", "elevenlabs"))
        self.assertIsNone(vm.motor_medido("openrouter-coral", "openrouter"))
        self.assertIsNone(vm.motor_medido("gemini-voz", "gemini"))


class MsPorCharTest(unittest.TestCase):
    def test_divide_tempo_pelos_caracteres(self):
        self.assertAlmostEqual(12.5, vm.ms_por_char(5000, 400))

    def test_sem_caracteres_ou_tempo_nao_mede(self):
        self.assertIsNone(vm.ms_por_char(5000, 0))
        self.assertIsNone(vm.ms_por_char(0, 400))
        self.assertIsNone(vm.ms_por_char(-1, 400))
        self.assertIsNone(vm.ms_por_char(float("nan"), 400))
        self.assertIsNone(vm.ms_por_char("x", 400))


class MediaMovelTest(unittest.TestCase):
    def test_primeira_amostra_substitui(self):
        self.assertEqual(10.0, vm.nova_media(None, 10.0))

    def test_media_exponencial_com_alfa_0_3(self):
        # 0,3 * 20 + 0,7 * 10 = 13
        self.assertAlmostEqual(13.0, vm.nova_media(10.0, 20.0))

    def test_media_anterior_invalida_e_tratada_como_ausente(self):
        self.assertEqual(7.0, vm.nova_media(float("nan"), 7.0))


class ExecucaoValidaTest(unittest.TestCase):
    def test_minimo_de_20_blocos(self):
        self.assertFalse(vm.execucao_valida(19, 0))
        self.assertTrue(vm.execucao_valida(20, 0))

    def test_falhas_ate_10_por_cento_passam(self):
        self.assertTrue(vm.execucao_valida(20, 2))   # 2 de 22 ~ 9%
        self.assertFalse(vm.execucao_valida(20, 3))  # 3 de 23 ~ 13% > 10%

    def test_entradas_invalidas_nao_passam(self):
        self.assertFalse(vm.execucao_valida(None, 0))
        self.assertFalse(vm.execucao_valida(30, -1))


class AplicarAmostraTest(unittest.TestCase):
    def test_primeira_amostra_cria_entrada(self):
        dados = vm.aplicar_amostra({}, "edge", 12.5)
        self.assertEqual({"edge": {"ms_por_char": 12.5, "amostras": 1}}, dados)

    def test_segunda_amostra_faz_media_e_conta(self):
        dados = vm.aplicar_amostra({"edge": {"ms_por_char": 10.0, "amostras": 1}}, "edge", 20.0)
        self.assertEqual(13.0, dados["edge"]["ms_por_char"])
        self.assertEqual(2, dados["edge"]["amostras"])

    def test_nao_altera_o_dict_original_e_preserva_outros_motores(self):
        original = {"kokoro": {"ms_por_char": 3.0, "amostras": 4}}
        novo = vm.aplicar_amostra(original, "edge", 9.0)
        self.assertEqual({"ms_por_char": 3.0, "amostras": 4}, original["kokoro"])
        self.assertEqual({"ms_por_char": 3.0, "amostras": 4}, novo["kokoro"])
        self.assertIn("edge", novo)


class LerGravarTest(unittest.TestCase):
    def test_ida_e_volta_e_formato_do_json(self):
        with tempfile.TemporaryDirectory() as tmp:
            caminho = os.path.join(tmp, vm.ARQUIVO)
            self.assertTrue(vm.gravar_medicoes(caminho, {"edge": {"ms_por_char": 12.5, "amostras": 3}}))
            with open(caminho, encoding="utf-8") as f:
                self.assertEqual({"edge": {"ms_por_char": 12.5, "amostras": 3}}, json.load(f))
            self.assertEqual({"edge": {"ms_por_char": 12.5, "amostras": 3}}, vm.ler_medicoes(caminho))
            self.assertFalse(os.path.exists(caminho + ".tmp"))

    def test_arquivo_ausente_ou_corrompido_devolve_vazio(self):
        with tempfile.TemporaryDirectory() as tmp:
            self.assertEqual({}, vm.ler_medicoes(os.path.join(tmp, "nao_existe.json")))
            ruim = os.path.join(tmp, "ruim.json")
            with open(ruim, "w", encoding="utf-8") as f:
                f.write("{isto não é json")
            self.assertEqual({}, vm.ler_medicoes(ruim))

    def test_entradas_invalidas_sao_descartadas(self):
        with tempfile.TemporaryDirectory() as tmp:
            caminho = os.path.join(tmp, "m.json")
            with open(caminho, "w", encoding="utf-8") as f:
                json.dump({"edge": {"ms_por_char": "x"}, "kokoro": {"ms_por_char": 2.0, "amostras": 1},
                           "lixo": 5}, f)
            self.assertEqual({"kokoro": {"ms_por_char": 2.0, "amostras": 1}}, vm.ler_medicoes(caminho))

    def test_falha_de_escrita_devolve_false_sem_levantar(self):
        with tempfile.TemporaryDirectory() as tmp:
            pasta_inexistente = os.path.join(tmp, "nao", "existe", "m.json")
            self.assertFalse(vm.gravar_medicoes(pasta_inexistente, {"edge": {}}))


class RegistrarExecucaoTest(unittest.TestCase):
    def test_grava_quando_a_execucao_e_valida(self):
        with tempfile.TemporaryDirectory() as tmp:
            ok = vm.registrar_execucao(tmp, "edge", 6000, 400, 20, 0)
            self.assertTrue(ok)
            self.assertEqual(
                {"edge": {"ms_por_char": 15.0, "amostras": 1}},
                vm.ler_medicoes(os.path.join(tmp, vm.ARQUIVO)),
            )

    def test_nao_grava_execucao_pequena_ou_com_muitas_falhas(self):
        with tempfile.TemporaryDirectory() as tmp:
            self.assertFalse(vm.registrar_execucao(tmp, "edge", 6000, 400, 10, 0))
            self.assertFalse(vm.registrar_execucao(tmp, "edge", 6000, 400, 20, 10))
            self.assertFalse(os.path.exists(os.path.join(tmp, vm.ARQUIVO)))

    def test_pasta_vazia_ou_motor_vazio_nao_faz_nada(self):
        self.assertFalse(vm.registrar_execucao("", "edge", 6000, 400, 20, 0))
        with tempfile.TemporaryDirectory() as tmp:
            self.assertFalse(vm.registrar_execucao(tmp, "", 6000, 400, 20, 0))

    def test_nunca_levanta_com_entradas_estranhas(self):
        with tempfile.TemporaryDirectory() as tmp:
            self.assertFalse(vm.registrar_execucao(tmp, "edge", None, None, None, None))


@unittest.skipIf(tts is None, "módulo tts indisponível")
class MotorDaVozTest(unittest.TestCase):
    def test_classificacao_espelha_o_roteamento(self):
        casos = {
            "android::pkg::voz": "android",
            "supertonic-m1": "onnx",
            "elevenlabs-x": "elevenlabs",
            "pocket-ptbr-rafael": "pocket",
            "kokoro-pf-dora": "kokoro",
            "pacote::voz": "kokoro",
            "openrouter-coral": "openrouter",
            "pt-BR-FranciscaNeural": "edge",
            "piper-pt_BR-faber": "edge",  # roteado para Edge pelo converter_tts_auto
        }
        for voz, esperado in casos.items():
            with self.subTest(voz=voz):
                self.assertEqual(esperado, tts.motor_da_voz(voz))


@unittest.skipIf(cpa is None, "módulo core_processor_android indisponível")
class RegistrarVelocidadeNoCoreTest(unittest.TestCase):
    def test_grava_com_blocos_suficientes_do_mesmo_motor(self):
        n = 20
        chunks = ["texto de teste " * 10] * n
        vozes = ["supertonic-m1"] * n
        with tempfile.TemporaryDirectory() as tmp, mock.patch.object(cpa._config, "FILES_DIR", tmp):
            cpa._registrar_velocidade(list(range(n)), chunks, vozes, set(range(n)), 8000.0)
            medicoes = vm.ler_medicoes(os.path.join(tmp, vm.ARQUIVO))
        self.assertIn("onnx", medicoes)
        self.assertEqual(1, medicoes["onnx"]["amostras"])

    def test_motores_misturados_nao_medem(self):
        n = 20
        chunks = ["x" * 100] * n
        vozes = ["supertonic-m1"] * 10 + ["pocket-ptbr-rafael"] * 10
        with tempfile.TemporaryDirectory() as tmp, mock.patch.object(cpa._config, "FILES_DIR", tmp):
            cpa._registrar_velocidade(list(range(n)), chunks, vozes, set(range(n)), 8000.0)
            self.assertFalse(os.path.exists(os.path.join(tmp, vm.ARQUIVO)))

    def test_sem_pasta_nao_levanta(self):
        with mock.patch.object(cpa._config, "FILES_DIR", ""):
            cpa._registrar_velocidade([0], ["x"], ["edge"], {0}, 10.0)


if __name__ == "__main__":
    unittest.main()

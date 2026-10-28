import asyncio
import json
import os
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

PYTHON_SRC = Path(__file__).parents[2] / "main" / "python"
sys.path.insert(0, str(PYTHON_SRC))
sys.path.insert(0, str(Path(__file__).parent))

try:
    import core_processor_android as core
    from fabricas_audio import gravar, mp3_falso
except ImportError:  # dependências de conversão ausentes no ambiente de teste
    core = None

JH = "0123456789abcdef"
VOZ = "supertonic-m1"


@unittest.skipIf(core is None, "módulo core_processor_android indisponível")
class RetomadaCompativelTest(unittest.TestCase):
    def test_tabela_verdade(self):
        self.assertTrue(core._retomada_compativel(None, None))
        self.assertTrue(core._retomada_compativel(None, "st=8;folga=0"))
        self.assertTrue(core._retomada_compativel("st=8;folga=0", None))
        self.assertTrue(core._retomada_compativel("st=8;folga=0", "st=8;folga=0"))
        self.assertFalse(core._retomada_compativel("st=8;folga=0", "st=4;folga=0"))
        self.assertFalse(core._retomada_compativel("st=8;folga=0", "st=8;folga=1"))
        self.assertFalse(core._retomada_compativel("", "st=8;folga=0"))


@unittest.skipIf(core is None, "módulo core_processor_android indisponível")
class AssinaturaAjustesTest(unittest.TestCase):
    def test_motores_sem_ajustes_devolvem_vazio(self):
        self.assertEqual("", core._assinatura_ajustes({"kokoro-pf-dora", "pacote::byom-voz"}))

    def test_ponte_indisponivel_devolve_none(self):
        # Sem Java/Chaquopy o import da ponte falha: a assinatura fica desconhecida.
        self.assertIsNone(core._assinatura_ajustes({VOZ}))
        self.assertIsNone(core._assinatura_ajustes({"pocket-ptbr-rafael"}))

    def test_ponte_responde_com_a_assinatura_de_cada_motor(self):
        class FakeOnnx:
            @staticmethod
            def assinaturaAjustes():
                return "st=8;folga=0"

        class FakePocket:
            @staticmethod
            def assinaturaAjustes():
                return "pk=4;t=50"

        modulo = mock.MagicMock(OnnxSynthBridge=FakeOnnx, PocketSynthBridge=FakePocket)
        with mock.patch.dict(sys.modules, {"com.jonjonesbr.audiobookgen.tts": modulo}):
            self.assertEqual("st=8;folga=0", core._assinatura_ajustes({VOZ}))
            self.assertEqual("pk=4;t=50", core._assinatura_ajustes({"pocket-ptbr-rafael"}))
            self.assertEqual("st=8;folga=0|pk=4;t=50",
                             core._assinatura_ajustes({"pocket-ptbr-rafael", VOZ, "kokoro-pf-dora"}))


@unittest.skipIf(core is None, "módulo core_processor_android indisponível")
class AjustesNoCheckpointTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.pasta = self.tmp.name
        os.makedirs(os.path.join(self.pasta, f"job_{JH}"))
        mock.patch.object(core, "TEMP_DIR", self.pasta).start()

    def tearDown(self):
        mock.patch.stopall()
        self.tmp.cleanup()

    def _pf(self) -> str:
        return os.path.join(self.pasta, f"job_{JH}", "progress.json")

    def test_salvar_e_carregar_com_ajustes(self):
        core._salvar_progresso(JH, {0, 1}, {0: 1000}, ajustes="st=8;folga=0")
        self.assertEqual("st=8;folga=0", core._carregar_ajustes(JH))
        self.assertEqual({0, 1}, core._carregar_progresso(JH))
        self.assertEqual({0: 1000}, core._carregar_duracoes(JH))

    def test_sem_ajustes_nao_grava_a_chave(self):
        core._salvar_progresso(JH, {0})
        with open(self._pf()) as f:
            self.assertNotIn("ajustes", json.load(f))
        self.assertIsNone(core._carregar_ajustes(JH))

    def test_checkpoint_antigo_sem_a_chave_devolve_none(self):
        with open(self._pf(), "w") as f:
            json.dump({"completed": [0]}, f)
        self.assertIsNone(core._carregar_ajustes(JH))
        self.assertEqual({0}, core._carregar_progresso(JH))


@unittest.skipIf(core is None, "módulo core_processor_android indisponível")
class RetomadaComAjustesTest(unittest.TestCase):
    """Roda o núcleo de verdade, com o hash do job fixo, para checar o que acontece na retomada."""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.pasta = self.tmp.name
        self.job = os.path.join(self.pasta, f"job_{JH}")
        os.makedirs(self.job)
        mock.patch.object(core, "TEMP_DIR", self.pasta).start()
        mock.patch.object(core, "_job_hash", lambda *_a, **_k: JH).start()
        mock.patch.object(core, "garantir_temp_dir", lambda: None).start()
        mock.patch.object(core, "obter_capa", lambda *_a, **_k: None).start()
        mock.patch.object(core._config, "adicionar_historico", lambda *_a, **_k: None).start()
        mock.patch.object(core, "_juncao_incremental_ativa", lambda: False).start()
        mock.patch.object(core, "concatenar_grupo_com_pausa", self._concatenar).start()
        self.salvos = []
        original = core._salvar_progresso

        def espiao(jh, completed, duracoes=None, ajustes=None):
            self.salvos.append(ajustes)
            return original(jh, completed, duracoes, ajustes=ajustes)

        mock.patch.object(core, "_salvar_progresso", espiao).start()

    def tearDown(self):
        mock.patch.stopall()
        self.tmp.cleanup()

    async def _concatenar(self, arquivos, saida, *_a, **_k):
        with open(saida, "wb") as f:
            f.write(mp3_falso(3000))
        return True

    def _checkpoint(self, ajustes):
        """Bloco 0 já pronto no disco, como numa conversão pausada com ajustes `ajustes`."""
        gravar(os.path.join(self.job, "part_0.mp3"), mp3_falso(3000))
        dados = {"completed": [0]}
        if ajustes is not None:
            dados["ajustes"] = ajustes
        with open(os.path.join(self.job, "progress.json"), "w") as f:
            json.dump(dados, f)

    def _rodar(self, converter):
        with mock.patch.object(core, "converter_tts_auto", converter):
            return asyncio.run(core.processar("Frase curta de teste.", VOZ,
                                              saida_mp3=os.path.join(self.pasta, "saida.mp3")))

    def test_ajustes_diferentes_limpam_o_job_e_reconvertem(self):
        self._checkpoint("st=4;folga=0")
        existia = {}

        async def converter(texto, voz, arquivo, *_a, **_k):
            existia["part"] = os.path.exists(os.path.join(self.job, "part_0.mp3"))
            existia["lote"] = os.path.exists(os.path.join(self.job, "batch_0.mp3"))
            gravar(arquivo, mp3_falso(3000))
            return True

        with mock.patch.object(core, "_assinatura_ajustes", lambda _v: "st=8;folga=0"), \
                self.assertLogs(core.logger, level="WARNING"):
            resultado = self._rodar(converter)
        self.assertTrue(resultado.get("ok"), resultado)
        self.assertFalse(existia["part"])
        self.assertFalse(existia["lote"])
        self.assertIn("st=8;folga=0", self.salvos)

    def test_checkpoint_antigo_sem_chave_retoma_sem_reconverter(self):
        self._checkpoint(None)
        chamadas = []

        async def converter(texto, voz, arquivo, *_a, **_k):
            chamadas.append(arquivo)
            gravar(arquivo, mp3_falso(3000))
            return True

        with mock.patch.object(core, "_assinatura_ajustes", lambda _v: "st=8;folga=0"):
            resultado = self._rodar(converter)
        self.assertTrue(resultado.get("ok"), resultado)
        self.assertEqual([], chamadas)
        self.assertEqual("st=8;folga=0", self.salvos[-1])

    def test_ponte_indisponivel_retoma_e_mantem_o_valor_salvo(self):
        self._checkpoint("st=8;folga=0")
        chamadas = []

        async def converter(texto, voz, arquivo, *_a, **_k):
            chamadas.append(arquivo)
            gravar(arquivo, mp3_falso(3000))
            return True

        with mock.patch.object(core, "_assinatura_ajustes", lambda _v: None):
            resultado = self._rodar(converter)
        self.assertTrue(resultado.get("ok"), resultado)
        self.assertEqual([], chamadas)
        self.assertTrue(self.salvos)
        self.assertTrue(all(valor == "st=8;folga=0" for valor in self.salvos))


if __name__ == "__main__":
    unittest.main()

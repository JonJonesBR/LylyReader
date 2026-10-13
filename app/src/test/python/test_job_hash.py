import os
import shutil
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

PYTHON_SRC = Path(__file__).parents[2] / "main" / "python"
sys.path.insert(0, str(PYTHON_SRC))

try:
    import core_processor_android as core
except ImportError:  # dependências de extração ausentes no ambiente de teste
    core = None


CHUNKS_BASE = ["Capítulo um. " + "x" * 600, "Fim do livro."]


@unittest.skipIf(core is None, "módulo core_processor_android indisponível")
class JobHashTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp()
        self.patch_temp = patch.object(core, "TEMP_DIR", self.tmp)
        self.patch_temp.start()

    def tearDown(self):
        self.patch_temp.stop()
        core._reset_cancelar()
        shutil.rmtree(self.tmp, ignore_errors=True)

    def _hash(self, chunks=None, velocidade="+0%", tom="+0Hz"):
        return core._job_hash(
            chunks if chunks is not None else list(CHUNKS_BASE),
            "pt-BR-Thalita", "padrao", None, velocidade, tom,
        )

    def test_hash_muda_com_velocidade(self):
        self.assertNotEqual(self._hash(velocidade="+0%"), self._hash(velocidade="+20%"))

    def test_hash_muda_com_tom(self):
        self.assertNotEqual(self._hash(tom="+0Hz"), self._hash(tom="+5Hz"))

    def test_hash_muda_quando_so_o_fim_dos_chunks_difere(self):
        # Os primeiros 500 caracteres são idênticos: o hash antigo (texto[:500]) colidiria.
        outro = list(CHUNKS_BASE)
        outro[-1] = "Outro final completamente diferente."
        self.assertNotEqual(self._hash(CHUNKS_BASE), self._hash(outro))

    def test_hash_igual_para_entradas_identicas(self):
        self.assertEqual(self._hash(), self._hash())

    def test_hash_tem_dezesseis_caracteres_hexadecimais(self):
        self.assertRegex(self._hash(), r"^[0-9a-f]{16}$")

    def test_descartar_job_recusa_entradas_invalidas(self):
        for invalido in ["../x", "", "zzzzzzzzzzzzzzzz", "0123456789abcdef0", "0123456789ABCDEF", None]:
            with self.subTest(jh=invalido):
                self.assertFalse(core.descartar_job(invalido))

    def test_descartar_job_recusa_caminho_mesmo_que_exista(self):
        # Um diretório fora de TEMP_DIR nunca pode ser apagado, mesmo com nome parecido com caminho.
        sentinela = os.path.join(self.tmp, "sentinela")
        os.makedirs(sentinela)
        self.assertFalse(core.descartar_job(sentinela))
        self.assertTrue(os.path.isdir(sentinela))

    def test_descartar_job_apaga_o_diretorio_do_job_valido(self):
        jh = self._hash()
        pasta = core._job_dir(jh)
        os.makedirs(pasta)
        with open(os.path.join(pasta, "part_0.mp3"), "wb") as f:
            f.write(b"x" * 600)
        self.assertTrue(core.descartar_job(jh))
        self.assertFalse(os.path.exists(pasta))

    def test_cancelar_e_descartar_liga_as_duas_flags_e_reset_desliga(self):
        core._reset_cancelar()
        core.cancelar_e_descartar()
        self.assertTrue(core._cancelar_flag)
        self.assertTrue(core._descartar_ao_cancelar)
        core._reset_cancelar()
        self.assertFalse(core._cancelar_flag)
        self.assertFalse(core._descartar_ao_cancelar)

    def test_cancelar_simples_nao_liga_o_descarte(self):
        core._reset_cancelar()
        core.cancelar()
        self.assertTrue(core._cancelar_flag)
        self.assertFalse(core._descartar_ao_cancelar)

    def test_retorno_cancelado_com_descarte_apaga_o_job(self):
        jh = self._hash()
        pasta = core._job_dir(jh)
        os.makedirs(pasta)
        core.cancelar_e_descartar()
        retorno = core._retorno_cancelado(jh)
        self.assertFalse(os.path.exists(pasta))
        self.assertEqual(jh, retorno["job"])
        self.assertFalse(retorno["ok"])

    def test_retorno_cancelado_sem_descarte_preserva_o_job(self):
        jh = self._hash()
        pasta = core._job_dir(jh)
        os.makedirs(pasta)
        core._reset_cancelar()
        core.cancelar()
        retorno = core._retorno_cancelado(jh)
        self.assertTrue(os.path.isdir(pasta))
        self.assertEqual(jh, retorno["job"])


DIA = 86400
AGORA = 1_800_000_000.0  # instante fixo para não depender do relógio do teste


@unittest.skipIf(core is None, "módulo core_processor_android indisponível")
class LimparJobsAntigosTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp()
        self.patch_temp = patch.object(core, "TEMP_DIR", self.tmp)
        self.patch_temp.start()

    def tearDown(self):
        self.patch_temp.stop()
        shutil.rmtree(self.tmp, ignore_errors=True)

    def _criar_job(self, nome: str, dias_atras: float, progress_dias_atras=None) -> str:
        pasta = os.path.join(self.tmp, nome)
        os.makedirs(pasta)
        with open(os.path.join(pasta, "part_0.mp3"), "wb") as f:
            f.write(b"x" * 600)
        t = AGORA - dias_atras * DIA
        os.utime(pasta, (t, t))
        if progress_dias_atras is not None:
            pf = os.path.join(pasta, "progress.json")
            with open(pf, "w") as f:
                f.write('{"completed": []}')
            tp = AGORA - progress_dias_atras * DIA
            os.utime(pf, (tp, tp))
        return pasta

    def test_apaga_job_velho(self):
        velho = self._criar_job("job_0123456789abcdef", dias_atras=40)
        self.assertEqual(1, core.limpar_jobs_antigos(agora=AGORA))
        self.assertFalse(os.path.exists(velho))

    def test_preserva_job_recente(self):
        recente = self._criar_job("job_0123456789abcdef", dias_atras=2)
        self.assertEqual(0, core.limpar_jobs_antigos(agora=AGORA))
        self.assertTrue(os.path.isdir(recente))

    def test_progress_recente_salva_job_com_diretorio_velho(self):
        # O diretório é antigo, mas o progress.json foi tocado há pouco: não é job abandonado.
        pasta = self._criar_job("job_0123456789abcdef", dias_atras=40, progress_dias_atras=1)
        self.assertEqual(0, core.limpar_jobs_antigos(agora=AGORA))
        self.assertTrue(os.path.isdir(pasta))

    def test_preserva_o_job_manter_mesmo_velho(self):
        jh = "0123456789abcdef"
        pasta = self._criar_job(f"job_{jh}", dias_atras=90)
        self.assertEqual(0, core.limpar_jobs_antigos(agora=AGORA, manter=jh))
        self.assertTrue(os.path.isdir(pasta))

    def test_ignora_nomes_fora_do_formato(self):
        nomes = [
            "job_ZZZZZZZZZZZZZZZZ",      # não é hexadecimal
            "job_0123456789abcdef0",     # 17 caracteres
            "job_0123456789ABCDEF",      # maiúsculas
            "job_0123456789abcde",       # 15 caracteres
            "outra_pasta",               # sem prefixo job_
            "job_0123456789abcdef_x",    # sufixo extra
        ]
        pastas = [self._criar_job(n, dias_atras=400) for n in nomes]
        self.assertEqual(0, core.limpar_jobs_antigos(agora=AGORA))
        for pasta in pastas:
            self.assertTrue(os.path.isdir(pasta), pasta)

    def test_ignora_arquivo_com_nome_de_job(self):
        arquivo = os.path.join(self.tmp, "job_0123456789abcdef")
        with open(arquivo, "w") as f:
            f.write("não é diretório")
        t = AGORA - 400 * DIA
        os.utime(arquivo, (t, t))
        self.assertEqual(0, core.limpar_jobs_antigos(agora=AGORA))
        self.assertTrue(os.path.isfile(arquivo))

    def test_nao_quebra_com_temp_dir_inexistente(self):
        with patch.object(core, "TEMP_DIR", os.path.join(self.tmp, "nao_existe")):
            self.assertEqual(0, core.limpar_jobs_antigos(agora=AGORA))

    def test_apaga_apenas_os_velhos_em_lote(self):
        velho_a = self._criar_job("job_aaaaaaaaaaaaaaaa", dias_atras=31)
        velho_b = self._criar_job("job_bbbbbbbbbbbbbbbb", dias_atras=100)
        recente = self._criar_job("job_cccccccccccccccc", dias_atras=29)
        self.assertEqual(2, core.limpar_jobs_antigos(agora=AGORA, manter="cccccccccccccccc"))
        self.assertFalse(os.path.exists(velho_a))
        self.assertFalse(os.path.exists(velho_b))
        self.assertTrue(os.path.isdir(recente))


if __name__ == "__main__":
    unittest.main()

import json
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
except ImportError:  # dependências de conversão ausentes no ambiente de teste
    cpa = None


def _gravar_audio(arquivo):
    with open(arquivo, "wb") as f:
        f.write(b"\xff\xfb" + b"\x00" * 2000)


@unittest.skipIf(cpa is None, "módulos de conversão indisponíveis")
class PrefixoContiguoTest(unittest.TestCase):
    def test_para_no_primeiro_bloco_que_falta(self):
        self.assertEqual([0, 1, 2], cpa._prefixo_contiguo({0, 1, 2, 4}, 6))

    def test_sem_nada_concluido_devolve_vazio(self):
        self.assertEqual([], cpa._prefixo_contiguo(set(), 5))
        self.assertEqual([], cpa._prefixo_contiguo({3, 4}, 5))

    def test_tudo_concluido_devolve_todos(self):
        self.assertEqual([0, 1, 2], cpa._prefixo_contiguo({0, 1, 2}, 3))


@unittest.skipIf(cpa is None, "módulos de conversão indisponíveis")
class LimitarPorDuracaoTest(unittest.TestCase):
    MIN = 60 * 1000

    def test_corta_antes_de_passar_do_limite(self):
        duracoes = {0: 30 * self.MIN, 1: 30 * self.MIN, 2: 30 * self.MIN}
        self.assertEqual([0, 1], cpa._limitar_por_duracao([0, 1, 2], duracoes, 60 * self.MIN))

    def test_primeiro_bloco_entra_mesmo_acima_do_limite(self):
        duracoes = {0: 90 * self.MIN}
        self.assertEqual([0], cpa._limitar_por_duracao([0, 1], duracoes, 60 * self.MIN))

    def test_bloco_sem_duracao_conta_zero(self):
        duracoes = {0: 59 * self.MIN}
        self.assertEqual([0, 1, 2], cpa._limitar_por_duracao([0, 1, 2], duracoes, 60 * self.MIN))

    def test_lista_vazia(self):
        self.assertEqual([], cpa._limitar_por_duracao([], {}, 60 * self.MIN))


@unittest.skipIf(cpa is None, "módulos de conversão indisponíveis")
class GerarPreviaTest(unittest.TestCase):
    def _job(self, tmp, total, concluidos, duracoes=None, criar=None):
        jh = "0123456789abcdef"
        pasta = os.path.join(tmp, f"job_{jh}")
        os.makedirs(pasta)
        for i in (criar if criar is not None else concluidos):
            _gravar_audio(os.path.join(pasta, f"part_{i}.mp3"))
        dados = {"completed": sorted(concluidos)}
        if duracoes:
            dados["duracoes_ms"] = {str(k): v for k, v in duracoes.items()}
        with open(os.path.join(pasta, "progress.json"), "w") as f:
            json.dump(dados, f)
        return jh

    def _gerar(self, tmp, jh, total):
        with mock.patch.object(cpa, "TEMP_DIR", tmp), \
                mock.patch.dict(cpa._job_ativo, {"jh": jh, "total": total}):
            return cpa.gerar_previa()

    def test_junta_o_prefixo_pronto_e_deixa_o_resto_de_fora(self):
        with tempfile.TemporaryDirectory() as tmp:
            jh = self._job(tmp, 5, {0, 1, 3}, duracoes={0: 1000, 1: 1000, 3: 1000})
            saida = self._gerar(tmp, jh, 5)
            self.assertEqual(os.path.join(tmp, "previa.mp3"), saida)
            self.assertGreater(os.path.getsize(saida), 0)
            self.assertFalse(os.path.exists(saida + ".tmp"))

    def test_sem_job_ativo_devolve_vazio(self):
        with mock.patch.dict(cpa._job_ativo, {"jh": None, "total": 0}):
            self.assertEqual("", cpa.gerar_previa())

    def test_sem_bloco_pronto_devolve_vazio(self):
        with tempfile.TemporaryDirectory() as tmp:
            jh = self._job(tmp, 3, set())
            self.assertEqual("", self._gerar(tmp, jh, 3))

    def test_bloco_que_falta_no_disco_nao_entra(self):
        # progress.json diz 0 e 1 concluídos, mas só o part_0 existe: a prévia leva só o bloco 0.
        with tempfile.TemporaryDirectory() as tmp:
            jh = self._job(tmp, 3, {0, 1}, criar={0})
            usados = []
            original = cpa._concatenar_mp3_puro

            def capturar(arquivos, saida):
                usados.extend(arquivos)
                return original(arquivos, saida)

            with mock.patch.object(cpa, "_concatenar_mp3_puro", capturar):
                saida = self._gerar(tmp, jh, 3)
            self.assertTrue(saida)
            self.assertEqual(1, len(usados))
            self.assertTrue(usados[0].endswith("part_0.mp3"))

    def test_progress_corrompido_devolve_vazio_sem_crash(self):
        with tempfile.TemporaryDirectory() as tmp:
            jh = self._job(tmp, 2, {0})
            with open(os.path.join(tmp, f"job_{jh}", "progress.json"), "w") as f:
                f.write("{ isto não é json")
            self.assertEqual("", self._gerar(tmp, jh, 2))

    def test_pasta_do_job_removida_devolve_vazio(self):
        with tempfile.TemporaryDirectory() as tmp:
            self.assertEqual("", self._gerar(tmp, "0123456789abcdef", 2))


if __name__ == "__main__":
    unittest.main()

import asyncio
import os
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import AsyncMock, patch

PYTHON_SRC = Path(__file__).parents[2] / "main" / "python"
sys.path.insert(0, str(PYTHON_SRC))
sys.path.insert(0, str(Path(__file__).parent))

import core_processor_android as core  # noqa: E402
import tts  # noqa: E402
from fabricas_audio import gravar, mp3_falso, wav_falso  # noqa: E402

JH = "0123456789abcdef"


def _tamanho_mp3(i: int) -> int:
    return 600 + (i * 137) % 2400


class JuncaoIncrementalTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.pasta = self.tmp.name
        self.job = os.path.join(self.pasta, f"job_{JH}")
        os.makedirs(self.job)
        patch.object(core, "TEMP_DIR", self.pasta).start()
        # Android: sem FFmpeg. A junção cai na concatenação binária pura, que é o que se compara aqui.
        patch("tts.executar_ffmpeg", new=AsyncMock(return_value=False)).start()
        core._reset_cancelar()

    def tearDown(self):
        patch.stopall()
        core._reset_cancelar()
        self.tmp.cleanup()

    def _blocos_mp3(self, n: int) -> list:
        return [
            gravar(os.path.join(self.job, f"part_{i}.mp3"),
                   mp3_falso(_tamanho_mp3(i), com_id3=(i % 7 == 0), semente=i))
            for i in range(n)
        ]

    def _blocos_wav(self, n: int) -> list:
        return [
            gravar(os.path.join(self.job, f"part_{i}.mp3"), wav_falso(400 + (i * 53) % 900, semente=i))
            for i in range(n)
        ]

    def _direta(self, blocos: list, pausas: list) -> bytes:
        saida = os.path.join(self.pasta, "direta.mp3")
        self.assertTrue(asyncio.run(tts.concatenar_grupo_com_pausa(blocos, saida, 600, pausas)))
        with open(saida, "rb") as f:
            return f.read()

    def _incremental(self, blocos: list, total_grupos_cheios: int, pausas: list) -> tuple:
        for g in range(total_grupos_cheios):
            idxs = range(g * 50, (g + 1) * 50)
            self.assertTrue(core._gerar_lote_sync(JH, g, [blocos[i] for i in idxs], [pausas[i] for i in idxs]))
        lista, pausas_lista = core._lista_para_juncao(len(blocos), list(range(len(blocos))), blocos, JH, pausas)
        saida = os.path.join(self.pasta, "incremental.mp3")
        self.assertTrue(asyncio.run(tts.concatenar_grupo_com_pausa(lista, saida, 600, pausas_lista)))
        with open(saida, "rb") as f:
            return f.read(), lista

    @staticmethod
    def _pausas(n: int) -> list:
        # Pausa padrão com trocas de voz curtas no meio, como no modo multi-personagem.
        return [160 if i % 9 == 4 else 600 for i in range(n)]

    def test_lotes_mp3_geram_os_mesmos_bytes_da_juncao_direta(self):
        blocos = self._blocos_mp3(120)
        pausas = self._pausas(120)
        direta = self._direta(blocos, pausas)
        incremental, lista = self._incremental(blocos, total_grupos_cheios=2, pausas=pausas)
        self.assertEqual([core._caminho_lote(JH, 0), core._caminho_lote(JH, 1)], lista[:2])
        self.assertEqual(22, len(lista))
        self.assertEqual(direta, incremental)

    def test_lotes_wav_geram_os_mesmos_bytes_da_juncao_direta(self):
        blocos = self._blocos_wav(60)
        pausas = self._pausas(60)
        direta = self._direta(blocos, pausas)
        incremental, lista = self._incremental(blocos, total_grupos_cheios=1, pausas=pausas)
        self.assertEqual(11, len(lista))
        self.assertEqual(direta, incremental)

    def test_lista_com_lotes_e_igual_a_concatenar_todos_os_blocos(self):
        blocos = self._blocos_mp3(100)
        for g in range(2):
            core._gerar_lote_sync(JH, g, blocos[g * 50:(g + 1) * 50])
        lista, _ = core._lista_para_juncao(100, list(range(100)), blocos, JH)
        self.assertEqual([core._caminho_lote(JH, 0), core._caminho_lote(JH, 1)], lista)

    def test_lote_feito_com_outras_pausas_nao_e_usado(self):
        blocos = self._blocos_mp3(50)
        self.assertTrue(core._gerar_lote_sync(JH, 0, blocos, [600] * 50))
        self.assertTrue(core._lote_valido(JH, 0, blocos, [600] * 50))
        self.assertFalse(core._lote_valido(JH, 0, blocos, [300] * 50))
        lista, pausas = core._lista_para_juncao(50, list(range(50)), blocos, JH, [300] * 50)
        self.assertEqual(blocos, lista)
        self.assertEqual([300] * 50, pausas)

    def test_formato_misto_nao_gera_lote(self):
        blocos = self._blocos_mp3(49) + self._blocos_wav(1)
        self.assertFalse(core._formato_homogeneo(blocos))
        self.assertFalse(core._gerar_lote_sync(JH, 0, blocos))
        self.assertFalse(os.path.exists(core._caminho_lote(JH, 0)))

    def test_bloco_que_muda_invalida_o_lote(self):
        blocos = self._blocos_mp3(50)
        self.assertTrue(core._gerar_lote_sync(JH, 0, blocos))
        self.assertTrue(core._lote_valido(JH, 0, blocos))
        gravar(blocos[5], mp3_falso(900, semente=9))
        self.assertFalse(core._lote_valido(JH, 0, blocos))
        lista, _ = core._lista_para_juncao(50, list(range(50)), blocos, JH)
        self.assertNotIn(core._caminho_lote(JH, 0), lista)
        self.assertEqual(blocos, lista)

    def test_lote_sem_manifesto_nao_e_usado(self):
        blocos = self._blocos_mp3(50)
        core._gerar_lote_sync(JH, 0, blocos)
        os.remove(core._caminho_manifesto_lote(JH, 0))
        self.assertFalse(core._lote_valido(JH, 0, blocos))

    def test_grupo_com_bloco_faltando_vai_bloco_a_bloco(self):
        blocos = self._blocos_mp3(50)
        core._gerar_lote_sync(JH, 0, blocos)
        validos = [i for i in range(50) if i != 3]
        lista, _ = core._lista_para_juncao(50, validos, blocos, JH)
        self.assertNotIn(core._caminho_lote(JH, 0), lista)
        self.assertEqual([blocos[i] for i in validos], lista)

    def test_limpar_lotes_apaga_so_os_lotes(self):
        blocos = self._blocos_mp3(50)
        core._gerar_lote_sync(JH, 0, blocos)
        core._limpar_lotes(JH)
        self.assertFalse(os.path.exists(core._caminho_lote(JH, 0)))
        self.assertFalse(os.path.exists(core._caminho_manifesto_lote(JH, 0)))
        self.assertTrue(all(os.path.exists(b) for b in blocos))

    def test_cancelamento_entre_lotes_levanta_juncao_cancelada_sem_gravar_saida(self):
        blocos = self._blocos_mp3(120)
        saida = os.path.join(self.pasta, "final.mp3")
        chamadas = [0]

        def cancelado() -> bool:
            chamadas[0] += 1
            return chamadas[0] > 2  # 1ª checagem (entrada) e 2ª (1º lote) passam; a 3ª (2º lote) cancela

        with self.assertRaises(tts.JuncaoCancelada):
            asyncio.run(tts.concatenar_grupo(blocos, saida, cancelado=cancelado))
        self.assertFalse(os.path.exists(saida))
        self.assertEqual(3, chamadas[0])
        self.assertFalse(any(nome.startswith("_lote") for nome in os.listdir(self.pasta)))

    def test_cancelamento_antes_da_juncao_nao_grava_saida(self):
        blocos = self._blocos_mp3(3)
        saida = os.path.join(self.pasta, "final.mp3")
        with self.assertRaises(tts.JuncaoCancelada):
            asyncio.run(tts.concatenar_grupo_com_pausa(blocos, saida, 600, None, cancelado=lambda: True))
        self.assertFalse(os.path.exists(saida))

    def test_cancelamento_preserva_blocos_e_checkpoint_e_limpa_lotes(self):
        blocos = self._blocos_mp3(50)
        core._gerar_lote_sync(JH, 0, blocos)
        core._salvar_progresso(JH, set(range(50)), {i: 100 for i in range(50)})
        core.cancelar()
        retorno = core._retorno_cancelado(JH)
        self.assertFalse(retorno["ok"])
        self.assertEqual(JH, retorno["job"])
        self.assertTrue(all(os.path.exists(b) for b in blocos))
        self.assertTrue(os.path.exists(os.path.join(self.job, "progress.json")))
        self.assertFalse(os.path.exists(core._caminho_lote(JH, 0)))
        self.assertFalse(os.path.exists(core._caminho_manifesto_lote(JH, 0)))


if __name__ == "__main__":
    unittest.main()

import asyncio
import os
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import AsyncMock, patch

PYTHON_SRC = Path(__file__).parents[2] / "main" / "python"
sys.path.insert(0, str(PYTHON_SRC))

from utils import (  # noqa: E402
    ProgressTracker, fracao_sintese, pct_sintese_global, pct_nao_regride,
)
from core_processor_android import _com_batimento  # noqa: E402
import tts  # noqa: E402

_SPINNERS = "⠋⠙⠹⠸⠼⠴⠦⠧⠇⠏"


class FracaoSintesePctTest(unittest.TestCase):
    def test_fracao_cresce_linear_ate_o_fim_da_sintese(self):
        self.assertEqual(0.0, fracao_sintese(0))
        self.assertEqual(0.5, fracao_sintese(45))
        self.assertEqual(1.0, fracao_sintese(90))

    def test_fracao_fica_entre_zero_e_um(self):
        self.assertEqual(1.0, fracao_sintese(120))
        self.assertEqual(0.0, fracao_sintese(-5))

    def test_pct_de_blocos_vira_faixa_global_da_sintese(self):
        self.assertEqual(0, pct_sintese_global(0))
        self.assertEqual(45, pct_sintese_global(50))
        self.assertEqual(89, pct_sintese_global(99))
        self.assertEqual(90, pct_sintese_global(100))

    def test_pct_nunca_regride(self):
        self.assertEqual(60, pct_nao_regride(50, 60))
        self.assertEqual(70, pct_nao_regride(70, 60))
        self.assertEqual(0, pct_nao_regride(0, -1))


class MensagemProgressoTest(unittest.TestCase):
    def test_mensagem_tem_tres_linhas_sem_barra_nem_spinner(self):
        with patch("utils.time.time", return_value=1.0):
            t = ProgressTracker(4, inicio=0.0)
            t.registrar(0, True)
            msg = t.mensagem_simples(45)
        linhas = msg.split("\n")
        self.assertEqual(3, len(linhas))
        self.assertEqual("Convertendo 45%", linhas[0])
        self.assertEqual("1/4 blocos", linhas[1])
        self.assertTrue(linhas[2].startswith("Tempo: "))
        self.assertNotIn("█", msg)
        self.assertNotIn("░", msg)
        self.assertFalse(any(c in msg for c in _SPINNERS))

    def test_falhas_aparecem_na_segunda_linha(self):
        with patch("utils.time.time", return_value=1.0):
            t = ProgressTracker(4, inicio=0.0)
            t.registrar(2, False)
            linhas = t.mensagem_simples(10).split("\n")
        self.assertEqual("0/4 blocos | 1 falhas", linhas[1])

    def test_sem_pct_explicito_usa_percentual_dos_blocos(self):
        with patch("utils.time.time", return_value=1.0):
            t = ProgressTracker(2, inicio=0.0)
            t.registrar(0, True)
            msg = t.mensagem_simples()
        self.assertTrue(msg.startswith("Convertendo 50%\n"))


class BatimentoTest(unittest.TestCase):
    def test_batimento_chama_cb_enquanto_a_corrotina_roda(self):
        chamadas = []

        async def _lenta():
            await asyncio.sleep(0.2)
            return "ok"

        resultado = asyncio.run(
            _com_batimento(_lenta(), lambda p, m: chamadas.append((p, m)), 90, "Juntando", intervalo=0.05)
        )
        self.assertEqual("ok", resultado)
        self.assertGreaterEqual(len(chamadas), 2)
        self.assertTrue(all(p == 90 for p, _ in chamadas))
        self.assertTrue(all(m.startswith("Juntando (") for _, m in chamadas))

    def test_batimento_para_ao_terminar(self):
        chamadas = []

        async def _principal():
            await _com_batimento(
                asyncio.sleep(0.12), lambda p, m: chamadas.append((p, m)), 90, "x", intervalo=0.03
            )
            n = len(chamadas)
            await asyncio.sleep(0.15)
            return n

        n_ao_terminar = asyncio.run(_principal())
        self.assertEqual(n_ao_terminar, len(chamadas))

    def test_batimento_para_quando_a_corrotina_falha(self):
        chamadas = []

        async def _falha():
            await asyncio.sleep(0.1)
            raise ValueError("falhou")

        async def _principal():
            with self.assertRaises(ValueError):
                await _com_batimento(_falha(), lambda p, m: chamadas.append((p, m)), 90, "x", intervalo=0.03)
            n = len(chamadas)
            await asyncio.sleep(0.12)
            return n

        n_ao_falhar = asyncio.run(_principal())
        self.assertEqual(n_ao_falhar, len(chamadas))

    def test_pct_dinamico_e_lido_a_cada_batimento(self):
        chamadas = []
        estado = {"pct": 10}

        async def _muda_pct():
            await asyncio.sleep(0.1)
            estado["pct"] = 20
            await asyncio.sleep(0.1)

        asyncio.run(_com_batimento(
            _muda_pct(), lambda p, m: chamadas.append((p, m)), lambda: estado["pct"], "x", intervalo=0.03,
        ))
        self.assertIn(10, [p for p, _ in chamadas])
        self.assertEqual(20, chamadas[-1][0])


class ConcatenarProgressoTest(unittest.TestCase):
    def test_lotes_reportam_ate_um_com_reserva_para_a_juncao_final(self):
        with tempfile.TemporaryDirectory() as pasta:
            arquivos = [os.path.join(pasta, f"p{i}.mp3") for i in range(120)]
            saida = os.path.join(pasta, "final.mp3")
            progresso = []
            with patch("tts._concatenar_lista", new=AsyncMock(return_value=True)) as concat:
                ok = asyncio.run(tts.concatenar_grupo(arquivos, saida, on_progress=progresso.append))
        self.assertTrue(ok)
        # 3 lotes (50, 50, 20) + a junção final dos lotes
        self.assertEqual(4, concat.await_count)
        self.assertEqual(4, len(progresso))
        self.assertEqual(sorted(progresso), progresso)
        for esperado, obtido in zip([0.3, 0.6, 0.9, 1.0], progresso):
            self.assertAlmostEqual(esperado, obtido)

    def test_lista_pequena_reporta_um_ao_fim(self):
        with tempfile.TemporaryDirectory() as pasta:
            arquivos = [os.path.join(pasta, f"p{i}.mp3") for i in range(3)]
            progresso = []
            with patch("tts._concatenar_lista", new=AsyncMock(return_value=True)):
                ok = asyncio.run(tts.concatenar_grupo(
                    arquivos, os.path.join(pasta, "final.mp3"), on_progress=progresso.append,
                ))
        self.assertTrue(ok)
        self.assertEqual([1.0], progresso)

    def test_concatenar_com_pausa_repassa_on_progress_sem_ffmpeg_de_silencio(self):
        # Sem ffmpeg para gerar o silêncio, a junção cai em concatenar_grupo com o mesmo on_progress.
        with tempfile.TemporaryDirectory() as pasta:
            arquivos = [os.path.join(pasta, f"p{i}.mp3") for i in range(2)]
            progresso = []
            with patch("tts.executar_ffmpeg", new=AsyncMock(return_value=False)), \
                    patch("tts._concatenar_lista", new=AsyncMock(return_value=True)):
                ok = asyncio.run(tts.concatenar_grupo_com_pausa(
                    arquivos, os.path.join(pasta, "final.mp3"), 600, None, on_progress=progresso.append,
                ))
        self.assertTrue(ok)
        self.assertEqual([1.0], progresso)


if __name__ == "__main__":
    unittest.main()

import asyncio
import sys
import time
import unittest
from pathlib import Path

PYTHON_SRC = Path(__file__).parents[2] / "main" / "python"
sys.path.insert(0, str(PYTHON_SRC))

try:
    from config_android import LimiteAdaptativo
except ImportError:  # dependências de configuração ausentes no ambiente de teste
    LimiteAdaptativo = None


@unittest.skipIf(LimiteAdaptativo is None, "módulo config_android indisponível")
class LimiteAdaptativoAjusteTest(unittest.TestCase):
    def test_comeca_no_inicial_limitado_ao_teto(self):
        self.assertEqual(8, LimiteAdaptativo(inicial=8, teto=10).limite)
        self.assertEqual(10, LimiteAdaptativo(inicial=30, teto=10).limite)

    def test_tres_falhas_na_janela_reduzem_pela_metade(self):
        lim = LimiteAdaptativo(inicial=8)
        for _ in range(3):
            lim.registrar(False)
        self.assertEqual(4, lim.limite)

    def test_duas_falhas_nao_reduzem(self):
        lim = LimiteAdaptativo(inicial=8)
        lim.registrar(False)
        lim.registrar(False)
        self.assertEqual(8, lim.limite)

    def test_nao_reduz_abaixo_do_minimo(self):
        lim = LimiteAdaptativo(inicial=8, minimo=2)
        for _ in range(30):
            lim.registrar(False)
        self.assertEqual(2, lim.limite)

    def test_falhas_fora_da_janela_nao_contam(self):
        lim = LimiteAdaptativo(inicial=8, janela=10)
        lim.registrar(False)
        lim.registrar(False)
        for _ in range(10):  # empurra as duas falhas para fora da janela de 10
            lim.registrar(True)
        lim.registrar(False)
        self.assertEqual(8, lim.limite)

    def test_vinte_sucessos_seguidos_sobem_um(self):
        lim = LimiteAdaptativo(inicial=4, teto=10)
        for _ in range(19):
            lim.registrar(True)
        self.assertEqual(4, lim.limite)
        lim.registrar(True)
        self.assertEqual(5, lim.limite)

    def test_falha_zera_a_contagem_de_sucessos(self):
        lim = LimiteAdaptativo(inicial=4, teto=10)
        for _ in range(19):
            lim.registrar(True)
        lim.registrar(False)
        for _ in range(19):
            lim.registrar(True)
        self.assertEqual(4, lim.limite)

    def test_nao_sobe_acima_do_teto(self):
        lim = LimiteAdaptativo(inicial=10, teto=10)
        for _ in range(60):
            lim.registrar(True)
        self.assertEqual(10, lim.limite)

    def test_nao_adaptativo_ignora_resultados(self):
        lim = LimiteAdaptativo(inicial=1, teto=1, adaptativo=False)
        for _ in range(5):
            lim.registrar(False)
        for _ in range(40):
            lim.registrar(True)
        self.assertEqual(1, lim.limite)


@unittest.skipIf(LimiteAdaptativo is None, "módulo config_android indisponível")
class LimiteAdaptativoConcorrenciaTest(unittest.TestCase):
    def test_desempenho_2000_blocos_com_limite_1_em_menos_de_1_segundo(self):
        async def rodar():
            lim = LimiteAdaptativo(inicial=1, teto=1, adaptativo=False)

            async def tarefa():
                async with lim:
                    await asyncio.sleep(0)

            inicio = time.perf_counter()
            await asyncio.gather(*[tarefa() for _ in range(2000)])
            return time.perf_counter() - inicio

        self.assertLess(asyncio.run(rodar()), 1.0)

    def test_aumento_de_limite_acorda_quem_espera(self):
        async def rodar():
            lim = LimiteAdaptativo(inicial=1, teto=3, adaptativo=False)
            await lim.__aenter__()  # A ocupa a única vaga
            entrou = asyncio.Event()

            async def espera():
                async with lim:
                    entrou.set()

            tarefa = asyncio.create_task(espera())
            await asyncio.sleep(0)
            lim.limite = 2  # aumento de limite (como AIMD faria após 20 sucessos)
            await lim.__aexit__(None, None, None)  # A libera: a vaga extra acorda o waiter
            await asyncio.wait_for(entrou.wait(), timeout=2)
            await tarefa
            return entrou.is_set()

        self.assertTrue(asyncio.run(rodar()))

    def test_cancelamento_de_quem_espera_nao_trava_os_demais(self):
        async def rodar():
            lim = LimiteAdaptativo(inicial=1, teto=1, adaptativo=False)
            await lim.__aenter__()  # A ocupa a vaga
            concluidos = []

            async def espera(nome):
                async with lim:
                    concluidos.append(nome)

            cancelado = asyncio.create_task(espera("B"))
            restante = asyncio.create_task(espera("C"))
            await asyncio.sleep(0)
            await asyncio.sleep(0)
            cancelado.cancel()
            await lim.__aexit__(None, None, None)
            await asyncio.wait_for(restante, timeout=2)
            with self.assertRaises(asyncio.CancelledError):
                await cancelado
            return concluidos

        self.assertEqual(["C"], asyncio.run(rodar()))

    def test_nunca_passa_do_limite_atual(self):
        async def rodar():
            lim = LimiteAdaptativo(inicial=2, teto=2, adaptativo=False)
            em_voo = 0
            pico = 0

            async def tarefa():
                nonlocal em_voo, pico
                async with lim:
                    em_voo += 1
                    pico = max(pico, em_voo)
                    await asyncio.sleep(0.01)
                    em_voo -= 1

            await asyncio.gather(*[tarefa() for _ in range(6)])
            return pico

        self.assertEqual(2, asyncio.run(rodar()))

    def test_reducao_no_meio_da_execucao_nao_trava_os_blocos(self):
        async def rodar():
            lim = LimiteAdaptativo(inicial=4, minimo=2, teto=10)
            concluidos = 0

            async def tarefa(i):
                nonlocal concluidos
                async with lim:
                    if i == 0:
                        for _ in range(3):
                            lim.registrar(False)  # limite cai de 4 para 2 no meio da execução
                    await asyncio.sleep(0.01)
                    concluidos += 1

            await asyncio.wait_for(asyncio.gather(*[tarefa(i) for i in range(8)]), timeout=5)
            return lim.limite, concluidos

        limite, concluidos = asyncio.run(rodar())
        self.assertEqual(2, limite)
        self.assertEqual(8, concluidos)


if __name__ == "__main__":
    unittest.main()

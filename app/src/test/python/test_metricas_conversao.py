import asyncio
import os
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

PYTHON_SRC = Path(__file__).parents[2] / "main" / "python"
sys.path.insert(0, str(PYTHON_SRC))

from metricas_conversao import MetricasConversao, _percentil  # noqa: E402

try:
    import core_processor_android as cpa
except ImportError:  # dependências de conversão ausentes no ambiente de teste
    cpa = None


class PercentilTest(unittest.TestCase):
    def test_lista_vazia_devolve_zero(self):
        self.assertEqual(0, _percentil([], 50))

    def test_posto_mais_proximo(self):
        valores = [10, 20, 30, 40]
        self.assertEqual(20, _percentil(valores, 50))  # ceil(0,5*4)=2 -> 20
        self.assertEqual(40, _percentil(valores, 95))  # ceil(3,8)=4 -> 40
        self.assertEqual(10, _percentil(valores, 1))

    def test_um_unico_valor(self):
        self.assertEqual(7, _percentil([7], 95))


class MetricasConversaoTest(unittest.TestCase):
    def test_resumo_sem_blocos_nao_quebra(self):
        m = MetricasConversao()
        r = m.resumo()
        self.assertEqual(0, r["tentativas"])
        self.assertEqual(0, r["p50_ms"])
        self.assertEqual(0, r["p95_ms"])
        self.assertEqual(0, r["max_ms"])
        self.assertEqual({}, r["etapas_ms"])

    def test_p50_p95_e_maximo_por_bloco(self):
        m = MetricasConversao()
        m.definir_contexto("edge", "pt-BR-Francisca", 4, 8)
        for idx, ms in enumerate([100, 200, 300, 1000]):
            m.registrar_bloco(idx, ms, True)
        r = m.resumo()
        self.assertEqual(200, r["p50_ms"])
        self.assertEqual(1000, r["p95_ms"])
        self.assertEqual(1000, r["max_ms"])
        self.assertEqual(4, r["blocos_total"])
        self.assertEqual(8, r["workers"])

    def test_retries_e_falhas_sao_contados(self):
        m = MetricasConversao()
        m.registrar_bloco(0, 50, False, tentativa=1)
        m.registrar_bloco(0, 60, True, tentativa=2)
        m.registrar_bloco(1, 70, False, tentativa=1)
        r = m.resumo()
        self.assertEqual(3, r["tentativas"])
        self.assertEqual(1, r["blocos_ok"])
        self.assertEqual(2, r["blocos_falhos"])
        self.assertEqual(1, r["retries"])

    def test_etapas_somam_e_arredondam(self):
        m = MetricasConversao()
        m.registrar_etapa("juncao", 1200.4)
        m.registrar_etapa("juncao", 300.0)
        m.registrar_etapa("id3", 50.6)
        r = m.resumo()
        self.assertEqual({"juncao": 1500, "id3": 51}, r["etapas_ms"])

    def test_tamanho_final_entra_no_resumo(self):
        m = MetricasConversao()
        m.definir_tamanho(2048)
        self.assertEqual(2048, m.resumo()["tamanho_bytes"])

    def test_como_texto_traz_motor_e_etapas(self):
        m = MetricasConversao()
        m.definir_contexto("kokoro", "kokoro-pf-dora", 3, 1)
        m.registrar_bloco(0, 900, True)
        m.registrar_etapa("sintese", 2500)
        texto = m.como_texto()
        self.assertIn("motor=kokoro", texto)
        self.assertIn("sintese=2500 ms", texto)
        self.assertIn("p50=900 ms", texto)

    def test_resumo_e_serializavel_com_tipos_simples(self):
        m = MetricasConversao()
        m.registrar_bloco(0, 10.5, True)
        m.registrar_etapa("ffprobe", 3.2)
        for valor in m.resumo().values():
            self.assertIsInstance(valor, (str, int, dict))


async def _converter_ok(texto, voz, arquivo, *_args, **_kwargs):
    with open(arquivo, "wb") as f:
        f.write(b"\xff\xfb" + b"\x00" * 2000)
    return True


@unittest.skipIf(cpa is None, "módulo core_processor_android indisponível")
class ProcessarDevolveMetricasTest(unittest.TestCase):
    def _rodar(self, tmp):
        with mock.patch.object(cpa, "TEMP_DIR", tmp), \
                mock.patch.object(cpa, "garantir_temp_dir", lambda: None), \
                mock.patch.object(cpa, "converter_tts_auto", _converter_ok), \
                mock.patch.object(cpa, "obter_capa", lambda *_a, **_k: None), \
                mock.patch.object(cpa._config, "adicionar_historico", lambda *_a, **_k: None), \
                mock.patch.dict(cpa._config.CONFIG, {"motor_tts": "edge"}):
            return asyncio.run(cpa.processar(
                "Texto curto de teste para a métrica.", "pt-BR-FranciscaNeural",
                saida_mp3=os.path.join(tmp, "saida.mp3"),
            ))

    def test_sucesso_traz_chave_metricas_e_loga(self):
        with tempfile.TemporaryDirectory() as tmp, self.assertLogs("audiobook", level="INFO") as logs:
            resultado = self._rodar(tmp)
        self.assertTrue(resultado["ok"])
        self.assertIn("caminho", resultado)
        metricas = resultado["metricas"]
        self.assertEqual(1, metricas["blocos_total"])
        self.assertEqual(1, metricas["blocos_ok"])
        self.assertEqual("edge", metricas["motor"])
        self.assertIn("sintese", metricas["etapas_ms"])
        self.assertTrue(any("[METRICAS]" in linha for linha in logs.output))

    def test_erro_tambem_loga_e_traz_metricas(self):
        async def chave_recusada(*_a, **_k):
            raise cpa.ErroDeChave("chave recusada (teste)")

        # ErroDeChave aborta sem retries nem esperas, então o teste não depende de asyncio.sleep.
        with tempfile.TemporaryDirectory() as tmp, \
                mock.patch.object(cpa, "TEMP_DIR", tmp), \
                mock.patch.object(cpa, "garantir_temp_dir", lambda: None), \
                mock.patch.object(cpa, "converter_tts_auto", chave_recusada), \
                mock.patch.dict(cpa._config.CONFIG, {"motor_tts": "edge"}), \
                self.assertLogs("audiobook", level="INFO") as logs:
            resultado = asyncio.run(cpa.processar(
                "Texto curto de teste para a métrica.", "pt-BR-FranciscaNeural",
                saida_mp3=os.path.join(tmp, "saida.mp3"),
            ))
        self.assertFalse(resultado["ok"])
        self.assertIn("metricas", resultado)
        self.assertEqual(1, resultado["metricas"]["blocos_falhos"])
        self.assertTrue(any("[METRICAS]" in linha for linha in logs.output))


if __name__ == "__main__":
    unittest.main()

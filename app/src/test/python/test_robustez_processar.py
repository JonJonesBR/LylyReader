import asyncio
import json
import os
import sys
import tempfile
import threading
import time
import unittest
from pathlib import Path
from unittest import mock

PYTHON_SRC = Path(__file__).parents[2] / "main" / "python"
sys.path.insert(0, str(PYTHON_SRC))
sys.path.insert(0, str(Path(__file__).parent))

try:
    import core_processor_android as cpa
    from fabricas_audio import mp3_falso
except ImportError:  # dependências de conversão ausentes no ambiente de teste
    cpa = None

_TEXTO_LONGO = "Frase de teste para a conversão por blocos. " * 1200  # ~50 mil caracteres


async def _converter_mp3(texto, voz, arquivo, *_a, **_k):
    with open(arquivo, "wb") as f:
        f.write(mp3_falso(3000))
    return True


def _rodar(tmp, texto, voz, motor, extra=None):
    """Roda processar com a pasta de trabalho temporária e o motor indicado."""
    patches = [
        mock.patch.object(cpa, "TEMP_DIR", tmp),
        mock.patch.object(cpa, "garantir_temp_dir", lambda: None),
        mock.patch.object(cpa, "obter_capa", lambda *_a, **_k: None),
        mock.patch.object(cpa._config, "adicionar_historico", lambda *_a, **_k: None),
        mock.patch.dict(cpa._config.CONFIG, {"motor_tts": motor}),
    ]
    for p in patches:
        p.start()
    try:
        return asyncio.run(cpa.processar(texto, voz, saida_mp3=os.path.join(tmp, "saida.mp3"),
                                         **(extra or {})))
    finally:
        for p in reversed(patches):
            p.stop()


@unittest.skipIf(cpa is None, "módulos de conversão indisponíveis")
class JuncaoIncrementalSerialTest(unittest.TestCase):
    def test_lotes_sao_gerados_um_por_vez(self):
        ativos = {"n": 0, "pico": 0, "lotes": 0}
        trava = threading.Lock()

        def gerar_lote(jh, g, arquivos, pausas=None):
            with trava:
                ativos["lotes"] += 1
                ativos["n"] += 1
                ativos["pico"] = max(ativos["pico"], ativos["n"])
            time.sleep(0.05)
            with trava:
                ativos["n"] -= 1
            return None

        async def concatenar(arquivos, saida, *_a, **_k):
            with open(saida, "wb") as f:
                f.write(mp3_falso(3000))
            return True

        with tempfile.TemporaryDirectory() as tmp, \
                mock.patch.object(cpa, "_juncao_incremental_ativa", lambda: True), \
                mock.patch.object(cpa, "_gerar_lote_sync", gerar_lote), \
                mock.patch.object(cpa, "_lista_para_juncao",
                                  lambda total, idxs, temp, jh, pausas=None: ([temp[i] for i in idxs], None)), \
                mock.patch.object(cpa, "concatenar_grupo_com_pausa", concatenar), \
                mock.patch.object(cpa, "converter_tts_auto", _converter_mp3):
            _rodar(tmp, _TEXTO_LONGO, "supertonic-m1", "onnx")
        self.assertGreaterEqual(ativos["lotes"], 2)
        self.assertEqual(1, ativos["pico"])


@unittest.skipIf(cpa is None, "módulos de conversão indisponíveis")
class ProcessarEsperaLotesTest(unittest.TestCase):
    def test_lote_em_voo_termina_antes_de_processar_sair_com_erro(self):
        marca = {"terminou": False}

        async def lote_lento():
            await asyncio.sleep(0.2)
            marca["terminou"] = True

        async def nucleo_falha(*_a, tarefas_lote=None, **_k):
            tarefas_lote.append(asyncio.create_task(lote_lento()))
            raise RuntimeError("falha simulada")

        with tempfile.TemporaryDirectory() as tmp, \
                mock.patch.object(cpa, "_processar_nucleo", nucleo_falha):
            with self.assertRaises(RuntimeError):
                asyncio.run(cpa.processar("texto", "supertonic-m1", saida_mp3=os.path.join(tmp, "s.mp3")))
        self.assertTrue(marca["terminou"])


@unittest.skipIf(cpa is None, "módulos de conversão indisponíveis")
class CancelamentoPosJuncaoTest(unittest.TestCase):
    def _cancela_na_juncao(self, tmp, saida, existia_antes):
        async def concatenar(arquivos, destino, *_a, **_k):
            with open(destino, "wb") as f:
                f.write(mp3_falso(3000))  # MP3 parcial, ainda sem ID3
            cpa.cancelar()
            return True

        if existia_antes:
            with open(saida, "wb") as f:
                f.write(b"mp3 de conversao anterior")
        with mock.patch.object(cpa, "concatenar_grupo_com_pausa", concatenar), \
                mock.patch.object(cpa, "converter_tts_auto", _converter_mp3):
            return _rodar(tmp, "Texto curto de teste para o cancelamento.", "supertonic-m1", "onnx",
                          extra={})

    def test_mp3_parcial_criado_pela_execucao_e_apagado(self):
        with tempfile.TemporaryDirectory() as tmp:
            saida = os.path.join(tmp, "saida.mp3")
            resultado = self._cancela_na_juncao(tmp, saida, existia_antes=False)
            self.assertFalse(resultado["ok"])
            self.assertFalse(os.path.exists(saida))

    def test_mp3_de_conversao_anterior_nao_e_apagado(self):
        with tempfile.TemporaryDirectory() as tmp:
            saida = os.path.join(tmp, "saida.mp3")
            resultado = self._cancela_na_juncao(tmp, saida, existia_antes=True)
            self.assertFalse(resultado["ok"])
            self.assertTrue(os.path.exists(saida))


@unittest.skipIf(cpa is None, "módulos de conversão indisponíveis")
class ProgressoAtomicoEAgrupadoTest(unittest.TestCase):
    def test_salvar_progresso_nao_deixa_temporario_e_grava_json(self):
        with tempfile.TemporaryDirectory() as tmp, mock.patch.object(cpa, "TEMP_DIR", tmp):
            jh = "0123456789abcdef"
            os.makedirs(cpa._job_dir(jh), exist_ok=True)
            cpa._salvar_progresso(jh, {2, 0, 1})
            pf = os.path.join(cpa._job_dir(jh), "progress.json")
            self.assertFalse(os.path.exists(pf + ".tmp"))
            with open(pf, encoding="utf-8") as f:
                self.assertEqual([0, 1, 2], json.load(f)["completed"])

    def test_gravacoes_sao_agrupadas_mas_a_final_e_completa(self):
        chamadas = []
        original = cpa._salvar_progresso

        def contar(jh, completed, duracoes=None):
            chamadas.append(len(completed))
            original(jh, completed, duracoes)

        with tempfile.TemporaryDirectory() as tmp, \
                mock.patch.object(cpa, "_salvar_progresso", contar), \
                mock.patch.object(cpa, "converter_tts_auto", _converter_mp3):
            resultado = _rodar(tmp, _TEXTO_LONGO, "supertonic-m1", "onnx")
        blocos = resultado["metricas"]["blocos_total"]
        self.assertGreater(blocos, 30)
        # Antes eram ~1 gravação por bloco; agora são agrupadas (mais forçadas no fim da síntese).
        self.assertLess(len(chamadas), blocos)
        self.assertEqual(blocos, chamadas[-1])


if __name__ == "__main__":
    unittest.main()

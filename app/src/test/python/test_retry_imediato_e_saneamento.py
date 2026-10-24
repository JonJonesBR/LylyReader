import asyncio
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
class RetryImediatoTest(unittest.TestCase):
    """P1.2: falha de um bloco é repetida logo, até 2 vezes, antes das rodadas globais."""

    def _rodar(self, tmp, converter):
        with mock.patch.object(cpa, "TEMP_DIR", tmp), \
                mock.patch.object(cpa, "garantir_temp_dir", lambda: None), \
                mock.patch.object(cpa, "converter_tts_auto", converter), \
                mock.patch.object(cpa, "obter_capa", lambda *_a, **_k: None), \
                mock.patch.object(cpa._config, "adicionar_historico", lambda *_a, **_k: None), \
                mock.patch.object(cpa, "_ESPERAS_RETRY_IMEDIATO", (0, 0)), \
                mock.patch.dict(cpa._config.CONFIG, {"motor_tts": "edge"}):
            return asyncio.run(cpa.processar(
                "Texto curto de teste para o retry.", "supertonic-m1",
                saida_mp3=os.path.join(tmp, "saida.mp3"),
            ))

    def test_bloco_que_falha_duas_vezes_e_recuperado_na_terceira(self):
        chamadas = []

        async def converter(texto, voz, arquivo, *_a, **_k):
            chamadas.append(texto)
            if len(chamadas) < 3:
                return False
            _gravar_audio(arquivo)
            return True

        with tempfile.TemporaryDirectory() as tmp:
            resultado = self._rodar(tmp, converter)
        self.assertTrue(resultado["ok"])
        self.assertEqual(3, len(chamadas))  # 1 tentativa + 2 retries imediatos
        self.assertEqual(3, resultado["metricas"]["tentativas"])
        self.assertEqual(2, resultado["metricas"]["retries"])

    def test_chave_recusada_nao_repete_o_bloco(self):
        chamadas = []

        async def converter(texto, voz, arquivo, *_a, **_k):
            chamadas.append(texto)
            raise cpa.ErroDeChave("chave recusada (teste)")

        with tempfile.TemporaryDirectory() as tmp:
            resultado = self._rodar(tmp, converter)
        self.assertFalse(resultado["ok"])
        self.assertEqual(1, len(chamadas))


@unittest.skipIf(cpa is None, "módulos de conversão indisponíveis")
class RetryImediatoPorMotorTest(unittest.TestCase):
    """Retry imediato só nos motores sem retry interno; Edge fica com o retry da própria tts.py."""

    def _chamadas(self, voz, motor, falhas_antes_de_ok):
        chamadas = []

        async def converter(texto, v, arquivo, *_a, **_k):
            chamadas.append(v)
            if len(chamadas) <= falhas_antes_de_ok:
                return False
            _gravar_audio(arquivo)
            return True

        with tempfile.TemporaryDirectory() as tmp, \
                mock.patch.object(cpa, "TEMP_DIR", tmp), \
                mock.patch.object(cpa, "garantir_temp_dir", lambda: None), \
                mock.patch.object(cpa, "converter_tts_auto", converter), \
                mock.patch.object(cpa, "obter_capa", lambda *_a, **_k: None), \
                mock.patch.object(cpa._config, "adicionar_historico", lambda *_a, **_k: None), \
                mock.patch.object(cpa, "_ESPERAS_RETRY_IMEDIATO", (0, 0)), \
                mock.patch.dict(cpa._config.CONFIG, {"motor_tts": motor}):
            resultado = asyncio.run(cpa.processar(
                "Texto curto de teste para o retry.", voz,
                saida_mp3=os.path.join(tmp, "saida.mp3"),
            ))
        return len(chamadas), resultado

    def test_motor_local_repete_imediatamente(self):
        # 2 falhas seguidas e sucesso na 3ª, dentro da mesma rodada (sem rodada global).
        chamadas, resultado = self._chamadas("supertonic-m1", "onnx", falhas_antes_de_ok=2)
        self.assertTrue(resultado["ok"])
        self.assertEqual(3, chamadas)

    def test_edge_nao_tem_retry_imediato_extra(self):
        # Primeira tentativa falha; sem retry imediato a próxima chamada vem na rodada global (2 s).
        chamadas, resultado = self._chamadas("pt-BR-FranciscaNeural", "edge", falhas_antes_de_ok=1)
        self.assertTrue(resultado["ok"])
        self.assertEqual(2, chamadas)


@unittest.skipIf(cpa is None, "módulos de conversão indisponíveis")
class SaneamentoPrevioTest(unittest.TestCase):
    """P1.3: bloco com URL/processo/etc. vai sanitizado já na 1ª tentativa."""

    def _enviados(self, texto):
        enviados = []

        async def converter(t, voz, arquivo, *_a, **_k):
            enviados.append(t)
            _gravar_audio(arquivo)
            return True

        with tempfile.TemporaryDirectory() as tmp, \
                mock.patch.object(cpa, "TEMP_DIR", tmp), \
                mock.patch.object(cpa, "garantir_temp_dir", lambda: None), \
                mock.patch.object(cpa, "converter_tts_auto", converter), \
                mock.patch.object(cpa, "obter_capa", lambda *_a, **_k: None), \
                mock.patch.object(cpa._config, "adicionar_historico", lambda *_a, **_k: None), \
                mock.patch.dict(cpa._config.CONFIG, {"motor_tts": "edge"}):
            asyncio.run(cpa.processar(texto, "pt-BR-FranciscaNeural",
                                      saida_mp3=os.path.join(tmp, "saida.mp3")))
        return enviados

    def test_bloco_com_url_vai_sanitizado_na_primeira_tentativa(self):
        enviados = self._enviados("Veja 🎉 https://exemplo.com/x agora.")
        self.assertEqual(1, len(enviados))
        self.assertNotIn("🎉", enviados[0])
        self.assertIn("https://exemplo.com/x", enviados[0])

    def test_bloco_comum_vai_sem_alteracao(self):
        enviados = self._enviados("Frase comum sem nada especial aqui.")
        self.assertEqual(["Frase comum sem nada especial aqui."], enviados)


if __name__ == "__main__":
    unittest.main()

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
    import audiobook_android as aa
    import audio_cache
    import config_android as cfg
    import core_processor_android as cpa
    import tts
    from mutagen.id3 import ID3
    from fabricas_audio import mp3_falso
except ImportError:  # dependências de conversão ausentes no ambiente local
    aa = None


_LIVRO_TXT = (
    "Capítulo 1 — O Começo\n\n"
    + ("Era uma vez um teste automatizado de conversão em audiobook. " * 45)
    + "\n\nCapítulo 2 — A Continuação\n\n"
    + ("Depois da divisão em blocos, a síntese em cache e a junção em MP3 fecham o ciclo. " * 35)
    + "\n"
)


@unittest.skipIf(aa is None, "módulos de conversão indisponíveis")
class ConversaoPontaAPontaTest(unittest.TestCase):
    def test_fluxo_completo_txt_com_edge_mock_e_cache(self):
        chamadas_edge = []

        class _CommunicateFalso:
            def __init__(self, texto, voz, rate="+0%", pitch="+0Hz", **_k):
                self.texto = texto
                self.voz = voz
                self.rate = rate
                self.pitch = pitch

            async def save(self, arquivo_saida):
                chamadas_edge.append((self.texto, self.voz, self.rate, self.pitch))
                with open(arquivo_saida, "wb") as f:
                    f.write(mp3_falso(3000, semente=len(chamadas_edge)))

        with tempfile.TemporaryDirectory() as tmp:
            files_dir = os.path.join(tmp, "files")
            cache_dir = os.path.join(tmp, "cache")
            os.makedirs(files_dir, exist_ok=True)
            os.makedirs(cache_dir, exist_ok=True)

            livro_path = os.path.join(tmp, "livro_teste.txt")
            saida_mp3 = os.path.join(tmp, "livro_teste.mp3")
            with open(livro_path, "w", encoding="utf-8") as f:
                f.write(_LIVRO_TXT)

            progressos = []

            def on_progress(pct, msg):
                progressos.append((pct, msg))

            estado_anterior = (
                tempfile.tempdir,
                cfg.FILES_DIR,
                cfg.CACHE_DIR,
                cfg.BASE_DIR,
                cfg.TEMP_DIR,
                cfg.CONFIG_FILE,
                cfg.HISTORICO_FILE,
                cfg.TEXTO_PRONTO,
                cfg.SEMAFORO_TTS,
                audio_cache.CACHE.DIR,
                cpa.TEMP_DIR,
            )
            try:
                aa.init(files_dir, cache_dir)
                # Garante motor auto/edge
                aa.set_motor("auto")

                with mock.patch.object(tts.edge_tts, "Communicate", _CommunicateFalso), \
                        mock.patch.object(cpa, "TEMP_DIR", cfg.TEMP_DIR), \
                        mock.patch.object(cpa, "obter_capa", lambda *_a, **_k: None):
                    res1 = aa.processar_arquivo_sync(
                        caminho_arquivo=livro_path,
                        voz="pt-BR-ThalitaMultilingualNeural",
                        velocidade="+0%",
                        tom="+0Hz",
                        estilo="padrao",
                        saida_mp3=saida_mp3,
                        progress_callback=on_progress,
                        titulo_etiqueta="Livro Teste",
                        autor_etiqueta="Autora Teste",
                        album_etiqueta="Coleção Teste",
                    )

                    self.assertTrue(res1.get("ok"), f"1ª conversão falhou: {res1}")
                    self.assertTrue(os.path.isfile(saida_mp3))
                    self.assertGreater(os.path.getsize(saida_mp3), 0)
                    self.assertGreaterEqual(len(res1.get("capitulos", [])), 2)

                    trechos = json.loads(res1.get("trechos_json", "[]"))
                    self.assertGreaterEqual(len(trechos), 1)
                    self.assertGreaterEqual(len(chamadas_edge), 1)
                    self.assertTrue(all(v == "pt-BR-ThalitaMultilingualNeural" for _, v, _, _ in chamadas_edge))
                    self.assertTrue(any(pct == 100 for pct, _ in progressos))

                    tags = ID3(saida_mp3)
                    self.assertEqual(["Livro Teste"], list(tags["TIT2"].text))
                    self.assertEqual(["Autora Teste"], list(tags["TPE1"].text))
                    self.assertEqual(["Coleção Teste"], list(tags["TALB"].text))
                    self.assertGreaterEqual(len(tags.getall("CHAP")), 2)

                    chamadas_apos_primeira = len(chamadas_edge)

                    # 2ª conversão do mesmo livro/voz: deve reutilizar o cache sem chamar o Edge TTS
                    os.remove(saida_mp3)
                    res2 = aa.processar_arquivo_sync(
                        caminho_arquivo=livro_path,
                        voz="pt-BR-ThalitaMultilingualNeural",
                        velocidade="+0%",
                        tom="+0Hz",
                        estilo="padrao",
                        saida_mp3=saida_mp3,
                        titulo_etiqueta="Livro Teste",
                        autor_etiqueta="Autora Teste",
                        album_etiqueta="Coleção Teste",
                    )

                    self.assertTrue(res2.get("ok"), f"2ª conversão falhou: {res2}")
                    self.assertTrue(os.path.isfile(saida_mp3))
                    self.assertEqual(
                        chamadas_apos_primeira,
                        len(chamadas_edge),
                        "2ª conversão deveria usar o cache sem novas chamadas ao Edge TTS",
                    )
            finally:
                (
                    tempfile.tempdir,
                    cfg.FILES_DIR,
                    cfg.CACHE_DIR,
                    cfg.BASE_DIR,
                    cfg.TEMP_DIR,
                    cfg.CONFIG_FILE,
                    cfg.HISTORICO_FILE,
                    cfg.TEXTO_PRONTO,
                    cfg.SEMAFORO_TTS,
                    audio_cache.CACHE.DIR,
                    cpa.TEMP_DIR,
                ) = estado_anterior

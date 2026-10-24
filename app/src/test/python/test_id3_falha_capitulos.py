import os
import sys
import tempfile
import unittest
from pathlib import Path

PYTHON_SRC = Path(__file__).parents[2] / "main" / "python"
sys.path.insert(0, str(PYTHON_SRC))
sys.path.insert(0, str(Path(__file__).parent))

try:
    import tts
    from mutagen.id3 import ID3
    from fabricas_audio import gravar, mp3_falso
except ImportError:  # dependências de áudio ausentes no ambiente de teste
    tts = None


@unittest.skipIf(tts is None, "módulo tts indisponível")
class FalhaEmCapitulosNaoPerdeMetadadosTest(unittest.TestCase):
    def test_capitulo_invalido_mantem_titulo_e_capa(self):
        capa = b"\xff\xd8" + b"c" * 2000  # JPEG falso, acima do mínimo de 1000 bytes
        # "titulo" ausente faz _aplicar_capitulos falhar (KeyError) depois de ter mexido nas tags.
        capitulos_ruins = [{"inicio_ms": 0, "fim_ms": 1000}]
        with tempfile.TemporaryDirectory() as tmp:
            caminho = gravar(os.path.join(tmp, "livro.mp3"), mp3_falso(20000))
            ok = tts.adicionar_metadados_e_capitulos_mp3(
                caminho, titulo="Meu Livro", capa_bytes=capa, capitulos=capitulos_ruins,
            )
            self.assertTrue(ok)
            tags = ID3(caminho)
            self.assertEqual("Meu Livro", str(tags["TIT2"].text[0]))
            self.assertIn("APIC:Cover", tags)
            self.assertFalse(any(k.startswith(("CHAP:", "CTOC:")) for k in tags.keys()))

    def test_capitulos_validos_continuam_gravados(self):
        capitulos = [
            {"titulo": "Um", "inicio_ms": 0, "fim_ms": 1000},
            {"titulo": "Dois", "inicio_ms": 1000, "fim_ms": 2000},
        ]
        with tempfile.TemporaryDirectory() as tmp:
            caminho = gravar(os.path.join(tmp, "livro.mp3"), mp3_falso(20000))
            self.assertTrue(tts.adicionar_metadados_e_capitulos_mp3(
                caminho, titulo="Meu Livro", capitulos=capitulos,
            ))
            tags = ID3(caminho)
            self.assertTrue(any(k.startswith("CHAP:") for k in tags.keys()))


if __name__ == "__main__":
    unittest.main()

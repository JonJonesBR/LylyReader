import importlib.util
import io
import os
import pathlib
import tarfile
import tempfile
import unittest

LOCAL_TEMP = pathlib.Path(__file__).parents[3] / "build" / "tmp" / "piper-archive-tests"
LOCAL_TEMP.mkdir(parents=True, exist_ok=True)

ROOT = pathlib.Path(__file__).parents[2] / "main" / "python" / "piper_archive.py"
SPEC = importlib.util.spec_from_file_location("piper_archive", ROOT)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)

class PiperTarExtractorTest(unittest.TestCase):
    def _archive(self, entries):
        temp = tempfile.TemporaryDirectory(dir=LOCAL_TEMP, ignore_cleanup_errors=True)
        archive_path = os.path.join(temp.name, "model.tar.bz2")
        with tarfile.open(archive_path, "w:bz2") as archive:
            for name, content, kind in entries:
                info = tarfile.TarInfo(name)
                if kind == "dir":
                    info.type = tarfile.DIRTYPE
                    archive.addfile(info)
                elif kind == "symlink":
                    info.type = tarfile.SYMTYPE
                    info.linkname = "../../outside"
                    archive.addfile(info)
                else:
                    info.size = len(content)
                    archive.addfile(info, io.BytesIO(content))
        return temp, archive_path

    def test_normaliza_modelo_tokens_e_espeak(self):
        temp, archive_path = self._archive([
            ("voice/pt_BR-cadu-medium.onnx", b"model", "file"),
            ("voice/tokens.txt", b"tokens", "file"),
            ("voice/espeak-ng-data/pt-BR", b"data", "file"),
        ])
        try:
            output = os.path.join(temp.name, "out")
            MODULE.extract_piper_tar_bz2(archive_path, output, "pt_BR-cadu-medium.onnx")
            self.assertEqual(b"model", pathlib.Path(output, "model.onnx").read_bytes())
            self.assertEqual(b"tokens", pathlib.Path(output, "tokens.txt").read_bytes())
            self.assertEqual(b"data", pathlib.Path(output, "espeak-ng-data", "pt-BR").read_bytes())
        finally:
            temp.cleanup()

    def test_rejeita_traversal(self):
        temp, archive_path = self._archive([("../escape", b"x", "file")])
        try:
            with self.assertRaises(ValueError):
                MODULE.extract_piper_tar_bz2(archive_path, os.path.join(temp.name, "out"), "model.onnx")
        finally:
            temp.cleanup()

    def test_rejeita_link_simbolico(self):
        temp, archive_path = self._archive([("voice/link", b"", "symlink")])
        try:
            with self.assertRaises(ValueError):
                MODULE.extract_piper_tar_bz2(archive_path, os.path.join(temp.name, "out"), "model.onnx")
        finally:
            temp.cleanup()

    def test_rejeita_bundle_incompleto(self):
        temp, archive_path = self._archive([("voice/tokens.txt", b"tokens", "file")])
        try:
            with self.assertRaises(ValueError):
                MODULE.extract_piper_tar_bz2(archive_path, os.path.join(temp.name, "out"), "model.onnx")
        finally:
            temp.cleanup()

if __name__ == "__main__":
    unittest.main()
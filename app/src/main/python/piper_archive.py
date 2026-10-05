import os
import tarfile

def extract_piper_tar_bz2(archive_path: str, output_dir: str, expected_model: str):
    """Safely normalize an official sherpa-onnx Piper archive for BYOM VITS."""
    os.makedirs(output_dir, exist_ok=True)
    total = 0
    count = 0
    found = {"model": False, "tokens": False, "espeak": False}
    with tarfile.open(archive_path, mode="r:bz2") as archive:
        for member in archive:
            name = member.name.replace("\\", "/").rstrip("/")
            parts = name.split("/")
            if name.startswith("/") or any(part in ("", ".", "..") for part in parts):
                raise ValueError("Entrada insegura no pacote Piper")
            if not (member.isfile() or member.isdir()):
                raise ValueError("Tipo de arquivo não permitido no pacote Piper")
            count += 1
            if count > 2048:
                raise ValueError("Pacote Piper contém entradas demais")
            total += member.size
            if total > 250 * 1024 * 1024:
                raise ValueError("Pacote Piper excede o limite de extração")
            basename = parts[-1]
            if member.isdir():
                if "espeak-ng-data" in parts:
                    os.makedirs(os.path.join(output_dir, "espeak-ng-data"), exist_ok=True)
                continue
            target = None
            if basename == expected_model and not found["model"]:
                target = os.path.join(output_dir, "model.onnx")
                found["model"] = True
            elif basename == "tokens.txt" and not found["tokens"]:
                target = os.path.join(output_dir, "tokens.txt")
                found["tokens"] = True
            elif "espeak-ng-data" in parts:
                relative = parts[parts.index("espeak-ng-data") + 1:]
                if not relative:
                    continue
                target = os.path.join(output_dir, "espeak-ng-data", *relative)
                found["espeak"] = True
            else:
                continue
            os.makedirs(os.path.dirname(target), exist_ok=True)
            source = archive.extractfile(member)
            if source is None:
                raise ValueError("Arquivo inválido no pacote Piper")
            with source, open(target, "wb") as destination:
                remaining = member.size
                while remaining:
                    chunk = source.read(min(65536, remaining))
                    if not chunk:
                        raise ValueError("Arquivo truncado no pacote Piper")
                    destination.write(chunk)
                    remaining -= len(chunk)
    if not all(found.values()):
        raise ValueError("Pacote Piper incompleto: model.onnx, tokens.txt ou espeak-ng-data ausente")

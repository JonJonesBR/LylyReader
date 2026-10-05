"""
audio_cache.py - Cache de chunks de áudio em disco.
Adaptado para Android/Chaquopy.

DIR não é criado no __init__ - é criado sob demanda em get/put.
config_android.init() reaponta CACHE.DIR para context.cacheDir após inicializar.
"""

import hashlib
import os
import time
from pathlib import Path
from typing import Optional


class AudioCache:
    # Placeholder - será substituído por config_android.init()
    # NÃO usa Path.home() nem /tmp pois o Android bloqueia ambos
    DIR = Path("/data/local/tmp/audio_chunks_placeholder")
    MIN_SIZE = 200

    def __init__(self):
        # Não cria o diretório aqui - espera o init() do config_android
        pass

    def _ensure_dir(self):
        """Garante que o diretório existe antes de qualquer operação."""
        try:
            self.DIR.mkdir(parents=True, exist_ok=True)
        except Exception:
            pass

    def _key(self, text: str, voice: str, engine: str) -> str:
        return hashlib.sha256(f"{engine}:{voice}:{text}".encode()).hexdigest()[:16]

    def get(self, text: str, voice: str, engine: str) -> Optional[bytes]:
        self._ensure_dir()
        p = self.DIR / f"{self._key(text, voice, engine)}.bin"
        if p.exists() and p.stat().st_size > self.MIN_SIZE:
            try:
                data = p.read_bytes()
                try:
                    os.utime(p, None)  # marca como usado agora (o teto remove os menos usados)
                except Exception:
                    pass
                return data
            except Exception:
                return None
        return None

    def put(self, text: str, voice: str, engine: str, data: bytes) -> bool:
        if not data or len(data) < self.MIN_SIZE:
            return False
        self._ensure_dir()
        try:
            (self.DIR / f"{self._key(text, voice, engine)}.bin").write_bytes(data)
            return True
        except Exception:
            return False

    def clear_old(self, days: int = 7) -> int:
        cutoff = time.time() - days * 86400
        removed = 0
        for f in self.DIR.glob("*.bin"):
            try:
                if f.stat().st_mtime < cutoff:
                    f.unlink()
                    removed += 1
            except Exception:
                pass
        return removed

    def clear_all(self) -> int:
        return self.clear_old(days=0)

    def info(self) -> dict:
        files = list(self.DIR.glob("*.bin")) if self.DIR.exists() else []
        total_size = sum(f.stat().st_size for f in files if f.exists())
        return {
            "arquivos": len(files),
            "tamanho_mb": round(total_size / (1024 * 1024), 2),
            "diretorio": str(self.DIR),
        }


CACHE = AudioCache()

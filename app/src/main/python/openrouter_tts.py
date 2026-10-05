"""
openrouter_tts.py - Cliente OpenRouter TTS com rotação de chaves e cache.

Usa a API /v1/audio/speech (compatível OpenAI) via OpenRouter.
Roteia entre múltiplos modelos e chaves automaticamente.

OpenRouter adiciona ~30ms de overhead sobre o provedor de inferência.
"""

from __future__ import annotations

import asyncio
import os
from pathlib import Path
from typing import List, Optional

import aiohttp

from audio_cache import CACHE
from config_android import logger


# Modelos TTS disponíveis no OpenRouter (mais usados)
OPENROUTER_TTS_MODELS: List[str] = [
    "openai/gpt-4o-mini-tts",
    "elevenlabs/eleven-turbo-v2",
    "openai/tts-1",
    "openai/tts-1-hd",
]

# Mapeamento modelo → vozes disponíveis
MODEL_VOICES = {
    "openai/gpt-4o-mini-tts": [
        "alloy", "ash", "ballad", "coral", "echo", "fable",
        "nova", "onyx", "sage", "shimmer", "verse", "marin", "cedar",
    ],
    "openai/tts-1": [
        "alloy", "echo", "fable", "nova", "onyx", "shimmer",
    ],
    "openai/tts-1-hd": [
        "alloy", "echo", "fable", "nova", "onyx", "shimmer",
    ],
    "elevenlabs/eleven-turbo-v2": [
        "Rachel", "Domi", "Bella", "Antoni", "Elli", "Josh",
        "Arnold", "Adam", "Sam",
    ],
}

# Vozes padrão por idioma (model-independent)
VOZES_PADRAO_PT = [
    {"id": "openrouter-coral", "name": "Coral", "model": "openai/gpt-4o-mini-tts", "voice": "coral", "language": "pt"},
    {"id": "openrouter-nova", "name": "Nova", "model": "openai/gpt-4o-mini-tts", "voice": "nova", "language": "pt"},
    {"id": "openrouter-sage", "name": "Sage", "model": "openai/gpt-4o-mini-tts", "voice": "sage", "language": "pt"},
    {"id": "openrouter-rachel", "name": "Rachel", "model": "elevenlabs/eleven-turbo-v2", "voice": "Rachel", "language": "pt"},
]

VOZES_PADRAO_EN = [
    {"id": "openrouter-alloy", "name": "Alloy", "model": "openai/gpt-4o-mini-tts", "voice": "alloy", "language": "en"},
    {"id": "openrouter-ash", "name": "Ash", "model": "openai/gpt-4o-mini-tts", "voice": "ash", "language": "en"},
    {"id": "openrouter-echo", "name": "Echo", "model": "openai/tts-1", "voice": "echo", "language": "en"},
    {"id": "openrouter-nova_en", "name": "Nova (EN)", "model": "openai/tts-1", "voice": "nova", "language": "en"},
    {"id": "openrouter-rachel_en", "name": "Rachel (EN)", "model": "elevenlabs/eleven-turbo-v2", "voice": "Rachel", "language": "en"},
]


class KeyManager:
    """Gerencia múltiplas chaves OpenRouter com rotação thread-safe."""

    def __init__(self, keys: List[str]):
        self._keys = [k.strip() for k in keys if k.strip()]
        self._idx = 0
        self._lock = asyncio.Lock()

    @property
    def has_keys(self) -> bool:
        return bool(self._keys)

    @property
    def count(self) -> int:
        return len(self._keys)

    async def current(self) -> Optional[str]:
        async with self._lock:
            return self._keys[self._idx] if self._keys else None

    async def rotate(self):
        async with self._lock:
            if not self._keys:
                return
            old = self._idx
            self._idx = (self._idx + 1) % len(self._keys)
            logger.warning(f"Chave OpenRouter rotacionada: #{old+1} -> #{self._idx+1}")


class OpenRouterTTSClient:
    """Cliente TTS via OpenRouter API (compatível OpenAI /v1/audio/speech)."""

    API_BASE = "https://openrouter.ai/api/v1"

    def __init__(
        self,
        km: KeyManager,
        model: str = "openai/gpt-4o-mini-tts",
        voice: str = "coral",
        speed: float = 1.0,
        http_referer: str = "",
        x_title: str = "LylyReader",
    ):
        self.km = km
        self.model = model
        self.voice = voice
        self.speed = max(0.5, min(2.0, speed))
        self.http_referer = http_referer
        self.x_title = x_title
        self._sess: Optional[aiohttp.ClientSession] = None

    async def _headers(self) -> dict:
        key = await self.km.current()
        if not key:
            return {}
        h = {
            "Authorization": f"Bearer {key}",
            "Content-Type": "application/json",
        }
        if self.http_referer:
            h["HTTP-Referer"] = self.http_referer
        if self.x_title:
            h["X-Title"] = self.x_title
        return h

    async def __aenter__(self):
        timeout = aiohttp.ClientTimeout(total=180)
        self._sess = aiohttp.ClientSession(timeout=timeout)
        return self

    async def __aexit__(self, *_):
        if self._sess:
            await self._sess.close()

    async def synth_chunk(self, text: str, out: str) -> bool:
        """Sintetiza texto para WAV via OpenRouter. Usa cache."""
        cached = CACHE.get(text, f"{self.model}/{self.voice}", "openrouter")
        if cached:
            try:
                Path(out).write_bytes(cached)
                return True
            except Exception:
                pass

        url = f"{self.API_BASE}/audio/speech"
        body = {
            "model": self.model,
            "input": text,
            "voice": self.voice,
            "response_format": "wav",
            "speed": self.speed,
        }

        for attempt in range(5):
            key = await self.km.current()
            if not key:
                return False

            headers = {
                "Authorization": f"Bearer {key}",
                "Content-Type": "application/json",
            }
            if self.http_referer:
                headers["HTTP-Referer"] = self.http_referer
            if self.x_title:
                headers["X-Title"] = self.x_title

            try:
                async with self._sess.post(url, headers=headers, json=body) as resp:
                    if resp.status == 200:
                        audio = await resp.read()
                        if len(audio) < 100:
                            logger.warning(f"[OpenRouter] Áudio muito curto ({len(audio)} bytes), tentativa {attempt+1}")
                            await asyncio.sleep(2)
                            continue
                        Path(out).write_bytes(audio)
                        CACHE.put(text, f"{self.model}/{self.voice}", "openrouter", audio)
                        return True

                    elif resp.status == 429:
                        logger.warning(f"[OpenRouter] Rate limit (429) na tentativa {attempt+1}, rotacionando chave...")
                        await self.km.rotate()
                        await asyncio.sleep(2 ** attempt * 5)
                    elif resp.status in (401, 403):
                        logger.error(f"[OpenRouter] Chave inválida ou proibida (HTTP {resp.status}) na tentativa {attempt+1}, rotacionando...")
                        await self.km.rotate()
                    elif resp.status == 400:
                        err = await resp.text()
                        logger.warning(f"[OpenRouter] HTTP 400 (Bad Request): {err[:300]}")
                        await asyncio.sleep(2)
                    elif resp.status >= 500:
                        err = await resp.text()
                        logger.error(f"[OpenRouter] Erro no servidor (HTTP {resp.status}) na tentativa {attempt+1}: {err[:200]}")
                        await asyncio.sleep(2 ** attempt)
                    else:
                        err = await resp.text()
                        logger.warning(f"[OpenRouter] HTTP {resp.status} inesperado na tentativa {attempt+1}: {err[:200]}")
                        await self.km.rotate()
                        await asyncio.sleep(2 ** attempt)

            except asyncio.TimeoutError:
                logger.error(f"[OpenRouter] Timeout na tentativa {attempt+1} (texto: {text[:50]}...)")
                await asyncio.sleep(2)
            except aiohttp.ClientConnectorError as e:
                logger.error(f"[OpenRouter] Erro de conexão ({e}) na tentativa {attempt+1}")
                await asyncio.sleep(2 ** attempt)
            except aiohttp.ClientError as e:
                logger.warning(f"[OpenRouter] Erro de rede ({type(e).__name__}: {e}), tentativa {attempt+1}")
                await asyncio.sleep(2 ** attempt)
            except asyncio.CancelledError:
                raise
            except Exception as e:
                logger.warning(f"[OpenRouter] {type(e).__name__}: {e}, tentativa {attempt+1}")
                await asyncio.sleep(2)

        return False

    async def process(
        self,
        chunks: List[str],
        temp_dir: str,
        progress_callback=None,
    ) -> tuple[List[str], int]:
        """Processa chunks sequencialmente.

        Returns:
            (lista de WAVs gerados com sucesso, no de falhas)
        """
        total = len(chunks)
        falhas = 0
        arquivos_ok: List[str] = []

        for i, chunk in enumerate(chunks):
            out = os.path.join(temp_dir, f"openrouter_chunk_{i:04d}.wav")
            ok = await self.synth_chunk(chunk, out)
            if ok:
                arquivos_ok.append(out)
            else:
                logger.warning(f"[OpenRouter] chunk {i+1}/{total} falhou")
                falhas += 1

            if progress_callback:
                try:
                    await progress_callback(i + 1, total)
                except Exception:
                    pass

        return arquivos_ok, falhas


def extrair_modelo_voz(voice_id: str) -> tuple:
    """Extrai (model, voice_name) de um ID de voz OpenRouter.

    Formato: "openrouter-<voice>" → modelo padrão (gpt-4o-mini-tts)
    Ou: "openrouter-<model>-<voice>" → modelo específico
    """
    if not voice_id.startswith("openrouter-"):
        return ("", "")

    parts = voice_id.split("-", 2)
    if len(parts) == 2:
        # openrouter-coral → modelo padrão
        return ("openai/gpt-4o-mini-tts", parts[1])
    # openrouter-elevenlabs-rachel
    model_key = parts[1]
    voice = parts[2]

    model_map = {
        "openai-tts": "openai/tts-1",
        "openai-tts-hd": "openai/tts-1-hd",
        "gpt4o-tts": "openai/gpt-4o-mini-tts",
        "elevenlabs": "elevenlabs/eleven-turbo-v2",
    }
    model = model_map.get(model_key, "openai/gpt-4o-mini-tts")
    return (model, voice)


def montar_catalogo_vozes() -> List[dict]:
    """Retorna lista de vozes OpenRouter para o catálogo do app."""
    return VOZES_PADRAO_PT + VOZES_PADRAO_EN

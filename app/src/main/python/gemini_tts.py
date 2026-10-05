"""
gemini_tts.py - Cliente Gemini TTS com rotação de chaves e cache.
Adaptado do Studio AI para o Bot Audiobook.

Funcionalidades:
  - KeyManager: rotação automática de chaves API com asyncio.Lock
  - GeminiTTSClient: síntese via Gemini API, converte PCM->WAV
  - Integração com AudioCache
  - Retry com backoff exponencial (até 10 tentativas)
"""

import asyncio
import base64
import os
import struct
import xml.sax.saxutils as saxutils
from pathlib import Path
from typing import List, Optional

import aiohttp

from audio_cache import CACHE
from config_android import logger


# =============================================================================
# GERENCIADOR DE CHAVES GEMINI
# =============================================================================

class KeyManager:
    def __init__(self, keys: List[str]):
        self._keys = [k.strip() for k in keys if k.strip()]
        self._idx  = 0
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
            logger.warning(f"Chave Gemini rotacionada: #{old+1} -> #{self._idx+1}")


# =============================================================================
# CLIENTE GEMINI TTS
# =============================================================================

class GeminiTTSClient:
    API = "https://generativelanguage.googleapis.com/v1beta/models"

    def __init__(
        self,
        km: KeyManager,
        models,
        speaking_rate: float = 0.95,
        enable_ssml: bool = True,
    ):
        self.km             = km
        # Aceita string (compat legada) ou lista de modelos para rotação
        self._models: List[str] = [models] if isinstance(models, str) else list(models)
        self.model          = self._models[0]  # propriedade legada
        self.speaking_rate  = max(0.5, min(2.0, speaking_rate))  # 0.5 a 2.0
        self.enable_ssml    = enable_ssml
        self._sess: Optional[aiohttp.ClientSession] = None

    async def __aenter__(self):
        timeout = aiohttp.ClientTimeout(total=120)
        self._sess = aiohttp.ClientSession(timeout=timeout)
        return self

    async def __aexit__(self, *_):
        if self._sess:
            await self._sess.close()

    @staticmethod
    def _pcm_to_wav(pcm: bytes) -> bytes:
        """Converte PCM bruto (24000Hz, mono, 16-bit) para WAV com header RIFF."""
        sr, ch, bps = 24000, 1, 16
        size = len(pcm)
        hdr  = bytearray()
        hdr += b"RIFF" + struct.pack("<I", 36 + size) + b"WAVE"
        hdr += b"fmt " + struct.pack("<IHHIIHH", 16, 1, ch, sr,
                                      sr * ch * bps // 8, ch * bps // 8, bps)
        hdr += b"data" + struct.pack("<I", size)
        return bytes(hdr) + pcm

    def _text_to_ssml(self, text: str) -> str:
        """
        Converte texto em SSML para melhor controle de pausas e ritmo.
        Substitui marcadores de pausa por tags SSML precisas.

        ⭐ Tempos aumentados para evitar aceleração do Gemini TTS:
        - Pausas longas: 1000ms (era 800ms)
        - Pausas médias: 700ms (era 500ms)
        - Reticências: 500ms (era 400ms)
        """
        # Escapar caracteres XML especiais
        text = saxutils.escape(text)

        # Pausas longas (parágrafos) -> 1000ms (aumentado de 800ms)
        text = text.replace("\\n\\n\\n", '<break time="1000ms"/>')
        text = text.replace("\n\n\n", '<break time="1000ms"/>')

        # Pausas médias (entre sentenças) -> 700ms (aumentado de 500ms)
        text = text.replace("\\n\\n", '<break time="700ms"/>')
        text = text.replace("\n\n", '<break time="700ms"/>')

        # Reticências -> 500ms (aumentado de 400ms)
        text = text.replace("...", '<break time="500ms"/>')
        text = text.replace("…", '<break time="500ms"/>')

        # Quebras de linha simples -> 300ms (aumentado de 200ms)
        text = text.replace("\\n", '<break time="300ms"/>')
        text = text.replace("\n", '<break time="300ms"/>')

        # Wrap em tags SSML com prosódia
        prosody = f'<prosody rate="{self.speaking_rate}">'
        return f"<speak>{prosody}{text}</prosody></speak>"

    async def synth_chunk(self, text: str, voice: str, out: str) -> bool:
        """
        Sintetiza um chunk de texto para WAV (PCM 24000Hz, mono, 16-bit).
        Usa cache - se já existir, não chama a API.
        Rotaciona modelos automaticamente em caso de erro de API.
        """
        # Verificar cache primeiro
        cached = CACHE.get(text, voice, "gemini")
        if cached:
            try:
                Path(out).write_bytes(cached)
                return True
            except Exception:
                pass

        # Converter para SSML se habilitado
        text_input = self._text_to_ssml(text) if self.enable_ssml else text

        model_idx = 0
        for attempt in range(10):
            model = self._models[model_idx % len(self._models)]
            key = await self.km.current()
            if not key:
                return False

            url = f"{self.API}/{model}:generateContent?key={key}"
            body = {
                "contents": [{"parts": [{"text": text_input}]}],
                "generationConfig": {
                    "responseModalities": ["AUDIO"],
                    "speechConfig": {
                        "voiceConfig": {
                            "prebuiltVoiceConfig": {"voiceName": voice}
                        }
                    },
                },
            }
            try:
                async with self._sess.post(url, json=body) as resp:
                    if resp.status == 200:
                        data = await resp.json()
                        parts = (
                            data.get("candidates", [{}])[0]
                                .get("content", {})
                                .get("parts", [])
                        )
                        b64 = (parts[0].get("inlineData", {}).get("data", "")) if parts else ""
                        if not b64:
                            logger.warning(f"Gemini: resposta sem áudio (tentativa {attempt+1})")
                            model_idx += 1
                            logger.warning(f"Tentando modelo: {self._models[model_idx % len(self._models)]}")
                            continue
                        pcm = base64.b64decode(b64)
                        wav = self._pcm_to_wav(pcm)
                        Path(out).write_bytes(wav)
                        CACHE.put(text, voice, "gemini", wav)
                        return True

                    elif resp.status == 429:
                        logger.warning(f"Rate limit Gemini (chave #{self.km._idx+1}), rotacionando chave e modelo...")
                        await self.km.rotate()
                        model_idx += 1
                        delay = min(60, 2 ** attempt)
                        await asyncio.sleep(delay)

                    elif resp.status in (400, 404):
                        body = await resp.text()
                        model_idx += 1
                        proximo = self._models[model_idx % len(self._models)]
                        logger.warning(f"HTTP {resp.status} no modelo '{model}', tentando '{proximo}'... Body: {body[:500]}")
                        await asyncio.sleep(2)

                    elif resp.status == 403:
                        logger.error("Gemini: chave inválida (403), rotacionando chave e modelo...")
                        await self.km.rotate()
                        model_idx += 1
                        await asyncio.sleep(2)

                    else:
                        logger.warning(f"Gemini HTTP {resp.status} (modelo '{model}'), rotacionando modelo...")
                        model_idx += 1
                        await asyncio.sleep(5)

            except aiohttp.ClientError as e:
                logger.warning(f"Gemini rede: {e}, aguardando 5s...")
                await asyncio.sleep(5)
            except Exception as e:
                logger.warning(f"Gemini tentativa {attempt+1}: {type(e).__name__}: {e}")
                await asyncio.sleep(2)

        return False

    async def process(
        self,
        chunks: List[str],
        voice: str,
        temp_dir: str,
        progress_callback=None,
    ) -> tuple[List[str], int]:
        """
        Processa todos os chunks sequencialmente.

        Args:
            chunks: lista de textos
            voice: ID da voz Gemini
            temp_dir: diretório para arquivos temporários
            progress_callback: async callable(concluidos, total) opcional

        Returns:
            (lista de arquivos gerados com sucesso, no de falhas)
        """
        total   = len(chunks)
        falhas  = 0
        arquivos_ok: List[str] = []

        for i, chunk in enumerate(chunks):
            out = os.path.join(temp_dir, f"gemini_chunk_{i:04d}.wav")

            ok = await self.synth_chunk(chunk, voice, out)
            if ok:
                arquivos_ok.append(out)
            else:
                logger.warning(f"Gemini chunk {i+1}/{total} falhou, re-tentando em 60s...")
                await asyncio.sleep(60)
                ok = await self.synth_chunk(chunk, voice, out)
                if ok:
                    arquivos_ok.append(out)
                else:
                    falhas += 1

            if progress_callback:
                try:
                    await progress_callback(i + 1, total)
                except Exception:
                    pass

        return arquivos_ok, falhas

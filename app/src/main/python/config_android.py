"""
config_android.py - Configurações adaptadas para Chaquopy/Android.

Diferenças em relação ao config.py original (VPS/Telegram):
  - Sem dotenv / .env / BOT_TOKEN / MEU_ID
  - Sem Telegram, sem asyncio.Semaphore global (inicializado sob demanda)
  - Sem logging para arquivo (usa apenas logcat via StreamHandler)
  - Paths resolvidos dinamicamente a partir de FILES_DIR passado pelo Kotlin
  - Chaves Gemini e configurações vêm de SharedPreferences via set_prefs()
  - SharedBotState mantido para compatibilidade com tts.py / core_processor_android.py
"""

import asyncio
import json
import logging
import os
import random
import tempfile
import time
from pathlib import Path
from typing import Optional

# ── Dirs dinâmicos - preenchidos pelo Kotlin antes de qualquer chamada ─────────
# Kotlin chama: config_android.init(files_dir, cache_dir)
FILES_DIR: str = ""    # context.filesDir.absolutePath
CACHE_DIR: str = ""    # context.cacheDir.absolutePath

def init(files_dir: str, cache_dir: str):
    """Chamado pelo Kotlin via Chaquopy logo na inicialização."""
    global FILES_DIR, CACHE_DIR, TEMP_DIR, CONFIG_FILE, HISTORICO_FILE, TEXTO_PRONTO, BASE_DIR
    global SEMAFORO_TTS
    FILES_DIR  = files_dir
    CACHE_DIR  = cache_dir
    BASE_DIR   = Path(files_dir)
    TEMP_DIR   = str(BASE_DIR / "temp")
    CONFIG_FILE    = str(BASE_DIR / "config.json")
    HISTORICO_FILE = str(BASE_DIR / "historico.json")
    TEXTO_PRONTO   = str(BASE_DIR / "texto_pronto.txt")
    os.makedirs(TEMP_DIR, exist_ok=True)
    # tempfile.gettempdir() por padrão cai em /tmp, que não existe no sandbox do
    # Android — aponta explicitamente para um dir gravável do app antes que
    # qualquer lib de terceiros (ex.: o pacote `mobi`, usado na extração de
    # .mobi) chame tempfile.mkdtemp() por conta própria.
    tempfile.tempdir = str(BASE_DIR / "py_tmp")
    os.makedirs(tempfile.tempdir, exist_ok=True)
    SEMAFORO_TTS = None  # Resetar para o novo event loop
    _reconfigure_cache()

# Valores default (substituídos após init())
BASE_DIR        = Path(".")
TEMP_DIR        = "temp"
CONFIG_FILE     = "config.json"
HISTORICO_FILE  = "historico.json"
TEXTO_PRONTO    = "texto_pronto.txt"

# ── Chaves Gemini - injetadas pelo Kotlin via set_gemini_keys() ───────────────
GEMINI_API_KEYS: list[str] = []
MODELOS_GEMINI = [
    "gemini-2.5-flash-preview-tts",
    "gemini-2.5-pro-preview-tts",
]
MODELO_GEMINI = MODELOS_GEMINI[0]  # compat: primeiro modelo da lista
MOTOR_TTS_PADRAO    = "edge"

# ── Chaves OpenRouter - injetadas pelo Kotlin via set_openrouter_keys() ───────
OPENROUTER_API_KEYS: list[str] = []

def set_gemini_keys(keys_csv: str):
    """Kotlin passa as chaves Gemini salvas no armazenamento local do app."""
    global GEMINI_API_KEYS
    GEMINI_API_KEYS = [k.strip() for k in keys_csv.split(",") if k.strip()]

def set_openrouter_keys(keys_csv: str):
    """Kotlin passa as chaves OpenRouter salvas no armazenamento local do app."""
    global OPENROUTER_API_KEYS
    OPENROUTER_API_KEYS = [k.strip() for k in keys_csv.split(",") if k.strip()]

def set_motor(motor: str):
    global MOTOR_TTS_PADRAO, SEMAFORO_TTS
    MOTOR_TTS_PADRAO = motor
    SEMAFORO_TTS = None

# ── Limites de processamento ──────────────────────────────────────────────────
# Edge TTS: sem limites oficiais. 8 workers + espaçamento menor (ver wait_for_tts_slot)
# acelera a geração; se aparecerem erros 429, reduzir TTS_WORKERS ou aumentar o min_interval.
TTS_WORKERS       = 8    # Edge TTS
CHUNK_LIMITE      = 2500 # chars por chunk (Edge/Gemini/OpenRouter — motores de rede)

# Supertonic (motor "onnx"), Pocket e vozes do sistema Android ("android") rodam localmente,
# 1 chunk por vez (get_tts_workers() já retorna 1 para eles). Cada chamada ao motor Kotlin
# (OnnxSynthBridge/AndroidSynthBridge) processa um chunk inteiro de forma síncrona e só depois
# devolve o controle para o Python — ou seja, o progresso da UI só avança quando um chunk
# INTEIRO termina. Com CHUNK_LIMITE=2500 (o mesmo do Edge), cada chunk podia levar minutos de
# inferência de CPU antes do primeiro retorno de progresso, dando a impressão de que nada
# estava acontecendo. Um chunk bem menor aqui não muda o tempo total de síntese (o motor
# Kotlin recorta internamente em pedaços ainda menores), mas faz a barra de progresso e o
# checkpoint de retomada avançarem em passos que o usuário realmente vê.
ONNX_CHUNK_LIMITE = 400  # chars por chunk (Supertonic/Android — motores locais)
POCKET_CHUNK_LIMITE = 400  # Pocket usa pacotes menores e serializa a inferência por memória.

# Gemini TTS free tier: 10 RPM, 250 RPD (por projeto).
# Com 1 worker sequencial e ~4s por req = ~15 req/min máx.
# Mantemos em 1 para ser conservador e não desperdiçar RPD.
GEMINI_TTS_WORKERS = 1

# OpenRouter TTS: depende do provedor (OpenAI ~50 req/min, ElevenLabs varia)
# Conservador: 2 workers, espaçamento de 1s entre requisições.
OPENROUTER_TTS_WORKERS = 2
OPENROUTER_CHUNK_LIMITE = 2500

# ElevenLabs (API oficial, chave própria do usuário): planos gratuitos/básicos têm poucos
# slots de requisição concorrente — mesmo conservadorismo do OpenRouter (que também
# encaminha pra ElevenLabs por baixo).
ELEVENLABS_TTS_WORKERS = 2
ELEVENLABS_CHUNK_LIMITE = 2500

# Chunk menor para Gemini: respostas mais rápidas, menos chance de timeout.
# O modelo suporta até 8.192 tokens de input (~32k chars), mas chunks menores
# evitam latencia excessiva e tornam o backoff exponencial mais seguro.
GEMINI_CHUNK_LIMITE = 1500  # ~375 palavras por chunk

def get_tts_workers() -> int:
    """Retorna o número certo de workers para o motor ativo."""
    motor = CONFIG.get("motor_tts", MOTOR_TTS_PADRAO)
    if motor == "gemini":
        return GEMINI_TTS_WORKERS
    elif motor == "openrouter":
        return OPENROUTER_TTS_WORKERS
    elif motor == "elevenlabs":
        return ELEVENLABS_TTS_WORKERS
    elif motor in ("onnx", "android", "kokoro", "pocket"):
        return 1
    return TTS_WORKERS

def get_chunk_limite() -> int:
    """Retorna o tamanho de chunk ideal para o motor ativo."""
    motor = CONFIG.get("motor_tts", MOTOR_TTS_PADRAO)
    if motor == "gemini":
        return GEMINI_CHUNK_LIMITE
    elif motor == "openrouter":
        return OPENROUTER_CHUNK_LIMITE
    elif motor == "elevenlabs":
        return ELEVENLABS_CHUNK_LIMITE
    elif motor in ("onnx", "android", "kokoro"):
        return ONNX_CHUNK_LIMITE
    elif motor == "pocket":
        return POCKET_CHUNK_LIMITE
    return CHUNK_LIMITE

TTS_TIMEOUT   = 90   # Gemini pode demorar mais que Edge
MAX_UPLOAD_MB = 0   # N/A no Android

# Limites legados (mantidos para não quebrar imports em tts.py)
TELEGRAM_FILE_LIMIT  = 0
TELEGRAM_SPLIT_LIMIT = 0
MAX_VIDEO_PART_SECONDS = 0
CONVERSION_TIMEOUT   = 3600

# Primitivas asyncio - criadas sob demanda E vinculadas ao loop atual.
# IMPORTANTE: asyncio.Semaphore/Lock ficam presos ao event loop em que são usados pela 1ª vez.
# Como cada conversão abre um loop novo (e podem haver execuções de worker sobrepostas/tasks
# pendentes que recriam a primitiva no loop antigo), guardamos o loop e RECRIAMOS a primitiva
# se o loop em execução mudou. Sem isso ocorria "Lock/Semaphore is bound to a different event
# loop" em livros grandes / 2ª conversão.
SEMAFORO_TTS: Optional[asyncio.Semaphore] = None
_SEM_LOOP = None
_RATE_LOCK: Optional[asyncio.Lock] = None
_RATE_LOCK_LOOP = None
_LAST_TTS_CALL: dict[str, float] = {"edge": 0.0, "gemini": 0.0, "openrouter": 0.0}

def _running_loop():
    try:
        return asyncio.get_running_loop()
    except RuntimeError:
        return None

def get_semaforo() -> asyncio.Semaphore:
    """Retorna (ou cria) o semáforo vinculado ao loop atual."""
    global SEMAFORO_TTS, _SEM_LOOP
    loop = _running_loop()
    if SEMAFORO_TTS is None or _SEM_LOOP is not loop:
        SEMAFORO_TTS = asyncio.Semaphore(get_tts_workers())
        _SEM_LOOP = loop
    return SEMAFORO_TTS

def _get_rate_lock() -> asyncio.Lock:
    global _RATE_LOCK, _RATE_LOCK_LOOP
    loop = _running_loop()
    if _RATE_LOCK is None or _RATE_LOCK_LOOP is not loop:
        _RATE_LOCK = asyncio.Lock()
        _RATE_LOCK_LOOP = loop
    return _RATE_LOCK

async def wait_for_tts_slot(motor: str):
    """Aplica espaçamento conservador entre requisições reais por motor."""
    min_interval = 6.5 if motor == "gemini" else (1.0 if motor == "openrouter" else 0.18)
    async with _get_rate_lock():
        now = time.monotonic()
        elapsed = now - _LAST_TTS_CALL.get(motor, 0.0)
        delay = min_interval - elapsed
        if delay > 0:
            await asyncio.sleep(delay + random.uniform(0, min_interval * 0.12))
        _LAST_TTS_CALL[motor] = time.monotonic()

# ── Controle de estado (cancel/ativa) - mantido para tts.py ──────────────────
class SharedBotState:
    def __init__(self):
        self._cancelar: dict[int, bool] = {}
        self._ativa:    dict[int, bool] = {}
        self._lock: Optional[asyncio.Lock] = None

    def _get_lock(self) -> asyncio.Lock:
        if self._lock is None:
            self._lock = asyncio.Lock()
        return self._lock

    async def get_cancelar(self, user_id: int = 0) -> bool:
        async with self._get_lock():
            return self._cancelar.get(user_id, False)

    async def set_cancelar(self, user_id: int = 0, status: bool = False):
        async with self._get_lock():
            self._cancelar[user_id] = status

    async def get_ativa(self, user_id: int = 0) -> bool:
        async with self._get_lock():
            return self._ativa.get(user_id, False)

    async def set_ativa(self, user_id: int = 0, status: bool = False):
        async with self._get_lock():
            self._ativa[user_id] = status

bot_state = SharedBotState()

# ── Logging - apenas StreamHandler (vai pro logcat) ──────────────────────────
logger = logging.getLogger("audiobook")
logger.setLevel(logging.DEBUG)
if not logger.handlers:
    _h = logging.StreamHandler()
    _h.setFormatter(logging.Formatter("%(levelname)s/LylyReader: %(message)s"))
    logger.addHandler(_h)

logging.getLogger("httpx").setLevel(logging.WARNING)
logging.getLogger("edge_tts").setLevel(logging.WARNING)

# ── AudioCache - diretório no cache do app ────────────────────────────────────
def _reconfigure_cache():
    """Reaponta o diretório do AudioCache para dentro do app."""
    try:
        from audio_cache import CACHE
        CACHE.DIR = Path(CACHE_DIR) / "audio_chunks"
        CACHE.DIR.mkdir(parents=True, exist_ok=True)
    except Exception:
        pass

# ── Catálogo de vozes (idêntico ao original) ─────────────────────────────────
VOZES_POR_CATEGORIA = {
    " Português (Brasil)": {
        " Thalita Multilingual (Feminina) ⭐": "pt-BR-ThalitaMultilingualNeural",
        " Antonio Neural (Masculina)":        "pt-BR-AntonioNeural",
        " Francisca Neural (Feminina)":       "pt-BR-FranciscaNeural",
    },
    " Português (Portugal)": {
        " Raquel Neural (Feminina) ⭐": "pt-PT-RaquelNeural",
        " Duarte Neural (Masculina)":  "pt-PT-DuarteNeural",
        " Fernanda Neural (Feminina)":        "pt-PT-FernandaNeural",
    },
    " Inglês (EUA)": {
        " Ava Multilingual (Feminina) ⭐":    "en-US-AvaMultilingualNeural",
        " Andrew Multilingual (Masculina) ⭐": "en-US-AndrewMultilingualNeural",
        " Emma Multilingual (Feminina) ⭐":   "en-US-EmmaMultilingualNeural",
        " Brian Multilingual (Masculina) ⭐": "en-US-BrianMultilingualNeural",
    },
    " Espanhol": {
        " Dalia Multilingual (Feminina)":  "es-MX-DaliaMultilingualNeural",
        " Jorge Multilingual (Masculina)": "es-MX-JorgeMultilingualNeural",
    },
    " Francês": {
        " Vivienne Multilingual (Feminina)": "fr-FR-VivienneMultilingualNeural",
        " Remy Multilingual (Masculina)":   "fr-FR-RemyMultilingualNeural",
    },
    " Alemão": {
        " Seraphina Multilingual (Feminina)": "de-DE-SeraphinaMultilingualNeural",
        " Florian Multilingual (Masculina)":  "de-DE-FlorianMultilingualNeural",
    },
    " Italiano": {
        " Giuseppe Multilingual (Masculino)": "it-IT-GiuseppeMultilingualNeural",
    },
    " Coreano": {
        " Hyunsu Multilingual (Masculino)": "ko-KR-HyunsuMultilingualNeural",
    },
    " Inglês (Austrália)": {
        " William Multilingual (Masculino)": "en-AU-WilliamMultilingualNeural",
    },
}

GEMINI_VOICES = {
    "⭐ Femininas - Destaque": {
        "Aoede (Conversacional) ⭐":    "Aoede",
        "Kore (Energética) ⭐":         "Kore",
        "Zephyr (Brilhante) ⭐":        "Zephyr",
        "Sulafat (Persuasiva) ⭐":      "Sulafat",
        "Despina (Acolhedora) ⭐":      "Despina",
        "Vindemiatrix (Serena) ⭐":     "Vindemiatrix",
    },
    " Femininas": {
        "Achird (Jovem)":           "Achird",
        "Algenib (Confiante)":      "Algenib",
        "Callirrhoe (Profissional)": "Callirrhoe",
        "Erinome (Articulada)":     "Erinome",
        "Laomedeia (Inquisitiva)":  "Laomedeia",
        "Leda (Composta)":          "Leda",
        "Pulcherrima (Animada)":    "Pulcherrima",
    },
    "⭐ Masculinas - Destaque": {
        "Enceladus (Entusiasta) ⭐":    "Enceladus",
        "Puck (Animado) ⭐":            "Puck",
        "Charon (Suave) ⭐":            "Charon",
        "Umbriel (Narrador) ⭐":        "Umbriel",
        "Sadachbia (Marcante) ⭐":      "Sadachbia",
    },
    " Masculinas": {
        "Achernar (Amigável)":      "Achernar",
        "Alnilam (Comercial)":      "Alnilam",
        "Autonoe (Maduro)":         "Autonoe",
        "Fenrir (Conversacional)":  "Fenrir",
        "Gacrux (Documentário)":    "Gacrux",
        "Iapetus (Casual)":         "Iapetus",
        "Orus (Profundo)":          "Orus",
        "Rasalgethi (Pensativo)":   "Rasalgethi",
        "Sadaltager (Apresentador)": "Sadaltager",
        "Schedar (Pé no chão)":     "Schedar",
        "Zubenelgenubi (Épico)":    "Zubenelgenubi",
    },
}

VOZES: dict[str, str] = {}
for _cat, _vozes in VOZES_POR_CATEGORIA.items():
    VOZES.update(_vozes)

VELOCIDADES = {
    " Muito Lenta (-50%)": "-50%",
    " Lenta (-25%)":       "-25%",
    " Normal (0%)":        "+0%",
    " Rápida (+25%)":      "+25%",
    " Muito Rápida (+50%)": "+50%",
}

TONS = {
    "⬇ Grave (-10Hz)":           "-10Hz",
    " Levemente Grave (-5Hz)":   "-5Hz",
    "➡ Normal (0Hz)":             "+0Hz",
    " Levemente Agudo (+5Hz)":   "+5Hz",
    "⬆ Agudo (+10Hz)":            "+10Hz",
}

ESTILOS_NARRACAO = {
    " Padrão (sem estilo)":   "padrao",
    " Terror":                "terror",
    "⚡ Ação":                  "acao",
    " Ficção Científica":     "scifi",
    " Drama":                 "drama",
    " Comédia":               "comedia",
}

PAUSAS_BLOCOS = {
    "⚡ Sem pausa (0ms)":       "0",
    " Leve (300ms)":          "300",
    " Natural (600ms) ⭐":   "600",
    " Audiobook (900ms)":     "900",
    " Dramático (1500ms)":    "1500",
}

IDIOMA_PATTERNS = {
    "pt": {
        "words": {"de","que","não","para","com","uma","ele","ela","mas","como","por","mais","também","muito","já","foi","bem","então","quando","depois","ainda","aqui","onde","agora","todos","pode","isso"},
        "voice": "pt-BR-ThalitaMultilingualNeural",
        "name":  " Thalita (Feminina) ⭐",
        "label": "Português",
    },
    "en": {
        "words": {"the","and","that","have","for","not","with","you","this","but","his","from","they","been","said","which","their","will","other","about","many","then","them","would","like","into"},
        "voice": "en-US-AvaMultilingualNeural",
        "name":  " Ava (Feminina)",
        "label": "Inglês",
    },
}

# ── Persistência de config (sem chaves sensíveis) ─────────────────────────────
_CHAVES_SENSIVEIS = {"gemini_api_keys", "bot_token", "meu_id"}

def carregar_config() -> dict:
    if os.path.exists(CONFIG_FILE):
        try:
            with open(CONFIG_FILE, "r", encoding="utf-8") as f:
                return json.load(f)
        except Exception:
            pass
    return {
        "velocidade": "+0%",
        "tom":        "+0Hz",
        "voz":        None,
        "nome_voz":   None,
        "estilo":     "padrao",
        "gemini_speaking_rate":       0.85,
        "gemini_enable_ssml":         True,
        "edge_pausa_entre_blocos_ms": 600,
    }

def salvar_config(config: dict):
    config_limpo = {k: v for k, v in config.items() if k not in _CHAVES_SENSIVEIS}
    try:
        with open(CONFIG_FILE, "w", encoding="utf-8") as f:
            json.dump(config_limpo, f, ensure_ascii=False, indent=2)
    except Exception as e:
        logger.error(f"Erro ao salvar config: {e}")

def adicionar_historico(titulo: str, formato: str, voz: str, duracao: str, tamanho_mb: int = 0):
    from datetime import datetime
    historico = _carregar_historico()
    historico.insert(0, {
        "titulo":      titulo,
        "formato":     formato.upper(),
        "voz":         voz,
        "duracao":     duracao,
        "tamanho_mb":  tamanho_mb,
        "data":        datetime.now().strftime("%d/%m/%Y %H:%M"),
    })
    historico = historico[:50]
    try:
        with open(HISTORICO_FILE, "w", encoding="utf-8") as f:
            json.dump(historico, f, ensure_ascii=False, indent=2)
    except Exception as e:
        logger.error(f"Erro ao salvar histórico: {e}")

def _carregar_historico() -> list:
    if os.path.exists(HISTORICO_FILE):
        try:
            with open(HISTORICO_FILE, "r", encoding="utf-8") as f:
                return json.load(f)
        except Exception:
            pass
    return []

# CONFIG global (carregado após init())
CONFIG: dict = {}

def reload_config():
    global CONFIG
    CONFIG = carregar_config()

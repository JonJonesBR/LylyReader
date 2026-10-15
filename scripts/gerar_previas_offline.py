"""
Gera os clipes de prévia MP3 das vozes offline (Kokoro, Piper e Pocket) usados pelo app LylyReader.

Saída (MP3 mono ~48 kbps) em %LOCALAPPDATA%\\lylyreader-previas\\saida\\:
  kokoro-preview-en-<id>.mp3     28 vozes Kokoro inglês (texto EN). Sids em KokoroTtsEngine.kt
                                 (KOKORO_ENGLISH_SIDS). id com "_" trocado por "-".
  piper-preview-<pacote>.mp3     9 pacotes Piper (texto no idioma do modelo: PT/EN/ES).
                                 Lista espelhada de PiperVitsModelManager.kt (models).
  mms-preview-main-pt.mp3        \\
  kokoro-preview-pf-dora.mp3      } baixados do repo HF (já publicados), sem síntese.
  kokoro-preview-pm-alex.mp3      /
  kokoro-preview-pm-santa.mp3    /
  pocket-preview-<rafael|alba|lola>.mp3   (ver função gerar_pocket)

Por que esta versão: a síntese usa os MESMOS motores do app (sherpa-onnx 1.13.8 via Python), com os
mesmos parâmetros de KokoroTtsEngine.kt / PiperVitsModelManager.kt (speed 1.0, sid por voz, noise 0.667,
noise_w 0.8, length 1.0, silence_scale 0.2, maxNumSentences 1). Textos de prévia = VoiceCatalog.kt
(PREVIEW_TEXT_PT / _EN / _ES).

Idempotente: pula o que já existe em saída (> 1 KB). Falha de uma voz é registrada e não aborta o resto.
Não envia nada para lugar nenhum (só baixa do HF e do GitHub de k2-fsa).

Uso:  python scripts\\gerar_previas_offline.py [--so kokoro,piper,hf,pocket]
"""

import argparse
import bz2  # noqa: F401  (tarfile precisa do módulo para modo r:bz2)
import glob
import json
import os
import shutil
import subprocess
import sys
import tarfile
import hashlib
import urllib.request
import zipfile

import numpy as np
import sherpa_onnx

sys.stdout.reconfigure(encoding="utf-8")

# ─── Caminhos ───────────────────────────────────────────────────────────────
RAIZ = os.path.join(os.environ["LOCALAPPDATA"], "lylyreader-previas")
SAIDA = os.path.join(RAIZ, "saida")
CACHE = os.path.join(RAIZ, "cache")
FFMPEG_WINGET = (
    r"C:\Users\Micro\AppData\Local\Microsoft\WinGet\Packages"
    r"\Gyan.FFmpeg.Essentials_Microsoft.Winget.Source_8wekyb3d8bbwe"
    r"\ffmpeg-8.1.1-essentials_build\bin\ffmpeg.exe"
)

HF_BASE = "https://huggingface.co/JonJonesBR/lylyreader-tts-models/resolve/main"
KOKORO_ZIP = "kokoro-multi-lang-v1_0.zip"
GH_PIPER = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models"

# ─── Textos de prévia (VoiceCatalog.kt) ─────────────────────────────────────
TEXTO = {
    "pt": "Olá! Esta é uma prévia da voz selecionada para a leitura do seu livro.",
    "en": "Hello! This is a preview of the selected voice for reading your book.",
    "es": "¡Hola! Esta es una vista previa de la voz seleccionada para leer tu libro.",
}

# ─── Kokoro inglês: id -> sid (KokoroTtsEngine.kt, KOKORO_ENGLISH_SIDS) ─────
KOKORO_EN = [
    "af_alloy", "af_aoede", "af_bella", "af_heart", "af_jessica", "af_kore", "af_nicole",
    "af_nova", "af_river", "af_sarah", "af_sky", "am_adam", "am_echo", "am_eric", "am_fenrir",
    "am_liam", "am_michael", "am_onyx", "am_puck", "am_santa", "bf_alice", "bf_emma",
    "bf_isabella", "bf_lily", "bm_daniel", "bm_fable", "bm_george", "bm_lewis",
]

# ─── Piper: espelho de PiperVitsModelManager.models ─────────────────────────
# (id do pacote, nome do arquivo .tar.bz2 sem extensão, nome do .onnx, idioma do texto)
PIPER = [
    ("piper-ptbr-cadu", "vits-piper-pt_BR-cadu-medium", "pt_BR-cadu-medium.onnx", "pt"),
    ("piper-ptbr-dii", "vits-piper-pt_BR-dii-high", "pt_BR-dii-high.onnx", "pt"),
    ("piper-ptbr-edresson", "vits-piper-pt_BR-edresson-low", "pt_BR-edresson-low.onnx", "pt"),
    ("piper-ptbr-faber", "vits-piper-pt_BR-faber-medium", "pt_BR-faber-medium.onnx", "pt"),
    ("piper-ptbr-jeff", "vits-piper-pt_BR-jeff-medium", "pt_BR-jeff-medium.onnx", "pt"),
    ("piper-ptbr-miro", "vits-piper-pt_BR-miro-high", "pt_BR-miro-high.onnx", "pt"),
    ("piper-enus-ljspeech-medium", "vits-piper-en_US-ljspeech-medium", "en_US-ljspeech-medium.onnx", "en"),
    ("piper-enus-norman-medium", "vits-piper-en_US-norman-medium", "en_US-norman-medium.onnx", "en"),
    ("piper-esmx-claude-high", "vits-piper-es_MX-claude-high", "es_MX-claude-high.onnx", "es"),
]

# ─── Clipes já publicados no HF (baixados, não sintetizados) ────────────────
HF_CLIPES = [
    "mms-preview-main-pt.mp3",
    "kokoro-preview-pf-dora.mp3",
    "kokoro-preview-pm-alex.mp3",
    "kokoro-preview-pm-santa.mp3",
]

TAMANHO_MIN_MP3 = 1024
SAMPLE_RATE_FALLBACK = 24000


def log(msg):
    print(msg, flush=True)


def caminho_ffmpeg():
    return shutil.which("ffmpeg") or (FFMPEG_WINGET if os.path.exists(FFMPEG_WINGET) else None)


def pronto(caminho):
    return os.path.exists(caminho) and os.path.getsize(caminho) > TAMANHO_MIN_MP3


def baixar(url, destino):
    """Baixa com .part + rename. Confere tamanho > 0."""
    os.makedirs(os.path.dirname(destino), exist_ok=True)
    parcial = destino + ".part"
    with urllib.request.urlopen(url, timeout=120) as resposta, open(parcial, "wb") as saida:
        shutil.copyfileobj(resposta, saida, 1024 * 1024)
    if os.path.getsize(parcial) == 0:
        os.remove(parcial)
        raise IOError(f"download vazio: {url}")
    os.replace(parcial, destino)
    return destino


def sha256_de(caminho):
    h = hashlib.sha256()
    with open(caminho, "rb") as f:
        for bloco in iter(lambda: f.read(1024 * 1024), b""):
            h.update(bloco)
    return h.hexdigest()


def wav_para_mp3(samples, sample_rate, destino, ffmpeg):
    """Escreve PCM float32 mono como WAV temporário e converte para MP3 48 kbps mono."""
    pcm = np.clip(np.asarray(samples, dtype=np.float32), -1.0, 1.0)
    pcm16 = (pcm * 32767.0).astype("<i2")
    tmp_wav = destino + ".tmp.wav"
    import wave

    with wave.open(tmp_wav, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(int(sample_rate))
        w.writeframes(pcm16.tobytes())
    try:
        subprocess.run(
            [ffmpeg, "-y", "-loglevel", "error", "-i", tmp_wav,
             "-ac", "1", "-codec:a", "libmp3lame", "-b:a", "48k", destino],
            check=True,
        )
    finally:
        os.remove(tmp_wav)


# ─── Kokoro ─────────────────────────────────────────────────────────────────

def preparar_kokoro_bundle():
    """Baixa o zip do bundle Kokoro, confere o sha256 e extrai. Devolve o diretório do bundle."""
    dir_bundle = os.path.join(CACHE, "kokoro", "bundle")
    marcador = os.path.join(dir_bundle, ".ready")
    if os.path.exists(marcador):
        return dir_bundle
    zip_path = os.path.join(CACHE, "kokoro", KOKORO_ZIP)
    sha_path = zip_path + ".sha256"
    if not pronto(zip_path) or os.path.getsize(zip_path) < 1024 * 1024:
        log("  baixando bundle Kokoro (zip)...")
        baixar(f"{HF_BASE}/{KOKORO_ZIP}", zip_path)
    if not os.path.exists(sha_path):
        baixar(f"{HF_BASE}/{KOKORO_ZIP}.sha256", sha_path)
    with open(sha_path, encoding="utf-8") as f:
        esperado = f.read().strip().split()[0].lower()
    real = sha256_de(zip_path)
    if real != esperado:
        os.remove(zip_path)
        raise IOError(f"sha256 do bundle Kokoro não confere (esperado {esperado}, obtido {real})")
    shutil.rmtree(dir_bundle, ignore_errors=True)
    os.makedirs(dir_bundle, exist_ok=True)
    with zipfile.ZipFile(zip_path) as z:
        for info in z.infolist():
            nome = info.filename.replace("\\", "/")
            if nome.startswith("/") or ".." in nome.split("/"):
                raise IOError(f"entrada insegura no zip: {nome}")
        z.extractall(dir_bundle)
    # O zip pode ter uma pasta raiz; acha o diretório que contém model.onnx.
    achados = glob.glob(os.path.join(dir_bundle, "**", "model.onnx"), recursive=True)
    if not achados:
        raise IOError("model.onnx não encontrado no bundle Kokoro")
    raiz = os.path.dirname(achados[0])
    if raiz != dir_bundle:
        for item in os.listdir(raiz):
            shutil.move(os.path.join(raiz, item), os.path.join(dir_bundle, item))
    open(marcador, "w").close()
    return dir_bundle


def criar_tts_kokoro(dir_bundle):
    lexicons = sorted(
        glob.glob(os.path.join(dir_bundle, "lexicon-*.txt")),
        key=lambda p: os.path.basename(p),
    )
    kokoro = sherpa_onnx.OfflineTtsKokoroModelConfig(
        model=os.path.join(dir_bundle, "model.onnx"),
        voices=os.path.join(dir_bundle, "voices.bin"),
        tokens=os.path.join(dir_bundle, "tokens.txt"),
        data_dir=os.path.join(dir_bundle, "espeak-ng-data"),
        lexicon=",".join(lexicons),
    )
    config = sherpa_onnx.OfflineTtsConfig(
        model=sherpa_onnx.OfflineTtsModelConfig(kokoro=kokoro, num_threads=4, debug=False),
        max_num_sentences=1,
        silence_scale=0.2,
    )
    return sherpa_onnx.OfflineTts(config)


def gerar_kokoro(ffmpeg):
    faltam = [v for v in KOKORO_EN if not pronto(os.path.join(SAIDA, f"kokoro-preview-en-{v.replace('_', '-')}.mp3"))]
    if not faltam:
        log("Kokoro EN: todos os clipes já existem.")
        return []
    dir_bundle = preparar_kokoro_bundle()
    tts = criar_tts_kokoro(dir_bundle)
    falhas = []
    for voz in faltam:
        nome = f"kokoro-preview-en-{voz.replace('_', '-')}.mp3"
        destino = os.path.join(SAIDA, nome)
        try:
            # sid = índice da voz no voices.bin (mesmo mapa de KOKORO_ENGLISH_SIDS)
            sid = KOKORO_EN.index(voz)
            audio = tts.generate(TEXTO["en"], sid=sid, speed=1.0)
            if len(audio.samples) == 0:
                raise RuntimeError("áudio vazio")
            wav_para_mp3(audio.samples, audio.sample_rate, destino, ffmpeg)
            log(f"  ok  {nome}")
        except Exception as e:  # noqa: BLE001 - reporta por voz e segue
            falhas.append((nome, str(e)))
            log(f"  FALHA {nome}: {e}")
    return falhas


# ─── Piper ──────────────────────────────────────────────────────────────────

def preparar_piper(pacote, arquivo_tar, nome_onnx):
    dir_pacote = os.path.join(CACHE, "piper", pacote)
    if os.path.exists(os.path.join(dir_pacote, ".ready")):
        return dir_pacote
    tar_path = os.path.join(CACHE, "piper", arquivo_tar + ".tar.bz2")
    if not os.path.exists(tar_path) or os.path.getsize(tar_path) < 1024 * 1024:
        log(f"  baixando {arquivo_tar}...")
        baixar(f"{GH_PIPER}/{arquivo_tar}.tar.bz2", tar_path)
    shutil.rmtree(dir_pacote, ignore_errors=True)
    os.makedirs(dir_pacote, exist_ok=True)
    with tarfile.open(tar_path, mode="r:bz2") as t:
        for m in t.getmembers():
            nome = m.name.replace("\\", "/")
            if nome.startswith("/") or ".." in nome.split("/"):
                raise IOError(f"entrada insegura no pacote Piper: {nome}")
        t.extractall(dir_pacote, filter="data")
    onnx = glob.glob(os.path.join(dir_pacote, "**", nome_onnx), recursive=True)
    tokens = glob.glob(os.path.join(dir_pacote, "**", "tokens.txt"), recursive=True)
    espeak = glob.glob(os.path.join(dir_pacote, "**", "espeak-ng-data"), recursive=True)
    if not (onnx and tokens and espeak):
        raise IOError(f"pacote {arquivo_tar} incompleto (onnx/tokens/espeak-ng-data)")
    info = {"onnx": onnx[0], "tokens": tokens[0], "espeak": espeak[0]}
    with open(os.path.join(dir_pacote, "info.json"), "w", encoding="utf-8") as f:
        json.dump(info, f)
    open(os.path.join(dir_pacote, ".ready"), "w").close()
    return dir_pacote


def gerar_piper(ffmpeg):
    falhas = []
    for pacote, arquivo_tar, nome_onnx, idioma in PIPER:
        nome = f"piper-preview-{pacote}.mp3"
        destino = os.path.join(SAIDA, nome)
        if pronto(destino):
            continue
        try:
            dir_pacote = preparar_piper(pacote, arquivo_tar, nome_onnx)
            with open(os.path.join(dir_pacote, "info.json"), encoding="utf-8") as f:
                info = json.load(f)
            vits = sherpa_onnx.OfflineTtsVitsModelConfig(
                model=info["onnx"],
                lexicon="",
                tokens=info["tokens"],
                data_dir=info["espeak"],
                dict_dir="",
                noise_scale=0.667,
                noise_scale_w=0.8,
                length_scale=1.0,
            )
            config = sherpa_onnx.OfflineTtsConfig(
                model=sherpa_onnx.OfflineTtsModelConfig(vits=vits, num_threads=4, debug=False),
                max_num_sentences=1,
                silence_scale=0.2,
            )
            tts = sherpa_onnx.OfflineTts(config)
            audio = tts.generate(TEXTO[idioma], sid=0, speed=1.0)
            if len(audio.samples) == 0:
                raise RuntimeError("áudio vazio")
            wav_para_mp3(audio.samples, audio.sample_rate, destino, ffmpeg)
            log(f"  ok  {nome}")
        except Exception as e:  # noqa: BLE001
            falhas.append((nome, str(e)))
            log(f"  FALHA {nome}: {e}")
    return falhas


# ─── HF (já publicados) ─────────────────────────────────────────────────────

def baixar_hf():
    falhas = []
    for nome in HF_CLIPES:
        destino = os.path.join(SAIDA, nome)
        if pronto(destino):
            continue
        try:
            baixar(f"{HF_BASE}/{nome}", destino)
            if os.path.getsize(destino) < 5 * 1024:
                raise IOError("arquivo menor que 5 KB")
            log(f"  ok  {nome} (HF)")
        except Exception as e:  # noqa: BLE001
            falhas.append((nome, str(e)))
            log(f"  FALHA {nome}: {e}")
    return falhas


# ─── Pocket (pendente: ver relatório) ───────────────────────────────────────

def gerar_pocket(ffmpeg):
    """Pocket não roda no sherpa-onnx. Implementação pendente (ver planejamento)."""
    return [(f"pocket-preview-{n}.mp3", "não implementado neste script") for n in ("rafael", "alba", "lola")]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--so", default="kokoro,piper,hf,pocket", help="etapas: kokoro,piper,hf,pocket")
    args = ap.parse_args()
    etapas = set(args.so.split(","))

    ffmpeg = caminho_ffmpeg()
    if not ffmpeg:
        log("ffmpeg não encontrado (PATH nem winget).")
        return 1
    os.makedirs(SAIDA, exist_ok=True)
    os.makedirs(CACHE, exist_ok=True)

    falhas = []
    if "kokoro" in etapas:
        log("Kokoro EN (28 vozes):")
        falhas += gerar_kokoro(ffmpeg)
    if "piper" in etapas:
        log("Piper (9 pacotes):")
        falhas += gerar_piper(ffmpeg)
    if "hf" in etapas:
        log("HF (clipes já publicados):")
        falhas += baixar_hf()
    if "pocket" in etapas:
        log("Pocket:")
        falhas += gerar_pocket(ffmpeg)

    log("")
    log(f"Saída: {SAIDA}")
    log(f"Falhas: {len(falhas)}")
    for nome, motivo in falhas:
        log(f"  - {nome}: {motivo}")
    return 0


if __name__ == "__main__":
    sys.exit(main())

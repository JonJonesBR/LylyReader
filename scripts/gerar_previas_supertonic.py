"""
Gera os clipes de prévia do Supertonic (V2) usados pelo app LylyReader.

Saída (14 arquivos MP3, mono, 48 kbps) em %LOCALAPPDATA%\\lylyreader-previas\\saida\\:
  supertonic-preview-{f1..f5,m1..m5}-pt.mp3   (estilos -pt, texto PT)
  supertonic-preview-{f1,m1}-es.mp3           (estilos multilíngues, texto ES)
  supertonic-preview-{f1,m1}-en.mp3           (estilos multilíngues, texto EN)
Observação: f1-pt/m1-pt do estilo multilíngue e o clipe PT da mesma voz são o mesmo arquivo
(mesmo F1.json/M1.json, mesmo texto e idioma "pt"), por isso o total é 14 e não 16.

Por que esta reimplementação: a síntese do app roda numa biblioteca nativa Rust
(`rust/src/` do repo: helper.rs + lang/), chamada via JNI por
`app/src/main/java/com/brahmadeo/supertonic/tts/SupertonicTTS.kt` a partir de
`OnnxTtsEngine.synthesizeSupertonicNative` com speed=1.0, steps=12 (padrão de AppPrefs),
gain=1.0 e silêncio entre chunks de 0,1 s. Este script porta esse pipeline Rust FIELMENTE
(normalização, chunking por bytes UTF-8, tokenização, duration predictor, vector estimator,
vocoder, truncamento pela duração). O ruído do flow-matching usa semente fixa (o app usa
thread_rng), então os clipes são reproduzíveis.

Uso:  python scripts\\gerar_previas_supertonic.py
(Só baixa do repo público JonJonesBR/lylyreader-tts-models; não envia nada.)
"""

import glob
import json
import math
import os
import re
import shutil
import subprocess
import sys
import unicodedata
import urllib.request
import wave

import numpy as np
import onnxruntime as ort

sys.stdout.reconfigure(encoding="utf-8")

# ─── Caminhos ───────────────────────────────────────────────────────────────
RAIZ = os.path.join(os.environ["LOCALAPPDATA"], "lylyreader-previas")
SAIDA = os.path.join(RAIZ, "saida")
TMP = os.path.join(RAIZ, "tmp")

HF_REPO = "JonJonesBR/lylyreader-tts-models"
def hf_base(versao):
    return f"https://huggingface.co/{HF_REPO}/resolve/main/supertonic-{versao}"


def hf_api(versao):
    return f"https://huggingface.co/api/models/{HF_REPO}/tree/main/supertonic-{versao}?recursive=true"


def cache_dir(versao):
    return os.path.join(RAIZ, "cache", f"supertonic-{versao}")

ARQUIVOS_ONNX = [
    "onnx/duration_predictor.onnx",
    "onnx/text_encoder.onnx",
    "onnx/vector_estimator.onnx",
    "onnx/vocoder.onnx",
    "onnx/tts.json",
    "onnx/unicode_indexer.json",
]
ESTILOS = ["M1", "M2", "M3", "M4", "M5", "F1", "F2", "F3", "F4", "F5"]
ARQUIVOS = ARQUIVOS_ONNX + [f"voice_styles/{e}.json" for e in ESTILOS]

# Textos de VoiceCatalog.kt (PREVIEW_TEXT_PT / _ES / _EN) — copiados literalmente.
TEXTO_PREVIA = {
    "pt": "Olá! Esta é uma prévia da voz selecionada para a leitura do seu livro.",
    "es": "¡Hola! Esta es una vista previa de la voz seleccionada para leer tu libro.",
    "en": "Hello! This is a preview of the selected voice for reading your book.",
}

# Parâmetros padrão do app (AppPrefs: RITMO_DEFAULT=100, SUPERTONIC_STEPS_PADRAO=12).
SPEED = 1.0
STEPS = 12
GAIN = 1.0
SILENCIO_ENTRE_CHUNKS = 0.1
SEMENTE = 20261013

# Clipes a gerar: (arquivo, estilo, idioma, texto)
CLIPES = []
for _e in ["f1", "f2", "f3", "f4", "f5", "m1", "m2", "m3", "m4", "m5"]:
    CLIPES.append((f"supertonic-preview-{_e}-pt.mp3", _e.upper(), "pt", TEXTO_PREVIA["pt"]))
for _e in ["f1", "m1"]:
    for _lang in ["es", "en"]:
        CLIPES.append((f"supertonic-preview-{_e}-{_lang}.mp3", _e.upper(), _lang, TEXTO_PREVIA[_lang]))


# ─── Download ───────────────────────────────────────────────────────────────
def tamanhos_remotos(versao):
    req = urllib.request.Request(hf_api(versao), headers={"User-Agent": "lylyreader-previas"})
    with urllib.request.urlopen(req, timeout=60) as r:
        lista = json.load(r)
    return {x["path"].removeprefix(f"supertonic-{versao}/"): x.get("size", 0) for x in lista if "size" in x}


def baixar(versao, caminho_rel, tamanho_esperado):
    destino = os.path.join(cache_dir(versao), *caminho_rel.split("/"))
    os.makedirs(os.path.dirname(destino), exist_ok=True)
    if os.path.exists(destino) and os.path.getsize(destino) == tamanho_esperado:
        return destino
    url = f"{hf_base(versao)}/{caminho_rel}"
    parcial = destino + ".part"
    print(f"  baixando {caminho_rel} ({tamanho_esperado / 1e6:.1f} MB)...")
    req = urllib.request.Request(url, headers={"User-Agent": "lylyreader-previas"})
    with urllib.request.urlopen(req, timeout=120) as r, open(parcial, "wb") as f:
        shutil.copyfileobj(r, f, 1 << 20)
    if os.path.getsize(parcial) != tamanho_esperado:
        raise RuntimeError(f"tamanho divergente em {caminho_rel}: {os.path.getsize(parcial)} != {tamanho_esperado}")
    os.replace(parcial, destino)
    return destino


def garantir_modelos(versao):
    print("Verificando modelos supertonic-" + versao + " (cache em " + cache_dir(versao) + ")")
    tam = tamanhos_remotos(versao)
    for arq in ARQUIVOS:
        if arq not in tam:
            raise RuntimeError(f"arquivo ausente no repo HF: supertonic-{versao}/{arq}")
        baixar(versao, arq, tam[arq])


# ─── Pré-processamento (portado de rust/src/lang/*.rs e rust/src/helper.rs) ──
def blen(s):
    """Comprimento em bytes UTF-8 (o Rust usa str.len(), que é em bytes)."""
    return len(s.encode("utf-8"))


EMOJI_RE = re.compile(
    "[\U0001F600-\U0001F64F\U0001F300-\U0001F5FF\U0001F680-\U0001F6FF\U0001F700-\U0001F77F"
    "\U0001F780-\U0001F7FF\U0001F800-\U0001F8FF\U0001F900-\U0001F9FF\U0001FA00-\U0001FA6F"
    "\U0001FA70-\U0001FAFF☀-⛿✀-➿\U0001F1E6-\U0001F1FF]+"
)
SUBSTITUICOES_COMUNS = [
    ("–", "-"), ("‑", "-"), ("—", ", "), ("_", " "),
    ("“", '"'), ("”", '"'), ("‘", "'"), ("’", "'"),
    ("´", "'"), ("`", "'"), ("[", " "), ("]", " "), ("|", " "), ("/", " "),
    ("#", " "), ("→", " "), ("←", " "),
]
SIMBOLOS_REMOVIDOS = ["♥", "☆", "♡", "©", "\\"]
ESPACOS_PONTUACAO = [(" , ", ","), (" . ", "."), (" ! ", "!"), (" ? ", "?"), (" ; ", ";"), (" : ", ":"), (" ' ", "'")]
ESPACO_RE = re.compile(r"\s+")
ABREV_EN = [
    "Dr.", "Mr.", "Mrs.", "Ms.", "Prof.", "Sr.", "Jr.",
    "St.", "Ave.", "Rd.", "Blvd.", "Dept.", "Inc.", "Ltd.",
    "Co.", "Corp.", "etc.", "vs.", "i.e.", "e.g.", "Ph.D.",
]
# configs/en.json: BTreeMap → iteração em ordem de chave.
SUBSTITUICOES_EN = {"@": " at ", "e.g.,": "for example, ", "i.e.,": "that is, "}

ABERTURA_PUNC = "({,:-¿¡「『【〈《‹«"
FECHAMENTO_PUNC = ")}.,!?:;-…。」』】〉》›»"
FIM_PUNCT_CHARS = set(".!?;:,'\"“”‘’)\\]}…。」』】〉》›»।॥")
FRASE_RE = re.compile(r"([.!?]['’”\"\)\}\]]?)\s+")


def _classifica_aspas(chars, i):
    if not chars or i >= len(chars):
        return "amb"
    antes = chars[i - 1] if i > 0 else None
    depois = chars[i + 1] if i + 1 < len(chars) else None
    antes_ws = antes is None or antes.isspace()
    depois_ws = depois is None or depois.isspace()
    antes_abre = antes is not None and antes in ABERTURA_PUNC
    depois_fecha = depois is not None and depois in FECHAMENTO_PUNC
    abre = (antes_ws or antes_abre) and not depois_ws
    fecha = (depois_ws or depois_fecha) and not antes_ws
    if abre and not fecha:
        return "abre"
    if fecha and not abre:
        return "fecha"
    return "amb"


def _remove_aspas_nao_pareadas(texto):
    chars = list(texto)
    remover = set()

    def processa(indices):
        pilha = []
        for idx in indices:
            tipo = _classifica_aspas(chars, idx)
            if tipo == "abre":
                pilha.append(idx)
            elif tipo == "fecha":
                if not pilha:
                    remover.add(idx)
                else:
                    pilha.pop()
            else:
                if not pilha:
                    pilha.append(idx)
                else:
                    pilha.pop()
        for idx in pilha:
            remover.add(idx)

    duplas = [i for i, c in enumerate(chars) if c == '"']
    if len(duplas) % 2 != 0:
        processa(duplas)

    simples = []
    for i, c in enumerate(chars):
        if c == "'":
            interna = 0 < i < len(chars) - 1 and chars[i - 1].isalnum() and chars[i + 1].isalnum()
            if not interna:
                simples.append(i)
    if len(simples) % 2 != 0:
        processa(simples)

    return "".join(c for i, c in enumerate(chars) if i not in remover)


def _pre_processamento_comum(texto):
    texto = unicodedata.normalize("NFKD", texto)
    texto = EMOJI_RE.sub("", texto)
    for de, para in SUBSTITUICOES_COMUNS:
        texto = texto.replace(de, para)
    for s in SIMBOLOS_REMOVIDOS:
        texto = texto.replace(s, "")
    for de, para in ESPACOS_PONTUACAO:
        texto = texto.replace(de, para)
    while '""' in texto:
        texto = texto.replace('""', '"')
    while "''" in texto:
        texto = texto.replace("''", "'")
    while "``" in texto:
        texto = texto.replace("``", "`")
    texto = ESPACO_RE.sub(" ", texto).strip()
    return _remove_aspas_nao_pareadas(texto)


def _fecha_com_pontuacao(texto):
    if texto and texto[-1] not in FIM_PUNCT_CHARS:
        texto += "."
    return texto


def _divide_frases(texto, abreviacoes):
    casamentos = list(FRASE_RE.finditer(texto))
    if not casamentos:
        return [texto]
    frases = []
    ultimo = 0
    for m in casamentos:
        antes = texto[ultimo:m.start()]
        punc = m.group(0)[0]
        abreviacao = any((antes.strip() + punc).endswith(a) for a in abreviacoes)
        if not abreviacao:
            frases.append(texto[ultimo:m.end()])
            ultimo = m.end()
    if ultimo < len(texto):
        frases.append(texto[ultimo:])
    return frases or [texto]


# Por idioma: (pré-processador, divisor de frases, comprimento máximo do chunk, envolve em <lang>)
def _pre_en(texto):
    for de in sorted(SUBSTITUICOES_EN):
        texto = texto.replace(de, SUBSTITUICOES_EN[de])
    return _fecha_com_pontuacao(_pre_processamento_comum(texto))


def _pre_padrao(texto):
    return _fecha_com_pontuacao(_pre_processamento_comum(texto))


NORMALIZADORES = {
    "en": dict(pre=_pre_en, frases=lambda t: _divide_frases(t, ABREV_EN), max_len=300, envolve=False),
    "pt": dict(pre=_pre_padrao, frases=lambda t: _divide_frases(t, []), max_len=300, envolve=True),
    "es": dict(pre=_pre_padrao, frases=lambda t: _divide_frases(t, []), max_len=300, envolve=True),
}


def preprocessa_texto(texto, lang):
    norm = NORMALIZADORES[lang]
    out = norm["pre"](texto)
    if norm["envolve"]:
        out = f"<{lang}>{out}</{lang}>"
    return out


def divide_chunks(texto, lang, max_len=None):
    """Porta de helper.rs::chunk_text (comprimentos em bytes UTF-8)."""
    norm = NORMALIZADORES[lang]
    max_len = max_len or norm["max_len"]
    texto = texto.strip()
    if not texto:
        return [""]

    paragrafos = []
    atual = ""
    for bruto in re.split(r"\n\s*\n", texto):
        p = bruto.strip()
        if not p:
            continue
        if atual:
            if blen(atual) + blen(p) + 2 > max_len or blen(p) >= 80:
                paragrafos.append(atual)
                atual = ""
            else:
                if not (atual.endswith(".") or atual.endswith("!") or atual.endswith("?") or atual.endswith("।")):
                    atual += " ."
                atual += " " + p
                continue
        if blen(p) < 80:
            atual += p
        else:
            paragrafos.append(p)
    if atual:
        paragrafos.append(atual)

    chunks = []
    for para in paragrafos:
        para = para.strip()
        if not para:
            continue
        if blen(para) <= max_len:
            chunks.append(para)
            continue

        cur = ""
        cur_len = 0
        for sent in norm["frases"](para):
            sent = sent.strip()
            if not sent:
                continue
            s_len = blen(sent)
            if s_len > max_len:
                if cur:
                    chunks.append(cur.strip())
                    cur, cur_len = "", 0
                for parte in sent.split(","):
                    parte = parte.strip()
                    if not parte:
                        continue
                    p_len = blen(parte)
                    if p_len > max_len:
                        palavra_chunk = ""
                        pl_len = 0
                        for palavra in parte.split():
                            w_len = blen(palavra)
                            if pl_len + w_len + 1 > max_len and palavra_chunk:
                                chunks.append(palavra_chunk.strip())
                                palavra_chunk, pl_len = "", 0
                            if palavra_chunk:
                                palavra_chunk += " "
                                pl_len += 1
                            palavra_chunk += palavra
                            pl_len += w_len
                        if palavra_chunk:
                            chunks.append(palavra_chunk.strip())
                    else:
                        if cur_len + p_len + 1 > max_len and cur:
                            chunks.append(cur.strip())
                            cur, cur_len = "", 0
                        if cur:
                            cur += ", "
                            cur_len += 2
                        cur += parte
                        cur_len += p_len
                continue

            if cur_len + s_len + 1 > max_len and cur:
                chunks.append(cur.strip())
                cur, cur_len = "", 0
            if cur:
                cur += " "
                cur_len += 1
            cur += sent
            cur_len += s_len
        if cur:
            chunks.append(cur.strip())

    chunks = [c for c in chunks if any(ch.isalnum() for ch in c)]
    return chunks if chunks else [""]


# ─── Inferência (porta de helper.rs::TextToSpeech) ──────────────────────────
class Supertonic:
    def __init__(self, onnx_dir):
        cfg = json.load(open(os.path.join(onnx_dir, "tts.json"), encoding="utf-8"))
        self.sr = int(cfg["ae"]["sample_rate"])
        self.base_chunk = int(cfg["ae"]["base_chunk_size"])
        self.chunk_compress = int(cfg["ttl"]["chunk_compress_factor"])
        self.latent_dim = int(cfg["ttl"]["latent_dim"])
        self.indexer = json.load(open(os.path.join(onnx_dir, "unicode_indexer.json"), encoding="utf-8"))
        opts = ort.SessionOptions()
        opts.intra_op_num_threads = max(1, min(4, os.cpu_count() or 2))
        prov = ["CPUExecutionProvider"]
        self.dp = ort.InferenceSession(os.path.join(onnx_dir, "duration_predictor.onnx"), opts, providers=prov)
        self.te = ort.InferenceSession(os.path.join(onnx_dir, "text_encoder.onnx"), opts, providers=prov)
        self.ve = ort.InferenceSession(os.path.join(onnx_dir, "vector_estimator.onnx"), opts, providers=prov)
        self.voc = ort.InferenceSession(os.path.join(onnx_dir, "vocoder.onnx"), opts, providers=prov)
        self.rng = np.random.default_rng(SEMENTE)

    @staticmethod
    def _nomes(sessao):
        return [i.name for i in sessao.get_inputs()], [o.name for o in sessao.get_outputs()]

    def _ids(self, texto):
        valores = [ord(c) for c in texto]
        ids = []
        for v in valores:
            if v < len(self.indexer):
                i = self.indexer[v]
                ids.append(0 if i == -1 else i)
            else:
                ids.append(0)
        return np.array([ids], dtype=np.int64), np.ones((1, 1, len(valores)), dtype=np.float32)

    def _infer(self, texto, lang, estilo, total_step, speed):
        proc = preprocessa_texto(texto, lang)
        ids, mask = self._ids(proc)
        dp_out = dict(zip([o.name for o in self.dp.get_outputs()],
                          self.dp.run(None, {"text_ids": ids, "style_dp": estilo["dp"], "text_mask": mask})))
        duracao = np.asarray(dp_out["duration"], dtype=np.float32).reshape(-1)
        duracao = np.maximum(duracao / speed, 0.1).astype(np.float32)

        te_out = dict(zip([o.name for o in self.te.get_outputs()],
                          self.te.run(None, {"text_ids": ids, "style_ttl": estilo["ttl"], "text_mask": mask})))
        text_emb = np.asarray(te_out["text_emb"], dtype=np.float32)

        xt, latent_mask = self._ruido_latente(duracao)
        total = np.full((1,), float(total_step), dtype=np.float32)
        for step in range(total_step):
            ve_out = dict(zip([o.name for o in self.ve.get_outputs()],
                              self.ve.run(None, {
                                  "noisy_latent": xt,
                                  "text_emb": text_emb,
                                  "style_ttl": estilo["ttl"],
                                  "latent_mask": latent_mask,
                                  "text_mask": mask,
                                  "current_step": np.full((1,), float(step), dtype=np.float32),
                                  "total_step": total,
                              })))
            xt = np.asarray(ve_out["denoised_latent"], dtype=np.float32)

        voc_out = dict(zip([o.name for o in self.voc.get_outputs()], self.voc.run(None, {"latent": xt})))
        wav = np.asarray(voc_out["wav_tts"], dtype=np.float32).reshape(-1)
        return wav, duracao

    def _ruido_latente(self, duracao):
        max_dur = float(duracao.max())
        wav_len_max = int(max_dur * self.sr)
        chunk = self.base_chunk * self.chunk_compress
        latent_len = max((wav_len_max + chunk - 1) // chunk, 1)
        dim = self.latent_dim * self.chunk_compress
        ruido = self.rng.standard_normal((1, dim, latent_len)).astype(np.float32)
        comprimento = int(float(duracao[0]) * self.sr)
        latent_comp = max((comprimento + chunk - 1) // chunk, 1)
        mascara = np.zeros((1, 1, latent_len), dtype=np.float32)
        mascara[0, 0, :min(latent_comp, latent_len)] = 1.0
        return ruido * mascara, mascara

    def sintetiza(self, texto, lang, estilo, total_step=STEPS, speed=SPEED):
        """Porta de TextToSpeech::call (um único estilo, sem callback)."""
        chunks = divide_chunks(texto, lang, NORMALIZADORES[lang]["max_len"])
        silencio = np.zeros(int(SILENCIO_ENTRE_CHUNKS * self.sr), dtype=np.float32)
        partes = []
        for i, chunk in enumerate(chunks):
            wav, dur = self._infer(chunk, lang, estilo, total_step, speed)
            n = int(float(dur[0]) * self.sr)
            if n < len(wav):
                wav = wav[:n]
            if i > 0:
                partes.append(silencio)
            partes.append(wav)
        audio = np.concatenate(partes) if partes else np.zeros(0, dtype=np.float32)
        return audio, len(chunks)


def carrega_estilo(caminho):
    dados = json.load(open(caminho, encoding="utf-8"))
    def arr(comp):
        dims = comp["dims"]
        valores = []
        for batch in comp["data"]:
            for linha in batch:
                valores.extend(linha)
        return np.array(valores, dtype=np.float32).reshape(1, dims[1], dims[2])
    return {"ttl": arr(dados["style_ttl"]), "dp": arr(dados["style_dp"])}


def para_pcm16(audio):
    limitado = np.clip(audio * GAIN, -1.0, 1.0)
    return np.trunc(limitado * 32767.0).astype(np.int16)  # `as i16` no Rust trunca


def grava_wav(caminho, pcm, sr):
    with wave.open(caminho, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(sr)
        w.writeframes(pcm.tobytes())


# ─── MP3 via ffmpeg ─────────────────────────────────────────────────────────
def acha_ffmpeg():
    achado = shutil.which("ffmpeg")
    if achado:
        return achado
    padrao = os.path.join(os.environ["LOCALAPPDATA"], "Microsoft", "WinGet", "Packages",
                          "Gyan.FFmpeg*", "*", "bin", "ffmpeg.exe")
    candidatos = sorted(glob.glob(padrao))
    if not candidatos:
        raise RuntimeError("ffmpeg não encontrado (nem no PATH nem no winget)")
    return candidatos[-1]


def acha_ffprobe(ffmpeg):
    irmao = os.path.join(os.path.dirname(ffmpeg), "ffprobe.exe")
    return irmao if os.path.exists(irmao) else (shutil.which("ffprobe") or "ffprobe")


def duracao_mp3(ffprobe, caminho):
    r = subprocess.run([ffprobe, "-v", "error", "-show_entries", "format=duration",
                        "-of", "default=nw=1:nk=1", caminho], capture_output=True, text=True, check=True)
    return float(r.stdout.strip())


def wav_para_mp3(ffmpeg, wav, mp3):
    subprocess.run([ffmpeg, "-y", "-loglevel", "error", "-i", wav, "-ac", "1",
                    "-c:a", "libmp3lame", "-b:a", "48k", mp3], check=True)


# ─── Conferência ────────────────────────────────────────────────────────────
def confere(pcm, sr, mp3, ffprobe):
    dur_wav = len(pcm) / sr
    rms = float(np.sqrt(np.mean(pcm.astype(np.float64) ** 2)) / 32768.0)
    rms_db = 20 * math.log10(rms) if rms > 0 else float("-inf")
    pico = float(np.max(np.abs(pcm.astype(np.float64))) / 32768.0)
    dur_mp3 = duracao_mp3(ffprobe, mp3)
    tam = os.path.getsize(mp3)
    problemas = []
    if not (3.0 <= dur_wav <= 12.0):
        problemas.append(f"duração fora de 3–12 s ({dur_wav:.2f} s)")
    if not (3.0 <= dur_mp3 <= 12.0):
        problemas.append(f"duração MP3 fora de 3–12 s ({dur_mp3:.2f} s)")
    if rms_db < -40 or pico < 0.05:
        problemas.append(f"possível mudo (RMS {rms_db:.1f} dBFS, pico {pico:.3f})")
    if tam < 5_000:
        problemas.append(f"arquivo pequeno demais ({tam} bytes)")
    return dur_mp3, tam, rms_db, pico, problemas


def main():
    import argparse
    ap = argparse.ArgumentParser(description="Gera as prévias Supertonic.")
    ap.add_argument("--versao", default="v2", choices=["v1", "v2"], help="pacote do modelo (padrão v2, como o app)")
    ap.add_argument("--somente", default="", help="nomes de arquivo separados por vírgula (padrão: todos)")
    ap.add_argument("--saida", default=SAIDA, help="pasta de saída")
    args = ap.parse_args()
    versao = args.versao
    saida = args.saida
    clipes = CLIPES
    if args.somente:
        pedidos = [x.strip() for x in args.somente.split(",") if x.strip()]
        clipes = [c for c in CLIPES if c[0] in pedidos]
        if len(clipes) != len(pedidos):
            raise SystemExit("nome de clipe desconhecido em --somente")
    cache = cache_dir(versao)
    os.makedirs(saida, exist_ok=True)
    os.makedirs(TMP, exist_ok=True)
    garantir_modelos(versao)

    ffmpeg = acha_ffmpeg()
    ffprobe = acha_ffprobe(ffmpeg)
    print("ffmpeg:", ffmpeg)

    modelo = Supertonic(os.path.join(cache, "onnx"))
    print(f"Modelo carregado (sample_rate={modelo.sr}, chunk={modelo.base_chunk}x{modelo.chunk_compress}, "
          f"latent_dim={modelo.latent_dim})")

    relatorio = []
    falhas = []
    estilos_cache = {}
    for arquivo, estilo, lang, texto in clipes:
        est_nome = estilo
        if est_nome not in estilos_cache:
            estilos_cache[est_nome] = carrega_estilo(os.path.join(cache, "voice_styles", f"{est_nome}.json"))
        print(f"Gerando {arquivo} [{est_nome}, {lang}]")
        audio, n_chunks = modelo.sintetiza(texto, lang, estilos_cache[est_nome])
        pcm = para_pcm16(audio)
        wav = os.path.join(TMP, arquivo.replace(".mp3", ".wav"))
        mp3 = os.path.join(saida, arquivo)
        grava_wav(wav, pcm, modelo.sr)
        wav_para_mp3(ffmpeg, wav, mp3)
        dur, tam, rms_db, pico, problemas = confere(pcm, modelo.sr, mp3, ffprobe)
        relatorio.append((arquivo, dur, tam, rms_db, n_chunks))
        if problemas:
            falhas.append((arquivo, problemas))
            print("  ATENÇÃO:", "; ".join(problemas))
        else:
            print(f"  OK {dur:.2f} s, {tam / 1024:.1f} KB, RMS {rms_db:.1f} dBFS, chunks={n_chunks}")

    print()
    print("Resumo:")
    for arquivo, dur, tam, rms_db, n in relatorio:
        print(f"  {arquivo:38s} {dur:6.2f} s  {tam / 1024:6.1f} KB  {rms_db:7.1f} dBFS")
    print(f"Total: {len(relatorio)} arquivos em {saida}")
    if falhas:
        print("Falhas de conferência:")
        for arquivo, problemas in falhas:
            print(f"  {arquivo}: {'; '.join(problemas)}")
        sys.exit(1)


if __name__ == "__main__":
    main()

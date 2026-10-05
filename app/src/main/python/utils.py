"""
utils.py - Utilitários gerais.
Adaptado para Android/Chaquopy: importa config_android em vez de config.
"""

import re
import os
import shutil
import math
import time
import collections
import ebooklib
from ebooklib import epub
from pypdf import PdfReader
from docx import Document
from bs4 import BeautifulSoup

import config_android as _config
from config_android import CHUNK_LIMITE, IDIOMA_PATTERNS, logger, TEMP_DIR, TEXTO_PRONTO
from audio_cache import CACHE  # noqa: F401
from narration_normalizer import sanitize_text_noise


def garantir_temp_dir():
    os.makedirs(TEMP_DIR, exist_ok=True)


def formatar_duracao(segundos: float) -> str:
    if segundos < 60:
        return f"{int(segundos)}s"
    elif segundos < 3600:
        m, s = divmod(int(segundos), 60)
        return f"{m}min {s}s"
    else:
        h, resto = divmod(int(segundos), 3600)
        m, s = divmod(resto, 60)
        return f"{h}h {m}min {s}s"


_cache_texto: dict = {"mtime": -1.0, "texto": ""}

def ler_texto_pronto(max_chars: int = 0) -> str:
    try:
        mtime = os.path.getmtime(TEXTO_PRONTO)
    except OSError:
        return ""
    if mtime != _cache_texto["mtime"]:
        try:
            with open(TEXTO_PRONTO, "r", encoding="utf-8") as f:
                _cache_texto["texto"] = f.read()
            _cache_texto["mtime"] = mtime
        except OSError:
            return ""
    texto = _cache_texto["texto"]
    return texto[:max_chars] if max_chars > 0 else texto


_SPINNERS = "⠋⠙⠹⠸⠼⠴⠦⠧⠇⠏"


class ProgressTracker:
    def __init__(self, total: int, inicio: float, concluidos_iniciais: int = 0):
        self.total = total
        self.inicio = inicio
        self.concluidos = concluidos_iniciais
        self.falhas: list = []
        self._janela: collections.deque = collections.deque(maxlen=5)
        self._ultimo_tick = time.time()
        self._spin = 0
        self._ultima_pct = -1
        self._ultima_atualizacao_ts = time.time()

    def registrar(self, idx: int, sucesso: bool) -> None:
        agora = time.time()
        self._janela.append(agora - self._ultimo_tick)
        self._ultimo_tick = agora
        self.concluidos += 1
        if not sucesso:
            self.falhas.append(idx)

    def pct(self) -> int:
        return math.floor(self.concluidos * 100 / self.total) if self.total else 0

    def deve_atualizar(self) -> bool:
        # Motores locais (Supertonic) processam poucos chunks grandes e lentos: esperar
        # 5% de progresso pode deixar a UI parada por minutos mesmo com síntese em andamento.
        # Por isso força uma atualização a cada chunk concluído (não só a cada 5%) sempre que
        # mais de 8s tiverem se passado desde a última mensagem exibida.
        p = self.pct()
        agora = time.time()
        deve = (
            p - self._ultima_pct >= 5
            or self.concluidos == self.total
            or agora - self._ultima_atualizacao_ts >= 8
        )
        if deve:
            self._ultima_atualizacao_ts = agora
        return deve

    def _eta(self) -> str:
        restantes = self.total - self.concluidos
        if restantes <= 0:
            return "0s"
        if len(self._janela) >= 3:
            media = sum(self._janela) / len(self._janela)
            return formatar_duracao(media * restantes)
        if self.concluidos > 0:
            decorrido = time.time() - self.inicio
            return formatar_duracao((decorrido / self.concluidos) * restantes)
        return "calculando..."

    def _barra(self) -> str:
        cheios = self.pct() // 5
        return "█" * cheios + "░" * (20 - cheios)

    def _spinner(self) -> str:
        c = _SPINNERS[self._spin % len(_SPINNERS)]
        self._spin += 1
        return c

    def mensagem_simples(self) -> str:
        """Versão sem Markdown para uso no Android (TextView)."""
        p = self.pct()
        self._ultima_pct = p
        decorrido = time.time() - self.inicio
        falhas_str = f" | {len(self.falhas)} falhas" if self.falhas else ""
        return (
            f"Convertendo {self._spinner()} {p}%\n"
            f"[{self._barra()}] {self.concluidos}/{self.total}{falhas_str}\n"
            f"Tempo: {formatar_duracao(decorrido)} | ETA: ~{self._eta()}"
        )


def estimar_duracao(texto: str, velocidade: str = "+0%") -> str:
    palavras = len(texto.split())
    wpm_base = 150
    try:
        mod = int(velocidade.replace("%", "").replace("+", ""))
        fator = 1 + (mod / 100)
    except ValueError:
        fator = 1.0
    wpm_real = max(wpm_base * fator, 1)
    segundos = (palavras / wpm_real) * 60
    return formatar_duracao(segundos)


def contar_estatisticas(texto: str) -> dict:
    palavras = texto.split()
    frases = re.split(r"[.!?]+", texto)
    paragrafos = [p for p in texto.split("\n\n") if p.strip()]
    return {
        "caracteres": len(texto),
        "palavras": len(palavras),
        "frases": len([f for f in frases if f.strip()]),
        "paragrafos": len(paragrafos),
        "paginas_estimadas": math.ceil(len(palavras) / 250),
    }


def limpar_texto_avancado(texto: str) -> str:
    texto = sanitize_text_noise(texto)
    texto = re.sub(r"\n{3,}", "\n\n", texto)
    texto = re.sub(r" {2,}", " ", texto)
    texto = re.sub(r"\n\s*\d{1,3}\s*\n", "\n", texto)
    texto = re.sub(r"https?://\S+", "", texto)
    texto = re.sub(r"\S+@\S+\.\S+", "", texto)
    texto = texto.replace("\u201c", '"').replace("\u201d", '"')
    texto = texto.replace("\u2018", "'").replace("\u2019", "'")
    texto = re.sub(r"[\x00-\x08\x0b\x0c\x0e-\x1f\x7f]", "", texto)
    return texto.strip()


_PALAVRAS_CHAVE_CAP = [
    r"prólogo", r"prolog", r"epílogo", r"epilog",
    r"introdução", r"introducao", r"prefácio", r"prefacio",
    r"conclusão", r"conclusao", r"agradecimentos",
    r"prologue", r"epilogue", r"preface", r"foreword", r"afterword", r"introduction",
    r"parte\s+(?:\d+|[IVXLCDMivxlcdm]+)", r"part\s+(?:\d+|[IVXLCDMivxlcdm]+)",
    r"capítulo\s+(?:\d+|[IVXLCDMivxlcdm]+)", r"capitulo\s+(?:\d+|[IVXLCDMivxlcdm]+)",
    r"chapter\s+(?:\d+|[IVXLCDMivxlcdm]+)",
]

_REGEX_GRUPO_A = re.compile(
    r"^\s*(?:#{1,6}\s*)?(?:" + "|".join(_PALAVRAS_CHAVE_CAP) + r")(?:\s*[:.\-\u2013\u2014]?\s*[^.]{0,80})?\s*$",
    re.IGNORECASE | re.UNICODE,
)
_REGEX_GRUPO_B = re.compile(r"^\s*\d{1,3}\s*$")
_REGEX_VAZIO   = re.compile(r"^\s*$")


def _e_titulo_capitulo(linha: str) -> bool:
    if _REGEX_VAZIO.match(linha):
        return False
    return bool(_REGEX_GRUPO_A.match(linha) or _REGEX_GRUPO_B.match(linha))


def dividir_em_capitulos(texto: str) -> list:
    linhas = texto.splitlines()
    secoes = []
    buffer = []
    for linha in linhas:
        if _e_titulo_capitulo(linha):
            if buffer and any(l.strip() for l in buffer):
                secoes.append("\n".join(buffer))
            buffer = [linha]
        else:
            buffer.append(linha)
    if buffer and any(l.strip() for l in buffer):
        secoes.append("\n".join(buffer))
    validas = [s.strip() for s in secoes if len(s.strip()) > 100]
    return validas if len(validas) > 1 else [texto]


def aplicar_intervalo(texto: str, intervalo: dict) -> str:
    if not intervalo:
        return texto
    tipo = intervalo.get("tipo")
    if tipo == "palavras":
        palavras = texto.split()
        n = min(max(1, intervalo.get("n", len(palavras))), len(palavras))
        return " ".join(palavras[:n])
    elif tipo == "capitulos":
        caps = dividir_em_capitulos(texto)
        inicio = max(0, intervalo.get("inicio", 1) - 1)
        fim    = min(len(caps), intervalo.get("fim", len(caps)))
        return "\n\n".join(caps[inicio:fim]) if inicio < fim else ""
    return texto


def dividir_em_sentencas(texto: str, limite: int = CHUNK_LIMITE) -> list:
    sentencas = re.split(r"(?<=[.!?])\s+", texto)
    blocos = []
    bloco_atual = ""
    for sentenca in sentencas:
        if len(bloco_atual) + len(sentenca) + 1 <= limite:
            bloco_atual += (" " if bloco_atual else "") + sentenca
        else:
            if bloco_atual:
                blocos.append(bloco_atual)
            if len(sentenca) > limite:
                blocos.extend([sentenca[i:i + limite] for i in range(0, len(sentenca), limite)])
                bloco_atual = ""
            else:
                bloco_atual = sentenca
    if bloco_atual:
        blocos.append(bloco_atual)
    return blocos


def detectar_idioma(texto: str) -> dict | None:
    _n = len(texto)
    _amostra = texto[:2000] + texto[_n // 2: _n // 2 + 1000] + texto[max(0, _n - 1000):]
    palavras = re.findall(r'\b[a-záàâãéèêíïóôõöúùüçñ]+\b', _amostra.lower())
    if len(palavras) < 10:
        return None
    scores = {}
    for idioma, data in IDIOMA_PATTERNS.items():
        matches = sum(1 for p in palavras if p in data["words"])
        scores[idioma] = matches
    total_matches = sum(scores.values())
    if total_matches < 3:
        return None
    melhor = max(scores, key=scores.get)
    confianca = int((scores[melhor] / total_matches) * 100) if total_matches > 0 else 0
    if confianca < 30:
        return None
    data = IDIOMA_PATTERNS[melhor]
    return {"idioma": melhor, "voice": data["voice"], "name": data["name"],
            "label": data["label"], "confianca": confianca}


_PDF_PAGE_MARKER = re.compile(
    r"^\s*(?:[-\u2013\u2014]\s*)?(?:(?:p[a\u00e1]gina|page|p[a\u00e1]g\.?)\s*)?"
    r"\d{1,4}(?:\s*(?:/|de|of)\s*\d{1,4})?(?:\s*[-\u2013\u2014])?\s*$",
    re.IGNORECASE,
)
_PDF_CONTACT_LINE = re.compile(
    r"^\s*(?:https?://|www\.|e-?mail\s*:|fones?\s*:|tel(?:efone)?\.?\s*:|"
    r"cep\s*:).*$",
    re.IGNORECASE,
)


def remover_ruido_pdf_paginas(paginas: list[str]) -> list[str]:
    """Remove page-level PDF noise before it can be merged into narration."""
    paginas = [sanitize_text_noise(str(pagina or "")) for pagina in paginas]
    ocorrencias = collections.Counter()
    for pagina in paginas:
        ocorrencias.update({
            linha.strip()
            for linha in pagina.splitlines()
            if linha.strip() and len(linha.strip()) <= 100
        })

    minimo_repeticoes = max(2, math.ceil(len(paginas) * 0.3))
    repetidas = {
        linha for linha, quantidade in ocorrencias.items()
        if len(paginas) > 1 and quantidade >= minimo_repeticoes
    }

    paginas_limpas = []
    for pagina in paginas:
        linhas = []
        for linha in pagina.splitlines():
            limpa = linha.strip()
            if limpa and (
                _PDF_PAGE_MARKER.fullmatch(limpa)
                or _PDF_CONTACT_LINE.match(limpa)
                or limpa in repetidas
            ):
                continue
            linhas.append(linha)
        paginas_limpas.append("\n".join(linhas).strip())
    return paginas_limpas


def corrigir_texto_pdf(texto: str) -> str:
    if not texto:
        return ""

    texto = sanitize_text_noise(texto)

    # 1. Resolver hifenização de fim de linha
    PRONOUNS_PT = {"o", "a", "os", "as", "me", "te", "se", "lhe", "lhes", "nos", "vos", "lo", "la", "los", "las", "no", "na", "num", "numa"}

    def dehyphenate_pt(match):
        part1 = match.group(1)
        part2 = match.group(2)
        if part2.lower() in PRONOUNS_PT:
            return f"{part1}-{part2}"
        else:
            return f"{part1}{part2}"

    # Remove hífens de quebra de linha quando dividem palavras, mas mantém pronomes
    texto = re.sub(r"(\w+)-[ \t]*\n[ \t]*(\w+)", dehyphenate_pt, texto)

    # 2. Corrigir quebras de linha indevidas
    # Divide por parágrafos (linhas vazias duplas) para não juntar parágrafos distintos
    paragraphs = texto.split("\n\n")
    cleaned_paragraphs = []

    for para in paragraphs:
        lines = para.splitlines()
        if not lines:
            continue

        joined_lines = []
        for line in lines:
            line_str = line.strip()
            if not line_str:
                continue

            if not joined_lines:
                joined_lines.append(line_str)
            else:
                prev_line = joined_lines[-1]
                # Se a linha anterior termina com pontuação forte (.!?:"”)
                # ou a linha atual começa com marcador de lista ou travessão de diálogo, não juntamos.
                starts_with_list_or_dialogue = re.match(r'^\s*(?:[-*\u2022\u2013\u2014]|\d+[.)])\s+', line_str)
                ends_with_terminator = prev_line[-1] in {'.', '!', '?', ':', '"', '”', '»'} if prev_line else True

                if ends_with_terminator or starts_with_list_or_dialogue:
                    joined_lines.append(line_str)
                else:
                    joined_lines[-1] = prev_line + " " + line_str

        cleaned_paragraphs.append("\n".join(joined_lines))

    return "\n\n".join(cleaned_paragraphs)


def _processar_mobi_para_texto(caminho: str) -> str:
    """Desempacota um .mobi e devolve texto limpo, reaproveitando os
    extratores de EPUB/PDF já existentes (a grande maioria dos .mobi modernos
    vira um .epub de verdade ao desempacotar)."""
    import mobi_extract
    kind, path, tempdir = mobi_extract.desempacotar(caminho)
    try:
        if kind == "epub":
            return extrair_texto_limpo(path, ".epub")
        if kind == "pdf":
            return extrair_texto_limpo(path, ".pdf")
        # kind == "html": Mobipocket antigo (KF7 puro), sem equivalente EPUB
        with open(path, "r", encoding="utf-8", errors="ignore") as f:
            soup = BeautifulSoup(f.read(), "html.parser")
        for tag in soup(["script", "style"]):
            tag.decompose()
        return soup.get_text(separator="\n").strip()
    finally:
        shutil.rmtree(tempdir, ignore_errors=True)


def _ler_texto_com_encoding(caminho: str) -> str:
    """Lê .txt/.md detectando o encoding real em vez de assumir UTF-8.

    Arquivos antigos de usuário costumam vir em cp1252/ANSI (Windows) ou UTF-16;
    ler como UTF-8 com errors="ignore" apagava os acentos silenciosamente.
    """
    with open(caminho, "rb") as f:
        dados = f.read()
    if dados[:2] in (b"\xff\xfe", b"\xfe\xff"):
        return dados.decode("utf-16")
    try:
        return dados.decode("utf-8")
    except UnicodeDecodeError:
        return dados.decode("cp1252")


def extrair_texto_limpo(caminho: str, ext: str) -> str:
    texto_final = []
    nomes_estruturais = [
        "cover", "toc", "nav", "copyright", "credito", "titlepage",
        "colophon", "imprint", "sobre_", "about",
    ]
    termos_heading_estrutural = [
        "créditos", "creditos", "sumário", "sumario", "copyright",
        "todos os direitos", "isbn",
    ]
    try:
        if ext == ".epub":
            book = epub.read_epub(caminho)
            id_map = {item.id: item for item in book.get_items_of_type(ebooklib.ITEM_DOCUMENT)}
            itens_ordenados = []
            for item_id, _ in book.spine:
                item = id_map.get(item_id)
                if item:
                    itens_ordenados.append(item)
            if not itens_ordenados:
                itens_ordenados = list(book.get_items_of_type(ebooklib.ITEM_DOCUMENT))
            for item in itens_ordenados:
                nome = item.get_name().lower().split("/")[-1]
                if any(nome.startswith(e) for e in nomes_estruturais):
                    continue
                soup = BeautifulSoup(item.get_content(), "html.parser")
                for tag in soup(["script", "style"]):
                    tag.decompose()
                conteudo = soup.get_text(separator="\n").strip()
                if len(conteudo) < 50:
                    continue
                primeira_linha = conteudo.splitlines()[0].strip().lower()
                if len(conteudo) < 2000 and any(t in primeira_linha for t in termos_heading_estrutural):
                    continue
                texto_final.append(conteudo)
        elif ext == ".pdf":
            reader = PdfReader(caminho)
            paginas = remover_ruido_pdf_paginas([page.extract_text() or "" for page in reader.pages])
            texto_final.extend(corrigir_texto_pdf(t) for t in paginas if t)
        elif ext in [".txt", ".md"]:
            texto_final.append(_ler_texto_com_encoding(caminho))
        elif ext == ".docx":
            doc = Document(caminho)
            texto_final.append("\n\n".join(p.text for p in doc.paragraphs if p.text.strip()))
        elif ext == ".doc":
            import doc_extract
            texto_final.append(doc_extract.extrair_texto_doc(caminho))
        elif ext == ".mobi":
            texto_final.append(_processar_mobi_para_texto(caminho))
    except Exception as e:
        logger.error(f"Erro na extração ({ext}): {e}")
        raise

    raw_texto = "\n\n".join(texto_final)
    return limpar_texto_avancado(raw_texto)

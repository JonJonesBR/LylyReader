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
import unicodedata
from typing import Optional
import ebooklib
from ebooklib import epub
from pypdf import PdfReader
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


# Faixas do percentual global da conversão (0–100). A síntese dos blocos ocupa 0–PCT_FIM_SINTESE;
# depois vêm junção, timbre, metadados e marcadores de capítulo, cada um com sua faixa.
# O 100 só é reportado no "Pronto!" (core_processor_android.processar).
PCT_FIM_SINTESE = 90
PCT_FIM_JUNCAO = 95
PCT_FIM_TIMBRE = 97
PCT_FIM_METADADOS = 98
PCT_FIM_CAPITULOS = 99


def fracao_sintese(pct: int) -> float:
    """Fração (0..1) da síntese dos blocos já feita, a partir do percentual global."""
    return max(0.0, min(1.0, pct / PCT_FIM_SINTESE))


def pct_sintese_global(pct_blocos: int) -> int:
    """Converte o percentual de blocos (0–100) para a faixa global da síntese (0–PCT_FIM_SINTESE)."""
    return pct_blocos * PCT_FIM_SINTESE // 100


def pct_nao_regride(novo: int, maior_ate_agora: int) -> int:
    """Percentual que não volta atrás: nunca menor que o maior já reportado nesta conversão."""
    return max(novo, maior_ate_agora)


class ProgressTracker:
    def __init__(self, total: int, inicio: float, concluidos_iniciais: int = 0):
        self.total = total
        self.inicio = inicio
        # `concluidos` = blocos com áudio válido (inclui os da retomada).
        self.concluidos = concluidos_iniciais
        # Índices de blocos que falharam e ainda não tiveram sucesso.
        self.falhas: list = []
        self._iniciais = concluidos_iniciais
        self._ok_nesta_execucao: set = set()
        # Intervalos (s) entre sucessos consecutivos desta execução.
        self._janela: collections.deque = collections.deque(maxlen=10)
        self._ultimo_sucesso = inicio
        self._ultima_pct = -1
        self._ultima_atualizacao_ts = time.time()

    def registrar(self, idx: int, sucesso: bool) -> None:
        if not sucesso:
            if idx not in self._ok_nesta_execucao and idx not in self.falhas:
                self.falhas.append(idx)
            return
        if idx in self._ok_nesta_execucao:
            return
        agora = time.time()
        self._janela.append(max(0.0, agora - self._ultimo_sucesso))
        self._ultimo_sucesso = agora
        self._ok_nesta_execucao.add(idx)
        if idx in self.falhas:
            self.falhas.remove(idx)
        self.concluidos += 1

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
        # Base só com blocos produzidos NESTA execução: a retomada não entra na taxa.
        produzidos = self.concluidos - self._iniciais
        if produzidos <= 0:
            return "calculando..."
        decorrido = time.time() - self.inicio
        taxa = decorrido / produzidos  # segundos por bloco, média da execução
        if len(self._janela) >= 3:
            taxa_janela = sum(self._janela) / len(self._janela)
            taxa = (taxa + taxa_janela) / 2  # suaviza com a janela recente
        # Mínimo de 1s: nunca mostrar "0s" enquanto restam blocos.
        return formatar_duracao(max(1.0, taxa * restantes))

    def mensagem_simples(self, pct: Optional[int] = None) -> str:
        """Versão sem Markdown para uso no Android (TextView). `pct` é o percentual já na faixa
        global da conversão (ver pct_sintese_global); sem ele, usa o percentual dos blocos."""
        self._ultima_pct = self.pct()
        p = self.pct() if pct is None else pct
        decorrido = time.time() - self.inicio
        falhas_str = f" | {len(self.falhas)} falhas" if self.falhas else ""
        eta = self._eta()
        prefixo_eta = "" if eta == "calculando..." else "~"
        return (
            f"Convertendo {p}%\n"
            f"{self.concluidos}/{self.total} blocos{falhas_str}\n"
            f"Tempo: {formatar_duracao(decorrido)} | ETA: {prefixo_eta}{eta}"
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


_TITULOS_SECAO_NOTAS = frozenset({
    "notas", "notas de rodapé", "notas do autor", "notas finais",
    "referências", "referências bibliográficas", "referencias", "referencias bibliograficas",
    "bibliografia", "notes", "endnotes", "references",
})
_RE_ITEM_NUMERADO = re.compile(r"\s*\d+\.")
_MIN_ITENS_SECAO_NOTAS = 10
_FRACAO_FINAL_SECAO_NOTAS = 0.7


def remover_secao_notas_final(texto: str) -> str:
    """Corta a seção de notas/referências do fim do livro (ex.: "Notas" seguido de 10+ itens
    numerados). Só age se o último título de notas estiver nos últimos 30% do texto; caso
    contrário devolve o texto intacto."""
    if not texto:
        return texto
    linhas = texto.splitlines(keepends=True)
    posicoes = []
    pos = 0
    for linha in linhas:
        posicoes.append(pos)
        pos += len(linha)

    idx_titulo = None
    for i in range(len(linhas) - 1, -1, -1):
        if linhas[i].strip().lower() in _TITULOS_SECAO_NOTAS:
            idx_titulo = i
            break
    if idx_titulo is None:
        return texto
    if posicoes[idx_titulo] < len(texto) * _FRACAO_FINAL_SECAO_NOTAS:
        return texto

    itens = sum(1 for linha in linhas[idx_titulo + 1:] if _RE_ITEM_NUMERADO.match(linha))
    if itens < _MIN_ITENS_SECAO_NOTAS:
        return texto
    return texto[:posicoes[idx_titulo]].rstrip()


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


_TROCAS_TIPOGRAFICAS_TTS = {
    "“": '"', "”": '"', "„": '"', "«": '"', "»": '"',
    "‘": "'", "’": "'", "‚": "'",
    "‒": "-", "–": "-", "—": "-", "−": "-",
}


def sanitizar_trecho_para_tts(texto: str) -> str:
    """Versão do trecho pensada para a última tentativa de um bloco que falhou no motor de voz.

    Normaliza NFKC, troca aspas/travessões tipográficos por equivalentes simples, remove controle,
    zero-width, emoji e símbolos (fora letras, dígitos, marcas e pontuação), e colapsa espaços.
    Acentos e pontuação de frase são preservados. Se o original tinha letras ou dígitos, nunca
    devolve vazio (nesse caso devolve o original).
    """
    original = texto or ""
    normalizado = unicodedata.normalize("NFKC", original)
    for origem, destino in _TROCAS_TIPOGRAFICAS_TTS.items():
        normalizado = normalizado.replace(origem, destino)
    partes = []
    for ch in normalizado:
        if ch.isspace():
            partes.append(" ")
            continue
        # Variation selectors (U+FE00..U+FE0F) são categoria Mn mas são só modificadores de emoji.
        if 0xFE00 <= ord(ch) <= 0xFE0F:
            continue
        categoria = unicodedata.category(ch)
        if categoria[0] in ("L", "N", "P") or categoria in ("Mn", "Mc", "Sc") or ch in "+=<>":
            partes.append(ch)
    resultado = re.sub(r" {2,}", " ", "".join(partes)).strip()
    if not resultado and any(c.isalnum() for c in original):
        return original
    return resultado


_RE_SANEAR_URL = re.compile(r"https?://|\bwww\.", re.IGNORECASE)
_RE_SANEAR_EMAIL = re.compile(r"[\w.+-]+@[\w-]+\.[\w.-]+")
_RE_SANEAR_PROCESSO = re.compile(r"\b\d{7}-\d{2}\.\d{4}\.\d\.\d{2}\.\d{4}\b")
_RE_SANEAR_DIGITOS = re.compile(r"\d{9,}")
_RE_SANEAR_SIMBOLOS = re.compile(r"[^\w\s]{4,}")
_RE_SANEAR_DISPONIVEL = re.compile(r"disponível em:", re.IGNORECASE)


def precisa_sanear(texto: str) -> bool:
    """True se o bloco traz algo que costuma travar o motor de voz (URL, e-mail, número de processo,
    sequência longa de dígitos ou de símbolos, ou 'Disponível em:'). Esses blocos recebem
    sanitizar_trecho_para_tts já na 1ª tentativa, em vez de só na última."""
    if not texto:
        return False
    return any(
        padrao.search(texto) is not None
        for padrao in (
            _RE_SANEAR_URL, _RE_SANEAR_EMAIL, _RE_SANEAR_PROCESSO,
            _RE_SANEAR_DIGITOS, _RE_SANEAR_SIMBOLOS, _RE_SANEAR_DISPONIVEL,
        )
    )


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


MENSAGEM_PDF_ESCANEADO = (
    "vazio: O PDF parece ser escaneado (só imagens); sem texto não dá para ler/converter"
)


def erro_pdf_legivel(e: Exception) -> str:
    """Mensagem em português para falha ao abrir/ler um PDF. O prefixo ("senha:", "invalido:")
    é o que o Kotlin usa para escolher a mensagem exibida ao usuário."""
    if isinstance(e, FileNotFoundError):
        return "invalido: arquivo PDF não encontrado"
    nome = type(e).__name__.lower()
    texto = str(e).lower()
    if "decrypt" in nome or "encrypt" in nome or "password" in nome or "password" in texto:
        return "senha: PDF protegido por senha; remova a proteção antes de abrir"
    return f"invalido: PDF corrompido ou ilegível ({e})"


def decodificar_texto_bruto(dados: bytes) -> str:
    """Decodifica o conteúdo de um .txt/.md sem assumir UTF-8.

    Ordem: BOM UTF-16 → BOM UTF-8 (removido) → UTF-16 sem BOM (detectado por bytes nulos) →
    UTF-8 → cp1252 → latin-1 (cp1252 não tem todos os bytes; latin-1 nunca falha). Arquivos
    antigos de usuário costumam vir em cp1252/ANSI ou UTF-16; ler como UTF-8 com
    errors="ignore" apagava os acentos silenciosamente.
    """
    if dados[:2] in (b"\xff\xfe", b"\xfe\xff"):
        return dados.decode("utf-16", errors="replace")
    if dados.startswith(b"\xef\xbb\xbf"):
        return dados[3:].decode("utf-8", errors="replace")
    amostra = dados[:1000]
    if b"\x00" in amostra:
        # UTF-16 sem BOM: o byte nulo cai na posição par (BE) ou ímpar (LE) da amostra.
        if amostra[0::2].count(0) > amostra[1::2].count(0):
            return dados.decode("utf-16-be", errors="replace")
        return dados.decode("utf-16-le", errors="replace")
    try:
        return dados.decode("utf-8")
    except UnicodeDecodeError:
        pass
    try:
        return dados.decode("cp1252")
    except UnicodeDecodeError:
        return dados.decode("latin-1")


def _ler_texto_com_encoding(caminho: str) -> str:
    """Lê .txt/.md detectando o encoding real (ver decodificar_texto_bruto). Quebras CRLF/CR
    viram LF para a narração não carregar \r."""
    with open(caminho, "rb") as f:
        texto = decodificar_texto_bruto(f.read())
    return texto.replace("\r\n", "\n").replace("\r", "\n")


def _caminho_zip_do_item(nomes_zip: set, nome_relativo: str):
    """Caminho completo, dentro do zip, de um item do ebooklib (que guarda o nome relativo ao
    OPF). Entre candidatos com a mesma cauda, vale o mais raso (mais perto da raiz do zip)."""
    candidatos = [n for n in nomes_zip if n == nome_relativo or n.endswith("/" + nome_relativo)]
    return min(candidatos, key=len) if candidatos else None


def _textos_epub_com_ebooklib(caminho: str) -> list:
    """Texto de cada capítulo (ordem da spine) pelo ebooklib. Um item que falha é pulado."""
    import zipfile

    from audiobook_android import ArquivoSuspeitoError, _LeitorZipSeguro

    nomes_estruturais = [
        "cover", "toc", "nav", "copyright", "credito", "titlepage",
        "colophon", "imprint", "sobre_", "about",
    ]
    termos_heading_estrutural = [
        "créditos", "creditos", "sumário", "sumario", "copyright",
        "todos os direitos", "isbn",
    ]
    textos = []
    book = epub.read_epub(caminho)
    id_map = {item.id: item for item in book.get_items_of_type(ebooklib.ITEM_DOCUMENT)}
    itens_ordenados = []
    for item_id, _ in book.spine:
        item = id_map.get(item_id)
        if item:
            itens_ordenados.append(item)
    if not itens_ordenados:
        itens_ordenados = list(book.get_items_of_type(ebooklib.ITEM_DOCUMENT))
    with zipfile.ZipFile(caminho) as zf:
        nomes_zip = set(zf.namelist())
        leitor = _LeitorZipSeguro(zf)
        for item in itens_ordenados:
            try:
                nome = item.get_name().lower().split("/")[-1]
                if any(nome.startswith(e) for e in nomes_estruturais):
                    continue
                # Bytes originais do zip (pelo mesmo leitor seguro do app: teto de tamanho e
                # recusa de DTD/entidades). O ebooklib regrava o XHTML com declaração UTF-8 e
                # mantém bytes latin-1/cp1252, o que faz o bs4 adivinhar a codificação errada.
                caminho_zip = _caminho_zip_do_item(nomes_zip, item.file_name)
                bruto = leitor.ler(caminho_zip) if caminho_zip else item.get_content()
                soup = BeautifulSoup(bruto, "html.parser")
                for tag in soup(["script", "style"]):
                    tag.decompose()
                conteudo = soup.get_text(separator="\n").strip()
                if not conteudo:
                    continue  # capa só com imagem ou página vazia; capítulo curto ("Fim.") fica
                primeira_linha = conteudo.splitlines()[0].strip().lower()
                if len(conteudo) < 2000 and any(t in primeira_linha for t in termos_heading_estrutural):
                    continue
                textos.append(conteudo)
            except ArquivoSuspeitoError:
                # Recusado por segurança: não pode cair no except genérico e virar texto.
                # Relançado para a conversão cair no extrator do leitor, que também o recusa.
                raise
            except Exception as e:
                logger.warning(f"Item do EPUB pulado ({item.get_name()}): {e}")
    return textos


def _textos_epub_para_conversao(caminho: str) -> list:
    """Texto do EPUB para conversão. Usa o ebooklib; se ele falhar ou render vazio, cai no
    extrator do leitor (audiobook_android), que tolera EPUB sem container/OPF/spine válidos."""
    textos = []
    try:
        textos = _textos_epub_com_ebooklib(caminho)
    except Exception as e:
        logger.warning(f"ebooklib não abriu o EPUB ({e}); usando o extrator do leitor")
    if textos:
        return textos

    import audiobook_android

    r = audiobook_android._extrair_epub(caminho)
    if r.get("ok"):
        return ["\n\n".join(p["texto"] for p in r["paragrafos"])]
    if r.get("erro") == "vazio":
        return []
    raise ValueError(r.get("erro") or "invalido: EPUB sem texto legível")


def _textos_pdf_para_conversao(caminho: str) -> list:
    """Texto do PDF para conversão: pypdf; sem texto, o extrator do leitor (pdfminer, se
    disponível); sem nada, erro de PDF escaneado."""
    try:
        reader = PdfReader(caminho)
        paginas = remover_ruido_pdf_paginas([page.extract_text() or "" for page in reader.pages])
    except Exception as e:
        raise ValueError(erro_pdf_legivel(e)) from e
    # Página só com números/símbolos (paginação, separadores) não é texto narrável.
    textos = [corrigir_texto_pdf(t) for t in paginas if t and re.search(r"[^\W\d_]", t)]
    if textos:
        return textos

    import audiobook_android

    r = audiobook_android._extrair_pdf(caminho)
    if r.get("ok"):
        return ["\n\n".join(p["texto"] for p in r["paragrafos"])]
    raise ValueError(MENSAGEM_PDF_ESCANEADO)


def extrair_texto_limpo(caminho: str, ext: str) -> str:
    texto_final = []
    try:
        if ext == ".epub":
            texto_final.extend(_textos_epub_para_conversao(caminho))
        elif ext == ".pdf":
            texto_final.extend(_textos_pdf_para_conversao(caminho))
        elif ext in [".txt", ".md"]:
            texto_final.append(_ler_texto_com_encoding(caminho))
        elif ext == ".docx":
            import audiobook_android

            # Mesmo leitor do app: inclui tabelas, listas, caixas de texto e hyperlinks.
            paragrafos = audiobook_android._paragrafos_docx(caminho)
            texto_final.append("\n\n".join(texto for texto, _ in paragrafos))
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

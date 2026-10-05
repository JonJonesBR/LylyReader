"""
smart_cleaner.py - Limpeza inteligente de texto para narração TTS.
Remove sumário, notas de rodapé, dedicatórias e cabeçalhos/rodapés.
Retorna (texto_limpo, relatorio, spans_removidos).
"""
import json
import re
from typing import List, Dict, Tuple, Any

# ── Padrões de Sumário ────────────────────────────────────────────────────────

_TOC_HEADER = re.compile(
    r'(?im)^\s*(sumário|table\s+of\s+contents|índice|contents|'
    r'tabla\s+de\s+contenidos|contenido|index)\s*$'
)
# Linha típica de TOC: "Capítulo 1 ........... 42" ou "Introdução ......... 5"
_TOC_LINE = re.compile(r'^.{3,80}[\s.…·]{3,}\s*\d{1,4}\s*$')

# ── Padrões de Nota de Rodapé ─────────────────────────────────────────────────

# "[1] Texto da nota" ou "1. Texto da nota" no início de parágrafo
_FOOTNOTE_LINE = re.compile(r'^\s*(\[\d+\]|\d+[\.\)])\s+\S')

# ── Padrões de Dedicatória / Epígrafe ─────────────────────────────────────────

_DEDICATION_HEADER = re.compile(
    r'(?im)^\s*(dedicatória|dedicatoria|dedication|'
    r'epígrafe|epigraph|aos meus|para minha|to my|à minha)\b'
)

# ── Padrões de Cabeçalho/Rodapé (PDF) ────────────────────────────────────────

# Linha repetida em muitas páginas detectada externamente; aqui apenas linhas
# muito curtas que parecem numeração de página isolada
_PAGE_NUMBER = re.compile(
    r'^\s*(?:[-–—]\s*)?(?:(?:p[aá]gina|page|p[áa]g\.?)\s*)?'
    r'\d{1,4}(?:\s*(?:/|de|of)\s*\d{1,4})?(?:\s*[-–—])?\s*$',
    re.IGNORECASE,
)
_DECORATIVE_LINE = re.compile(r'^\s*([*_=-])\1{2,}\s*$')

def _classificar_linha(linha: str) -> str | None:
    """Retorna categoria da linha se não-narrativa, ou None se narrativa."""
    s = linha.strip()
    if not s:
        return None
    if _TOC_HEADER.match(s):
        return "sumário"
    if _TOC_LINE.match(s):
        return "sumário"
    if _FOOTNOTE_LINE.match(s):
        return "nota_rodape"
    if _DEDICATION_HEADER.match(s):
        return "dedicatoria"
    if _PAGE_NUMBER.match(s):
        return "paginacao"
    if _DECORATIVE_LINE.match(s):
        return "decoracao"
    return None


def _detectar_bloco_toc(linhas: List[str]) -> List[bool]:
    """
    Marca linhas pertencentes a blocos de TOC.
    Um bloco começa com cabeçalho de TOC e termina quando encontra
    5+ linhas consecutivas sem padrão de TOC.
    """
    marcadas = [False] * len(linhas)
    em_toc = False
    linhas_sem_toc = 0

    for i, linha in enumerate(linhas):
        s = linha.strip()
        if _TOC_HEADER.match(s):
            em_toc = True
            marcadas[i] = True
            linhas_sem_toc = 0
            continue

        if em_toc:
            if _TOC_LINE.match(s) or not s:
                marcadas[i] = True
                if not s:
                    linhas_sem_toc += 1
                else:
                    linhas_sem_toc = 0
            else:
                linhas_sem_toc += 1
                if linhas_sem_toc >= 5:
                    em_toc = False
                else:
                    marcadas[i] = True  # dentro do bloco de TOC

    return marcadas


def _detectar_cabecalhos_rodape_pdf(linhas: List[str]) -> List[bool]:
    """
    Detecta cabeçalhos/rodapés repetidos em PDF.
    Uma linha é considerada header/footer se aparece em ≥5% das linhas.
    """
    if len(linhas) < 20:
        return [False] * len(linhas)

    from collections import Counter
    # Conta frequência de cada linha (normalizada)
    contagem = Counter(l.strip() for l in linhas if l.strip())
    total_nao_vazias = sum(1 for l in linhas if l.strip())
    threshold = max(3, total_nao_vazias * 0.05)  # aparece em ≥5% das linhas

    marcadas = []
    for linha in linhas:
        s = linha.strip()
        # Linha curta (< 60 chars) que aparece muitas vezes = header/footer
        if s and len(s) < 60 and contagem[s] >= threshold:
            marcadas.append(True)
        else:
            marcadas.append(False)
    return marcadas


def smart_clean_for_tts(
    texto: str,
    formato: str,
    idioma: str = "pt"
) -> Tuple[str, List[Dict[str, Any]], List[Tuple[int, int]]]:
    """
    Limpa texto para narração TTS.

    Args:
        texto:   Texto bruto extraído do arquivo.
        formato: Extensão do arquivo: "epub", "pdf", "txt", "docx", "md".

    Returns:
        Tupla (texto_limpo, relatorio, spans_removidos):
          - texto_limpo:    Texto após remoção dos trechos não-narrativos.
          - relatorio:      Lista de dicts {"tipo": str, "linhas": int}.
          - spans_removidos: Lista de (inicio_char, fim_char) no texto original.
    """
    linhas = texto.splitlines()
    n = len(linhas)

    # Máscara: True = remover esta linha
    remover = [False] * n

    # 1. Detectar blocos de TOC
    toc_mask = _detectar_bloco_toc(linhas)
    for i in range(n):
        if toc_mask[i]:
            remover[i] = True

    # 2. Detectar cabeçalhos/rodapés (apenas para PDF)
    if formato.lower() == "pdf":
        hf_mask = _detectar_cabecalhos_rodape_pdf(linhas)
        for i in range(n):
            if hf_mask[i]:
                remover[i] = True

    # 3. Classificar linhas individualmente
    tipos_removidos: Dict[str, int] = {}
    for i, linha in enumerate(linhas):
        if not remover[i]:
            tipo = _classificar_linha(linha)
            if tipo:
                remover[i] = True
                tipos_removidos[tipo] = tipos_removidos.get(tipo, 0) + 1

    # 4. Construir spans removidos (em chars do texto original)
    #    e montar texto limpo
    spans: List[Tuple[int, int]] = []
    linhas_limpas: List[str] = []
    char_offset = 0

    for i, linha in enumerate(linhas):
        comprimento = len(linha) + 1  # +1 pelo \n
        if remover[i]:
            spans.append((char_offset, char_offset + comprimento))
            tipo = _classificar_linha(linha) or "sumário"
            tipos_removidos[tipo] = tipos_removidos.get(tipo, 0) + 1
        else:
            linhas_limpas.append(linha)
        char_offset += comprimento

    from narration_normalizer import normalize_for_narration
    texto_limpo = normalize_for_narration("\n".join(linhas_limpas), idioma)

    relatorio = [
        {"tipo": tipo, "linhas": qtd}
        for tipo, qtd in tipos_removidos.items()
        if qtd > 0
    ]

    return texto_limpo, relatorio, spans


def normalize_blacklist(blacklist: Any) -> List[str]:
    """Convert a JSON string or wrapped collection into a native string list."""
    if not blacklist:
        return []

    parsed = blacklist
    if isinstance(blacklist, str):
        try:
            parsed = json.loads(blacklist)
        except (TypeError, ValueError):
            return []

    try:
        values = list(parsed)
    except TypeError:
        return []

    return [str(value).strip() for value in values if str(value).strip()]


def smart_clean_paragraphs_for_tts(
    paragrafos: List[str],
    formato: str,
    idioma: str = "pt",
    blacklist: Any = None
) -> Tuple[List[str], int]:
    """Clean paragraphs while preserving their original structural positions."""
    from narration_normalizer import normalize_for_narration

    linhas = []
    indices = []
    for indice, paragrafo in enumerate(paragrafos):
        for linha in str(paragrafo).splitlines() or [""]:
            linhas.append(linha)
            indices.append(indice)

    remover = _detectar_bloco_toc(linhas)
    if formato.lower() == "pdf":
        hf_mask = _detectar_cabecalhos_rodape_pdf(linhas)
        remover = [toc or hf for toc, hf in zip(remover, hf_mask)]

    resultado = [[] for _ in paragrafos]
    total_removidas = 0
    for posicao, (indice, linha) in enumerate(zip(indices, linhas)):
        if _classificar_linha(linha) or remover[posicao]:
            total_removidas += 1
        else:
            resultado[indice].append(linha)

    limpos = [normalize_for_narration("\n".join(linhas_paragrafo), idioma) for linhas_paragrafo in resultado]

    for term in normalize_blacklist(blacklist):
        pattern = re.compile(re.escape(term), re.IGNORECASE)
        for i in range(len(limpos)):
            texto, removidas = pattern.subn("", limpos[i])
            if removidas > 0:
                total_removidas += removidas
                limpos[i] = re.sub(r"[ \t]{2,}", " ", texto).strip()

    return limpos, total_removidas


def remove_global_occurrences(paragrafos: List[str], trecho: str) -> Tuple[List[str], int]:
    """Remove a literal word or phrase from every paragraph, case-insensitively."""
    trecho = str(trecho).strip()
    if not trecho:
        return [str(p) for p in paragrafos], 0
    pattern = re.compile(re.escape(trecho), re.IGNORECASE)
    total = 0
    resultado = []
    for paragrafo in paragrafos:
        texto, removidas = pattern.subn("", str(paragrafo))
        total += removidas
        resultado.append(re.sub(r"[ \t]{2,}", " ", texto).strip())
    return resultado, total

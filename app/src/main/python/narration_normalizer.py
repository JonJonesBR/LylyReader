"""Conservative, offline text normalization for TTS narration."""
import re

# Linha/par\u00e1grafo inteiro composto s\u00f3 por d\u00edgitos, com ou sem espa\u00e7o/tab entre eles \u2014
# n\u00famero de p\u00e1gina solto ("42") ou separador decorativo remanescente de convers\u00e3o
# PDF\u2192EPUB (fonte com glifo decorativo que virou d\u00edgitos "soltos" na extra\u00e7\u00e3o, ex.:
# "6 9 6" repetido centenas de vezes num livro real em vez de um "\u2767"). Nunca tem valor
# narrativo. Limite de 10 chars extras evita apagar n\u00fameros de telefone/ISBN por engano
# (esses t\u00eam mais d\u00edgitos e costumam ter outros separadores).
_LINHA_SO_NUMEROS = re.compile(r"^\d[\d \t]{0,10}$")

# Mesmo artefato de "6 9 6", mas GRUDADO no meio/fim de um par\u00e1grafo com texto real (o
# glifo decorativo virou d\u00edgitos soltos na extra\u00e7\u00e3o, mas o <p> original tamb\u00e9m tinha prosa
# \u2014 ex.: "...levava a palavras 6 9 6" ou "exclamou, 6 9 6 elevando a voz..."). Precisa de
# 2+ d\u00edgitos ISOLADOS (cada um cercado por espa\u00e7o/tab, nunca colados a outro d\u00edgito) para
# n\u00e3o confundir com um n\u00famero de verdade ("42", "1990", "100 200" como pre\u00e7os) \u2014 nenhum
# desses tem d\u00edgito isolado sozinho entre espa\u00e7os.
_DIGITOS_ISOLADOS_NO_MEIO_DO_TEXTO = re.compile(r"(?<!\d)\d(?:[ \t]\d){1,}(?!\d)")


def eh_apenas_numeros(texto: str) -> bool:
    """True quando `texto` (uma linha ou par\u00e1grafo inteiro, j\u00e1 sem espa\u00e7os nas pontas)
    n\u00e3o tem nada al\u00e9m de d\u00edgitos e espa\u00e7os \u2014 ver `_LINHA_SO_NUMEROS`."""
    return bool(_LINHA_SO_NUMEROS.fullmatch(texto.strip()))


def remover_digitos_isolados(texto: str) -> str:
    """Remove o artefato "6 9 6" (ou similar) de QUALQUER posi\u00e7\u00e3o do texto, mesmo grudado
    em prosa real \u2014 ver `_DIGITOS_ISOLADOS_NO_MEIO_DO_TEXTO`. N\u00e3o colapsa os espa\u00e7os
    remanescentes (chamador decide se precisa)."""
    return _DIGITOS_ISOLADOS_NO_MEIO_DO_TEXTO.sub("", texto)


def sanitize_text_noise(text: str) -> str:
    """Remove extraction artifacts which never add useful narration."""
    text = text.replace("\u00a0", " ").replace("\u202f", " ")
    text = text.replace("\uf0fe", "").replace("\ufffe", "")
    text = re.sub(r"[\u200b-\u200d\u2060\ufeff]", "", text)
    text = text.replace("\u201c", '"').replace("\u201d", '"')
    text = text.replace("\u2018", "'").replace("\u2019", "'")
    text = re.sub(r"https?://\S+|www\.\S+", "", text, flags=re.IGNORECASE)
    text = re.sub(r"\b[\w.+-]+@[\w.-]+\.[A-Za-z]{2,}\b", "", text)
    text = re.sub(r"(?m)^[ \t]*\d[\d \t]{0,10}[ \t]*$", "", text)
    text = remover_digitos_isolados(text)
    text = re.sub(r"[ \t]+([,.;:!?])", r"\1", text)
    text = re.sub(r"([!?;:])(?=[^\s\n])", r"\1 ", text)
    text = re.sub(r"[ \t]*[\u2013\u2014][ \t]*", " \u2014 ", text)
    return re.sub(r"[ \t]{2,}", " ", text)


def normalize_for_narration(text: str, language: str = "pt") -> str:
    """Normalize unambiguous patterns while preserving the author's meaning."""
    text = sanitize_text_noise(text)
    text = re.sub(r"(?m)^\s*([*_=-])\1{2,}\s*$", "", text)
    text = re.sub(r"(?<=\w)-[ \t]*\n[ \t]*(?=\w)", "", text)
    # Remove quebras de linha indevidas no meio de frases (comum em PDFs)
    text = re.sub(r"(?<=\w)[ \t]*\n[ \t]*(?=\w)", " ", text)
    text = re.sub(r"[ \t]+\n", "\n", text)
    text = re.sub(r"\n{3,}", "\n\n", text)
    text = re.sub(r"(?<!\.)\.{3}(?!\.)", ". ", text)
    text = re.sub(r"(?<!\w)_([^_\n]+)_(?!\w)", r"\1", text)
    if language.lower().startswith("pt"):
        text = _normalize_pt(text)
    return re.sub(r"[ \t]{2,}", " ", text).strip()


def _normalize_pt(text: str) -> str:
    text = re.sub(
        r"\b(cap[i\u00ed]tulo)\s+([ivxlcdm]+)\b",
        lambda m: m.group(1) + " " + number_pt(_roman_to_int(m.group(2))),
        text,
        flags=re.IGNORECASE,
    )
    replacements = (
        (r"\bDr\.\s+", "doutor "),
        (r"\bDra\.\s+", "doutora "),
        (r"\bSr\.\s+", "senhor "),
        (r"\bSra\.\s+", "senhora "),
        (r"\bcap\.\s*(\d+)\b", lambda m: "cap\u00edtulo " + number_pt(int(m.group(1)))),
        (r"\bn[\u00ba\u00b0]\s*(\d+)\b", lambda m: "n\u00famero " + number_pt(int(m.group(1)))),
        (r"\b(\d+)\s*%", lambda m: number_pt(int(m.group(1))) + " por cento"),
        (r"\b(\d+)\s*km\b", lambda m: number_pt(int(m.group(1))) + " quil\u00f4metros"),
    )
    for pattern, replacement in replacements:
        text = re.sub(pattern, replacement, text, flags=re.IGNORECASE)
    text = re.sub(
        r"R\$\s*([\d.]+)(?:,(\d{2}))?",
        _currency_pt,
        text,
    )
    return re.sub(
        r"\b(0?[1-9]|[12]\d|3[01])/(0?[1-9]|1[0-2])/(\d{4})\b",
        _date_pt,
        text,
    )


def number_pt(value: int) -> str:
    if value < 0:
        return "menos " + number_pt(-value)
    units = ("zero", "um", "dois", "tr\u00eas", "quatro", "cinco", "seis", "sete", "oito", "nove")
    teens = ("dez", "onze", "doze", "treze", "quatorze", "quinze", "dezesseis", "dezessete", "dezoito", "dezenove")
    tens = ("", "", "vinte", "trinta", "quarenta", "cinquenta", "sessenta", "setenta", "oitenta", "noventa")
    hundreds = ("", "cento", "duzentos", "trezentos", "quatrocentos", "quinhentos", "seiscentos", "setecentos", "oitocentos", "novecentos")
    if value < 10:
        return units[value]
    if value < 20:
        return teens[value - 10]
    if value < 100:
        return tens[value // 10] + ((" e " + units[value % 10]) if value % 10 else "")
    if value == 100:
        return "cem"
    if value < 1000:
        return hundreds[value // 100] + ((" e " + number_pt(value % 100)) if value % 100 else "")
    if value < 1_000_000:
        prefix = "mil" if value < 2000 else number_pt(value // 1000) + " mil"
        remainder = value % 1000
        return prefix + ((" e " if remainder < 100 else " ") + number_pt(remainder) if remainder else "")
    return str(value)


def _roman_to_int(value: str) -> int:
    values = {"I": 1, "V": 5, "X": 10, "L": 50, "C": 100, "D": 500, "M": 1000}
    total = 0
    previous = 0
    for char in reversed(value.upper()):
        current = values[char]
        total += -current if current < previous else current
        previous = max(previous, current)
    return total


def _currency_pt(match) -> str:
    reais = int(match.group(1).replace(".", ""))
    centavos = int(match.group(2) or "0")
    result = number_pt(reais) + (" real" if reais == 1 else " reais")
    if centavos:
        result += " e " + number_pt(centavos) + (" centavo" if centavos == 1 else " centavos")
    return result


def _date_pt(match) -> str:
    months = ("", "janeiro", "fevereiro", "mar\u00e7o", "abril", "maio", "junho", "julho", "agosto", "setembro", "outubro", "novembro", "dezembro")
    return f"{number_pt(int(match.group(1)))} de {months[int(match.group(2))]} de {number_pt(int(match.group(3)))}"

"""Dicionário de pronúncia do usuário (espelho do PronunciationDictionary.kt).

Antes da síntese cada palavra/expressão do dicionário é trocada pela grafia em que deve ser FALADA.
Palavra inteira, sem diferenciar maiúsculas/minúsculas, espaços internos flexíveis, expressão mais
longa vence e a troca é feita em uma única passada.
"""
import hashlib
import json
import re

_PATTERN = None
_SPOKEN = {}
_ASSINATURA = ""


def _colapsar(texto: str) -> str:
    return re.sub(r"\s+", " ", texto.strip())


def configurar(json_str: str) -> None:
    """Recebe o JSON do app ([{"word","spoken"}]); JSON inválido ou vazio zera o dicionário."""
    global _PATTERN, _SPOKEN, _ASSINATURA
    entradas = {}
    try:
        for item in json.loads(json_str or "[]"):
            palavra = _colapsar(str(item.get("word", "")))
            falada = str(item.get("spoken", "")).strip()
            if palavra and falada:
                entradas[palavra.lower()] = (palavra, falada)  # a última definição vence
    except (ValueError, AttributeError, TypeError):
        entradas = {}
    _SPOKEN = {chave: falada for chave, (_, falada) in entradas.items()}
    if not entradas:
        _PATTERN, _ASSINATURA = None, ""
        return
    ordenadas = sorted((p for p, _ in entradas.values()), key=len, reverse=True)
    alternancia = "|".join(r"\s+".join(re.escape(parte) for parte in p.split(" ")) for p in ordenadas)
    _PATTERN = re.compile(r"(?<![\w])(?:%s)(?![\w])" % alternancia, re.IGNORECASE)
    _ASSINATURA = hashlib.sha256(
        json.dumps(sorted(_SPOKEN.items()), ensure_ascii=False).encode("utf-8")
    ).hexdigest()[:8]


def aplicar(texto: str) -> str:
    if _PATTERN is None or not texto:
        return texto
    return _PATTERN.sub(lambda m: _SPOKEN.get(_colapsar(m.group(0)).lower(), m.group(0)), texto)


def assinatura() -> str:
    """Identifica o conteúdo atual (vazia sem dicionário); entra no hash do job de conversão."""
    return _ASSINATURA

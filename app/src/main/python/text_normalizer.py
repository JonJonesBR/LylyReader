"""
text_normalizer.py - Normalização de texto pré-G2P para TTS.

Consolida e expande as normalizações existentes (narration_normalizer.py,
text_processor.py) em um único ponto de entrada, adicionando cobertura para
casos críticos de audiobook: moedas, números, abreviações, siglas, símbolos,
datas, ordinais, unidades.

Chamado antes de qualquer G2P, em todos os pipelines (local e remoto).
"""

from __future__ import annotations

import difflib
import re
import unicodedata
from typing import List


# ═══════════════════════════════════════════════════════════════════════════════
#  Constantes
# ═══════════════════════════════════════════════════════════════════════════════

_MESES_PT = (
    "", "janeiro", "fevereiro", "março", "abril", "maio", "junho",
    "julho", "agosto", "setembro", "outubro", "novembro", "dezembro",
)

_UNIDADES_PT = (
    "zero", "um", "dois", "três", "quatro", "cinco", "seis", "sete", "oito", "nove"
)

_TEENS_PT = (
    "dez", "onze", "doze", "treze", "quatorze", "quinze",
    "dezesseis", "dezessete", "dezoito", "dezenove",
)

_DEZENAS_PT = (
    "", "", "vinte", "trinta", "quarenta", "cinquenta",
    "sessenta", "setenta", "oitenta", "noventa",
)

_CENTENAS_PT = (
    "", "cento", "duzentos", "trezentos", "quatrocentos",
    "quinhentos", "seiscentos", "setecentos", "oitocentos", "novecentos",
)

_ORDINAIS_PT_M = (
    "", "primeiro", "segundo", "terceiro", "quarto", "quinto",
    "sexto", "sétimo", "oitavo", "nono", "décimo",
)

_ORDINAIS_PT_F = (
    "", "primeira", "segunda", "terceira", "quarta", "quinta",
    "sexta", "sétima", "oitava", "nona", "décima",
)


_ORD_DEZENAS_PT = (
    "", "décimo", "vigésimo", "trigésimo", "quadragésimo", "quinquagésimo",
    "sexagésimo", "septuagésimo", "octogésimo", "nonagésimo",
)
_ORD_CENTENAS_PT = (
    "", "centésimo", "ducentésimo", "trecentésimo", "quadringentésimo", "quingentésimo",
    "sexcentésimo", "septingentésimo", "octingentésimo", "noningentésimo",
)


def _ordinal_masculino(n: int) -> str:
    """1..999 → "sexagésimo terceiro"."""
    c, resto = divmod(n, 100)
    d, u = divmod(resto, 10)
    partes = [_ORD_CENTENAS_PT[c], _ORD_DEZENAS_PT[d], _ORDINAIS_PT_M[u]]
    return " ".join(p for p in partes if p)


# ═══════════════════════════════════════════════════════════════════════════════
#  Números por extenso (PT-BR)
# ═══════════════════════════════════════════════════════════════════════════════

def _unidade(n: int, fem: bool) -> str:
    return {1: "uma", 2: "duas"}[n] if fem and n in (1, 2) else _UNIDADES_PT[n]


def _numero_extenso(valor: int, fem: bool = False) -> str:
    """Cardinal por extenso. [fem]=True concorda com substantivo feminino ("duas casas", "duzentas páginas")."""
    if valor < 0:
        return "menos " + _numero_extenso(-valor, fem)
    if valor < 10:
        return _unidade(valor, fem)
    if valor < 20:
        return _TEENS_PT[valor - 10]
    if valor < 100:
        d = _DEZENAS_PT[valor // 10]
        u = valor % 10
        return d + (f" e {_unidade(u, fem)}" if u else "")
    if valor == 100:
        return "cem"
    if valor < 1000:
        c = _CENTENAS_PT[valor // 100]
        if fem and valor // 100 >= 2:
            c = c.replace("ntos", "ntas")
        r = valor % 100
        return c + (f" e {_numero_extenso(r, fem)}" if r else "")
    if valor < 1_000_000:
        m = valor // 1000
        r = valor % 1000
        prefixo = "mil" if m == 1 else _numero_extenso(m, fem) + " mil"
        if r:
            # "mil e cem", "mil e quinhentos", "mil e vinte"; mas "mil duzentos e trinta"
            conj = " e " if (r < 100 or r % 100 == 0) else " "
            return prefixo + conj + _numero_extenso(r, fem)
        return prefixo
    if valor < 1_000_000_000:
        m = valor // 1_000_000
        r = valor % 1_000_000
        prefixo = "um milhão" if m == 1 else _numero_extenso(m) + " milhões"
        if r:
            conj = " e " if (r < 100 or r % 100 == 0) else " "
            return prefixo + conj + _numero_extenso(r, fem)
        return prefixo
    return str(valor)


# Substantivos masculinos que aceitam ordinal grande ("233º batalhão"); sem eles, "360º" é lido como graus.
_SUBSTANTIVOS_ORDINAIS = frozenset("""
batalhão regimento lugar dia ano andar aniversário século distrito pelotão grupo exército corpo livro capítulo volume
artigo parágrafo título tomo episódio pavimento canto ato esquadrão destacamento colocado classificado
""".split())

# Substantivos femininos que costumam vir depois de número ("duas casas", "uma vez"). Fora da lista vale o
# masculino; palavras com os sufixos abaixo (-ção, -dade, -agem…) são tratadas como femininas.
_FEMININOS = frozenset("""
hora horas vez vezes pessoa pessoas semana semanas mulher mulheres casa casas noite noites página páginas carta
cartas linha linhas palavra palavras coisa coisas criança crianças moeda moedas porta portas folha folhas mão mãos
foto fotos frase frases estrela estrelas lua luas vítima vítimas câmara câmaras câmera câmeras cópia cópias família
famílias história histórias tábua tábuas pedra pedras sala salas cadeira cadeiras mesa mesas garrafa garrafas tarde
tardes manhã manhãs semente sementes rua ruas janela janelas parede paredes árvore árvores flor flores nave naves
arma armas mala malas caixa caixas chave chaves luz luzes voz vozes amiga amigas irmã irmãs filha filhas mãe mães
tia tias avó avós esposa esposas rainha rainhas princesa princesas bruxa bruxas cidade cidades pergunta perguntas
resposta respostas notícia notícias ideia ideias razão razões lágrima lágrimas perna pernas orelha orelhas unha unhas
cabeça cabeças espada espadas pista pistas ordem ordens vida vidas morte mortes guerra guerras batalha batalhas
missão missões nação nações canção canções
""".split())
_SUFIXOS_FEMININOS = ("ção", "ções", "são", "sões", "dade", "dades", "agem", "agens", "ência", "ências", "ância", "âncias")


def _proxima_palavra(texto: str, pos: int) -> str:
    m = re.match(r"[ \t]+([^\W\d_]+)", texto[pos:])
    return m.group(1).lower() if m else ""


def _concorda_feminino(palavra: str) -> bool:
    return palavra in _FEMININOS or palavra.endswith(_SUFIXOS_FEMININOS)


def _digitos_extenso(digitos: str) -> str:
    """Lê dígito a dígito ('007' → 'zero zero sete'), para códigos e números muito longos."""
    return " ".join(_UNIDADES_PT[int(d)] for d in digitos)


def _inteiro_texto_extenso(digitos: str, agrupado: bool = False) -> str:
    """Inteiro por extenso. Sem separador de milhar, mais de 6 dígitos é código (lido dígito a dígito);
    com separador ('1.234.567') é quantidade, até 9 dígitos."""
    if len(digitos) > (9 if agrupado else 6):
        return _digitos_extenso(digitos)
    return _numero_extenso(int(digitos))


def _parte_decimal_extenso(digitos: str) -> str:
    """'5' → 'cinco', '05' → 'zero cinco', '50' → 'cinquenta'; mais de 3 dígitos, um a um."""
    if len(digitos) > 3:
        return _digitos_extenso(digitos)
    zeros = len(digitos) - len(digitos.lstrip("0"))
    resto = digitos.lstrip("0")
    return " ".join(["zero"] * zeros + ([_numero_extenso(int(resto))] if resto else []))


def _decimal_extenso(num) -> str:
    """Converte '1,5' → 'um vírgula cinco', '2,05' → 'dois vírgula zero cinco'.

    Aceita o texto do número (preferível: preserva '3,50') ou float. Vírgula é sempre decimal;
    ponto seguido de exatamente 3 dígitos é milhar ('1.234' → 1234); demais pontos, decimal.
    """
    texto = num if isinstance(num, str) else repr(float(num))
    texto = texto.strip()
    if "," in texto:
        parte_int, _, parte_dec = texto.partition(",")
        parte_int = parte_int.replace(".", "")
    elif re.fullmatch(r"\d{1,3}(?:\.\d{3})+", texto):
        parte_int, parte_dec = texto.replace(".", ""), ""
    else:
        parte_int, _, parte_dec = texto.partition(".")
    parte_int = parte_int or "0"
    agrupado = bool(re.fullmatch(r"\d{1,3}(?:\.\d{3})+(?:,\d+)?", texto))
    if not parte_dec.strip("0"):
        return _inteiro_texto_extenso(parte_int, agrupado)
    return f"{_inteiro_texto_extenso(parte_int, agrupado)} vírgula {_parte_decimal_extenso(parte_dec)}"


# ═══════════════════════════════════════════════════════════════════════════════
#  Utilitários
# ═══════════════════════════════════════════════════════════════════════════════

def _romano_para_int(r: str) -> int:
    valores = {"I": 1, "V": 5, "X": 10, "L": 50, "C": 100, "D": 500, "M": 1000}
    total = 0
    anterior = 0
    for char in reversed(r.upper()):
        atual = valores[char]
        total += -atual if atual < anterior else atual
        anterior = max(anterior, atual)
    return total


# ═══════════════════════════════════════════════════════════════════════════════
#  Sanitização (ruído de extração, unicode)
# ═══════════════════════════════════════════════════════════════════════════════

# Caracteres invisíveis que os EPUBs trazem dentro das palavras: hífen de quebra opcional (&shy;,
# "es\u00adtá"), larguras zero, marcas de direção e BOM. Na tela não aparecem, mas chegavam ao motor
# (nem a extração do EPUB nem o pré-processamento nativo do Supertonic os tiram): fora do vocabulário,
# o Supertonic os troca pelo token 0 NO MEIO da palavra (rust/src/helper.rs, UnicodeProcessor::call).
_INVISIVEIS = re.compile(r"[\u00ad\u200b-\u200f\u202a-\u202e\u2060-\u2064\u2066-\u2069\ufeff]")


def _sanitizar(texto: str) -> str:
    """Remove ruído de extração de documentos."""
    t = texto.replace("\u00a0", " ").replace("\u202f", " ")
    t = t.replace("\uf0fe", "").replace("\ufffe", "")
    t = _INVISIVEIS.sub("", t)
    t = re.sub(r"[\u2122\u00a9\u00ae]", "", t)  # marca/copyright: o motor leria o símbolo
    t = re.sub(r"(?<=\d)\s?\u00b0(?![CcFf])", " graus", t)  # 360° → 360 graus
    t = t.replace("\u201c", '"').replace("\u201d", '"')
    t = t.replace("\u2018", "'").replace("\u2019", "'")
    t = re.sub(r"https?://\S+|www\.\S+", "", t, flags=re.IGNORECASE)
    t = re.sub(r"\b[\w.+-]+@[\w.-]+\.[A-Za-z]{2,}\b", "", t)
    t = re.sub(r"[ \t]+([,.;:!?])", r"\1", t)
    # espaço depois de pontuação colada; horas "7:45" (dígito:dígito) ficam intactas
    t = re.sub(
        r"(?<!\d)([!?;:])(?=[^\s\n])|(?<=\d)([!?;:])(?=[^\s\n\d])",
        lambda m: (m.group(1) or m.group(2)) + " ",
        t,
    )
    t = re.sub(r"[ \t]*[\u2013\u2014][ \t]*", " — ", t)
    t = re.sub(r"(?m)^\s*([*_=-])\1{2,}\s*$", "", t)
    t = re.sub(r"(?<=\w)-[ \t]*\n[ \t]*(?=\w)", "", t)
    t = re.sub(r"(?<=\w)[ \t]*\n[ \t]*(?=\w)", " ", t)
    t = re.sub(r"[ \t]+\n", "\n", t)
    t = re.sub(r"\n{3,}", "\n\n", t)
    t = re.sub(r"(?<!\.)\.{3}(?!\.)", ". ", t)
    t = re.sub(r"(?<!\w)_([^_\n]+)_(?!\w)", r"\1", t)
    t = re.sub(r"[ \t]{2,}", " ", t)
    return t.strip()


# ═══════════════════════════════════════════════════════════════════════════════
#  Normalizações específicas PT-BR
# ═══════════════════════════════════════════════════════════════════════════════

def _expandir_abreviacoes_pt(texto: str) -> str:
    """Expande abreviações comuns do português."""
    subs: List[tuple] = [
        (r"\bDr\.\s+", "doutor "),
        (r"\bDra\.\s+", "doutora "),
        (r"\bSr\.\s+", "senhor "),
        (r"\bSra\.\s+", "senhora "),
        (r"\bSrta\.\s+", "senhorita "),
        (r"\bProf\.\s+", "professor "),
        (r"\bProfa\.\s+", "professora "),
        # "Cap. 7" / "cap. IV" é capítulo; "Cap. Nemo" segue sendo capitão.
        (r"\b[Cc]ap\.\s+(?=\d|[IVXLC]+\b)", "capítulo "),
        (r"\bCap\.\s+", "capitão "),
        (r"\bCel\.\s+", "coronel "),
        (r"\bMaj\.\s+", "major "),
        (r"\bTen\.\s+", "tenente "),
        (r"\bEng\.\s+", "engenheiro "),
        (r"\bAv\.\s+", "avenida "),
        (r"\bR\.\s*Av\.\s+", "rua avenida "),
        (r"\bR\.\s+", "rua "),
        (r"\bPç(a)?\.\s+", "praça "),
        (r"\bLgo\.\s+", "largo "),
        (r"\bPág\.\s+", "página "),
        (r"\bpág\.\s+", "página "),
        (r"\bEtc\.\s+", "etcetera "),
        (r"\betc\.\s+", "etcetera "),
        (r"\bVol\.\s+", "volume "),
        (r"\bArt\.\s+", "artigo "),
        (r"\bNº\s+", "número "),
        (r"\bnº\s+", "número "),
        (r"\bSta\.\s+", "santa "),
        (r"\bSto\.\s+", "santo "),
    ]
    # "S. Paulo", "S. João": abreviação de "são" diante de nome próprio (maiúscula). Os pontos
    # cardeais N./S./L./O. foram retirados: sozinhos são ambíguos com iniciais e com "São".
    texto = re.sub(r"\bS\.\s*(?=[A-ZÁÀÂÃÉÊÍÓÔÕÚÇ])", "são ", texto)
    for padrao, repl in subs:
        texto = re.sub(padrao, repl, texto, flags=re.IGNORECASE)
    return texto


def _expandir_abreviacoes_en(texto: str) -> str:
    """Expande abreviações comuns do inglês."""
    subs: List[tuple] = [
        (r"\bMr\.\s+", "mister "),
        (r"\bMrs\.\s+", "missus "),
        (r"\bMs\.\s+", "miss "),
        (r"\bDr\.\s+", "doctor "),
        (r"\bProf\.\s+", "professor "),
        (r"\bvs\.\s+", "versus "),
        (r"\be\.g\.\s+", "for example "),
        (r"\bi\.e\.\s+", "that is "),
        (r"\betc\.\s+", "etcetera "),
        (r"\bSt\.\s+", "saint "),
        (r"\bAve\.\s+", "avenue "),
        (r"\bDept\.\s+", "department "),
        (r"\bInc\.\s+", "incorporated "),
        (r"\bLtd\.\s+", "limited "),
        (r"\bCorp\.\s+", "corporation "),
    ]
    for padrao, repl in subs:
        texto = re.sub(padrao, repl, texto, flags=re.IGNORECASE)
    return texto


def _expandir_numeros_pt(texto: str) -> str:
    """Expande números, ordinais, porcentagens, moedas, datas, unidades."""

    # Ordinais: 1º → primeiro, 2ª → segunda
    def _ordinal(m):
        n = int(m.group(1))
        if n < 1 or n > 999:
            return m.group(0)
        if m.group(2) == "º" and 100 <= n <= 360 and _proxima_palavra(m.string, m.end()) not in _SUBSTANTIVOS_ORDINAIS:
            return _numero_extenso(n) + " graus"  # "360º" é ângulo; "233º batalhão" é ordinal
        masc = _ordinal_masculino(n)
        if m.group(2) == "ª":
            return " ".join(p[:-1] + "a" for p in masc.split())
        return masc

    texto = re.sub(r"(\d+)\.?([ºª])", _ordinal, texto)

    # Horas: 10h30 → dez horas e trinta minutos; 7:45 → sete horas e quarenta e cinco minutos
    def _hora(m):
        h = int(m.group(1))
        mi = int(m.group(2)) if m.group(2) else 0
        if h > 24 or mi > 59:
            return m.group(0)
        nome_h = {1: "uma", 2: "duas"}.get(h) or _numero_extenso(h)
        partes = [nome_h + (" hora" if h == 1 else " horas")]
        if mi:
            partes.append("e " + _numero_extenso(mi) + (" minuto" if mi == 1 else " minutos"))
        return " ".join(partes)

    texto = re.sub(r"(?<![\w:])(\d{1,2})h(\d{2})?(?![\w])", _hora, texto)
    texto = re.sub(r"(?<![\w:.,])(\d{1,2}):(\d{2})(?![\w:.,]\d)", _hora, texto)

    # Milhar separado por espaço (PDF): 12 000 → 12000
    texto = re.sub(
        r"(?<![\w.,])\d{1,3}(?:[ \u00a0\u202f]\d{3})+(?![\w]|[.,]\d)",
        lambda m: re.sub(r"[ \u00a0\u202f]", "", m.group(0)),
        texto,
    )

    # Negativos: -5 → menos 5 (só depois de espaço/abre-parêntese, para não atingir intervalos 10-20)
    texto = re.sub(r"(?<![\w\d])(?<=[\s(])-(?=\d)", "menos ", texto)

    # Porcentagem: 50% → cinquenta por cento
    texto = re.sub(
        r"\b(\d+(?:[.,]\d+)?)\s*%",
        lambda m: _decimal_extenso(m.group(1)) + " por cento",
        texto,
    )

    # Unidades (PT-BR): 10km → dez quilômetros
    unidades = {
        "km": "quilômetros",
        "m": "metros",
        "cm": "centímetros",
        "mm": "milímetros",
        "kg": "quilos",
        "g": "gramas",
        "mg": "miligramas",
        "l": "litros",
        "ml": "mililitros",
        "°C": "graus celsius",
        "°F": "graus fahrenheit",
        "km/h": "quilômetros por hora",
    }
    for abrev, extenso in sorted(unidades.items(), key=lambda x: -len(x[0])):
        texto = re.sub(
            rf"\b(\d+(?:[.,]\d+)?)\s*{re.escape(abrev)}\b",
            lambda m, e=extenso: _decimal_extenso(m.group(1)) + " " + e,
            texto,
            flags=re.IGNORECASE,
        )

    # Moeda R$: R$ 1.234,56
    def _moeda_real(m):
        reais = int(m.group(1).replace(".", ""))
        centavos = int(m.group(2) or "0")
        result = _numero_extenso(reais) + (" real" if reais == 1 else " reais")
        if centavos:
            result += " e " + _numero_extenso(centavos) + (" centavo" if centavos == 1 else " centavos")
        return result

    texto = re.sub(r"R\$\s*([\d.]+)(?:,(\d{2}))?", _moeda_real, texto)

    # Outras moedas: $, €, £

    _UNIDADE = {"$": ("dólar", "dólares"), "€": ("euro", "euros"), "£": ("libra", "libras")}

    def _valor_moeda(valor, simbolo):
        inteiro_txt, _, cent_txt = valor.partition(",")
        inteiro = int(inteiro_txt.replace(".", "") or "0")
        centavos = int((cent_txt + "0")[:2]) if cent_txt else 0
        singular, plural = _UNIDADE[simbolo]
        texto_valor = _numero_extenso(inteiro) + " " + (singular if inteiro == 1 else plural)
        if centavos:
            texto_valor += " e " + _numero_extenso(centavos) + (" centavo" if centavos == 1 else " centavos")
        return texto_valor

    def _moeda_outra(m):
        return _valor_moeda(m.group(1), m.group(2))

    texto = re.sub(r"(?<![\d.,])(\d[\d.]*(?:,\d{1,2})?)\s*([$€£])", _moeda_outra, texto)
    texto = re.sub(
        r"(?:US)?([$€£])\s*(\d[\d.]*(?:,\d{1,2})?)",
        lambda m: _valor_moeda(m.group(2), m.group(1)),
        texto,
    )

    # Datas DD/MM/YYYY
    def _data_pt(m):
        d, mes, a = int(m.group(1)), int(m.group(2)), int(m.group(3))
        return f"{_numero_extenso(d)} de {_MESES_PT[mes]} de {_numero_extenso(a)}"

    texto = re.sub(r"\b(0?[1-9]|[12]\d|3[01])/(0?[1-9]|1[0-2])/(\d{4})\b", _data_pt, texto)

    # Capítulo N → capítulo [extenso]
    texto = re.sub(
        r"\b(cap[ií]tulo)\s+(\d+)\b",
        lambda m: m.group(1) + " " + _numero_extenso(int(m.group(2))),
        texto,
        flags=re.IGNORECASE,
    )

    # Romanos em contexto de seção: Capítulo X, Parte II
    texto = re.sub(
        r"\b(cap[íi]tulo|parte|livro|se[çc][ãa]o|ato|cena)\s+(X{1,3}(?:IX|IV|VI?)?|IX|IV|VI?|I{1,3})\b",
        lambda m: m.group(1) + " " + _numero_extenso(_romano_para_int(m.group(2))),
        texto,
        flags=re.IGNORECASE,
    )

    # Romanos maiúsculos depois de século/tomo/volume/título/canto/artigo: século XXI → século vinte e um
    texto = re.sub(
        r"\b(s[ée]culo|tomo|volume|t[íi]tulo|canto|artigo|milênio)\s+([IVXLCDM]{1,8})\b",
        lambda m: m.group(1) + " " + _numero_extenso(_romano_para_int(m.group(2))),
        texto,
        flags=re.IGNORECASE,
    )

    return texto


def _expandir_simbolos(texto: str) -> str:
    """Expande símbolos comuns."""
    # Espaço ao redor do símbolo é absorvido pela troca: "Tom & Jerry" → "Tom e Jerry" (sem espaço duplo).
    subs = [
        (r"[ \t]*&[ \t]*", " e "),
        (r"[ \t]*@[ \t]*", " arroba "),
        (r"[ \t]*\+[ \t]*", " mais "),
        (r"[ \t]*=[ \t]*", " igual "),
        (r"[ \t]*#[ \t]*", " número "),
    ]
    for padrao, repl in subs:
        texto = re.sub(padrao, repl, texto)
    return texto


# ═══════════════════════════════════════════════════════════════════════════════
#  Siglas
# ═══════════════════════════════════════════════════════════════════════════════

_SIGLAS_LIDAS_COMO_PALAVRA = frozenset({
    "IBGE", "PETROBRAS", "EMBRAER", "FIESP", "SESI", "SENAI", "SENAC",
    "SESC", "IPTU", "IPVA", "FGTS", "PIS", "COFINS", "ICMS", "IPI",
    "UNESCO", "UNICEF", "ONU", "UE", "NASA", "NATO", "OPEP", "MERCOSUL",
    "BNDES", "INSS", "SUS", "FAB", "MEC",
    "OTAN", "FIFA", "AIDS", "OVNI", "DETRAN", "PROCON", "ANVISA", "IBAMA", "FUNAI", "ENEM",
    "INMETRO", "SEBRAE", "EMBRAPA", "CAPES", "USP", "UNICAMP", "UNESP", "UNIFESP", "FUVEST",
    "PUC", "ONG", "ABIN", "DOPS", "FEBEM", "INPE", "IPEA", "ANATEL", "ANEEL", "ANAC", "BOPE",
    "SAMU", "VIP", "NAFTA", "OPEC", "LASER", "RADAR", "SONAR", "UNAM", "SIDA",
})

_SIGLAS_SOLETRADAS = frozenset({
    "CPF", "RG", "CNPJ", "SSP", "TV", "HD", "DVD", "CD", "USB",
    "CPU", "RAM", "HDMI", "FPS", "RGB", "CEO", "CTO", "CFO",
    "HR", "RH", "TI", "EUA", "UK", "US", "BRL", "USD", "EUR",
    "STF", "STJ", "TSE", "TST", "TRF", "TJ", "OAB", "PT", "PSDB", "MDB", "PSOL", "PF", "PM",
    "PIB", "CEP", "CNH", "UTI", "SP", "RJ", "MG", "DF", "CBF", "ABL", "ABNT", "UFRJ", "UFMG",
    "DNA", "RNA", "HIV", "FBI", "CIA", "KGB", "BBC", "CNN", "IBM", "NBA", "NFL", "MIT", "URSS",
    "PC", "PDF", "SMS", "GPS", "DJ", "VHS", "MPB", "EEUU", "USA",
})

# Palavras curtas e frequentes (pt, en, es) que podem aparecer em títulos e nunca são siglas.
_PALAVRAS_CURTAS_COMUNS = frozenset("""
a o e é os as um uns de da do das dos em no na nos nas ao à aos às por que se me te ti lhe eu tu ele
vi ir vou vai foi ser era ver dar sou há boa bom mau má lar ar rio mal bem tão mim
ela nós vós eles elas meu teu seu sua mas nem ou já não sim lá cá aí ali só sem com pra pro mar sol
lua céu rei paz fim dia mês ano vez voz luz mãe pai avó avô fé dor amor vida
the of and to in on at by for an as is it be we he she you me my his her our its not no yes all
end day sun sea war man men god old new one two red
el la lo los las un una y del al en con por para su sus mi tu yo es son no si sí más fin día rey dios
""".split())

_NOMES_DAS_LETRAS = {
    "pt": {
        "A": "á", "B": "bê", "C": "cê", "D": "dê", "E": "é", "F": "efe", "G": "gê", "H": "agá", "I": "i",
        "J": "jota", "K": "cá", "L": "ele", "M": "eme", "N": "ene", "O": "ó", "P": "pê", "Q": "quê",
        "R": "erre", "S": "esse", "T": "tê", "U": "u", "V": "vê", "W": "dáblio", "X": "xis", "Y": "ípsilon",
        "Z": "zê",
    },
    "en": {
        "A": "ay", "B": "bee", "C": "see", "D": "dee", "E": "ee", "F": "ef", "G": "jee", "H": "aitch",
        "I": "eye", "J": "jay", "K": "kay", "L": "el", "M": "em", "N": "en", "O": "oh", "P": "pee",
        "Q": "cue", "R": "ar", "S": "ess", "T": "tee", "U": "you", "V": "vee", "W": "double you", "X": "ex",
        "Y": "why", "Z": "zee",
    },
    "es": {
        "A": "a", "B": "be", "C": "ce", "D": "de", "E": "e", "F": "efe", "G": "ge", "H": "hache", "I": "i",
        "J": "jota", "K": "ka", "L": "ele", "M": "eme", "N": "ene", "O": "o", "P": "pe", "Q": "cu",
        "R": "erre", "S": "ese", "T": "te", "U": "u", "V": "uve", "W": "uve doble", "X": "equis", "Y": "ye",
        "Z": "zeta",
    },
}

_PALAVRA = re.compile(r"[^\W\d_]+")
_ROMANO_IVX = re.compile(r"[IVX]{2,}")
_VOGAIS = frozenset("AEIOUYÁÉÍÓÚÂÊÔÃÕÀÜ")


# ═══════════════════════════════════════════════════════════════════════════════
#  Vocabulário do livro
# ═══════════════════════════════════════════════════════════════════════════════
# Palavras que o livro escreve em minúsculas em algum lugar: "CASA" num título é palavra porque
# "casa" aparece no texto; "OAB" nunca aparece assim, então é sigla. Guarda os últimos livros
# registrados (leitura guiada e conversão podem estar abertas ao mesmo tempo, com livros diferentes).

_MAX_VOCABULARIOS = 3
_vocabularios: dict = {}
_vocabulario_unido: frozenset = frozenset()


def registrar_vocabulario(chave: str, texto: str) -> int:
    """Registra as palavras que [texto] escreve com minúsculas. Devolve quantas são."""
    global _vocabulario_unido
    palavras = frozenset(
        m.group(0).lower() for m in _PALAVRA.finditer(texto or "") if not m.group(0).isupper()
    )
    _vocabularios.pop(chave, None)
    _vocabularios[chave] = palavras
    while len(_vocabularios) > _MAX_VOCABULARIOS:
        _vocabularios.pop(next(iter(_vocabularios)))
    _vocabulario_unido = frozenset().union(*_vocabularios.values())
    return len(palavras)


def esquecer_vocabularios() -> None:
    global _vocabulario_unido
    _vocabularios.clear()
    _vocabulario_unido = frozenset()


def _eh_sigla_conhecida(palavra: str) -> bool:
    return palavra in _SIGLAS_LIDAS_COMO_PALAVRA or palavra in _SIGLAS_SOLETRADAS


def _eh_palavra(palavra: str) -> bool:
    minuscula = palavra.lower()
    return minuscula in _vocabulario_unido or minuscula in _PALAVRAS_CURTAS_COMUNS


def _le_como_palavra(w: str, em_sequencia: bool) -> bool:
    """Decide se a palavra maiúscula [w] (2+ letras) é lida como palavra ou fica como sigla."""
    if em_sequencia and _eh_palavra(w):
        return True  # título: "PARA TI", "TAKE US HOME" (o vocabulário vence a lista de siglas)
    if _eh_sigla_conhecida(w) or not any(c in _VOGAIS for c in w):
        return False  # "OAB", "STF", "CNPJ"
    if _eh_palavra(w):
        return True  # "viu A CASA"
    if em_sequencia:
        # Num título, palavra curta que o livro nunca escreve em minúsculas é sigla ("A HISTÓRIA DA
        # OAB"). Sem vocabulário registrado não há como saber: lê como palavra.
        return len(w) >= 4 or not _vocabulario_unido
    return len(w) >= 5


def _suavizar_caixa_alta(texto: str) -> str:
    """Passa para minúsculas o texto escrito em CAIXA ALTA (títulos, gritos).

    Sem isso, "O DIA EM QUE TUDO MUDOU" virava "dê i á é eme..." no passo de siglas, e os
    motores soletravam o resto. Continua em maiúsculas (para o passo de siglas) o que parece
    sigla (ver _le_como_palavra) e o romano no fim de uma sequência ("CHAPTER XII", "LUÍS XIV").
    Letra solta ("A", "O") não forma sequência sozinha.
    """
    palavras = list(_PALAVRA.finditer(texto))
    if not palavras:
        return texto
    caixa_alta = [p.group(0).isupper() for p in palavras]
    # Sem nenhuma minúscula (título de uma palavra só, como "PRÓLOGO" ou "FIM"): tudo é caixa alta.
    so_caixa_alta = all(caixa_alta)
    # Sequências de palavras maiúsculas seguidas; conta as de 2+ letras em cada uma.
    sequencia = [0] * len(palavras)
    longas = [0]
    for i, maiuscula in enumerate(caixa_alta):
        if maiuscula and i > 0 and caixa_alta[i - 1]:
            sequencia[i] = sequencia[i - 1]
        elif maiuscula:
            sequencia[i] = len(longas)
            longas.append(0)
        if maiuscula and len(palavras[i].group(0)) > 1:
            longas[sequencia[i]] += 1
    partes = []
    fim_anterior = 0
    for i, p in enumerate(palavras):
        w = p.group(0)
        partes.append(texto[fim_anterior:p.start()])
        fim_anterior = p.end()
        if not caixa_alta[i]:
            partes.append(w)
            continue
        inicio = i == 0 or not caixa_alta[i - 1]
        em_sequencia = so_caixa_alta or longas[sequencia[i]] >= 2
        if len(w) == 1:
            # Letra solta só acompanha a sequência ("A CASA" → "a casa"); a do começo fica.
            partes.append(w.lower() if em_sequencia and i > 0 else w)
            continue
        # Romano fecha a sequência ("CHAPTER XII", "LUÍS XIV, O REI"): segue para os números/siglas.
        seguinte_colado = (
            i + 1 < len(palavras) and caixa_alta[i + 1]
            and not texto[p.end():palavras[i + 1].start()].strip()
        )
        romano = bool(_ROMANO_IVX.fullmatch(w)) and _romano(w) > 0
        romano_final = romano and not inicio and not seguinte_colado
        # Romano que abre o título ("XII", "XII. O RETORNO") segue para _expandir_romanos.
        romano_titulo = romano and i == 0 and (
            len(palavras) == 1 or texto[p.end():palavras[1].start()].strip()
        )
        if romano_final or romano_titulo:
            partes.append(w)
            continue
        # No meio de um título, "VI" de "EU VI O MAR" é palavra, não número nem sigla.
        if (em_sequencia and romano) or _le_como_palavra(w, em_sequencia):
            w = w.capitalize() if inicio else w.lower()
        partes.append(w)
    partes.append(texto[fim_anterior:])
    return "".join(partes)


# ═══════════════════════════════════════════════════════════════════════════════
#  Algarismos romanos (fora de "capítulo/século..." do português, tratados em _expandir_numeros_pt)
# ═══════════════════════════════════════════════════════════════════════════════

_ROMANO_VALIDO = re.compile(r"M{0,3}(?:CM|CD|D?C{0,3})(?:XC|XL|L?X{0,3})(?:IX|IV|V?I{0,3})")
_ORDINAIS_EN = (
    "", "first", "second", "third", "fourth", "fifth", "sixth", "seventh", "eighth", "ninth", "tenth",
    "eleventh", "twelfth",
)
_ORDINAIS_ES = (
    "", "primero", "segundo", "tercero", "cuarto", "quinto", "sexto", "séptimo", "octavo", "noveno", "décimo",
)
# Depois destas palavras o romano é cardinal em inglês ("World War two"), não "the second".
_ANTES_DE_CARDINAL_EN = frozenset({"War", "Phase", "Type", "Class", "Level", "Stage", "Mark", "Grade"})
_NOMES_FEMININOS = frozenset({"Isabel", "Elizabeth", "Elisabete", "Beatriz", "Leonor", "Ester"})
_SECOES = (
    r"chapter|part|book|volume|section|act|scene|canto|cap[íi]tulo|parte|libro|tomo|secci[óo]n|acto|escena"
)


def _romano(r: str) -> int:
    return _romano_para_int(r) if r and _ROMANO_VALIDO.fullmatch(r) else 0


def _cardinal(n: int, lang: str) -> str:
    if lang == "en":
        return _numero_extenso_en(n)
    if lang == "es":
        return _numero_extenso_es(n)
    return _numero_extenso(n)


def _ordinal_en(n: int) -> str:
    if n < len(_ORDINAIS_EN):
        return _ORDINAIS_EN[n]
    if n < 20:
        return _numero_extenso_en(n) + "th"  # thirteenth..nineteenth
    if n < 100:
        dezena, unidade = divmod(n, 10)
        if not unidade:
            return _DEZ_EN[dezena][:-1] + "ieth"  # twentieth, thirtieth
        return _DEZ_EN[dezena] + "-" + _ORDINAIS_EN[unidade]
    return _numero_extenso_en(n)


def _numero_de_nome(nome: str, n: int, lang: str) -> str:
    """Romano depois de nome: "Pedro I" → "primeiro", "Luís XIV" → "catorze", "Henry VIII" → "the eighth"."""
    if lang == "en":
        return _cardinal(n, lang) if nome in _ANTES_DE_CARDINAL_EN else "the " + _ordinal_en(n)
    if n > 10:
        return _cardinal(n, lang)
    ordinal = _ORDINAIS_ES[n] if lang == "es" else _ordinal_masculino(n)
    if nome.endswith("a") or nome in _NOMES_FEMININOS:
        return " ".join(p[:-1] + "a" for p in ordinal.split())
    return ordinal


def _expandir_romanos(texto: str, lang: str) -> str:
    """Romanos com contexto seguro: parágrafo só com o número, início de título ("XII. O retorno"),
    depois de seção (chapter, capítulo, libro...) e depois de nome ("Luís XIV", "Henry VIII")."""
    # Parágrafo que é só o romano ("XII", "IV."): título de capítulo.
    sozinho = re.fullmatch(r"(\s*)([IVXLCDM]+)(\s*[.:]?\s*)", texto)
    if sozinho and _romano(sozinho.group(2)):
        return sozinho.group(1) + _cardinal(_romano(sozinho.group(2)), lang) + sozinho.group(3)

    def _inicio(m):
        n = _romano(m.group(2))
        return m.group(1) + _cardinal(n, lang) + m.group(3) if n else m.group(0)

    texto = re.sub(r"(?m)^(\s*)([IVXL]{2,})(\s*[.:)\-–—]\s)", _inicio, texto)

    def _secao(m):
        n = _romano(m.group(2))
        return m.group(1) + " " + _cardinal(n, lang) if n else m.group(0)

    texto = re.sub(r"\b((?i:" + _SECOES + r"))\s+([IVXLCDM]+)\b", _secao, texto)

    def _nome(m):
        nome, romano = m.group(1), m.group(2)
        n = _romano(romano)
        if not n or (lang == "en" and romano == "I"):  # "Then I went"
            return m.group(0)
        return nome + " " + _numero_de_nome(nome, n, lang)

    return re.sub(r"\b([A-ZÀ-Ý][a-zà-ÿ]+)\s+([IVX]+)\b(?![\w'’])", _nome, texto)


def _expandir_siglas(texto: str, idioma: str = "pt") -> str:
    """Expande siglas: lidas como palavra ou soletradas (nomes das letras no idioma do texto)."""
    nomes = _NOMES_DAS_LETRAS.get(idioma.lower()[:2], _NOMES_DAS_LETRAS["pt"])

    def _soletrar(sigla: str) -> str:
        # Nomes das letras ("efe cê dê"): letras soltas ("f c d") os motores leem mal ou engolem.
        return " ".join(nomes.get(c, c.lower()) for c in sigla.upper())

    def _substituir_sigla(m):
        s = m.group(0).upper().strip()
        if s in _SIGLAS_LIDAS_COMO_PALAVRA:
            return s.lower()
        if s in _SIGLAS_SOLETRADAS or not any(c in _VOGAIS for c in s):
            return _soletrar(s)
        if len(s) <= 4 and s.isalpha() and s != s.lower():
            return _soletrar(s)
        return m.group(0)

    # Aplica apenas em palavras que NÃO são início de sentença (evita falso positivo)
    texto = re.sub(r"(?<![.!?]\s)\b[A-Z]{2,8}\b", _substituir_sigla, texto)
    return texto


# ═══════════════════════════════════════════════════════════════════════════════
#  Normalização geral (pontuação, espaços)
# ═══════════════════════════════════════════════════════════════════════════════

def _normalizar_pontuacao(texto: str) -> str:
    """Garante espaçamento consistente ao redor de pontuação."""
    texto = re.sub(r"\s+([,.;:!?])", r"\1", texto)
    texto = re.sub(r"([,.;:!?])(?=[^\s])", r"\1 ", texto)
    texto = re.sub(r"\s+", " ", texto)
    return texto.strip()


# ═══════════════════════════════════════════════════════════════════════════════
#  Ponto de entrada principal
# ═══════════════════════════════════════════════════════════════════════════════

_UNI_EN = ["zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven",
           "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen"]
_DEZ_EN = ["", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety"]


def _numero_extenso_en(n: int) -> str:
    if n < 0:
        return "minus " + _numero_extenso_en(-n)
    if n < 20:
        return _UNI_EN[n]
    if n < 100:
        return _DEZ_EN[n // 10] + (f"-{_UNI_EN[n % 10]}" if n % 10 else "")
    if n < 1000:
        return _UNI_EN[n // 100] + " hundred" + (f" {_numero_extenso_en(n % 100)}" if n % 100 else "")
    for limite, nome in ((1_000_000_000, "billion"), (1_000_000, "million"), (1000, "thousand")):
        if n >= limite:
            resto = n % limite
            return _numero_extenso_en(n // limite) + f" {nome}" + (f" {_numero_extenso_en(resto)}" if resto else "")
    return str(n)


def _ano_extenso_en(n: int) -> str:
    """1984 → nineteen eighty-four; 2005 → two thousand five; 2024 → twenty twenty-four."""
    if 2000 <= n <= 2009:
        return _numero_extenso_en(n)
    hi, lo = divmod(n, 100)
    if lo == 0:
        return _numero_extenso_en(hi) + " hundred"
    return _numero_extenso_en(hi) + " " + (f"oh {_UNI_EN[lo]}" if lo < 10 else _numero_extenso_en(lo))


_UNI_ES = ["cero", "uno", "dos", "tres", "cuatro", "cinco", "seis", "siete", "ocho", "nueve", "diez", "once",
           "doce", "trece", "catorce", "quince", "dieciséis", "diecisiete", "dieciocho", "diecinueve", "veinte",
           "veintiuno", "veintidós", "veintitrés", "veinticuatro", "veinticinco", "veintiséis", "veintisiete",
           "veintiocho", "veintinueve"]
_DEZ_ES = ["", "", "", "treinta", "cuarenta", "cincuenta", "sesenta", "setenta", "ochenta", "noventa"]
_CEN_ES = ["", "ciento", "doscientos", "trescientos", "cuatrocientos", "quinientos", "seiscientos",
           "setecientos", "ochocientos", "novecientos"]


def _numero_extenso_es(n: int, apocopar: bool = False) -> str:
    """Cardinal em espanhol; `apocopar` troca 'uno' por 'un' antes de substantivo (veintiún, treinta y un)."""
    if n < 0:
        return "menos " + _numero_extenso_es(-n)
    if n < 30:
        if apocopar and n == 1:
            return "un"
        if apocopar and n == 21:
            return "veintiún"
        return _UNI_ES[n]
    if n < 100:
        u = n % 10
        return _DEZ_ES[n // 10] + (" y " + _numero_extenso_es(u, apocopar) if u else "")
    if n == 100:
        return "cien"
    if n < 1000:
        r = n % 100
        return _CEN_ES[n // 100] + (" " + _numero_extenso_es(r, apocopar) if r else "")
    if n < 1_000_000:
        m, r = divmod(n, 1000)
        prefixo = "mil" if m == 1 else _numero_extenso_es(m, True) + " mil"
        return prefixo + (" " + _numero_extenso_es(r, apocopar) if r else "")
    if n < 1_000_000_000:
        m, r = divmod(n, 1_000_000)
        prefixo = "un millón" if m == 1 else _numero_extenso_es(m, True) + " millones"
        return prefixo + (" " + _numero_extenso_es(r, apocopar) if r else "")
    return str(n)


def _expandir_numeros_soltos_en_es(texto: str, lang: str) -> str:
    """Números soltos em inglês/espanhol ('99', '1,500', '3.5', '1984', '50%') para extenso."""
    en = lang == "en"
    milhar = "," if en else "."
    decimal = "." if en else ","
    cardinal = _numero_extenso_en if en else _numero_extenso_es
    ponto = " point " if en else " coma "
    por_cento = " percent" if en else " por ciento"

    def _inteiro_txt(digitos: str) -> str:
        if len(digitos) > 9 or (len(digitos) > 1 and digitos.startswith("0")):
            return " ".join(_UNI_EN[int(d)] if en else _UNI_ES[int(d)] for d in digitos)
        return cardinal(int(digitos))

    def _numero(m):
        inteiro = m.group("int").replace(milhar, "")
        dec = m.group("dec")
        pct = m.group("pct")
        if dec is None and en and len(inteiro) == 4 and 1100 <= int(inteiro) <= 2099 and milhar not in m.group("int"):
            falado = _ano_extenso_en(int(inteiro))
        else:
            falado = _inteiro_txt(inteiro)
        if dec:
            falado += ponto.rstrip() + " " + " ".join(_UNI_EN[int(d)] if en else _UNI_ES[int(d)] for d in dec)
        return falado + (por_cento if pct else "")

    padrao = (
        rf"(?<![\w.,])(?P<int>\d{{1,3}}(?:{re.escape(milhar)}\d{{3}})+|\d+)"
        rf"(?:{re.escape(decimal)}(?P<dec>\d+))?(?P<pct>\s?%)?(?![\w])"
    )
    return re.sub(padrao, _numero, texto)


def _expandir_numeros_soltos_pt(texto: str) -> str:
    """Expande inteiros e decimais que sobraram ('99', '1.990', '3,5') para extenso.

    Só roda quando pedido (`numeros_soltos=True`): o tokenizador do Pocket TTS não lê dígitos,
    enquanto os demais motores tratam números sozinhos e não devem mudar de comportamento.
    Dígitos colados a letras (A4, mp3, H2O) ficam intactos.
    """

    def _decimal(m):
        return _decimal_extenso(f"{m.group(1)},{m.group(2)}")

    def _inteiro(m):
        digitos = m.group(0).replace(".", "")
        agrupado = "." in m.group(0)  # "1.234.567" é quantidade; "1234567" é código
        if len(digitos) > (9 if agrupado else 6) or (len(digitos) > 1 and digitos.startswith("0")):
            return _digitos_extenso(digitos)
        return _numero_extenso(int(digitos), fem=_concorda_feminino(_proxima_palavra(m.string, m.end())))

    def _intervalo(m):
        a, b = int(m.group(1)), int(m.group(2))
        limite = 2100 if max(len(m.group(1)), len(m.group(2))) == 4 else 10 ** 4
        return f"{m.group(1)} a {m.group(2)}" if a < b <= limite else m.group(0)

    sem_letra_antes = r"(?<![\w.,])"
    sem_letra_depois = r"(?![\w])"
    # Telefones (99999-9999 / 9999-9999): dígito a dígito, com pausa entre os blocos
    def _telefone(m):
        a, b = m.group(1), m.group(2)
        if len(a) == 4 and int(a) < int(b) <= 2100:  # 1984-1990 é intervalo de anos, não telefone
            return m.group(0)
        return _digitos_extenso(a) + ", " + _digitos_extenso(b)

    texto = re.sub(r"\b(\d+)(O+)\b", lambda m: m.group(1) + "0" * len(m.group(2)), texto)  # OCR: 420O → 4200
    texto = re.sub(r"(?<![\w.,-])(\d{4,5})-(\d{4})(?![\w-]|[.,]\d)", _telefone, texto)
    # Intervalos com hífen: 10-20 → 10 a 20, 1984-1990 → 1984 a 1990 (telefones 99999-9999 ficam)
    texto = re.sub(r"(?<![\w.,-])(\d{1,4})-(\d{1,4})(?![\w-]|[.,]\d)", _intervalo, texto)
    texto = re.sub(rf"{sem_letra_antes}(\d+),(\d+){sem_letra_depois}", _decimal, texto)
    texto = re.sub(rf"{sem_letra_antes}\d{{1,3}}(?:\.\d{{3}})+{sem_letra_depois}", _inteiro, texto)
    texto = re.sub(rf"{sem_letra_antes}\d+{sem_letra_depois}", _inteiro, texto)
    return texto


def _remover_travessoes(texto: str) -> str:
    """Tira travessões de diálogo e os troca por pausa (o Pocket os lê como um ruído estranho).

    '— Não sei — disse Maria.' → 'Não sei, disse Maria.'; '10–20' → '10 a 20'.
    """
    texto = re.sub(r"(?m)^[ \t]*[—–―‒−][ \t]*", "", texto)          # marcador de fala no início da linha
    texto = re.sub(r"(?<=\d)[ \t]*[—–―‒−][ \t]*(?=\d)", " a ", texto)  # intervalos numéricos
    texto = re.sub(r"[ \t]*[—–―‒−][ \t]*$", "", texto, flags=re.MULTILINE)  # travessão sobrando no fim
    texto = re.sub(r"[ \t]*[—–―‒−][ \t]*", ", ", texto)              # travessão no meio → pausa
    texto = re.sub(r"([:;,])[ \t]+-[ \t]", r"\1 ", texto)         # ': - fala' → ': fala'
    texto = re.sub(r"[ \t]-[ \t]", ", ", texto)                   # hífen usado como travessão
    texto = re.sub(r",[ \t]*,", ",", texto)
    texto = re.sub(r",[ \t]*([.!?…])", r"\1", texto)
    return _limpar_simbolos_pocket(texto)


# Símbolos que o Pocket lê como som/fala ou que abrem o áudio com ~0,6 s de vazio: no INÍCIO do
# texto (hífen, marcador, reticências, #, *) e em quebras de cena ("***", "* * *", "---").
_SIMBOLOS_DE_ABERTURA = r"\s\-–—―‒−•·▪◦*#_~^|<>…"


def _parenteses_por_pausa(texto: str) -> str:
    """Troca (), [] e {} por pausas. Medido no Redmi com o Pocket (Whisper-small, 8 frases × 8 variantes):
    texto entre parênteses perdia 8% das palavras e errava outros 20-30%, às vezes com um "Pui!" no fim;
    aspas, vírgulas, dois-pontos, ponto e vírgula e travessões viraram vírgula ficaram em 0-1%."""
    if not re.search(r"[()\[\]{}]", texto):
        return texto
    texto = re.sub(r"[ 	]*[(\[{][ 	]*", ", ", texto)
    texto = re.sub(r"[ 	]*[)\]}][ 	]*", ", ", texto)
    texto = re.sub(r"[ 	]*,(?:[ 	]*,)+", ",", texto)          # vírgulas repetidas
    texto = re.sub(r"(?m)^[ 	,]+", "", texto)                    # vírgula no começo da linha
    texto = re.sub(r"[ 	]*,[ 	]*(?=[.!?…:;]|$)", "", texto, flags=re.MULTILINE)  # vírgula antes de pontuação/fim
    return texto.strip()


def _limpar_simbolos_pocket(texto: str) -> str:
    texto = re.sub(r"(?m)^[ \t]*(?:[*_=~#\-–—―‒−]{2,}|[*_=~#\-–—―‒−](?:[ \t][*_=~#\-–—―‒−])+)[ \t]*$", "", texto)  # quebra de cena
    texto = re.sub(rf"(?m)^[{_SIMBOLOS_DE_ABERTURA}]+(?=[^\s])", "", texto).strip()
    return texto if any(c.isalnum() for c in texto) else ""  # só símbolos: nada para falar


def normalizar(
    texto: str,
    idioma: str = "pt",
    numeros_soltos: bool = False,
    sem_travessoes: bool = False,
) -> str:
    """Normaliza texto para TTS: expande abreviações, números, moedas, etc.

    Args:
        texto: Texto bruto de entrada.
        idioma: Código do idioma ('pt', 'en', ou prefixo como 'pt-BR').
        numeros_soltos: Se True (só português), também expande inteiros/decimais soltos
            ('99' → 'noventa e nove'). Usado pelo Pocket TTS, que não lê dígitos.

    Returns:
        Texto normalizado, pronto para G2P.
    """
    if not texto or not texto.strip():
        return texto

    t = unicodedata.normalize("NFC", texto)

    # 1. Sanitização básica
    t = _sanitizar(t)
    if sem_travessoes:
        t = _remover_travessoes(t)
        t = _parenteses_por_pausa(t)

    # 2. Símbolos (antes da expansão de números)
    t = _expandir_simbolos(t)

    # Texto em CAIXA ALTA (títulos) vira minúsculas, senão cada palavra curta dele seria
    # soletrada como sigla. Antes dos números: "um" por extenso esconderia que o título é todo maiúsculo.
    t = _suavizar_caixa_alta(t)

    # 3. Expansões específicas do idioma
    lang = idioma.lower()[:2]

    if lang == "pt":
        t = _expandir_abreviacoes_pt(t)
        t = _expandir_numeros_pt(t)
        if numeros_soltos:
            t = _expandir_numeros_soltos_pt(t)
    elif lang == "en":
        t = _expandir_abreviacoes_en(t)
        if numeros_soltos:
            t = _expandir_numeros_soltos_en_es(t, "en")
    elif lang == "es" and numeros_soltos:
        t = _expandir_numeros_soltos_en_es(t, "es")

    # 4. Romanos com contexto (título, seção, nome) e siglas (após as abreviações, para evitar conflito)
    t = _expandir_romanos(t, lang)
    t = _expandir_siglas(t, lang)

    # 5. Normalização final de pontuação e espaços
    t = _normalizar_pontuacao(t)

    return t


def relatorio_trocas(
    texto: str,
    idioma: str = "pt",
    numeros_soltos: bool = False,
    sem_travessoes: bool = False,
) -> List[dict]:
    """Lista, linha a linha, o que a normalização troca (antes → depois), só para conferência.

    Não altera nada: chama `normalizar()` com as mesmas flags da síntese e compara palavra a
    palavra (difflib). Cada item é um trecho que mudou, com `antes` (como está) e `depois`
    (como será falado). Quando a troca é uma inserção/remoção pura, inclui uma palavra vizinha
    para o trecho fazer sentido. Espaços e pontuação que não mudam a leitura não aparecem.

    O texto deve chegar já com o dicionário de pronúncia aplicado (é quem chama que aplica).
    """
    trocas: List[dict] = []
    if not texto or not texto.strip():
        return trocas

    for linha in texto.split("\n"):
        if not linha.strip():
            continue
        depois = normalizar(
            linha, idioma, numeros_soltos=numeros_soltos, sem_travessoes=sem_travessoes
        )
        trocas.extend(comparar_textos(linha, depois))
    return trocas


_PONTUACAO_BORDAS = '.,;:!?"\'()«»“”‘’[]…—–-'


def _sem_pontuacao_bordas(palavras: List[str]) -> List[str]:
    """Tira pontuação das bordas de cada palavra e descarta as que ficam vazias (ex.: '—')."""
    return [p for p in (w.strip(_PONTUACAO_BORDAS) for w in palavras) if p]


def comparar_textos(antes: str, depois: str) -> List[dict]:
    """Trocas entre duas versões de um mesmo trecho, por palavra (difflib), no formato
    `{"antes", "depois"}`. Usado pelo relatório para comparar o texto original com o texto após o
    dicionário de pronúncia e, depois, com a normalização. Texto igual ou vazio devolve `[]`.

    A pontuação das bordas não entra no par exibido ('coso,' e 'coso.' viram 'coso'), e uma troca
    que só mudou pontuação é descartada."""
    # Invisíveis tirados pela normalização não são troca de leitura ("es\u00adtá" → "está").
    palavras_antes = _INVISIVEIS.sub("", antes).split()
    palavras_depois = depois.split()
    casador = difflib.SequenceMatcher(None, palavras_antes, palavras_depois, autojunk=False)
    trocas: List[dict] = []
    for i1, i2, j1, j2 in _grupos_alterados(casador.get_opcodes()):
        ini_a, fim_a, ini_b, fim_b = i1, i2, j1, j2
        if i1 == i2 or j1 == j2:
            ini_a, fim_a = max(0, i1 - 1), min(len(palavras_antes), i2 + 1)
            ini_b, fim_b = max(0, j1 - 1), min(len(palavras_depois), j2 + 1)
        trecho_antes = _sem_pontuacao_bordas(palavras_antes[ini_a:fim_a])
        trecho_depois = _sem_pontuacao_bordas(palavras_depois[ini_b:fim_b])
        if trecho_antes == trecho_depois:
            continue  # só mudou pontuação
        trocas.append({
            "antes": " ".join(trecho_antes),
            "depois": " ".join(trecho_depois),
        })
    return trocas


def _grupos_alterados(ops) -> List[tuple]:
    """Junta trechos alterados separados por no máximo 1 palavra igual (ex.: 'R$ 45,90 e US$ 10'
    → um único grupo, em vez de 'e' casado no meio e a troca partida em dois pedaços sem sentido)."""
    grupos: List[list] = []
    aberto = False
    for k, (tag, i1, i2, j1, j2) in enumerate(ops):
        if tag == "equal":
            proximo_alterado = k + 1 < len(ops) and ops[k + 1][0] != "equal"
            if aberto and proximo_alterado and (i2 - i1) <= 1:
                grupos[-1][1], grupos[-1][3] = i2, j2  # ponte: palavras iguais entre duas trocas
            else:
                aberto = False
            continue
        if aberto:
            grupos[-1][1], grupos[-1][3] = i2, j2
        else:
            grupos.append([i1, i2, j1, j2])
            aberto = True
    return [tuple(g) for g in grupos]

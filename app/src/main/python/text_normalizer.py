"""
text_normalizer.py - Normalização de texto pré-G2P para TTS.

Consolida e expande as normalizações existentes (narration_normalizer.py,
text_processor.py) em um único ponto de entrada, adicionando cobertura para
casos críticos de audiobook: moedas, números, abreviações, siglas, símbolos,
datas, ordinais, unidades.

Chamado antes de qualquer G2P, em todos os pipelines (local e remoto).
"""

from __future__ import annotations

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

def _sanitizar(texto: str) -> str:
    """Remove ruído de extração de documentos."""
    t = texto.replace("\u00a0", " ").replace("\u202f", " ")
    t = t.replace("\uf0fe", "").replace("\ufffe", "")
    t = re.sub(r"[\u200b-\u200d\u2060\ufeff]", "", t)
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
    subs = [
        (r"&", " e "),
        (r"@", " arroba "),
        (r"\+", " mais "),
        (r"=", " igual "),
        (r"#", " número "),
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
    "BNDES", "INSS", "SUS", "FAB", "MEC", "CNPJ", "CPF", "RG",
})

_SIGLAS_SOLETRADAS = frozenset({
    "CPF", "RG", "CNPJ", "SSP", "TV", "HD", "DVD", "CD", "USB",
    "CPU", "RAM", "HDMI", "USB", "FPS", "RGB", "CEO", "CTO", "CFO",
    "HR", "RH", "TI", "EUA", "UK", "US", "BRL", "USD", "EUR",
})


_NOMES_DAS_LETRAS = {
    "A": "á", "B": "bê", "C": "cê", "D": "dê", "E": "é", "F": "efe", "G": "gê", "H": "agá", "I": "i",
    "J": "jota", "K": "cá", "L": "ele", "M": "eme", "N": "ene", "O": "ó", "P": "pê", "Q": "quê",
    "R": "erre", "S": "esse", "T": "tê", "U": "u", "V": "vê", "W": "dáblio", "X": "xis", "Y": "ípsilon", "Z": "zê",
}


def _expandir_siglas(texto: str) -> str:
    """Expande siglas: lidas como palavra ou soletradas."""

    def _soletrar(sigla: str) -> str:
        # Nomes das letras ("efe cê dê"): letras soltas ("f c d") os motores leem mal ou engolem.
        return " ".join(_NOMES_DAS_LETRAS.get(c, c.lower()) for c in sigla.upper())

    def _substituir_sigla(m):
        s = m.group(0).upper().strip()
        if s in _SIGLAS_LIDAS_COMO_PALAVRA:
            return s.lower() + " "
        if s in _SIGLAS_SOLETRADAS:
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

    # 4. Siglas (após expansão de abreviações para evitar conflito)
    t = _expandir_siglas(t)

    # 5. Normalização final de pontuação e espaços
    t = _normalizar_pontuacao(t)

    return t

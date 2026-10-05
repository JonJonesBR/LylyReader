"""
text_processor.py - Processamento avançado de texto para TTS.
Adaptado do Studio AI para o Bot Audiobook.

Classes:
  - TextProcessor: limpeza básica, substituições fonéticas, split em chunks
  - AudioScripter: modo slow-motion (frases isoladas + pausas longas)
  - SpacingFixer: corrige "C A P Í T U L O" -> "CAPÍTULO"
"""

import re
from typing import List, Tuple


# Romanos ambíguos (I, V, X): converter apenas em contexto de seção ou linha isolada
_ROMANO_SECAO_RE = re.compile(
    r'(?i)(cap[íi]tulo|chapter|parte|part|livro|book|se[çc][ãa]o|section|ato|act|cena|scene)\s+(X|V|I)\b'
)
_ROMANO_LINHA_RE = re.compile(r'(?m)^[ \t]*(X|V|I)[ \t]*$')
_ROMANO_SIMPLES = {'X': 'dez', 'V': 'cinco', 'I': 'um'}


# Limite padrão de chars por chunk (alinhado com CHUNK_LIMITE de config.py)
DEFAULT_CHUNK_SIZE = 5000

# Descrição do estilo personalizado (definida via bot em tempo de execução)
_descricao_personalizada: str = ""

def set_descricao_personalizada(descricao: str) -> None:
    global _descricao_personalizada
    _descricao_personalizada = descricao.strip()


# =============================================================================
# TEXT PROCESSOR
# =============================================================================

class TextProcessor:
    """Limpeza básica + divisão em chunks + substituições fonéticas."""

    # Abreviações -> forma falada
    ABREV = {
        r'\bSr\.': 'Senhor', r'\bSra\.': 'Senhora', r'\bDr\.': 'Doutor',
        r'\bDra\.': 'Doutora', r'\bProf\.': 'Professor', r'\bCap\.': 'Capitão',
        r'\bpág\.': 'página', r'\betc\.': 'etcetera', r'\bvol\.': 'volume',
    }

    # Símbolos -> forma falada
    SIMBOLOS = {
        r'%': ' por cento', r'km/h': ' quilômetros por hora',
        r'\bkg\b': ' quilos', r'\bkm\b': ' quilômetros',
        r'°C': ' graus celsius', r'R\$': ' reais ',
        r'\$': ' dólares ', r'€': ' euros ', r'&': ' e ',
    }

    # Numerais romanos -> extenso (multi-letra: sem ambiguidade)
    ROMANOS = {
        r'\bXXI\b': 'vinte e um', r'\bXX\b':   'vinte',      r'\bXIX\b': 'dezenove',
        r'\bXVIII\b': 'dezoito',  r'\bXVII\b': 'dezessete',  r'\bXVI\b': 'dezesseis',
        r'\bXV\b':  'quinze',     r'\bXIV\b':  'quatorze',   r'\bXIII\b': 'treze',
        r'\bXII\b': 'doze',       r'\bXI\b':   'onze',       r'\bIX\b':  'nove',
        r'\bVIII\b': 'oito',      r'\bVII\b':  'sete',       r'\bVI\b':  'seis',
        r'\bIV\b':  'quatro',     r'\bIII\b':  'três',       r'\bII\b':  'dois',
    }
    # X, V, I são ambíguos (pronome inglês, variável, alínea) - tratados em phonetic()

    @staticmethod
    def basic_clean(text: str) -> str:
        """Limpeza para TTS - remove lixo, normaliza espaços."""
        text = re.sub(r'\{.*?\}|\[.*?\]', '', text, flags=re.DOTALL)
        text = re.sub(r'[--]|--', '.', text)
        text = re.sub(r'(^|\s|["\'])-+(\s*)', r'\1. ', text)
        text = re.sub(r'\.{4,}', '...', text)          # 4+ pontos -> reticências
        text = re.sub(r'(?<!\.)\.\.(?!\.)', '.', text)  # exatamente 2 pontos -> ponto
        text = re.sub(r'[\x00-\x08\x0b\x0c\x0e-\x1f\x7f]', '', text)
        text = text.replace('\u00ad', '').replace('\u200b', '')
        text = re.sub(r'[ \t]+', ' ', text)
        text = re.sub(r'\n\s*\n', '\n\n', text)
        return text.strip()

    @classmethod
    def phonetic(cls, text: str) -> str:
        """Substitui abreviações, símbolos e romanos por formas faladas."""
        for p, r in cls.ABREV.items():
            text = re.sub(p, r, text, flags=re.IGNORECASE)
        for p, r in cls.SIMBOLOS.items():
            text = re.sub(p, r, text)
        for p, r in cls.ROMANOS.items():
            text = re.sub(p, r, text)
        # I, V, X: apenas após palavra-chave de seção ("Capítulo X", "Parte I")
        text = _ROMANO_SECAO_RE.sub(
            lambda m: f'{m.group(1)} {_ROMANO_SIMPLES[m.group(2).upper()]}', text
        )
        # I, V, X: em linha completamente isolada (título de capítulo sozinho)
        text = _ROMANO_LINHA_RE.sub(
            lambda m: _ROMANO_SIMPLES[m.group(1).upper()], text
        )
        return text

    @staticmethod
    def split_chunks(text: str, max_chars: int = DEFAULT_CHUNK_SIZE) -> List[str]:
        """Divide por parágrafos, subdividindo frases se necessário."""
        paragraphs = text.split('\n\n')
        chunks, cur = [], ""
        for p in paragraphs:
            p = p.strip()
            if not p:
                continue
            if len(p) > max_chars:
                sentences = re.split(r'(?<=[.!?])\s+', p)
                for s in sentences:
                    if len(cur) + len(s) + 1 < max_chars:
                        cur += s + " "
                    else:
                        if cur.strip():
                            chunks.append(cur.strip())
                        if len(s) > max_chars > 0:
                            # Frase unica excede o limite (texto sem pontuacao de
                            # frase, ex.: extração de PDF que perdeu o ponto): quebra
                            # por blocos de max_chars para garantir chunk limitado.
                            restante = s.strip()
                            while len(restante) > max_chars:
                                corte = restante.rfind(" ", 0, max_chars)
                                if corte < max_chars // 2:
                                    corte = max_chars
                                chunks.append(restante[:corte].strip())
                                restante = restante[corte:].strip()
                            cur = restante + " "
                        else:
                            cur = s + " "
            elif len(cur) + len(p) + 2 < max_chars:
                cur += p + "\n\n"
            else:
                if cur.strip():
                    chunks.append(cur.strip())
                cur = p + "\n\n"
        if cur.strip():
            chunks.append(cur.strip())
        return chunks


# =============================================================================
# AUDIO SCRIPTER - SLOW MOTION
# =============================================================================

class AudioScripter:
    """
    Transforma texto em roteiro com frases isoladas e pausas longas.
    Ideal para narrações mais lentas e compreensíveis (audiobooks).
    """

    def __init__(self):
        self.rx_spaced   = re.compile(r'\b(?:[a-zA-ZÀ-ÿ]\s){2,}[a-zA-ZÀ-ÿ]\b')
        self.rx_headers  = re.compile(
            r'(?im)^\s*(?P<tag>Capítulo|Chapter|Parte|Part|Livro|Book|'
            r'Prólogo|Prologue|Epílogo|Epilogue|Prefácio|Introdução|Conclusão)'
            r'(?P<num>\s+(?:[\d]+|[IVXLCDM]+))?(?P<sep>\s*[:.-])?(?P<content>.*)$')
        self.rx_hyphen      = re.compile(r'(\w)-\n\s*(\w)')
        self.rx_broken_line = re.compile(r'(?<![.?!])\n(?=[a-zà-ú])')
        self.rx_sentence    = re.compile(r'([.?!])\s+([A-ZÀ-Ú])')
        self.rx_dialog      = re.compile(r'[\u2010-\u2015]')

    def process(self, text: str) -> str:
        """Aplica transformações slow-motion no texto."""
        if not text:
            return ""

        # Limpeza inicial
        text = re.sub(r'\[.*?\]', '', text, flags=re.DOTALL)
        text = self.rx_hyphen.sub(r'\1\2', text)
        text = self.rx_broken_line.sub(' ', text)

        # Texto espaçado: "C A P" -> "CAP"
        text = self.rx_spaced.sub(lambda m: m.group(0).replace(" ", ""), text)

        # Títulos com pausa tripla
        def fix_header(m):
            tag  = m.group('tag').title()
            num  = m.group('num') or ""
            body = (m.group('content') or "").strip()
            body = body.lstrip('.:-').strip()
            head = f"{tag}{num}"
            return (
                f"\n\n\n{head}. ... ...\n\n\n{body}"
                if body else
                f"\n\n\n{head}. ... ...\n\n\n"
            )
        text = self.rx_headers.sub(fix_header, text)

        # Normaliza espaços
        text = re.sub(r'(?<!\n)\n(?!\n)', ' ', text)
        text = re.sub(r'\s+', ' ', text)

        # Fonética
        text = TextProcessor.phonetic(text)

        # Diálogos
        text = self.rx_dialog.sub('-', text)
        text = re.sub(r'(\s*-\s*)', '\n- ', text)

        # Isola frases: "Fim. Início" -> "Fim.\n\nInício"
        text = self.rx_sentence.sub(r'\1\n\n\2', text)
        text = text.replace('…', '...')

        # Pausa longa entre parágrafos
        text = re.sub(r'\n\s*\n', '<§>', text)
        text = text.replace('<§>', ' ... ...\n\n\n')
        text = text.replace('... ... ...', '... ...')

        return text.strip()


# =============================================================================
# SPACING FIXER
# =============================================================================

class SpacingFixer:
    """
    Corrige palavras separadas por espaços: 'C A P Í T U L O' -> 'CAPÍTULO'.
    Comum em EPUBs com OCR defeituoso.
    """

    TARGETS = {
        "PRÓLOGO":  r'(?i)\bP[rR]\s*[oóOÓ]\s*[lL]\s*[oóOÓ]\s*[gG]\s*[oóOÓ]\b',
        "CAPÍTULO": r'(?i)\bC\s*[aáAÁ]\s*P\s*[iíIÍ]\s*T\s*[uúUÚ]?\s*[lL]\s*[oóOÓ]\b',
        "EPÍLOGO":  r'(?i)\bE\s*P\s*[iíIÍ]\s*[lL]\s*[oóOÓ]\s*G\s*[oóOÓ]\b',
    }

    @classmethod
    def fix(cls, text: str) -> Tuple[str, int]:
        """
        Substitui palavras espaçadas pelas formas corretas.

        Returns:
            (texto corrigido, no de ocorrências corrigidas)
        """
        total = 0
        for replacement, pattern in cls.TARGETS.items():
            found = len(re.findall(pattern, text))
            total += found
            text   = re.sub(pattern, replacement, text)
        return text, total


# =============================================================================
# NARRATION STYLER
# =============================================================================

class NarrationStyler:
    """
    Aplica estilos de narração via pré-processamento de texto.
    Mecanismo: insere marcadores de pausa que o TTS interpreta como silêncio.
      \\n\\n\\n    = pausa longa (~800ms entre parágrafos)
      \\n\\n      = pausa média (~500ms entre sentenças)
      ...       = pausa natural (~400ms dentro de sentença)
      \\n        = pausa curta (~200ms em quebras)

    Estes marcadores são convertidos em tags SSML <break> pelo Gemini TTS.
    Para melhor naturalidade, use speakingRate=0.90-0.95.
    """

    # Conectores de suspense (Terror)
    _CONECTORES_SUSPENSE = re.compile(
        r'\b(mas|porém|contudo|entretanto|de repente|de súbito|quando|até que|foi então|'
        r'naquele momento|de repente|subitamente|inesperadamente)\b',
        re.IGNORECASE,
    )

    # Conectores de revelação (Ficção Científica)
    _CONECTORES_REVELACAO = re.compile(
        r'\b(portanto|logo|assim|consequentemente|em conclusão|isto é|ou seja)\b',
        re.IGNORECASE,
    )

    # Palavras-gatilho de punchline (Comédia)
    _PUNCHLINE = re.compile(
        r'\b(resultado|só que|aí|adivinha|sabe o que aconteceu|por isso|'
        r'moral da história|resumindo|enfim|olha|detalhe)\b',
        re.IGNORECASE,
    )

    # Intensificadores sarcásticos (pt-BR)
    _INTENSIFICADORES = re.compile(
        r'\b(muito|absolutamente|completamente|totalmente|obviamente|'
        r'claramente|definitivamente|extremamente|incrivelmente)\s+(\w+)',
        re.IGNORECASE,
    )

    # Linhas de diálogo
    _DIALOGO = re.compile(r'(^|\n)(-\s*.+)', re.MULTILINE)

    # ALL-CAPS (acrônimos, nomes de naves)
    _CAPS = re.compile(r'\b([A-Z]{2,})\b')

    # Número + unidade
    _NUM_UNIDADE = re.compile(r'(\d+)\s*(km|m|kg|g|l|ml|anos?|meses?|dias?|horas?|min(?:utos?)?|s(?:egundos?)?)\b', re.IGNORECASE)

    @classmethod
    def apply(cls, text: str, estilo: str) -> str:
        if not estilo or estilo == "padrao":
            return text
        if estilo == "personalizado":
            return cls._estilo_personalizado(text, _descricao_personalizada)
        method = getattr(cls, f'_estilo_{estilo}', None)
        if method is None:
            return text
        return method(text)

    @classmethod
    def _estilo_personalizado(cls, text: str, descricao: str) -> str:
        """Aplica transformações de pausa baseadas em palavras-chave da descrição do usuário."""
        desc = descricao.lower()

        lento    = any(p in desc for p in ["lento", "devagar", "pausado", "calmo", "tranquilo", "suave", "cadenciado", "reposado"])
        rapido   = any(p in desc for p in ["rápido", "rapido", "veloz", "animado", "acelerado", "ágil", "agil", "dinâmico", "dinamico"])
        dramatico = any(p in desc for p in ["dramático", "dramatico", "intenso", "tenso", "grave", "sombrio", "misterioso", "épico", "epico"])
        poetico  = any(p in desc for p in ["poético", "poetico", "lírico", "lirico", "melodioso", "cadenciado"])

        if lento or poetico:
            # Pausas longas entre sentenças e parágrafos
            text = re.sub(r'([.!?])\s+([A-ZÀ-Ú])', r'\1\n\n\n\2', text)
            text = re.sub(r'(?<!\n)\n\n(?!\n)', '\n\n\n', text)
        elif rapido:
            # Comprime pausas - ritmo acelerado
            text = re.sub(r'\n\n\n+', '\n\n', text)
            text = re.sub(r'(\.\.\.\s*){2,}', '... ', text)
        elif dramatico:
            # Pausas dramáticas após pontuação forte
            text = re.sub(r'([.!?])\s+([A-ZÀ-Ú])', r'\1\n\n\n\2', text)
            text = re.sub(r'([!?])', r'\1\n\n\n', text)
        else:
            # Pausa suave e uniforme entre frases (padrão aprimorado)
            text = re.sub(r'([.!?])\s+([A-ZÀ-Ú])', r'\1\n\n\2', text)

        return text

    @classmethod
    def _estilo_terror(cls, text: str) -> str:
        # Pausa longa entre sentenças (800ms)
        text = re.sub(r'([.!?])\s+([A-ZÀ-Ú])', r'\1\n\n\n\2', text)
        # Pausa antes de conectores de suspense (400ms)
        text = cls._CONECTORES_SUSPENSE.sub(r'... \g<0>', text)
        # Pausa extra antes de cada quebra de parágrafo (500ms)
        text = re.sub(r'\n\n', ' ...\n\n', text)
        return text

    @classmethod
    def _estilo_acao(cls, text: str) -> str:
        # Comprime pausas redundantes - mantém 500ms entre frases
        text = re.sub(r'\n\n\n+', '\n\n', text)
        # Remove reticências duplas/triplas - mantém apenas uma
        text = re.sub(r'(\.\.\.\s*){2,}', '... ', text)
        return text

    @classmethod
    def _estilo_scifi(cls, text: str) -> str:
        # Pausa após ALL-CAPS (400ms de curiosidade científica)
        text = cls._CAPS.sub(lambda m: m.group(0) + '... ', text)
        # Pausa após número+unidade (400ms para processamento)
        text = cls._NUM_UNIDADE.sub(r'\1 \2... ', text)
        # Pausa antes de conectores de revelação (400ms)
        text = cls._CONECTORES_REVELACAO.sub(r'... \g<0>', text)
        return text

    @classmethod
    def _estilo_drama(cls, text: str) -> str:
        # Pausa extra após linhas de diálogo (500ms)
        text = cls._DIALOGO.sub(r'\1\2\n\n', text)
        # Pausa antes de conectores (400ms)
        text = re.sub(
            r'\b(mas|porém|contudo|porque|pois|então|entretanto)\b',
            r'... \g<0>', text, flags=re.IGNORECASE,
        )
        # Pausa longa após ! e ? (800ms - emoção)
        text = re.sub(r'([!?])\s*\n', r'\1\n\n\n', text)
        return text

    @classmethod
    def _estilo_comedia(cls, text: str) -> str:
        # Comprime pausas longas - comédia é rápida, mas não apressada
        text = re.sub(r'\n\n\n+', '\n\n', text)
        # Beat antes de punchline (400ms)
        text = cls._PUNCHLINE.sub(r'... \g<0>', text)
        return text

    @classmethod
    def _estilo_sarcastico(cls, text: str) -> str:
        # Pausa antes de intensificador + adjetivo (400ms)
        text = cls._INTENSIFICADORES.sub(r'... \1 \2', text)
        # Pausa longa após ! (800ms - ênfase sarcástica)
        text = re.sub(r'!\s+', '!\n\n\n', text)
        # Pausa antes de números e porcentagens (400ms)
        text = re.sub(r'(\s)(\d[\d.,]*\s*%?)', r'\1... \2', text)
        return text


# =============================================================================
# FUNÇÃO UTILITÁRIA
# =============================================================================

# =============================================================================
# PAUSAS CRESCENTES - Anti-Aceleração
# =============================================================================

class PausasCrescentes:
    """
    Injeta pausas cada vez maiores conforme o texto avança.
    Resolve o problema de aceleração do Gemini TTS no final de chunks longos.

    Princípio: quanto mais longe no texto, mais respiro/pausa o TTS precisa.
    """

    @staticmethod
    def adicionar(text: str) -> str:
        """
        Adiciona pausas progressivas ao texto.

        Estratégia:
        - Primeiras 25% do texto: pausas normais (400ms)
        - 25-50%: pausas médias (500ms)
        - 50-75%: pausas longas (600ms)
        - Últimas 25%: pausas extra longas (700ms)

        Isso força o TTS a "respirar" mais conforme avança,
        evitando aceleração no final.
        """
        if not text or len(text) < 100:
            return text

        linhas = text.split('\n')
        if not linhas:
            return text

        total_linhas = len(linhas)
        linhas_processadas = []

        for idx, linha in enumerate(linhas):
            progresso = idx / total_linhas if total_linhas > 0 else 0

            # Determinar "força" de pausa baseada na posição
            if progresso < 0.25:
                pausa_extra = ""  # Sem pausa extra
            elif progresso < 0.50:
                pausa_extra = "  "  # Uma pausa extra leve
            elif progresso < 0.75:
                pausa_extra = "   "  # Duas pausas
            else:
                pausa_extra = "    "  # Três pausas (máximo)

            # Adicionar pausa ao final da linha se houver pontuação
            if linha.strip() and linha.rstrip()[-1] in ".!?":
                linha = linha.rstrip() + pausa_extra
            elif pausa_extra and idx % 5 == 0:  # A cada 5 linhas, adiciona pausa
                linha = linha.rstrip() + pausa_extra

            linhas_processadas.append(linha)

        return '\n'.join(linhas_processadas)


def preparar_texto(raw: str, slow_motion: bool = False, chunk_size: int = DEFAULT_CHUNK_SIZE) -> List[str]:
    """
    Pipeline completo: corrige -> limpa -> (slow-motion) -> pausas crescentes -> divide em chunks.

    Args:
        raw: texto bruto extraído do livro
        slow_motion: se True, aplica AudioScripter (frases isoladas + pausas)
        chunk_size: tamanho máximo de cada chunk em caracteres

    Returns:
        Lista de chunks prontos para TTS
    """
    # 1. Corrigir espaçamentos (C A P Í T U L O -> CAPÍTULO)
    text, _ = SpacingFixer.fix(raw)

    # 2. Limpeza básica
    text = TextProcessor.basic_clean(text)

    # 3. Slow-motion opcional
    if slow_motion:
        scripter = AudioScripter()
        text = scripter.process(text)

    # 4. ⭐ NOVO: Adicionar pausas crescentes para evitar aceleração
    text = PausasCrescentes.adicionar(text)

    # 5. Dividir em chunks
    return TextProcessor.split_chunks(text, max_chars=chunk_size)

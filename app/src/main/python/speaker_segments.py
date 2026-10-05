"""Pure helpers for routing audiobook chunks to character voices."""

import re

# Pausa entre a fala de um personagem e a narração/fala seguinte DENTRO do mesmo parágrafo. A pausa
# padrão do audiobook (600 ms) é pensada para o fim de um bloco de texto; aplicada a cada troca de voz
# ela deixava o diálogo picotado ("— Não vá!" ... "gritou Pedro.").
PAUSA_ENTRE_VOZES_MS = 160

# Sinais que sobram no começo de um trecho de narração cortado de dentro da frase (", disse Marina.").
_PONTUACAO_INICIAL = " \t,;:\u2014\u2013\u2015-"


# Pontuação que fecha uma fala/trecho e deve ir junto com ele (para a prosódia do final da frase).
_PONTUACAO_FINAL = ".!?\u2026\u201d\"'\u00bb)]"
_ABERTURA = "\u201c\"\u00ab\u2018("


def _alnum_index(text: str) -> tuple[str, list[int]]:
    """Só letras e dígitos (sem caixa) e, para cada um, a posição no texto original."""
    chars = []
    positions = []
    for position, char in enumerate(text):
        if char.isalnum():
            chars.append(char.casefold())
            positions.append(position)
    return "".join(chars), positions


def _align_segments(source: str, segments: list[dict]) -> list[tuple[int, int, str | None]] | None:
    """Localiza cada segmento no texto do parágrafo ignorando pontuação, travessões e espaços.

    A análise de personagens junta trechos vizinhos do mesmo falante e tira os travessões, então o texto do
    segmento quase nunca é uma subcadeia exata do parágrafo. Comparar só letras/dígitos resolve isso.
    Devolve (início, fim, speaker_id) no texto original, ou None se algum segmento não for encontrado.
    """
    normalized, positions = _alnum_index(source)
    cursor = 0
    aligned = []
    for segment in segments:
        segment_text = segment.get("text") or ""
        segment_normalized, _ = _alnum_index(segment_text)
        if not segment_normalized:
            continue
        start = normalized.find(segment_normalized, cursor)
        if start < 0:
            return None
        end = start + len(segment_normalized)
        first = positions[start]
        while first > 0 and source[first - 1] in _ABERTURA:
            first -= 1
        last = positions[end - 1] + 1
        while last < len(source) and source[last] in _PONTUACAO_FINAL:
            last += 1
        aligned.append((first, last, segment.get("speaker_id")))
        cursor = end
    return aligned


def _split_into_chunks(text: str, limit: int) -> list[str]:
    sentences = re.split(r"(?<=[.!?])\s+", text)
    chunks = []
    current = ""
    for sentence in sentences:
        if len(current) + len(sentence) + 1 <= limit:
            current += (" " if current else "") + sentence
        else:
            if current:
                chunks.append(current)
            if len(sentence) > limit:
                chunks.extend(sentence[i:i + limit] for i in range(0, len(sentence), limit))
                current = ""
            else:
                current = sentence
    if current:
        chunks.append(current)
    return chunks


def _find_paragraph_range(source_texts: list[str], text: str) -> tuple[int, int] | None:
    """Intervalo [início, fim) de parágrafos cuja junção com linha em branco é exatamente [text]."""
    for start, source in enumerate(source_texts):
        if not source or not text.startswith(source):
            continue
        joined = source
        end = start + 1
        while len(joined) < len(text) and end < len(source_texts):
            joined += "\n\n" + source_texts[end]
            end += 1
        if joined == text:
            return start, end
    return None


def build_speaker_chunk_plan(
    text: str,
    attribution: dict,
    narrator_voice: str,
    chunk_limit: int,
    transform_text=None,
) -> dict | None:
    """Build ordered (text, voice) chunks only when the sidecar exactly matches the book."""
    paragraphs = attribution.get("paragraphs") or []
    source_texts = [paragraph.get("source_text") for paragraph in paragraphs]
    if not paragraphs or any(source is None for source in source_texts):
        return None
    chapter_ranges = attribution.get("chapters") or []
    if "\n\n".join(source_texts) != text:
        # Pode ser um trecho contínuo do livro (ex.: um capítulo): usa só os parágrafos dele.
        found = _find_paragraph_range(source_texts, text)
        if found is None:
            return None
        paragraphs = paragraphs[found[0]:found[1]]
        source_texts = source_texts[found[0]:found[1]]
        chapter_ranges = []

    voices_by_speaker = {
        speaker.get("id"): speaker.get("voice_id") or narrator_voice
        for speaker in attribution.get("speakers", [])
    }
    chapter_for_paragraph = {}
    for chapter_index, chapter in enumerate(chapter_ranges):
        start = max(0, int(chapter.get("start_paragraph", 0)))
        end = min(len(paragraphs) - 1, int(chapter.get("end_paragraph", start)))
        for paragraph_index in range(start, end + 1):
            chapter_for_paragraph[paragraph_index] = (chapter_index, chapter)

    chunks = []
    voices = []
    pauses = []  # pausa (ms) depois de cada bloco; None = pausa padrão do audiobook
    chapter_info = {}
    chapter_limits = []
    limit = max(1, int(chunk_limit))

    for paragraph_index, paragraph in enumerate(paragraphs):
        source = source_texts[paragraph_index]
        parts = []
        aligned = _align_segments(source, paragraph.get("segments", []))
        if aligned is None:
            # Um parágrafo que não alinha não derruba o livro todo: fica inteiro com o narrador.
            if source.strip():
                parts.append((source.strip(), narrator_voice))
        else:
            cursor = 0
            for start, end, speaker_id in aligned:
                gap = source[cursor:start]
                if any(char.isalnum() for char in gap):
                    parts.append((gap.strip(), narrator_voice))
                parts.append((source[start:end].strip(), voices_by_speaker.get(speaker_id, narrator_voice)))
                cursor = end
            tail = source[cursor:]
            if any(char.isalnum() for char in tail):
                parts.append((tail.strip(), narrator_voice))
        if not parts and source.strip():
            parts.append((source.strip(), narrator_voice))

        chapter = chapter_for_paragraph.get(paragraph_index)
        chapter_key = chapter[0] if chapter else None
        first_chunk = len(chunks)
        for part_index, (part_text, voice_id) in enumerate(parts):
            transformed = transform_text(part_text) if transform_text else part_text
            if not transformed or not transformed.strip():
                continue
            transformed = transformed.strip().lstrip(_PONTUACAO_INICIAL).strip()
            if not transformed:
                continue
            part_chunks = [chunk for chunk in _split_into_chunks(transformed, limit) if chunk.strip()]
            for chunk_index, chunk in enumerate(part_chunks):
                chunks.append(chunk)
                voices.append(voice_id or narrator_voice)
                # Só o último bloco de um trecho troca de voz; o último do parágrafo usa a pausa padrão.
                ultimo_do_trecho = chunk_index == len(part_chunks) - 1
                ultimo_do_paragrafo = part_index == len(parts) - 1 and ultimo_do_trecho
                pauses.append(PAUSA_ENTRE_VOZES_MS if ultimo_do_trecho and not ultimo_do_paragrafo else None)
        if chapter is not None and len(chunks) > first_chunk:
            chapter_index, metadata = chapter
            existing = chapter_info.get(chapter_index)
            if existing is None:
                chapter_info[chapter_index] = {
                    "titulo": metadata.get("title") or f"Capítulo {chapter_index + 1}",
                    "inicio_chunk": first_chunk,
                    "fim_chunk": len(chunks),
                }
            else:
                existing["fim_chunk"] = len(chunks)

    if not chunks:
        return None
    for chapter_index in sorted(chapter_info):
        item = chapter_info[chapter_index]
        chapter_limits.append((item["inicio_chunk"], item["fim_chunk"]))
    return {
        "chunks": chunks,
        "voices": voices,
        "pauses": pauses,
        "chapters": [chapter_info[index] for index in sorted(chapter_info)],
        "chapter_limits": chapter_limits,
    }

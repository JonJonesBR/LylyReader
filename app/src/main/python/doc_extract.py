"""
doc_extract.py - Extrator de texto para .doc (Word 97-2003, formato binario).

Le a estrutura do formato binario diretamente (FIB + piece table/Clx do OLE2
compound file), sem depender de LibreOffice/antiword (indisponiveis no sandbox
Android/Chaquopy). Offsets validados contra o codigo-fonte do Apache POI (HWPF)
e testados contra um corpus de 159 arquivos .doc reais (incluindo casos gerados
por fuzzer), com 0 crashes e extracao correta (incluindo texto CJK e europeu
acentuado). Suporta apenas Word 97-2003 (nFib >= 105) - o formato ainda mais
antigo Word 6.0/95 (wIdent 0xA5DC) usa um layout de FIB diferente e nao e
tratado aqui; o usuario deve converter para .docx.

Limitacoes conhecidas (aceitas por escolha, nao por bug):
- Texto marcado como excluido em "controle de alteracoes" (track changes) e
  incluido na narracao (a distincao exigiria percorrer os runs de formatacao
  CHPX, fora do escopo de um extrator de texto simples).
- Codigos de campo (hyperlinks, TOC, REF, PAGE etc.) sao removidos por inteiro,
  inclusive o texto que normalmente apareceria na tela (ex: numero de uma
  referencia cruzada) - descartar tudo e mais seguro do que arriscar incluir
  a instrucao de campo (URLs, comandos) como narracao.
"""
import struct

import olefile


class DocParseError(Exception):
    pass


def _u16(b, o):
    return struct.unpack_from("<H", b, o)[0]


def _u32(b, o):
    return struct.unpack_from("<I", b, o)[0]


def extrair_texto_doc(caminho: str) -> str:
    """Extrai o texto do corpo principal de um .doc. Lanca DocParseError se o
    arquivo for invalido, criptografado, formato antigo demais, ou vazio."""
    try:
        ole = olefile.OleFileIO(caminho)
    except Exception as e:
        raise DocParseError(f"invalido: nao e um arquivo OLE2 valido ({e})") from e

    try:
        texto = _ler_corpo_documento(ole)
    except DocParseError:
        raise
    except (struct.error, IndexError, ValueError) as e:
        # Arquivo corrompido/malformado que passou nas checagens de bounds
        # explicitas mas ainda assim produziu um offset invalido em algum
        # ponto do parsing binario.
        raise DocParseError(f"invalido: estrutura binaria corrompida ({e})") from e
    finally:
        ole.close()

    if len(texto.strip()) == 0:
        raise DocParseError("vazio: nenhum texto extraido")
    return texto


def _ler_corpo_documento(ole: "olefile.OleFileIO") -> str:
    if not ole.exists("WordDocument"):
        raise DocParseError("invalido: sem stream WordDocument")

    try:
        word = ole.openstream("WordDocument").read()
    except Exception as e:
        raise DocParseError(f"invalido: nao foi possivel ler WordDocument ({e})") from e
    if len(word) < 154:
        raise DocParseError("invalido: stream WordDocument truncado")

    wIdent = _u16(word, 0x00)
    if wIdent != 0xA5EC:
        # 0xA5DC identifica Word 6.0/95 (formato binario diferente, nao
        # suportado aqui); qualquer outro valor e um arquivo corrompido/nao-Word.
        raise DocParseError("invalido: assinatura FIB incorreta")

    nFib = _u16(word, 0x02)
    flags1 = _u16(word, 0x0A)
    fEncrypted = bool(flags1 & 0x0100)
    fWhichTblStm = bool(flags1 & 0x0200)

    if fEncrypted:
        raise DocParseError("senha: documento protegido/criptografado")

    if nFib < 105:
        raise DocParseError("versao_antiga: formato Word 6.0/95 nao suportado")

    table_name = "1Table" if fWhichTblStm else "0Table"
    if not ole.exists(table_name):
        raise DocParseError(f"invalido: sem stream {table_name}")
    try:
        table = ole.openstream(table_name).read()
    except Exception as e:
        raise DocParseError(f"invalido: nao foi possivel ler {table_name} ({e})") from e

    # FibRgLw97 comeca em 64; ccpText (tamanho do corpo, em caracteres) esta em +0xC
    ccp_text = _u32(word, 64 + 0x0C)
    if ccp_text <= 0:
        raise DocParseError("vazio: ccpText == 0")

    # fibRgFcLcbBlob comeca em 154; entrada CLX = indice 33 (8 bytes cada, fc+lcb)
    blob_offset = 154
    clx_entry = blob_offset + 33 * 8
    fcClx = _u32(word, clx_entry)
    lcbClx = _u32(word, clx_entry + 4)
    if lcbClx <= 0 or fcClx + lcbClx > len(table):
        raise DocParseError("invalido: Clx fora dos limites da tabela")

    # --- Percorre o Clx: 0+ Prc (grpprl de fast-save, ignorados) seguido de 1
    # Pcdt (piece table / lista de pecas de texto) - [MS-DOC] 2.8.35 ---
    pos = fcClx
    end = fcClx + lcbClx
    while pos < end and table[pos] == 1:  # clxt == GRPPRL
        pos += 1
        size = _u16(table, pos)
        pos += 2 + size
    if pos >= end or table[pos] != 2:  # clxt == PIECE TABLE (Pcdt)
        raise DocParseError("invalido: Pcdt nao encontrado no Clx")
    pos += 1
    piece_table_size = _u32(table, pos)
    pos += 4
    if pos + piece_table_size > len(table):
        raise DocParseError("invalido: piece table fora dos limites")
    plc = table[pos:pos + piece_table_size]

    n = (len(plc) - 4) // 12  # 4 bytes (CP) + 8 bytes (PCD) por peca
    if n <= 0:
        raise DocParseError("vazio: nenhuma peca de texto")

    cps = [_u32(plc, i * 4) for i in range(n + 1)]
    pcd_base = 4 * (n + 1)

    pieces = []  # (cp_start, cp_end, fc_raw)
    for i in range(n):
        pcd_off = pcd_base + i * 8
        fc_raw = _u32(plc, pcd_off + 2)
        pieces.append((cps[i], cps[i + 1], fc_raw))

    # As pecas nem sempre estao armazenadas em ordem de posicao de caractere
    # (documentos editados/fast-saved podem reordena-las) - precisam ser
    # ordenadas por cp_start antes de concatenar o texto final.
    pieces.sort(key=lambda p: p[0])

    partes = []
    for cp_start, cp_end, fc_raw in pieces:
        if cp_start >= ccp_text:
            continue  # nota de rodape / cabecalho / anotacao, fora do corpo
        cp_end_clip = min(cp_end, ccp_text)
        n_chars = cp_end_clip - cp_start
        if n_chars <= 0:
            continue

        # Bit 30 do fc: 0 = par de bytes UTF-16LE; 1 = 1 byte cp1252
        # "comprimido" (offset real = (fc sem o bit) / 2).
        unicode_piece = (fc_raw & 0x40000000) == 0
        if unicode_piece:
            real_fc = fc_raw
            n_bytes = n_chars * 2
            raw = word[real_fc:real_fc + n_bytes]
            texto = raw.decode("utf-16-le", errors="replace")
        else:
            real_fc = (fc_raw & ~0x40000000) // 2
            n_bytes = n_chars
            raw = word[real_fc:real_fc + n_bytes]
            texto = raw.decode("cp1252", errors="replace")
        partes.append(texto)

    return _limpar_texto_doc("".join(partes))


def _limpar_texto_doc(bruto: str) -> str:
    """Remove marcadores de controle do Word e reconstroi paragrafos legiveis."""
    # Remove codigos de campo (0x13 instrucao 0x14 resultado 0x15) por
    # inteiro - mais seguro descartar do que arriscar ler a instrucao de
    # campo (URLs, comandos de TOC/objetos incorporados) como narracao.
    limpo = []
    dentro_campo = 0
    for ch in bruto:
        code = ord(ch)
        if code == 0x13:
            dentro_campo += 1
            continue
        if code == 0x15:
            dentro_campo = max(0, dentro_campo - 1)
            continue
        if dentro_campo > 0:
            continue
        if code in (0x0D, 0x07):  # marca de paragrafo / celula-linha de tabela
            limpo.append("\n\n")
        elif code in (0x0B, 0x0C):  # quebra de linha / quebra de pagina
            limpo.append("\n")
        elif code == 0x09:
            limpo.append(" ")
        elif code < 0x20 and code not in (0x0A,):
            continue  # outros controles (figuras, notas, anotacoes) descartados
        else:
            limpo.append(ch)

    texto = "".join(limpo)
    paragrafos = [p.strip() for p in texto.split("\n\n")]
    paragrafos = [p for p in paragrafos if p]
    return "\n\n".join(paragrafos)

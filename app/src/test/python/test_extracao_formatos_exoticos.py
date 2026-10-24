"""Extração de formatos "fora do padrão": EPUB, DOCX, TXT/MD, PDF e erros de DOC/MOBI.

Todos os arquivos são GERADOS em tmp_path (nada de binário no repositório). Cada caso roda
nos dois caminhos do app: o LEITOR (audiobook_android.extrair_texto_estruturado) e a
CONVERSÃO (utils.extrair_texto_limpo).
"""
import posixpath
import sys
import zipfile
from pathlib import Path
from urllib.parse import quote

import pytest


PYTHON_SRC = Path(__file__).parents[2] / "main" / "python"
sys.path.insert(0, str(PYTHON_SRC))

from audiobook_android import extrair_texto_estruturado  # noqa: E402
from utils import extrair_texto_limpo  # noqa: E402


# ── Auxiliares de geração ─────────────────────────────────────────────────────

def _gravar_zip(caminho: Path, entradas) -> Path:
    """entradas: lista de (nome, bytes, tipo_de_compressão). Aceita nomes duplicados."""
    with zipfile.ZipFile(caminho, "w") as zf:
        for nome, dados, compressao in entradas:
            zf.writestr(nome, dados, compress_type=compressao)
    return caminho


def _xhtml(corpo: str, declaracao: str = "") -> bytes:
    return (
        declaracao
        + '<html xmlns="http://www.w3.org/1999/xhtml"><head><title>T</title></head>'
        + f"<body>{corpo}</body></html>"
    ).encode("utf-8")


def _epub(caminho: Path, arquivos, *, opf="OEBPS/content.opf", spine=None, spine_extra="",
          manifest_extra="", container=True, container_bytes=None, opf_bytes=None,
          extras=(), mimetype_comprimido=False) -> Path:
    """Monta um EPUB. arquivos: lista de (nome_no_zip, bytes). Com opf=None não há OPF nem
    container. spine: lista de nomes na ordem de leitura (padrão: ordem de `arquivos`)."""
    stored = zipfile.ZIP_DEFLATED if mimetype_comprimido else zipfile.ZIP_STORED
    entradas = [("mimetype", b"application/epub+zip", stored)]
    ids = {}
    for i, (nome, dados) in enumerate(arquivos):
        ids[nome] = f"id{i}"
        entradas.append((nome, dados, zipfile.ZIP_DEFLATED))

    if opf is not None:
        pasta = opf.rsplit("/", 1)[0] if "/" in opf else "."
        manifest = "".join(
            f'<item id="{ids[n]}" href="{quote(posixpath.relpath(n, pasta), safe="/")}" '
            f'media-type="application/xhtml+xml"/>'
            for n, _ in arquivos
        )
        ordem = spine if spine is not None else [n for n, _ in arquivos]
        itens_spine = "".join(f'<itemref idref="{ids[n]}"/>' for n in ordem)
        opf_xml = (
            '<?xml version="1.0" encoding="utf-8"?>'
            '<package xmlns="http://www.idpf.org/2007/opf" version="3.0">'
            '<metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>Livro</dc:title></metadata>'
            f"<manifest>{manifest}{manifest_extra}</manifest>"
            f"<spine>{itens_spine}{spine_extra}</spine></package>"
        ).encode("utf-8")
        entradas.append((opf, opf_bytes if opf_bytes is not None else opf_xml, zipfile.ZIP_DEFLATED))
        if container:
            cont = container_bytes if container_bytes is not None else (
                '<?xml version="1.0"?><container version="1.0" '
                'xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles>'
                f'<rootfile full-path="{opf}" media-type="application/oebps-package+xml"/>'
                "</rootfiles></container>"
            ).encode("utf-8")
            entradas.append(("META-INF/container.xml", cont, zipfile.ZIP_DEFLATED))
    entradas.extend(extras)
    return _gravar_zip(caminho, entradas)


def _paragrafos(caminho: Path):
    """Parágrafos do LEITOR (lista de textos). Falha o teste se o extrator devolver erro."""
    r = extrair_texto_estruturado(str(caminho))
    assert r["ok"], r.get("erro")
    return [p["texto"] for p in r["paragrafos"]], r


def _texto_do_leitor(caminho: Path) -> str:
    textos, _ = _paragrafos(caminho)
    return "\n".join(textos)


def _conversao(caminho: Path) -> str:
    return extrair_texto_limpo(str(caminho), caminho.suffix.lower())


def _norm(texto: str) -> str:
    return " ".join(texto.split())


def _linhas(texto: str):
    return [_norm(linha) for linha in texto.splitlines() if linha.strip()]


def _xhtml_com_texto_em_ambos(caminho: Path, *trechos: str):
    """Confere que cada trecho aparece no leitor E na conversão."""
    leitor = _norm(_texto_do_leitor(caminho))
    conversao = _norm(_conversao(caminho))
    for trecho in trechos:
        assert trecho in leitor, f"leitor sem: {trecho!r}\n{leitor[:800]}"
        assert trecho in conversao, f"conversão sem: {trecho!r}\n{conversao[:800]}"


# ── EPUB ──────────────────────────────────────────────────────────────────────

def test_epub_texto_solto_no_body_com_br_vira_uma_linha_por_quebra(tmp_path):
    corpo = (
        "Primeira linha solta do livro.<br/>"
        "Segunda linha solta do livro.<br/>"
        "Terceira linha solta, sem paragrafo."
    )
    epub = _epub(tmp_path / "a.epub", [("OEBPS/c1.xhtml", _xhtml(corpo))])
    textos, _ = _paragrafos(epub)
    assert "Primeira linha solta do livro." in textos
    assert "Segunda linha solta do livro." in textos
    assert "Terceira linha solta, sem paragrafo." in textos
    _xhtml_com_texto_em_ambos(epub, "Segunda linha solta do livro.")


def test_epub_so_divs_aninhadas_e_texto_solto_entre_elas(tmp_path):
    corpo = (
        "<div><div>Texto do bloco interno um, com conteudo suficiente.</div></div>"
        "<div>Texto do segundo bloco, tambem com conteudo suficiente.</div>"
        "<div>Solto antes do bloco interno aqui.<div>Bloco interno com texto longo o bastante.</div>"
        "Solto depois do bloco interno.</div>"
    )
    epub = _epub(tmp_path / "b.epub", [("OEBPS/c1.xhtml", _xhtml(corpo))])
    textos, _ = _paragrafos(epub)
    assert "Texto do bloco interno um, com conteudo suficiente." in textos
    assert "Texto do segundo bloco, tambem com conteudo suficiente." in textos
    # Texto solto dentro de <div> que também contém <div> filho não pode sumir.
    assert "Solto antes do bloco interno aqui." in textos
    assert "Solto depois do bloco interno." in textos
    _xhtml_com_texto_em_ambos(epub, "Solto depois do bloco interno.")


def test_epub_sem_toc_ncx_e_sem_nav(tmp_path):
    arquivos = [
        ("OEBPS/c1.xhtml", _xhtml("<h1>Um</h1><p>Paragrafo do primeiro capitulo do livro.</p>")),
        ("OEBPS/c2.xhtml", _xhtml("<h1>Dois</h1><p>Paragrafo do segundo capitulo do livro.</p>")),
    ]
    epub = _epub(tmp_path / "c.epub", arquivos)
    textos, _ = _paragrafos(epub)
    assert "Paragrafo do primeiro capitulo do livro." in textos
    assert "Paragrafo do segundo capitulo do livro." in textos
    _xhtml_com_texto_em_ambos(epub, "Paragrafo do segundo capitulo do livro.")


def test_epub_opf_em_subpasta_com_href_relativo_maiusculas_e_acentos(tmp_path):
    # OPF em OEBPS/pacote/, arquivos em OEBPS/Texto/ com acento e espaço; href com ../ e %20.
    # A spine inverte a ordem de nome: cap2 deve vir antes de cap1.
    arquivos = [
        ("OEBPS/Texto/Capítulo 1.xhtml", _xhtml("<p>Conteudo do capitulo acentuado um.</p>")),
        ("OEBPS/Texto/CAP2.XHTML", _xhtml("<p>Conteudo do capitulo maiusculo dois.</p>")),
    ]
    epub = _epub(
        tmp_path / "d.epub", arquivos, opf="OEBPS/pacote/content.opf",
        spine=["OEBPS/Texto/CAP2.XHTML", "OEBPS/Texto/Capítulo 1.xhtml"],
    )
    textos, _ = _paragrafos(epub)
    assert textos.index("Conteudo do capitulo maiusculo dois.") < textos.index(
        "Conteudo do capitulo acentuado um."
    )
    _xhtml_com_texto_em_ambos(epub, "Conteudo do capitulo acentuado um.")


def test_epub_sem_opf_usa_html_em_ordem_do_nome(tmp_path):
    # Entradas gravadas fora de ordem: a ordem de leitura deve ser a do nome.
    arquivos = [
        ("capitulo10.xhtml", _xhtml("<p>Texto do capitulo dez do livro.</p>")),
        ("capitulo02.xhtml", _xhtml("<p>Texto do capitulo dois do livro.</p>")),
        ("capitulo01.xhtml", _xhtml("<p>Texto do capitulo um do livro.</p>")),
    ]
    epub = _epub(tmp_path / "e1.epub", arquivos, opf=None)
    textos, _ = _paragrafos(epub)
    assert textos.index("Texto do capitulo um do livro.") < textos.index(
        "Texto do capitulo dois do livro."
    ) < textos.index("Texto do capitulo dez do livro.")
    _xhtml_com_texto_em_ambos(epub, "Texto do capitulo dez do livro.")


def test_epub_sem_container_xml_ainda_le_o_opf(tmp_path):
    arquivos = [("OEBPS/c1.xhtml", _xhtml("<p>Capitulo sem container, mas com OPF valido.</p>"))]
    epub = _epub(tmp_path / "e2.epub", arquivos, container=False)
    textos, _ = _paragrafos(epub)
    assert "Capitulo sem container, mas com OPF valido." in textos
    _xhtml_com_texto_em_ambos(epub, "Capitulo sem container, mas com OPF valido.")


def test_epub_container_xml_invalido_e_opf_lixo_nao_derrubam_o_livro(tmp_path):
    arquivos = [
        ("OEBPS/c1.xhtml", _xhtml("<p>Primeiro capitulo sobrevive ao OPF quebrado.</p>")),
        ("OEBPS/c2.xhtml", _xhtml("<p>Segundo capitulo sobrevive ao OPF quebrado.</p>")),
    ]
    epub = _epub(
        tmp_path / "e3.epub", arquivos,
        container_bytes=b"<<<nao e xml de jeito nenhum",
        opf_bytes=b"\x00\xff\xfe lixo binario <<<",
    )
    textos, _ = _paragrafos(epub)
    assert "Primeiro capitulo sobrevive ao OPF quebrado." in textos
    assert "Segundo capitulo sobrevive ao OPF quebrado." in textos
    _xhtml_com_texto_em_ambos(epub, "Segundo capitulo sobrevive ao OPF quebrado.")


def test_epub_spine_com_item_inexistente_nao_perde_os_outros(tmp_path):
    arquivos = [
        ("OEBPS/c1.xhtml", _xhtml("<p>Capitulo valido que continua na leitura.</p>")),
        ("OEBPS/c2.xhtml", _xhtml("<p>Outro capitulo valido tambem preservado.</p>")),
    ]
    epub = _epub(
        tmp_path / "f.epub", arquivos,
        spine_extra='<itemref idref="fantasma"/>',
        manifest_extra='<item id="sumido" href="nao_existe.xhtml" media-type="application/xhtml+xml"/>',
    )
    textos, _ = _paragrafos(epub)
    assert "Capitulo valido que continua na leitura." in textos
    assert "Outro capitulo valido tambem preservado." in textos
    _xhtml_com_texto_em_ambos(epub, "Outro capitulo valido tambem preservado.")


def test_epub_xhtml_com_declaracao_namespaces_e_entidades(tmp_path):
    decl = '<?xml version="1.0" encoding="utf-8"?>\n<!DOCTYPE html>\n'
    corpo = "<p>Caf&eacute; com&nbsp;leite &amp; a&ccedil;&uacute;car &mdash; fim.</p>"
    html = (
        decl
        + '<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">'
        + f"<head><title>X</title></head><body><section epub:type=\"chapter\">{corpo}</section></body></html>"
    ).encode("utf-8")
    epub = _epub(tmp_path / "g.epub", [("OEBPS/c1.xhtml", html)])
    textos, _ = _paragrafos(epub)
    assert "Café com leite & açúcar — fim." in textos
    _xhtml_com_texto_em_ambos(epub, "Café com leite & açúcar — fim.")


def test_epub_html_latin1_e_cp1252_declarado_sem_caractere_substituto(tmp_path):
    cp1252 = (
        '<html><head><meta http-equiv="Content-Type" content="text/html; charset=windows-1252"/>'
        "</head><body><p>Ação “citada” — fim do capítulo um.</p></body></html>"
    ).encode("cp1252")
    latin1 = (
        '<html><head><meta charset="iso-8859-1"/></head>'
        "<body><p>Coração e máquina, sem dúvida nenhuma.</p></body></html>"
    ).encode("latin-1")
    epub = _epub(
        tmp_path / "h.epub",
        [("OEBPS/c1.htm", cp1252), ("OEBPS/c2.html", latin1)],
    )
    textos, _ = _paragrafos(epub)
    assert "Ação “citada” — fim do capítulo um." in textos
    assert "Coração e máquina, sem dúvida nenhuma." in textos
    assert not any("�" in t for t in textos)
    conversao = _conversao(epub)
    assert "�" not in conversao
    _xhtml_com_texto_em_ambos(epub, "Coração e máquina, sem dúvida nenhuma.")


def test_epub_capa_svg_e_capitulos_curtos_nao_somem(tmp_path):
    capa = (
        '<html xmlns="http://www.w3.org/1999/xhtml" xmlns:xlink="http://www.w3.org/1999/xlink">'
        '<body><svg xmlns="http://www.w3.org/2000/svg" width="100%" height="100%">'
        '<image width="600" height="800" xlink:href="imagens/capa.jpg"/></svg></body></html>'
    ).encode("utf-8")
    arquivos = [
        ("OEBPS/capa.xhtml", capa),
        ("OEBPS/c1.xhtml", _xhtml("<p>Um capitulo curto.</p>")),
        ("OEBPS/c2.xhtml", _xhtml("<p>Fim.</p>")),
    ]
    epub = _epub(tmp_path / "i.epub", arquivos)
    textos, _ = _paragrafos(epub)
    assert "Um capitulo curto." in textos
    assert "Fim." in textos
    assert all(t.strip() for t in textos)
    _xhtml_com_texto_em_ambos(epub, "Um capitulo curto.", "Fim.")


def test_epub_entrada_duplicada_no_zip_nao_repete_capitulo(tmp_path):
    # Sem OPF, o fallback lista os HTML do zip: a entrada repetida não pode duplicar o capítulo.
    # (zipfile lê a última entrada de um nome repetido; o teste não depende de qual versão ganha.)
    epub = _epub(
        tmp_path / "j1.epub",
        [("OEBPS/c1.xhtml", _xhtml("<p>Capitulo um aparece uma unica vez no livro.</p>"))],
        opf=None,
        extras=[("OEBPS/c1.xhtml", _xhtml("<p>Capitulo um aparece uma unica vez no livro.</p>"),
                 zipfile.ZIP_DEFLATED)],
    )
    textos, _ = _paragrafos(epub)
    assert textos.count("Capitulo um aparece uma unica vez no livro.") == 1


def test_epub_mimetype_comprimido_continua_legivel(tmp_path):
    arquivos = [("OEBPS/c1.xhtml", _xhtml("<p>Livro com mimetype comprimido no zip.</p>"))]
    epub = _epub(tmp_path / "j2.epub", arquivos, mimetype_comprimido=True)
    textos, _ = _paragrafos(epub)
    assert "Livro com mimetype comprimido no zip." in textos
    _xhtml_com_texto_em_ambos(epub, "Livro com mimetype comprimido no zip.")


def _ncx_aninhado(niveis: int) -> bytes:
    """NCX com `niveis` navPoints aninhados, um arquivo por nível (c1.xhtml ... cN.xhtml)."""
    partes = []
    for n in range(niveis, 0, -1):
        partes.insert(
            0,
            f'<navPoint id="np{n}" playOrder="{n}"><navLabel><text>Nivel {n}</text></navLabel>'
            f'<content src="Text/c{n}.xhtml"/>'
            + "".join(partes)
            + "</navPoint>",
        )
    return (
        '<?xml version="1.0" encoding="utf-8"?>'
        '<ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1"><navMap>'
        + "".join(partes)
        + "</navMap></ncx>"
    ).encode("utf-8")


def test_epub_ncx_aninhado_profundo_mapeia_titulo_de_cada_nivel(tmp_path):
    arquivos = [
        (f"OEBPS/Text/c{n}.xhtml", _xhtml(f"<p>Texto exclusivo do nivel numero {n} do livro.</p>"))
        for n in range(1, 7)
    ]
    epub = _epub(
        tmp_path / "k.epub", arquivos + [("OEBPS/toc.ncx", _ncx_aninhado(6))],
        manifest_extra='<item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>',
    )
    _xhtml_com_texto_em_ambos(epub, "Texto exclusivo do nivel numero 6 do livro.")
    textos, r = _paragrafos(epub)
    titulos = [c["titulo"] for c in r["capitulos"]]
    assert "Nivel 6" in titulos
    indice = textos.index("Texto exclusivo do nivel numero 6 do livro.")
    idx_cap = r["paragrafos"][indice]["capitulo_idx"]
    assert r["capitulos"][idx_cap]["titulo"] == "Nivel 6"


def test_epub_ncx_pai_sem_content_nao_rouba_o_titulo_do_filho(tmp_path):
    # "Parte I" não tem <content> próprio; o parser não pode pegar o content do filho.
    ncx = (
        '<?xml version="1.0" encoding="utf-8"?>'
        '<ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1"><navMap>'
        '<navPoint id="p1"><navLabel><text>Parte I</text></navLabel>'
        '<navPoint id="p2"><navLabel><text>Cap Real</text></navLabel>'
        '<content src="c1.xhtml"/></navPoint></navPoint>'
        "</navMap></ncx>"
    ).encode("utf-8")
    epub = _epub(
        tmp_path / "k2.epub",
        [("OEBPS/c1.xhtml", _xhtml("<p>Paragrafo do capitulo real do livro.</p>")),
         ("OEBPS/toc.ncx", ncx)],
        manifest_extra='<item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>',
    )
    textos, r = _paragrafos(epub)
    idx = r["paragrafos"][textos.index("Paragrafo do capitulo real do livro.")]["capitulo_idx"]
    assert r["capitulos"][idx]["titulo"] == "Cap Real"


def test_epub_item_com_doctype_interno_e_pulado_sem_derrubar_o_livro(tmp_path):
    ruim = (
        b'<!DOCTYPE html [<!ENTITY bomba "ataque">]><html><body><p>Nao deve entrar.</p></body></html>'
    )
    arquivos = [
        ("OEBPS/c1.xhtml", ruim),
        ("OEBPS/c2.xhtml", _xhtml("<p>Capitulo bom sobrevive ao item suspeito.</p>")),
    ]
    epub = _epub(tmp_path / "l.epub", arquivos)
    textos, _ = _paragrafos(epub)
    assert "Capitulo bom sobrevive ao item suspeito." in textos
    assert not any("Nao deve entrar" in t for t in textos)


def test_epub_corrompido_devolve_erro_claro_sem_excecao_crua(tmp_path):
    caminho = tmp_path / "corrompido.epub"
    caminho.write_bytes(b"isto nao e um zip, so lixo" * 10)
    r = extrair_texto_estruturado(str(caminho))
    assert r["ok"] is False
    assert "invalido" in r["erro"]
    with pytest.raises(Exception) as ctx:
        _conversao(caminho)
    assert "invalido" in str(ctx.value)


def test_epub_vazio_devolve_erro_claro(tmp_path):
    epub = _epub(tmp_path / "vazio.epub", [("OEBPS/c1.xhtml", _xhtml("<p>.</p>"))])
    r = extrair_texto_estruturado(str(epub))
    assert r["ok"] is False
    assert r["erro"].startswith("vazio")


# ── DOCX ──────────────────────────────────────────────────────────────────────

_W = (
    'xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main" '
    'xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" '
    'xmlns:v="urn:schemas-microsoft-com:vml"'
)


def _docx(caminho: Path, corpo_xml: str) -> Path:
    estilos = (
        f'<?xml version="1.0" encoding="UTF-8" standalone="yes"?><w:styles {_W}>'
        '<w:style w:type="paragraph" w:styleId="Ttulo1"><w:name w:val="heading 1"/></w:style>'
        '<w:style w:type="paragraph" w:styleId="Ttulo2"><w:name w:val="Título 2"/></w:style>'
        '<w:style w:type="paragraph" w:styleId="Normal"><w:name w:val="Normal"/></w:style>'
        "</w:styles>"
    )
    documento = (
        f'<?xml version="1.0" encoding="UTF-8" standalone="yes"?><w:document {_W}>'
        f"<w:body>{corpo_xml}</w:body></w:document>"
    )
    content_types = (
        '<?xml version="1.0" encoding="UTF-8"?>'
        '<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">'
        '<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>'
        '<Default Extension="xml" ContentType="application/xml"/>'
        '<Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>'
        '<Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/>'
        "</Types>"
    )
    rels = (
        '<?xml version="1.0" encoding="UTF-8"?>'
        '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
        '<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>'
        "</Relationships>"
    )
    doc_rels = (
        '<?xml version="1.0" encoding="UTF-8"?>'
        '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
        '<Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>'
        '<Relationship Id="rId9" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/hyperlink" Target="https://exemplo.com/livro" TargetMode="External"/>'
        "</Relationships>"
    )
    entradas = [
        ("[Content_Types].xml", content_types.encode(), zipfile.ZIP_DEFLATED),
        ("_rels/.rels", rels.encode(), zipfile.ZIP_DEFLATED),
        ("word/_rels/document.xml.rels", doc_rels.encode(), zipfile.ZIP_DEFLATED),
        ("word/document.xml", documento.encode("utf-8"), zipfile.ZIP_DEFLATED),
        ("word/styles.xml", estilos.encode("utf-8"), zipfile.ZIP_DEFLATED),
    ]
    return _gravar_zip(caminho, entradas)


def _p(texto: str, estilo: str = "") -> str:
    pp = f'<w:pPr><w:pStyle w:val="{estilo}"/></w:pPr>' if estilo else ""
    return f'<w:p>{pp}<w:r><w:t xml:space="preserve">{texto}</w:t></w:r></w:p>'


def test_docx_paragrafos_em_tabelas_listas_e_caixas_de_texto(tmp_path):
    corpo = (
        _p("Parágrafo comum do documento de teste.")
        + '<w:tbl><w:tblPr/><w:tblGrid><w:gridCol w:w="4000"/></w:tblGrid><w:tr><w:tc>'
        + _p("Texto dentro de uma célula de tabela.")
        + "</w:tc></w:tr></w:tbl>"
        + '<w:p><w:pPr><w:pStyle w:val="PargrafodaLista"/><w:numPr><w:ilvl w:val="0"/>'
        '<w:numId w:val="1"/></w:numPr></w:pPr><w:r><w:t>Item de lista com texto suficiente.</w:t></w:r></w:p>'
        + '<w:p><w:r><w:t xml:space="preserve">Texto antes da caixa de texto, com frase longa. </w:t></w:r>'
        "<w:r><w:pict><v:shape><v:textbox><w:txbxContent>"
        + _p("Conteudo dentro da caixa de texto.")
        + "</w:txbxContent></v:textbox></v:shape></w:pict></w:r></w:p>"
    )
    docx = _docx(tmp_path / "m.docx", corpo)
    textos, _ = _paragrafos(docx)
    assert "Texto dentro de uma célula de tabela." in textos
    assert "Item de lista com texto suficiente." in textos
    assert textos.count("Conteudo dentro da caixa de texto.") == 1
    # O parágrafo que contém a caixa não pode repetir o texto dela.
    assert not any("Conteudo dentro da caixa" in t and t != "Conteudo dentro da caixa de texto." for t in textos)
    conversao = _conversao(docx)
    assert "Texto dentro de uma célula de tabela." in conversao
    assert "Item de lista com texto suficiente." in conversao
    assert conversao.count("Conteudo dentro da caixa de texto.") == 1


def test_docx_titulos_com_estilo_localizado_viram_capitulos(tmp_path):
    corpo = (
        _p("Capítulo Um", "Ttulo1")
        + _p("Texto do primeiro capítulo com tamanho suficiente.")
        + _p("Segunda Parte", "Ttulo2")
        + _p("Texto da segunda parte com tamanho suficiente.")
    )
    docx = _docx(tmp_path / "n.docx", corpo)
    _, r = _paragrafos(docx)
    titulos = [c["titulo"] for c in r["capitulos"]]
    assert "Capítulo Um" in titulos
    assert "Segunda Parte" in titulos


def test_docx_quebra_de_linha_e_hyperlink_mantem_as_palavras(tmp_path):
    corpo = (
        '<w:p><w:r><w:t>Primeira frase da quebra.</w:t></w:r><w:r><w:br/></w:r>'
        "<w:r><w:t>Segunda frase da quebra.</w:t></w:r></w:p>"
        '<w:p><w:r><w:t xml:space="preserve">Leia o </w:t></w:r>'
        '<w:hyperlink r:id="rId9"><w:r><w:t>site oficial do livro</w:t></w:r></w:hyperlink>'
        '<w:r><w:t xml:space="preserve"> para mais detalhes.</w:t></w:r></w:p>'
    )
    docx = _docx(tmp_path / "o.docx", corpo)
    textos, _ = _paragrafos(docx)
    assert "Primeira frase da quebra. Segunda frase da quebra." in textos
    assert "Leia o site oficial do livro para mais detalhes." in textos
    conversao = _norm(_conversao(docx))
    assert "Primeira frase da quebra. Segunda frase da quebra." in conversao
    assert "Leia o site oficial do livro para mais detalhes." in conversao


# ── TXT / MD ──────────────────────────────────────────────────────────────────

def _txt(tmp_path: Path, nome: str, dados: bytes) -> Path:
    caminho = tmp_path / nome
    caminho.write_bytes(dados)
    return caminho


def test_txt_utf8_com_bom_nao_perde_titulo_do_primeiro_capitulo(tmp_path):
    dados = "Capítulo 1\n\nTexto com acentos suficiente para virar parágrafo.\n".encode("utf-8-sig")
    caminho = _txt(tmp_path, "bom.txt", dados)
    textos, r = _paragrafos(caminho)
    assert "Capítulo 1" in [c["titulo"] for c in r["capitulos"]]
    assert not any(t.startswith("﻿") for t in textos)
    conversao = _conversao(caminho)
    assert not conversao.startswith("﻿")
    assert "Texto com acentos suficiente para virar parágrafo." in _norm(conversao)


def test_txt_utf16_com_bom_e_sem_bom(tmp_path):
    texto = "Texto em UTF-16 com acentos çãé e tamanho suficiente."
    com_bom = _txt(tmp_path, "u16bom.txt", texto.encode("utf-16"))
    sem_bom = _txt(tmp_path, "u16.txt", texto.encode("utf-16-le"))
    for caminho in (com_bom, sem_bom):
        assert texto in _texto_do_leitor(caminho)
        assert texto in _norm(_conversao(caminho))


def test_txt_cp1252_mantem_aspas_e_acentos(tmp_path):
    texto = "Ação — “aspas” e Coração, sem nenhum erro de codificação."
    caminho = _txt(tmp_path, "cp.txt", texto.encode("cp1252"))
    assert texto in _texto_do_leitor(caminho)
    # A conversão normaliza aspas tipográficas (limpar_texto_avancado): confere só os acentos.
    assert "Ação" in _conversao(caminho) and "Coração, sem nenhum erro" in _conversao(caminho)


def test_txt_byte_invalido_em_cp1252_nao_quebra(tmp_path):
    # 0x81 é indefinido em cp1252: não pode gerar exceção nem caractere substituto.
    caminho = _txt(tmp_path, "sujo.txt", b"Texto com byte \x81 estranho e tamanho suficiente.")
    texto = _texto_do_leitor(caminho)
    assert "Texto com byte" in texto
    assert "�" not in texto
    assert "�" not in _conversao(caminho)


def test_txt_quebras_crlf_e_cr_separam_paragrafos(tmp_path):
    crlf = _txt(tmp_path, "crlf.txt", (
        "Parágrafo um com texto suficiente.\r\n\r\nParágrafo dois com texto suficiente.\r\n"
    ).encode("utf-8"))
    cr = _txt(tmp_path, "cr.txt", (
        "Parágrafo um com texto suficiente.\r\rParágrafo dois com texto suficiente.\r"
    ).encode("utf-8"))
    for caminho in (crlf, cr):
        textos, _ = _paragrafos(caminho)
        assert textos == [
            "Parágrafo um com texto suficiente.",
            "Parágrafo dois com texto suficiente.",
        ]
        conversao = _conversao(caminho)
        assert "\r" not in conversao
        assert "Parágrafo dois com texto suficiente." in _norm(conversao)


def test_txt_sem_acentos_funciona(tmp_path):
    caminho = _txt(tmp_path, "ascii.txt", b"Texto simples sem nenhum acento e com tamanho suficiente.")
    assert "Texto simples sem nenhum acento e com tamanho suficiente." in _texto_do_leitor(caminho)
    assert "Texto simples sem nenhum acento" in _conversao(caminho)


def test_md_com_titulos_vira_capitulos(tmp_path):
    caminho = _txt(tmp_path, "livro.md", (
        "# Primeiro Capítulo\n\nTexto do primeiro capítulo, bem comprido.\n\n"
        "## Segundo Capítulo\n\nTexto do segundo capítulo, também comprido.\n"
    ).encode("utf-8"))
    _, r = _paragrafos(caminho)
    titulos = [c["titulo"] for c in r["capitulos"]]
    assert "Primeiro Capítulo" in titulos and "Segundo Capítulo" in titulos


# ── PDF ───────────────────────────────────────────────────────────────────────

def _escapar_pdf(dados: bytes) -> bytes:
    return dados.replace(b"\\", b"\\\\").replace(b"(", b"\\(").replace(b")", b"\\)")


def _pdf_com_texto(caminho: Path, paginas) -> Path:
    """PDF mínimo com camada de texto (Helvetica/WinAnsi). paginas: lista de listas de linhas."""
    n = len(paginas)
    kids = " ".join(f"{4 + 2 * i} 0 R" for i in range(n))
    objetos = [
        b"<< /Type /Catalog /Pages 2 0 R >>",
        f"<< /Type /Pages /Kids [{kids}] /Count {n} >>".encode(),
        b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>",
    ]
    for i, linhas in enumerate(paginas):
        conteudo = b"BT /F1 12 Tf 14 TL 72 720 Td "
        for linha in linhas:
            conteudo += b"(" + _escapar_pdf(linha.encode("cp1252")) + b") Tj T* "
        conteudo += b"ET"
        objetos.append(
            f"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] "
            f"/Resources << /Font << /F1 3 0 R >> >> /Contents {5 + 2 * i} 0 R >>".encode()
        )
        objetos.append(b"<< /Length %d >>\nstream\n" % len(conteudo) + conteudo + b"\nendstream")

    saida = bytearray(b"%PDF-1.4\n")
    offsets = []
    for numero, corpo in enumerate(objetos, start=1):
        offsets.append(len(saida))
        saida += f"{numero} 0 obj\n".encode() + corpo + b"\nendobj\n"
    xref = len(saida)
    saida += f"xref\n0 {len(objetos) + 1}\n0000000000 65535 f \n".encode()
    for off in offsets:
        saida += f"{off:010d} 00000 n \n".encode()
    saida += (
        f"trailer\n<< /Size {len(objetos) + 1} /Root 1 0 R >>\nstartxref\n{xref}\n%%EOF\n"
    ).encode()
    caminho.write_bytes(bytes(saida))
    return caminho


def test_pdf_com_camada_de_texto_le_e_converte(tmp_path):
    pdf = _pdf_com_texto(tmp_path / "t.pdf", [[
        "Primeiro paragrafo do PDF com texto suficiente para contar.",
        "Segundo bloco do PDF, com acentos ção e tamanho suficiente.",
    ]])
    textos, _ = _paragrafos(pdf)
    assert any("Primeiro paragrafo do PDF" in t for t in textos)
    conversao = _norm(_conversao(pdf))
    assert "Primeiro paragrafo do PDF com texto suficiente para contar." in conversao
    assert "acentos ção" in conversao


def test_pdf_escaneado_devolve_mensagem_clara(tmp_path):
    from pypdf import PdfWriter

    escaneado = tmp_path / "scan.pdf"
    escritor = PdfWriter()
    escritor.add_blank_page(612, 792)
    with open(escaneado, "wb") as f:
        escritor.write(f)

    r = extrair_texto_estruturado(str(escaneado))
    assert r["ok"] is False
    assert r["erro"].startswith("vazio")
    assert "escaneado" in r["erro"]
    assert "Erro ao abrir" not in r["erro"]

    with pytest.raises(ValueError) as ctx:
        _conversao(escaneado)
    assert "escaneado" in str(ctx.value)


def test_pdf_corrompido_devolve_erro_em_portugues(tmp_path):
    corrompido = tmp_path / "ruim.pdf"
    corrompido.write_bytes(b"%PDF-1.4\nlixo sem estrutura nenhuma")
    r = extrair_texto_estruturado(str(corrompido))
    assert r["ok"] is False
    assert r["erro"].startswith("invalido")
    with pytest.raises(Exception) as ctx:
        _conversao(corrompido)
    assert "invalido" in str(ctx.value)


def test_pdf_protegido_por_senha_pede_senha(tmp_path):
    from pypdf import PdfWriter

    protegido = tmp_path / "senha.pdf"
    escritor = PdfWriter()
    escritor.add_blank_page(612, 792)
    escritor.encrypt("segredo123")
    with open(protegido, "wb") as f:
        escritor.write(f)

    r = extrair_texto_estruturado(str(protegido))
    assert r["ok"] is False
    assert "senha" in r["erro"].lower()
    with pytest.raises(Exception) as ctx:
        _conversao(protegido)
    assert "senha" in str(ctx.value).lower()


# ── DOC / MOBI: só caminhos de erro (não há como gerar Word/Kindle aqui) ─────

def test_doc_corrompido_devolve_erro_claro(tmp_path):
    doc = _txt(tmp_path, "ruim.doc", b"\x00\x01 nada de OLE2 aqui" * 20)
    r = extrair_texto_estruturado(str(doc))
    assert r["ok"] is False
    assert r["erro"].startswith("invalido")
    with pytest.raises(Exception) as ctx:
        _conversao(doc)
    assert "invalido" in str(ctx.value)


def test_mobi_corrompido_devolve_erro_claro(tmp_path):
    mobi = _txt(tmp_path, "ruim.mobi", b"BOOKMOBI" + b"\x00" * 60)
    r = extrair_texto_estruturado(str(mobi))
    assert r["ok"] is False
    assert r["erro"].startswith("invalido")
    with pytest.raises(Exception) as ctx:
        _conversao(mobi)
    assert "invalido" in str(ctx.value)


def test_arquivo_vazio_nao_derruba_nenhum_formato(tmp_path):
    for nome in ("vazio.epub", "vazio.docx", "vazio.pdf", "vazio.doc", "vazio.mobi", "vazio.txt"):
        caminho = _txt(tmp_path, nome, b"")
        r = extrair_texto_estruturado(str(caminho))
        assert r["ok"] is False, nome
        assert r["erro"], nome
        assert "Traceback" not in r["erro"], nome


# ── Revisão do consultor: segurança na conversão, ordem natural, blocos e páginas sem letras ─

def test_conversao_recusa_item_com_entidade_como_o_leitor(tmp_path):
    ruim = b'<!DOCTYPE html><html><body><p>Conteudo de entidade nao deve entrar.</p></body></html>'
    ruim = ruim.replace(b"<!DOCTYPE html>", b'<!DOCTYPE html [<!ENTITY a "b">]>')
    epub = _epub(tmp_path / "seg1.epub", [
        ("OEBPS/c1.xhtml", ruim),
        ("OEBPS/c2.xhtml", _xhtml("<p>Capitulo seguro entra na conversao.</p>")),
    ])
    conversao = _norm(_conversao(epub))
    assert "Capitulo seguro entra na conversao." in conversao
    assert "nao deve entrar" not in conversao


def test_conversao_recusa_item_acima_do_teto_como_o_leitor(tmp_path, monkeypatch):
    import audiobook_android

    monkeypatch.setattr(audiobook_android._LeitorZipSeguro, "LIMITE_ENTRADA", 300)
    grande = _xhtml("<p>" + "Texto enorme que estoura o teto de entrada. " * 40 + "</p>")
    epub = _epub(tmp_path / "seg2.epub", [
        ("OEBPS/c1.xhtml", grande),
        ("OEBPS/c2.xhtml", _xhtml("<p>Capitulo pequeno entra.</p>")),
    ])
    conversao = _norm(_conversao(epub))
    assert "Capitulo pequeno entra." in conversao
    assert "Texto enorme que estoura" not in conversao
    textos, _ = _paragrafos(epub)
    assert "Capitulo pequeno entra." in textos


def test_fallback_sem_opf_usa_ordem_natural_ch2_antes_de_ch10(tmp_path):
    arquivos = [
        ("OEBPS/ch10.xhtml", _xhtml("<p>Texto do capitulo dez, sem zero a esquerda.</p>")),
        ("OEBPS/ch2.xhtml", _xhtml("<p>Texto do capitulo dois, sem zero a esquerda.</p>")),
        ("OEBPS/ch1.xhtml", _xhtml("<p>Texto do capitulo um, sem zero a esquerda.</p>")),
    ]
    epub = _epub(tmp_path / "nat.epub", arquivos, opf=None)
    textos, _ = _paragrafos(epub)
    assert textos.index("Texto do capitulo dois, sem zero a esquerda.") < textos.index(
        "Texto do capitulo dez, sem zero a esquerda."
    )


def test_linhas_de_body_separam_celulas_de_tabela(tmp_path):
    corpo = (
        "<table><tr><td>Celula um do quadro, com texto longo.</td>"
        "<td>Celula dois do quadro, com texto longo.</td></tr></table>"
    )
    epub = _epub(tmp_path / "td.epub", [("OEBPS/c1.xhtml", _xhtml(corpo))])
    textos, _ = _paragrafos(epub)
    assert "Celula um do quadro, com texto longo." in textos
    assert "Celula dois do quadro, com texto longo." in textos


def test_linhas_de_body_preservam_id_de_div_simples():
    from bs4 import BeautifulSoup
    from audiobook_android import _blocos_de_texto_do_body

    soup = BeautifulSoup(
        '<html><body>Solto antes do bloco aqui.<div id="cap2">Texto do bloco com ancora.</div>'
        "</body></html>",
        "html.parser",
    )
    blocos = _blocos_de_texto_do_body(soup.find("body"))
    marcados = [b for b in blocos if b.get("id") == "cap2"]
    assert len(marcados) == 1
    assert marcados[0].get_text() == "Texto do bloco com ancora."
    assert all("\x01" not in b.get_text() and "\x02" not in b.get_text() for b in blocos)


def test_pdf_so_com_numeros_vira_escaneado(tmp_path):
    pdf = _pdf_com_texto(tmp_path / "numeros.pdf", [["12", "- 3 -"]])
    with pytest.raises(ValueError) as ctx:
        _conversao(pdf)
    assert "escaneado" in str(ctx.value)

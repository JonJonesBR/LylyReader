"""
audiobook_android.py - Entry point para o Kotlin via Chaquopy.

Uso no Kotlin:
    val py    = Python.getInstance()
    val mod   = py.getModule("audiobook_android")
    mod.callAttr("init", filesDir.absolutePath, cacheDir.absolutePath)
    mod.callAttr("set_gemini_keys", savedGeminiKeys)

    // Processar arquivo (roda em thread separada no Kotlin)
    val result = mod.callAttr("processar_arquivo_sync",
        "/storage/.../livro.epub",   // caminho do arquivo
        "pt-BR-ThalitaMultilingualNeural",  // voz
        "+0%",   // velocidade
        "+0Hz",  // tom
        "padrao" // estilo
    )
    // result é um PyObject - acesse com result.asMap() ou result["ok"].toBoolean()
"""

import asyncio
import tarfile
import threading
from typing import Optional

# ── Bootstrap ─────────────────────────────────────────────────────────────────



def init(files_dir: str, cache_dir: str):
    """
    DEVE ser chamado uma vez antes de qualquer processamento.
    Kotlin chama isso em Application.onCreate() ou MainActivity.onCreate().
    """
    import config_android
    config_android.init(files_dir, cache_dir)
    config_android.reload_config()
    import audio_cache
    # config_android.init() já reaponta audio_cache.CACHE.DIR


def set_gemini_keys(keys_csv: str):
    """Injeta chaves Gemini vindas do armazenamento local do app."""
    import config_android
    config_android.set_gemini_keys(keys_csv)


def set_openrouter_keys(keys_csv: str):
    """Injeta chaves OpenRouter vindas do armazenamento local do app."""
    import config_android
    config_android.set_openrouter_keys(keys_csv)


def set_motor(motor: str):
    """'edge', 'gemini' ou 'openrouter'"""
    import config_android
    config_android.set_motor(motor)
    # Persiste na config
    config_android.CONFIG["motor_tts"] = motor
    config_android.salvar_config(config_android.CONFIG)


def set_pronunciations(json_str: str):
    """Injeta o dicionário de pronúncia do usuário ([{"word","spoken"}])."""
    import pronunciation_dict
    pronunciation_dict.configurar(json_str)


def set_config(chave: str, valor):
    """Atualiza um campo de config e persiste."""
    import config_android
    config_android.CONFIG[chave] = valor
    config_android.salvar_config(config_android.CONFIG)


def get_vozes_json() -> str:
    """Retorna o catálogo de vozes como JSON para popular spinners na UI."""
    import json
    import config_android
    return json.dumps(config_android.VOZES_POR_CATEGORIA, ensure_ascii=False)


def get_gemini_vozes_json() -> str:
    import json
    import config_android
    return json.dumps(config_android.GEMINI_VOICES, ensure_ascii=False)


def get_openrouter_vozes_json() -> str:
    import json
    from openrouter_tts import montar_catalogo_vozes
    return json.dumps(montar_catalogo_vozes(), ensure_ascii=False)


def get_estilos_json() -> str:
    import json
    import config_android
    return json.dumps(config_android.ESTILOS_NARRACAO, ensure_ascii=False)


def get_historico_json() -> str:
    import json
    import config_android
    historico = config_android._carregar_historico()
    return json.dumps(historico, ensure_ascii=False)


def cancelar():
    """Cancela o processamento em andamento."""
    from core_processor_android import cancelar as _cancelar
    _cancelar()


def get_cache_info() -> str:
    """Retorna informações do cache de áudio como JSON."""
    import json
    from audio_cache import CACHE
    return json.dumps(CACHE.info())


def limpar_cache() -> int:
    """Limpa todo o cache de áudio. Retorna número de arquivos removidos."""
    from audio_cache import CACHE
    return CACHE.clear_all()


def preview_voz_sync(voz: str, velocidade: str = "+0%", texto: str = "") -> dict:
    """
    Sintetiza uma frase curta com a voz selecionada para preview (~5s).
    Chame sempre de uma thread de background.

    Retorna {"ok": True, "caminho": str} ou {"ok": False, "erro": str}.
    O arquivo MP3 gerado é temporário - o Kotlin deve deletá-lo após tocar.
    """
    import asyncio
    import os
    import uuid
    import traceback as _tb
    import config_android as _cfg

    if not texto:
        if voz.startswith("en-"):
            texto = (
                "Hello! This is a sample of the selected voice for your audiobook. "
                "I hope you enjoy it!"
            )
        else:
            texto = (
                "Olá! Esta é uma amostra da voz selecionada para o seu audiobook. "
                "Espero que goste!"
            )

    saida = os.path.join(_cfg.TEMP_DIR, f"preview_{uuid.uuid4().hex[:8]}.mp3")

    _cfg.SEMAFORO_TTS = None
    _cfg._RATE_LOCK = None
    loop = asyncio.new_event_loop()
    asyncio.set_event_loop(loop)
    try:
        from tts import converter_tts_auto
        ok = loop.run_until_complete(
            converter_tts_auto(texto, voz, saida, velocidade)
        )
        if ok and os.path.exists(saida) and os.path.getsize(saida) > 500:
            return {"ok": True, "caminho": saida}
        return {"ok": False, "erro": "Falha ao gerar áudio de amostra"}
    except BaseException as e:
        return {"ok": False, "erro": type(e).__name__ + ": " + str(e)}
    finally:
        loop.close()


def sintetizar_paragrafo_sync(
    texto: str,
    voz: str,
    velocidade: str = "+0%",
    tom: str = "+0Hz",
    motor: str = "edge",
    chave_gemini: str = "",
) -> dict:
    """
    Sintetiza um parágrafo de texto de forma síncrona.
    """
    import asyncio
    import os
    import uuid
    import config_android as _cfg

    # Atualiza configurações dinâmicas
    _cfg.set_motor(motor)
    _cfg.set_gemini_keys(chave_gemini)

    saida = os.path.join(_cfg.TEMP_DIR, f"paragrafo_{uuid.uuid4().hex[:8]}.mp3")

    # Zera as primitivas asyncio para o novo event loop (ver processar_arquivo_sync) — senão
    # a 2ª chamada falha com "Lock is bound to a different event loop".
    _cfg.SEMAFORO_TTS = None
    _cfg._RATE_LOCK = None
    loop = asyncio.new_event_loop()
    asyncio.set_event_loop(loop)
    try:
        from tts import converter_tts_auto
        ok = loop.run_until_complete(
            converter_tts_auto(texto, voz, saida, velocidade, tom)
        )
        if ok and os.path.exists(saida) and os.path.getsize(saida) > 500:
            return {"ok": True, "caminho": saida}
        return {"ok": False, "erro": "Falha ao gerar áudio do parágrafo"}
    except BaseException as e:
        return {"ok": False, "erro": type(e).__name__ + ": " + str(e)}
    finally:
        loop.close()


# ── Processamento síncrono (para rodar via executor no Kotlin) ────────────────

def extrair_texto_estruturado(caminho_arquivo: str) -> dict:
    """
    Extrai texto estruturado (capítulos + parágrafos) de EPUB, PDF, DOC(X), MOBI,
    TXT ou MD. Chamado pela ReaderActivity antes de exibir o leitor.

    Retorna:
        {"ok": True, "capitulos": [...], "paragrafos": [...], "total_chars": int}
        {"ok": False, "erro": str}
    """
    import os
    import traceback as _tb

    ext = os.path.splitext(caminho_arquivo)[1].lower()

    try:
        if ext == ".epub":
            return _extrair_epub(caminho_arquivo)
        elif ext == ".pdf":
            return _extrair_pdf(caminho_arquivo)
        elif ext == ".docx":
            return _extrair_docx(caminho_arquivo)
        elif ext == ".doc":
            return _extrair_doc(caminho_arquivo)
        elif ext == ".mobi":
            return _extrair_mobi(caminho_arquivo)
        elif ext in (".txt", ".md"):
            return _extrair_txt_md(caminho_arquivo)
        else:
            return {"ok": False, "erro": f"Formato não suportado: {ext}"}
    except Exception as e:
        return {"ok": False, "erro": type(e).__name__ + ": " + str(e) + "\n" + _tb.format_exc()}


def _montar_resultado(capitulos_raw, paragrafos_raw):
    """Monta o dicionário de retorno calculando charOffset acumulado."""
    offset = 0
    paragrafos = []
    for p in paragrafos_raw:
        paragrafos.append({
            "texto": p["texto"],
            "char_offset": offset,
            "capitulo_idx": p.get("capitulo_idx", 0)
        })
        offset += len(p["texto"])
    return {
        "ok": True,
        "capitulos": capitulos_raw,
        "paragrafos": paragrafos,
        "total_chars": offset
    }


def _extrair_toc_epub3(zf, nav_path: str) -> dict:
    """Lê o Navigation Document do EPUB3 (`<nav epub:type="toc">`, obrigatório pela spec) e
    devolve capítulos no MESMO formato do NCX ({arquivo_minusculo: [(âncora, título), ...]}) —
    usado quando o EPUB não tem `toc.ncx` (comum em livros gerados por ferramentas modernas,
    ex.: Pandoc, que só produzem o Navigation Document do EPUB3)."""
    from bs4 import BeautifulSoup
    import urllib.parse

    toc_chapters: dict = {}
    try:
        soup = BeautifulSoup(zf.read(nav_path), "html.parser")
        nav_dir = nav_path.rsplit("/", 1)[0] if "/" in nav_path else ""
        toc_nav = soup.find("nav", attrs={"epub:type": "toc"}) or soup.find("nav")
        if not toc_nav:
            return toc_chapters
        for a in toc_nav.find_all("a"):
            href = a.get("href", "")
            titulo = a.get_text().strip()
            if not href or not titulo:
                continue
            href_decoded = urllib.parse.unquote(href).replace("\\", "/")
            full = (nav_dir + "/" + href_decoded).replace("//", "/").lstrip("/")
            file_path, anchor = full.split("#", 1) if "#" in full else (full, "")
            toc_chapters.setdefault(file_path.lower(), []).append((anchor, titulo))
    except Exception:
        pass
    return toc_chapters


def _extrair_epub(caminho: str) -> dict:
    from bs4 import BeautifulSoup
    from narration_normalizer import eh_apenas_numeros, remover_digitos_isolados
    import zipfile, re, urllib.parse

    capitulos_raw = []
    paragrafos_raw = []
    cap_idx = 0

    with zipfile.ZipFile(caminho) as zf:
        namelist_lower = {n.lower(): n for n in zf.namelist()}

        # 1. Parse NCX (EPUB2) first to get reliable TOC chapters mapped to files and anchors
        ncx_path = next((n for n in zf.namelist() if n.lower().endswith(".ncx")), None)
        toc_chapters = {}
        if ncx_path:
            try:
                ncx_soup = BeautifulSoup(zf.read(ncx_path), "html.parser")
                ncx_dir = ncx_path.rsplit("/", 1)[0] if "/" in ncx_path else ""
                for np in ncx_soup.find_all("navpoint"):
                    nav_label = np.find("navlabel")
                    content_tag = np.find("content")
                    if nav_label and content_tag:
                        title = nav_label.get_text().strip()
                        src = content_tag.get("src", "")
                        if title and src:
                            src_decoded = urllib.parse.unquote(src).replace("\\", "/")
                            src_full = (ncx_dir + "/" + src_decoded).replace("//", "/").lstrip("/")
                            if "#" in src_full:
                                file_path, anchor = src_full.split("#", 1)
                            else:
                                file_path, anchor = src_full, ""
                            file_path = file_path.lower()
                            toc_chapters.setdefault(file_path, []).append((anchor, title))
            except Exception:
                pass

        # 2. Descobre a ordem de leitura via OPF (spine) + localiza o item de navegação
        # EPUB3 (properties="nav") pra usar como fallback do passo 3 abaixo.
        opf_path = None
        for name in zf.namelist():
            if name.lower().endswith(".opf"):
                opf_path = name
                break

        items_in_order = []
        nav_full_path = None
        if opf_path:
            try:
                soup = BeautifulSoup(zf.read(opf_path), "html.parser")
                item_map = {}
                basedir = opf_path.rsplit("/", 1)[0] if "/" in opf_path else ""
                for item in soup.find_all("item"):
                    href = item.get("href", "")
                    href = urllib.parse.unquote(href).replace("\\", "/").replace("//", "/")
                    item_id = item.get("id", "")
                    item_map[item_id] = href
                    if "nav" in (item.get("properties", "") or "").split():
                        nav_full_path = (basedir + "/" + href).replace("\\", "/").replace("//", "/").lstrip("/")

                for ref in soup.find_all("itemref"):
                    idref = ref.get("idref", "")
                    href = item_map.get(idref, "")
                    full = (basedir + "/" + href).replace("\\", "/").replace("//", "/").lstrip("/")

                    if full in zf.namelist():
                        items_in_order.append(full)
                    elif full.lower() in namelist_lower:
                        items_in_order.append(namelist_lower[full.lower()])
            except Exception:
                pass

        if not items_in_order:
            items_in_order = [n for n in zf.namelist() if n.lower().endswith((".xhtml", ".html", ".htm"))]

        # 3. EPUB3 sem toc.ncx (comum em livros sem o legado EPUB2): usa o Navigation Document
        # como fonte do TOC em vez de cair direto na heurística de headings h1/h2/h3 abaixo.
        if not toc_chapters and nav_full_path:
            nav_path = namelist_lower.get(nav_full_path.lower())
            if nav_path:
                toc_chapters = _extrair_toc_epub3(zf, nav_path)

        for item_path in items_in_order:
            try:
                content = zf.read(item_path)
                soup = BeautifulSoup(content, "html.parser")
            except Exception:
                continue

            item_path_lower = item_path.lower()
            file_chapters = toc_chapters.get(item_path_lower, [])
            # Âncora vazia OU igual ao id da própria <body> = "início do arquivo" (calibre e
            # outras ferramentas costumam gerar `<content src="arquivo.html#ID-DO-BODY">`
            # apontando pro id da tag <body> em vez de deixar a âncora vazia — sem esse
            # casamento, o capítulo nunca encontra onde começar e é descartado silenciosamente).
            body_tag = soup.find("body")
            body_id = body_tag.get("id") if body_tag else None
            anchors_to_titles = {
                anchor: title for anchor, title in file_chapters
                if anchor and anchor != body_id
            }
            start_title = next(
                (title for anchor, title in file_chapters if not anchor or anchor == body_id),
                None
            )

            if start_title:
                capitulos_raw.append({
                    "titulo": start_title,
                    "inicio": len(paragrafos_raw),
                    "fim": len(paragrafos_raw)
                })
                cap_idx = len(capitulos_raw) - 1

            # Só sintetiza capítulo a partir de heading (h1/h2/h3) quando o livro INTEIRO não
            # tem TOC curado (NCX/nav.xhtml) — nunca pra preencher arquivos fora do TOC de um
            # livro que TEM um curado. Livros reais costumam ter páginas fora do TOC que não são
            # capítulos (ex.: anúncio de "outros títulos" da editora, com <h2> pro nome de CADA
            # livro anunciado) — tratar heading nelas como capítulo novo quebra a navegação com
            # entradas falsas. Sem TOC nenhum, headings continuam sendo a única fonte disponível.
            if not toc_chapters:
                headings = soup.find_all(["h1", "h2", "h3"])
                for h in headings:
                    titulo = h.get_text().strip()
                    if titulo:
                        capitulos_raw.append({
                            "titulo": titulo,
                            "inicio": len(paragrafos_raw),
                            "fim": len(paragrafos_raw)
                        })
                        cap_idx = len(capitulos_raw) - 1

            # Parse blockquotes too, since calibre sometimes uses blockquote for standard paragraphs
            paras = soup.find_all(["p", "blockquote"])
            if not paras:
                body = soup.find("body")
                if body:
                    divs = [d for d in body.find_all("div") if not d.find(["div", "p", "blockquote"])]
                    divs = [d for d in divs if d.get_text().strip()]
                    if divs:
                        paras = divs
                    else:
                        text_content = body.get_text()
                        paras = []
                        for line in text_content.splitlines():
                            line_stripped = line.strip()
                            if line_stripped:
                                class PseudoTag:
                                    def __init__(self, t): self.t = t
                                    def get_text(self): return self.t
                                paras.append(PseudoTag(line_stripped))

            for p in paras:
                matched_title = None
                p_id = p.get("id")
                if p_id in anchors_to_titles:
                    matched_title = anchors_to_titles[p_id]
                else:
                    for a in p.find_all("a"):
                        a_id = a.get("id") or a.get("name")
                        if a_id in anchors_to_titles:
                            matched_title = anchors_to_titles[a_id]
                            break
                
                if matched_title:
                    capitulos_raw.append({
                        "titulo": matched_title,
                        "inicio": len(paragrafos_raw),
                        "fim": len(paragrafos_raw)
                    })
                    cap_idx = len(capitulos_raw) - 1

                texto = " ".join(p.get_text().split()).strip()
                # Ruído numérico (número de página solto ou separador decorativo remanescente
                # de conversão PDF→EPUB, ex.: "6 9 6" repetido centenas de vezes em vez de um
                # glifo decorativo) nunca é narração. Acontece tanto como parágrafo INTEIRO
                # (eh_apenas_numeros, descartado abaixo) quanto GRUDADO em prosa real do mesmo
                # <p> (remover_digitos_isolados, ex.: "...exclamou, 6 9 6 elevando a voz...") —
                # remove o segundo caso ANTES de decidir se o que sobrou ainda é narração.
                texto = " ".join(remover_digitos_isolados(texto).split())
                if len(texto) > 3 and not eh_apenas_numeros(texto):
                    paragrafos_raw.append({"texto": texto, "capitulo_idx": cap_idx})
                    if capitulos_raw:
                        capitulos_raw[-1]["fim"] = len(paragrafos_raw) - 1

    # EPUB com TOC "curado" mas DEGENERADO (ex.: NCX com um único navpoint genérico
    # "Start" apontando pro primeiro arquivo — calibre gera isso quando o original não
    # tinha marcação de capítulo real, comum em conversões de PDF) — sem isso, o livro
    # inteiro vira 1 "capítulo" só e a navegação/leitura guiada não tem por onde se guiar.
    # Só entra quando a extração por TOC/heading não achou nada além de 1 capítulo (nunca
    # sobrepõe um TOC curado de verdade — mesmo cuidado do bloco de headings acima) e
    # reaproveita a heurística textual já usada por PDF/DOCX/TXT (_titulo_capitulo_textual),
    # mas SEM o filtro de tamanho de _montar_capitulos_textuais — reclassifica os parágrafos
    # já extraídos no lugar, sem descartar nenhuma narração.
    if len(capitulos_raw) <= 1 and paragrafos_raw:
        capitulos_textuais = _recapitular_por_titulo_textual(paragrafos_raw)
        if len(capitulos_textuais) > 1:
            capitulos_raw = capitulos_textuais

    if not capitulos_raw and paragrafos_raw:
        capitulos_raw = [{"titulo": "Texto completo", "inicio": 0, "fim": len(paragrafos_raw) - 1}]

    if not paragrafos_raw:
        return {"ok": False, "erro": "vazio"}

    return _montar_resultado(capitulos_raw, paragrafos_raw)


def _extrair_pdf(caminho: str) -> dict:
    pypdf_result = _extrair_pdf_fallback(caminho)
    if pypdf_result.get("ok"):
        return pypdf_result

    try:
        from pdfminer.high_level import extract_pages
        from pdfminer.layout import LTTextBox
    except ImportError:
        # Fallback simples sem pdfminer
        return _extrair_pdf_fallback(caminho)

    blocos_por_pagina = []
    for page_layout in extract_pages(caminho):
        blocos_pagina = [
            element.get_text().strip()
            for element in page_layout
            if isinstance(element, LTTextBox) and element.get_text().strip()
        ]
        blocos_por_pagina.append(blocos_pagina)

    if not any(blocos_por_pagina):
        return {"ok": False, "erro": "vazio"}

    # Tenta capítulos reais (títulos textuais, "Capítulo N"/"Chapter N"/etc.) antes de cair
    # no fallback "1 capítulo por página" — mesma ordem de tentativa do caminho pypdf
    # (_extrair_pdf_fallback), evitando que PDFs com capítulos detectáveis caiam aqui sem
    # nem tentar (esse caminho pdfminer só roda quando o pypdf falha). Sem filtro de
    # tamanho aqui (um título de capítulo real costuma ser curto, ex.: "Capítulo 1") —
    # mesmo critério do blocos_documento em _extrair_pdf_fallback.
    blocos_documento = [b for blocos_pagina in blocos_por_pagina for b in blocos_pagina]
    estruturado = _montar_capitulos_textuais(blocos_documento)
    if estruturado is not None:
        return estruturado

    capitulos_raw = []
    paragrafos_raw = []
    for page_num, blocos_pagina in enumerate(blocos_por_pagina):
        capitulos_raw.append({
            "titulo": f"Página {page_num + 1}",
            "inicio": len(paragrafos_raw),
            "fim": len(paragrafos_raw)
        })
        cap_idx = len(capitulos_raw) - 1
        for texto in blocos_pagina:
            if len(texto) > 15:
                paragrafos_raw.append({"texto": texto, "capitulo_idx": cap_idx})
                capitulos_raw[-1]["fim"] = len(paragrafos_raw) - 1

    if not paragrafos_raw:
        return {"ok": False, "erro": "vazio"}

    return _montar_resultado(capitulos_raw, paragrafos_raw)


def _extrair_pdf_fallback(caminho: str) -> dict:
    """Fallback simples para PDFs quando o pdfminer falha ou nao esta disponivel."""
    try:
        from pypdf import PdfReader

        reader = PdfReader(caminho)
        capitulos_raw = []
        paragrafos_raw = []
        blocos_documento = []

        from utils import corrigir_texto_pdf, remover_ruido_pdf_paginas
        paginas = remover_ruido_pdf_paginas([page.extract_text() or "" for page in reader.pages])

        for page_num, texto in enumerate(paginas):
            texto = corrigir_texto_pdf(texto)
            blocos = [b.strip() for b in texto.split("\n\n") if b.strip()]
            if not blocos:
                blocos = [linha.strip() for java_linha in texto.splitlines() if (linha := java_linha.strip())]
            if not blocos:
                continue
            blocos_documento.extend(blocos)

            capitulos_raw.append({
                "titulo": f"Pagina {page_num + 1}",
                "inicio": len(paragrafos_raw),
                "fim": len(paragrafos_raw)
            })
            cap_idx = len(capitulos_raw) - 1

            for bloco in blocos:
                if len(bloco) > 20:
                    paragrafos_raw.append({
                        "texto": " ".join(bloco.split()),
                        "capitulo_idx": cap_idx
                    })
                    capitulos_raw[-1]["fim"] = len(paragrafos_raw) - 1

        estruturado = _montar_capitulos_textuais(blocos_documento)
        if estruturado is not None:
            return estruturado

        if not paragrafos_raw:
            return {"ok": False, "erro": "vazio"}

        return _montar_resultado(capitulos_raw, paragrafos_raw)
    except Exception as e:
        return {"ok": False, "erro": str(e)}


def _extrair_docx(caminho: str) -> dict:
    import zipfile
    import xml.etree.ElementTree as ET

    W = "{http://schemas.openxmlformats.org/wordprocessingml/2006/main}"
    capitulos_raw = []
    paragrafos_raw = []
    cap_idx = 0

    with zipfile.ZipFile(caminho) as zf:
        if "word/document.xml" not in zf.namelist():
            return {"ok": False, "erro": "DOCX inválido"}
        tree = ET.fromstring(zf.read("word/document.xml"))

    for p in tree.findall(f".//{W}p"):
        # Detecta estilo de heading
        style_tag = p.find(f".//{W}pStyle")
        style_val = style_tag.get(f"{W}val", "") if style_tag is not None else ""

        texto = "".join(t.text or "" for t in p.findall(f".//{W}t")).strip()
        if not texto:
            continue

        if "Heading" in style_val or "heading" in style_val or style_val in ("1", "2"):
            capitulos_raw.append({
                "titulo": texto,
                "inicio": len(paragrafos_raw),
                "fim": len(paragrafos_raw)
            })
            cap_idx = len(capitulos_raw) - 1
        elif len(texto) > 20:
            paragrafos_raw.append({"texto": texto, "capitulo_idx": cap_idx})
            if capitulos_raw:
                capitulos_raw[-1]["fim"] = len(paragrafos_raw) - 1

    if not capitulos_raw and paragrafos_raw:
        capitulos_raw = [{"titulo": "Documento", "inicio": 0, "fim": len(paragrafos_raw) - 1}]

    if not paragrafos_raw:
        return {"ok": False, "erro": "vazio"}

    return _montar_resultado(capitulos_raw, paragrafos_raw)


def _titulo_capitulo_textual(linha: str):
    """Return a chapter title when a plain-text line is structurally unambiguous."""
    import re
    titulo = linha.strip()
    if not titulo or len(titulo) > 100:
        return None
    padroes = (
        r"^(cap[i\u00ed]tulo|chapter|parte|part|livro|book)\s+([0-9]+|[ivxlcdm]+|one|two|three|first|second|primeiro|segundo)(?:\b.*)?$",
        r"^([ivxlcdm]+|\d+)[.)]\s*(?:[A-Z\u00c0-\u00dc].*)?$",
    )
    return titulo if any(re.match(p, titulo, re.IGNORECASE) for p in padroes) else None


def _recapitular_por_titulo_textual(paragrafos_raw):
    """Reclassifica capitulo_idx dos parágrafos JÁ EXTRAÍDOS de um EPUB usando marcadores
    de capítulo em texto puro (`_titulo_capitulo_textual`) — usado quando o TOC/heading do
    livro não rendeu capítulos de verdade (ver chamada em `_extrair_epub`). Ao contrário de
    `_montar_capitulos_textuais`, NÃO filtra por tamanho de parágrafo: só reatribui
    capitulo_idx nos dicts existentes (mutação in-place), preservando toda a narração já
    extraída (diálogos curtos incluídos)."""
    capitulos = []
    cap_idx = 0
    for i, paragrafo in enumerate(paragrafos_raw):
        titulo = _titulo_capitulo_textual(paragrafo["texto"])
        if titulo:
            capitulos.append({"titulo": titulo, "inicio": i, "fim": i})
            cap_idx = len(capitulos) - 1
        paragrafo["capitulo_idx"] = cap_idx
        if capitulos:
            capitulos[-1]["fim"] = i
    return capitulos


def _montar_capitulos_textuais(blocos):
    """Build chapters from plain-text headings, returning None when none exist."""
    capitulos = []
    paragrafos = []
    cap_idx = 0
    encontrou_titulo = False

    for bloco in blocos:
        texto = " ".join(str(bloco).split())
        titulo = _titulo_capitulo_textual(texto)
        if titulo:
            encontrou_titulo = True
            capitulos.append({"titulo": titulo, "inicio": len(paragrafos), "fim": len(paragrafos)})
            cap_idx = len(capitulos) - 1
            paragrafos.append({"texto": titulo, "capitulo_idx": cap_idx})
        elif len(texto) > 20:
            if not capitulos:
                capitulos.append({"titulo": "Introducao", "inicio": 0, "fim": 0})
            paragrafos.append({"texto": texto, "capitulo_idx": cap_idx})
            capitulos[-1]["fim"] = len(paragrafos) - 1

    if not encontrou_titulo or not paragrafos:
        return None
    return _montar_resultado(capitulos, paragrafos)


def _estruturar_texto_plano(conteudo: str) -> dict:
    """Divide um texto plano (sem marcação de capítulos além de headings
    Markdown/heurística textual) em capítulos/parágrafos. Usada por TXT/MD,
    DOC (após extração binária) e MOBI antigo (após extração de HTML)."""
    import re

    linhas = conteudo.splitlines()
    capitulos_raw = []
    paragrafos_raw = []
    cap_idx = 0
    buffer = []

    def flush_buffer():
        nonlocal buffer
        texto = " ".join(buffer).strip()
        if len(texto) > 20:
            paragrafos_raw.append({"texto": texto, "capitulo_idx": cap_idx})
            if capitulos_raw:
                capitulos_raw[-1]["fim"] = len(paragrafos_raw) - 1
        buffer = []

    for linha in linhas:
        stripped = linha.strip()
        # Detecta heading Markdown
        heading_match = re.match(r"^(#{1,3})\s+(.+)", stripped)
        divisor = stripped in ("---", "===", "***")

        titulo_textual = _titulo_capitulo_textual(stripped)
        if heading_match or titulo_textual:
            flush_buffer()
            titulo = heading_match.group(2).strip() if heading_match else titulo_textual
            capitulos_raw.append({
                "titulo": titulo,
                "inicio": len(paragrafos_raw),
                "fim": len(paragrafos_raw)
            })
            cap_idx = len(capitulos_raw) - 1
            paragrafos_raw.append({"texto": titulo, "capitulo_idx": cap_idx})
        elif divisor:
            flush_buffer()
        elif stripped == "":
            flush_buffer()
        else:
            buffer.append(stripped)

    flush_buffer()

    if not capitulos_raw and paragrafos_raw:
        capitulos_raw = [{"titulo": "Texto completo", "inicio": 0, "fim": len(paragrafos_raw) - 1}]

    if not paragrafos_raw:
        return {"ok": False, "erro": "vazio"}

    return _montar_resultado(capitulos_raw, paragrafos_raw)


def _extrair_txt_md(caminho: str) -> dict:
    try:
        with open(caminho, "r", encoding="utf-8") as f:
            conteudo = f.read()
    except UnicodeDecodeError:
        try:
            with open(caminho, "r", encoding="latin-1") as f:
                conteudo = f.read()
        except Exception:
            return {"ok": False, "erro": "encoding"}

    return _estruturar_texto_plano(conteudo)


def _extrair_doc(caminho: str) -> dict:
    import doc_extract

    try:
        conteudo = doc_extract.extrair_texto_doc(caminho)
    except doc_extract.DocParseError as e:
        return {"ok": False, "erro": str(e)}

    return _estruturar_texto_plano(conteudo)


def _extrair_mobi(caminho: str) -> dict:
    import shutil

    import mobi_extract

    try:
        kind, path, tempdir = mobi_extract.desempacotar(caminho)
    except mobi_extract.MobiParseError as e:
        return {"ok": False, "erro": str(e)}

    try:
        if kind == "epub":
            return _extrair_epub(path)
        if kind == "pdf":
            return _extrair_pdf(path)
        # kind == "html": Mobipocket antigo (KF7 puro), sem equivalente EPUB
        from bs4 import BeautifulSoup
        with open(path, "r", encoding="utf-8", errors="ignore") as f:
            soup = BeautifulSoup(f.read(), "html.parser")
        for tag in soup(["script", "style"]):
            tag.decompose()
        return _estruturar_texto_plano(soup.get_text(separator="\n"))
    finally:
        shutil.rmtree(tempdir, ignore_errors=True)


def processar_arquivo_sync(
    caminho_arquivo: str,
    voz: str                   = "pt-BR-ThalitaMultilingualNeural",
    velocidade: str            = "+0%",
    tom: str                   = "+0Hz",
    estilo: str                = "padrao",
    saida_mp3: str             = "",
    progress_callback          = None,
    blacklist                  = None,
) -> dict:
    """
    Versão SÍNCRONA de processar_arquivo() - bloqueia a thread atual.
    Chame sempre de uma thread de background (não da UI thread!).

    O Kotlin passa um lambda/interface como progress_callback:
        mod.callAttr("processar_arquivo_sync", ..., kotlinCallback)
    O callback recebe (pct: Int, msg: String).

    Retorna dict Python: {"ok": True/False, "caminho": str, "duracao": str, ...}
    """
    import traceback as _tb
    import logging as _logging
    _log = _logging.getLogger("audiobook")

    def _cb(pct: int, msg: str):
        if progress_callback is not None:
            try:
                progress_callback(pct, msg)
            except Exception:
                pass

    import config_android as _cfg
    # Primitivas asyncio (semáforo e rate lock) são vinculadas ao event loop em que foram
    # criadas. Como abrimos um loop novo a cada conversão, ambas PRECISAM ser zeradas — senão
    # a 2ª conversão (ou um livro grande retomado do cache, cujos blocos novos chamam
    # wait_for_tts_slot) falha com "Lock is bound to a different event loop".
    _cfg.SEMAFORO_TTS = None
    _cfg._RATE_LOCK = None

    loop = asyncio.new_event_loop()
    asyncio.set_event_loop(loop)  # Necessário em threads não-principais (Python 3.10+)
    try:
        from core_processor_android import processar_arquivo
        result = loop.run_until_complete(
            processar_arquivo(
                caminho_arquivo=caminho_arquivo,
                voz=voz,
                velocidade=velocidade,
                tom=tom,
                estilo=estilo,
                saida_mp3=saida_mp3,
                progress_cb=_cb,
                blacklist=blacklist,
            )
        )
        if result is None:
            _log.error("processar_arquivo retornou None inesperadamente")
            return {"ok": False, "erro": "Processamento retornou resultado vazio. Verifique o logcat."}
        return result
    except BaseException as e:
        tb_str = _tb.format_exc()
        _log.error("TRACEBACK COMPLETO:\n" + tb_str)
        return {"ok": False, "erro": type(e).__name__ + ": " + str(e) + "\n\n" + tb_str}
    finally:
        loop.close()


def processar_texto_sync(
    texto: str,
    voz: str                   = "pt-BR-ThalitaMultilingualNeural",
    velocidade: str            = "+0%",
    tom: str                   = "+0Hz",
    estilo: str                = "padrao",
    titulo: str                = "Audiobook",
    saida_mp3: str             = "",
    progress_callback          = None,
    blacklist                  = None,
    speaker_map_path: str      = "",
) -> dict:
    """
    Igual a processar_arquivo_sync mas recebe texto diretamente.
    Útil para converter textos colados na UI.
    """
    import traceback as _tb
    import logging as _logging
    _log = _logging.getLogger("audiobook")

    def _cb(pct: int, msg: str):
        if progress_callback is not None:
            try:
                progress_callback(pct, msg)
            except Exception:
                pass

    import config_android as _cfg
    _cfg.SEMAFORO_TTS = None
    _cfg._RATE_LOCK = None

    loop = asyncio.new_event_loop()
    asyncio.set_event_loop(loop)
    try:
        from core_processor_android import processar
        result = loop.run_until_complete(
            processar(
                texto=texto,
                voz=voz,
                velocidade=velocidade,
                tom=tom,
                estilo=estilo,
                titulo=titulo,
                saida_mp3=saida_mp3,
                progress_cb=_cb,
                blacklist=blacklist,
                speaker_map_path=speaker_map_path or None,
            )
        )
        if result is None:
            _log.error("processar retornou None inesperadamente")
            return {"ok": False, "erro": "Processamento retornou resultado vazio. Verifique o logcat."}
        return result
    except BaseException as e:
        tb_str = _tb.format_exc()
        _log.error("TRACEBACK COMPLETO:\n" + tb_str)
        return {"ok": False, "erro": type(e).__name__ + ": " + str(e) + "\n\n" + tb_str}
    finally:
        loop.close()

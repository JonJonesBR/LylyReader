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
import posixpath
import re
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


def cancelar_e_descartar():
    """Cancela o processamento e apaga o job dir ao parar (cancelamento definitivo)."""
    from core_processor_android import cancelar_e_descartar as _cancelar_e_descartar
    _cancelar_e_descartar()


def atualizar_tamanho_historico(antigo: str, novo: str) -> bool:
    """Depois de comprimir o WAV para AAC, corrige o tamanho da entrada do histórico desta conversão."""
    import config_android
    return config_android.atualizar_tamanho_historico(antigo, novo)


def gerar_previa() -> str:
    """Junta o que já foi gerado do job em andamento em previa.mp3. Devolve o caminho, ou "" se não há
    bloco pronto."""
    from core_processor_android import gerar_previa as _gerar_previa
    return _gerar_previa()


def descartar_job(jh: str) -> bool:
    """Apaga o job dir de uma conversão pausada descartada. Recusa hash em formato inválido."""
    from core_processor_android import descartar_job as _descartar_job
    return _descartar_job(jh)


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


class ArquivoSuspeitoError(ValueError):
    """Entrada de EPUB/DOCX recusada por parecer maliciosa (zip bomb, DTD/entidades)."""


_RE_ENTITY = re.compile(rb"<!ENTITY", re.IGNORECASE)
_RE_DOCTYPE = re.compile(rb"<!DOCTYPE", re.IGNORECASE)
# DOCTYPE com subconjunto interno (`<!DOCTYPE x [ ... ]>`). O `<!DOCTYPE html>` simples é
# usado por XHTML de EPUB válido, então no EPUB só o subconjunto interno é recusado.
_RE_DOCTYPE_INTERNO = re.compile(rb"<!DOCTYPE[^>]*\[", re.IGNORECASE)


def _verificar_xml_sem_dtd(dados: bytes, doctype_qualquer: bool) -> None:
    """Recusa `<!ENTITY` e DOCTYPE (qualquer, ou só com subconjunto interno se
    doctype_qualquer=False). Sem isso, entidades internas podem ser expandidas sem limite."""
    if _RE_ENTITY.search(dados):
        raise ArquivoSuspeitoError("XML com declaração <!ENTITY recusado")
    padrao = _RE_DOCTYPE if doctype_qualquer else _RE_DOCTYPE_INTERNO
    if padrao.search(dados):
        raise ArquivoSuspeitoError("XML com DOCTYPE/DTD recusado")


class _LeitorZipSeguro:
    """Lê entradas de um ZipFile com teto por entrada (50 MB) e no total (200 MB).
    O tamanho declarado no cabeçalho é checado antes; a leitura também é limitada, porque o
    cabeçalho pode mentir. Entradas que estouram o teto levantam ArquivoSuspeitoError."""

    LIMITE_ENTRADA = 50 * 1024 * 1024
    LIMITE_TOTAL = 200 * 1024 * 1024

    def __init__(self, zf, doctype_qualquer: bool = False):
        self._zf = zf
        self._doctype_qualquer = doctype_qualquer
        self._lido = 0

    def ler(self, nome: str) -> bytes:
        info = self._zf.getinfo(nome)
        if info.file_size > self.LIMITE_ENTRADA or self._lido + info.file_size > self.LIMITE_TOTAL:
            raise ArquivoSuspeitoError(f"entrada grande demais: {nome}")
        with self._zf.open(info) as f:
            dados = f.read(self.LIMITE_ENTRADA + 1)
        if len(dados) > self.LIMITE_ENTRADA or self._lido + len(dados) > self.LIMITE_TOTAL:
            raise ArquivoSuspeitoError(f"entrada descomprimida grande demais: {nome}")
        self._lido += len(dados)
        _verificar_xml_sem_dtd(dados, self._doctype_qualquer)
        return dados


def _caminho_no_zip(diretorio: str, href: str) -> str:
    """Caminho dentro do zip a partir do diretório do arquivo que referencia (OPF, NCX, nav) e
    de um href relativo. Resolve `../` e `./`: EPUB com OPF em subpasta usa isso com frequência."""
    bruto = f"{diretorio}/{href}" if diretorio else href
    return posixpath.normpath(bruto).lstrip("/")


def _extrair_toc_epub3(leitor: "_LeitorZipSeguro", nav_path: str) -> dict:
    """Lê o Navigation Document do EPUB3 (`<nav epub:type="toc">`, obrigatório pela spec) e
    devolve capítulos no MESMO formato do NCX ({arquivo_minusculo: [(âncora, título), ...]}) —
    usado quando o EPUB não tem `toc.ncx` (comum em livros gerados por ferramentas modernas,
    ex.: Pandoc, que só produzem o Navigation Document do EPUB3)."""
    from bs4 import BeautifulSoup
    import urllib.parse

    toc_chapters: dict = {}
    try:
        soup = BeautifulSoup(leitor.ler(nav_path), "html.parser")
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
            full = _caminho_no_zip(nav_dir, href_decoded)
            file_path, anchor = full.split("#", 1) if "#" in full else (full, "")
            toc_chapters.setdefault(file_path.lower(), []).append((anchor, titulo))
    except Exception:
        pass
    return toc_chapters


def _chave_ordem_natural(nome: str):
    """Chave de ordenação natural: "ch2" vem antes de "ch10" (números comparados como número)."""
    return [int(p) if p.isdigit() else p for p in re.split(r"(\d+)", nome.lower())]


class _LinhaDeTexto:
    """Linha de texto que imita só o que o laço de parágrafos do EPUB usa de uma tag do
    BeautifulSoup (`get`, `find_all`, `get_text`). Usada quando o arquivo não tem <p>, <blockquote>
    nem <div> folha (texto solto no <body>, <br>, <span>…). `id_` é o id de um bloco simples
    (<div id=...>) que contém esta linha, para o casamento com âncoras do TOC."""

    def __init__(self, texto: str, id_: str = None):
        self._texto = texto
        self._id = id_

    def get(self, _nome, padrao=None):
        return self._id if (_nome == "id" and self._id) else padrao

    def find_all(self, *_args, **_kwargs):
        return []

    def get_text(self) -> str:
        return self._texto


_TAGS_DE_BLOCO = [
    "div", "section", "article", "aside", "header", "footer", "h1", "h2", "h3", "h4", "h5",
    "h6", "li", "ul", "ol", "table", "tr", "td", "th", "caption", "dt", "dd", "hr",
]
_RE_ID_SIMPLES = re.compile(r"^[A-Za-z0-9_.:-]+$")
_MARCA_ID = "\x01"  # delimita o id de bloco embutido no texto (nunca aparece em livro)
_FIM_MARCA_ID = "\x02"


def _blocos_de_texto_do_body(body) -> list:
    """Quebra em blocos o texto de um <body> que não tem <p> nem <blockquote>.

    Se as <div> folha cobrem todas as palavras do body, cada uma vira um bloco (comportamento
    anterior). Senão há texto solto no body ou entre <div> aninhadas: então o texto é quebrado
    em linhas nas fronteiras de <br> e de blocos, sem perder nada."""
    folhas = [
        d for d in body.find_all("div")
        if not d.find(["div", "p", "blockquote"]) and d.get_text().strip()
    ]
    palavras_total = len(body.get_text().split())
    palavras_folhas = sum(len(d.get_text().split()) for d in folhas)
    if folhas and palavras_folhas >= palavras_total:
        return folhas

    for br in body.find_all("br"):
        br.replace_with("\n")
    for tag in body.find_all(_TAGS_DE_BLOCO):
        id_bloco = tag.get("id")
        if id_bloco and _RE_ID_SIMPLES.match(id_bloco):
            # Marca o id logo antes do bloco; a linha seguinte herda o id (ver abaixo).
            tag.insert_before(f"\n{_MARCA_ID}{id_bloco}{_FIM_MARCA_ID}")
        tag.insert_before("\n")
        tag.insert_after("\n")

    blocos = []
    id_pendente = None
    for linha in body.get_text().splitlines():
        if _MARCA_ID in linha:
            antes, _, resto = linha.partition(_MARCA_ID)
            id_pendente, _, depois = resto.partition(_FIM_MARCA_ID)
            linha = antes + depois
        linha = linha.strip()
        if not linha:
            continue
        blocos.append(_LinhaDeTexto(linha, id_pendente))
        id_pendente = None
    return blocos


def _extrair_epub(caminho: str) -> dict:
    from bs4 import BeautifulSoup
    from narration_normalizer import eh_apenas_numeros, remover_digitos_isolados
    import zipfile, re, urllib.parse

    capitulos_raw = []
    paragrafos_raw = []
    cap_idx = 0

    try:
        zf_ctx = zipfile.ZipFile(caminho)
    except zipfile.BadZipFile as e:
        return {"ok": False, "erro": f"invalido: arquivo EPUB corrompido ou não é um ZIP ({e})"}

    with zf_ctx as zf:
        leitor = _LeitorZipSeguro(zf)
        nomes_zip = set(zf.namelist())
        namelist_lower = {n.lower(): n for n in zf.namelist()}

        # 1. Parse NCX (EPUB2) first to get reliable TOC chapters mapped to files and anchors
        ncx_path = next((n for n in zf.namelist() if n.lower().endswith(".ncx")), None)
        toc_chapters = {}
        if ncx_path:
            try:
                ncx_soup = BeautifulSoup(leitor.ler(ncx_path), "html.parser")
                ncx_dir = ncx_path.rsplit("/", 1)[0] if "/" in ncx_path else ""
                for np in ncx_soup.find_all("navpoint"):
                    # recursive=False: um navPoint pai sem <content> próprio não pode pegar
                    # o título/conteúdo do filho aninhado.
                    nav_label = np.find("navlabel", recursive=False)
                    content_tag = np.find("content", recursive=False)
                    if nav_label and content_tag:
                        title = nav_label.get_text().strip()
                        src = content_tag.get("src", "")
                        if title and src:
                            src_decoded = urllib.parse.unquote(src).replace("\\", "/")
                            src_full = _caminho_no_zip(ncx_dir, src_decoded)
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
                soup = BeautifulSoup(leitor.ler(opf_path), "html.parser")
                item_map = {}
                basedir = opf_path.rsplit("/", 1)[0] if "/" in opf_path else ""
                for item in soup.find_all("item"):
                    href = item.get("href", "")
                    href = urllib.parse.unquote(href).replace("\\", "/")
                    item_id = item.get("id", "")
                    item_map[item_id] = href
                    if "nav" in (item.get("properties", "") or "").split():
                        nav_full_path = _caminho_no_zip(basedir, href)

                for ref in soup.find_all("itemref"):
                    idref = ref.get("idref", "")
                    href = item_map.get(idref, "")
                    if not href:
                        continue
                    full = _caminho_no_zip(basedir, href)

                    if full in nomes_zip:
                        items_in_order.append(full)
                    elif full.lower() in namelist_lower:
                        items_in_order.append(namelist_lower[full.lower()])
            except Exception:
                pass
            # Spine repetido ou com a mesma entrada duas vezes não pode duplicar o capítulo.
            items_in_order = list(dict.fromkeys(items_in_order))

        if not items_in_order:
            # Sem OPF/spine utilizável: todo HTML do zip, na ordem do nome (não a do arquivo).
            items_in_order = sorted(
                {n for n in zf.namelist() if n.lower().endswith((".xhtml", ".html", ".htm"))},
                key=_chave_ordem_natural,
            )

        # 3. EPUB3 sem toc.ncx (comum em livros sem o legado EPUB2): usa o Navigation Document
        # como fonte do TOC em vez de cair direto na heurística de headings h1/h2/h3 abaixo.
        if not toc_chapters and nav_full_path:
            nav_path = namelist_lower.get(nav_full_path.lower())
            if nav_path:
                toc_chapters = _extrair_toc_epub3(leitor, nav_path)

        for item_path in items_in_order:
            try:
                content = leitor.ler(item_path)
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
                    paras = _blocos_de_texto_do_body(body)

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
    from utils import MENSAGEM_PDF_ESCANEADO, erro_pdf_legivel

    pypdf_result = _extrair_pdf_fallback(caminho)
    if pypdf_result.get("ok"):
        return pypdf_result

    try:
        from pdfminer.high_level import extract_pages
        from pdfminer.layout import LTTextBox
    except ImportError:
        # Sem pdfminer: fica com o resultado do pypdf (vazio → mensagem de escaneado)
        return pypdf_result

    try:
        blocos_por_pagina = []
        for page_layout in extract_pages(caminho):
            blocos_pagina = [
                element.get_text().strip()
                for element in page_layout
                if isinstance(element, LTTextBox) and element.get_text().strip()
            ]
            blocos_por_pagina.append(blocos_pagina)
    except Exception as e:
        return {"ok": False, "erro": erro_pdf_legivel(e)}

    if not any(blocos_por_pagina):
        return {"ok": False, "erro": MENSAGEM_PDF_ESCANEADO}

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
        return {"ok": False, "erro": MENSAGEM_PDF_ESCANEADO}

    return _montar_resultado(capitulos_raw, paragrafos_raw)


def _extrair_pdf_fallback(caminho: str) -> dict:
    """Fallback simples para PDFs quando o pdfminer falha ou nao esta disponivel."""
    from utils import MENSAGEM_PDF_ESCANEADO, corrigir_texto_pdf, erro_pdf_legivel, remover_ruido_pdf_paginas

    try:
        from pypdf import PdfReader

        reader = PdfReader(caminho)
        capitulos_raw = []
        paragrafos_raw = []
        blocos_documento = []

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
            return {"ok": False, "erro": MENSAGEM_PDF_ESCANEADO}

        return _montar_resultado(capitulos_raw, paragrafos_raw)
    except Exception as e:
        return {"ok": False, "erro": erro_pdf_legivel(e)}


_RE_NOME_ESTILO_TITULO = re.compile(
    r"^(heading|t[ií]tulo|[üu]berschrift|titre|encabezado|titolo)\s*\d", re.IGNORECASE
)
_RE_ID_ESTILO_TITULO = re.compile(
    r"^(heading|t[ií]tulo|ttulo|titulo|berschrift|titre)\d*$", re.IGNORECASE
)


def _paragrafos_docx(caminho: str) -> list:
    """Lê todos os parágrafos de um DOCX, inclusive os de tabelas, listas, hyperlinks e caixas de
    texto (uma vez só, mesmo quando a caixa vem em mc:AlternateContent). Quebras de linha viram
    espaço. Devolve lista de (texto, eh_titulo); títulos são detectados pelo nome do estilo no
    styles.xml (funciona em qualquer idioma do Word), pelo id do estilo e pelo nível de contorno.
    Lança ValueError('invalido: ...') se o arquivo não for um DOCX legível."""
    import zipfile
    import xml.etree.ElementTree as ET

    W = "{http://schemas.openxmlformats.org/wordprocessingml/2006/main}"
    MC = "{http://schemas.openxmlformats.org/markup-compatibility/2006}"

    try:
        zf_ctx = zipfile.ZipFile(caminho)
    except zipfile.BadZipFile as e:
        raise ValueError(f"invalido: arquivo DOCX corrompido ou não é um ZIP ({e})") from e

    with zf_ctx as zf:
        if "word/document.xml" not in zf.namelist():
            raise ValueError("invalido: DOCX sem word/document.xml")
        # DOCX legítimo nunca tem DOCTYPE: recusa qualquer um (zip bomb/entidades) e o teto.
        leitor = _LeitorZipSeguro(zf, doctype_qualquer=True)
        tree = ET.fromstring(leitor.ler("word/document.xml"))
        estilos_titulo = set()
        if "word/styles.xml" in zf.namelist():
            estilos_xml = ET.fromstring(leitor.ler("word/styles.xml"))
            for st in estilos_xml.iter(f"{W}style"):
                sid = st.get(f"{W}styleId", "")
                nome_el = st.find(f"{W}name")
                nome = nome_el.get(f"{W}val", "") if nome_el is not None else ""
                nivel = st.find(f"{W}pPr/{W}outlineLvl")
                if (
                    _RE_NOME_ESTILO_TITULO.match(nome)
                    or _RE_ID_ESTILO_TITULO.match(sid)
                    or (nivel is not None and nivel.get(f"{W}val", "9") != "9")
                ):
                    estilos_titulo.add(sid)

    # Parágrafos dentro do ramo mc:Fallback repetem os de mc:Choice (caixa de texto em dobro).
    ignorados = set()
    for fallback in tree.iter(f"{MC}Fallback"):
        for p in fallback.iter(f"{W}p"):
            ignorados.add(id(p))

    def texto_do_paragrafo(p) -> str:
        partes = []

        def caminhar(el):
            for filho in el:
                if filho.tag in (f"{W}p", f"{W}txbxContent"):
                    continue  # caixa de texto tem parágrafos próprios, lidos à parte
                if filho.tag == f"{W}t":
                    partes.append(filho.text or "")
                elif filho.tag in (f"{W}br", f"{W}cr", f"{W}tab"):
                    partes.append(" ")
                else:
                    caminhar(filho)

        caminhar(p)
        return " ".join("".join(partes).split())

    resultado = []
    for p in tree.iter(f"{W}p"):
        if id(p) in ignorados:
            continue
        texto = texto_do_paragrafo(p)
        if not texto:
            continue
        style_tag = p.find(f"{W}pPr/{W}pStyle")
        style_val = style_tag.get(f"{W}val", "") if style_tag is not None else ""
        nivel = p.find(f"{W}pPr/{W}outlineLvl")
        eh_titulo = (
            style_val in estilos_titulo
            or _RE_ID_ESTILO_TITULO.match(style_val) is not None
            or "heading" in style_val.lower()
            or style_val in ("1", "2")
            or (nivel is not None and nivel.get(f"{W}val", "9") != "9")
        )
        resultado.append((texto, eh_titulo))
    return resultado


def _extrair_docx(caminho: str) -> dict:
    capitulos_raw = []
    paragrafos_raw = []
    cap_idx = 0

    try:
        paragrafos_lidos = _paragrafos_docx(caminho)
    except ValueError as e:
        return {"ok": False, "erro": str(e)}

    for texto, eh_titulo in paragrafos_lidos:
        if eh_titulo:
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
    from utils import decodificar_texto_bruto

    try:
        with open(caminho, "rb") as f:
            conteudo = decodificar_texto_bruto(f.read())
    except Exception as e:
        return {"ok": False, "erro": f"encoding: não foi possível ler o texto ({e})"}

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


def relatorio_normalizacao_json(texto: str, voz: str) -> str:
    """Relatório de conferência (antes → depois) da normalização que a síntese faria com esta voz.

    Usa o mesmo idioma e as mesmas flags de tts.converter_tts_auto e aplica antes o dicionário de
    pronúncia. Não sintetiza nada. Devolve JSON: {"ok": bool, "trocas": [{"antes","depois"}], "erro"?}.
    """
    import json
    import pronunciation_dict
    from text_normalizer import comparar_textos, relatorio_trocas
    from tts import _idioma_para_voz
    try:
        idioma = _idioma_para_voz(voz)
        trocas = []
        for linha in (texto or "").split("\n"):
            if not linha.strip():
                continue
            # 1) Dicionário de pronúncia: original → após o dicionário (é o que será falado antes da normalização).
            falado = pronunciation_dict.aplicar(linha)
            trocas.extend(comparar_textos(linha, falado))
            # 2) Normalizador sobre o texto já com o dicionário, como a síntese faz.
            trocas.extend(relatorio_trocas(
                falado,
                idioma=idioma,
                # Mesmas flags de tts.converter_tts_auto: o Pocket lê números soltos por extenso e
                # Pocket/Piper não podem receber travessão no início do trecho.
                numeros_soltos=voz.startswith("pocket-"),
                sem_travessoes=voz.startswith(("pocket-", "piper-")),
            ))
        return json.dumps({"ok": True, "trocas": trocas}, ensure_ascii=False)
    except Exception as e:
        return json.dumps({"ok": False, "erro": str(e), "trocas": []}, ensure_ascii=False)

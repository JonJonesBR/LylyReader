"""
cover_art.py - Gerenciamento de capas para audiobooks.

Funcionalidades:
  - Extrai capa de EPUBs automaticamente
  - Gera capas estilizadas quando não há imagem disponível
  - Embute capa nos MP3 via tag APIC (ID3)
"""

import os
import io
import hashlib
from pathlib import Path
from typing import Optional

from config_android import TEMP_DIR, logger

# Tamanho alvo da capa (quadrado, padrão para ID3/audiobook players)
COVER_SIZE = (600, 600)
COVER_QUALITY = 85  # JPEG quality


def extrair_capa_epub(caminho_epub: str) -> Optional[bytes]:
    """
    Extrai a imagem de capa de um arquivo EPUB.

    Estratégia:
    1. Procura item com propriedade 'cover-image' nos metadados
    2. Procura item de imagem com 'cover' no nome/id
    3. Usa a primeira imagem encontrada como fallback

    Returns:
        bytes da imagem ou None se não encontrar
    """
    try:
        import ebooklib
        from ebooklib import epub

        book = epub.read_epub(caminho_epub)

        # Estratégia 1: metadata cover-image
        cover_id = None
        for meta in book.get_metadata('OPF', 'meta'):
            attrs = meta[1] if len(meta) > 1 else {}
            if isinstance(attrs, dict) and attrs.get('name') == 'cover':
                cover_id = attrs.get('content')
                break

        if cover_id:
            for item in book.get_items():
                if item.get_id() == cover_id:
                    data = item.get_content()
                    if data and len(data) > 1000:
                        logger.info(f" Capa extraída via metadata cover: {item.get_name()} ({len(data)//1024}KB)")
                        return _processar_imagem(data)

        # Estratégia 2: item com 'cover' no nome
        for item in book.get_items_of_type(ebooklib.ITEM_IMAGE):
            nome = (item.get_name() or "").lower()
            item_id = (item.get_id() or "").lower()
            if "cover" in nome or "cover" in item_id:
                data = item.get_content()
                if data and len(data) > 1000:
                    logger.info(f" Capa extraída por nome: {item.get_name()} ({len(data)//1024}KB)")
                    return _processar_imagem(data)

        # Estratégia 3: primeira imagem > 10KB (provavelmente a capa)
        for item in book.get_items_of_type(ebooklib.ITEM_IMAGE):
            data = item.get_content()
            if data and len(data) > 10000:  # > 10KB para ignorar ícones
                logger.info(f" Capa extraída (primeira imagem grande): {item.get_name()} ({len(data)//1024}KB)")
                return _processar_imagem(data)

        logger.info(" EPUB sem capa detectável")
        return None

    except Exception as e:
        logger.warning(f"Erro ao extrair capa do EPUB: {e}")
        return None


def gerar_capa_padrao(titulo: str, autor: str = "Audiobook") -> bytes:
    """
    Gera uma capa estilizada para audiobooks sem capa própria.

    Design: Gradiente escuro elegante com título centralizado.

    Args:
        titulo: Título do livro / arquivo
        autor: Nome do narrador / artista

    Returns:
        bytes da imagem JPEG
    """
    try:
        from PIL import Image, ImageDraw, ImageFont

        width, height = COVER_SIZE
        img = Image.new("RGB", (width, height))
        draw = ImageDraw.Draw(img)

        # Gradiente de fundo baseado no hash do título (cor única por livro)
        h = int(hashlib.md5(titulo.encode()).hexdigest()[:3], 16) % 360
        _desenhar_gradiente(draw, width, height, h)

        # Overlay escuro semi-transparente para legibilidade
        overlay = Image.new("RGBA", (width, height), (0, 0, 0, 100))
        img = Image.alpha_composite(img.convert("RGBA"), overlay).convert("RGB")
        draw = ImageDraw.Draw(img)

        # Tentar carregar fonte de sistema
        font_titulo = _carregar_fonte(36)
        font_autor = _carregar_fonte(22)
        font_emoji = _carregar_fonte(64)

        # Ícone de fone de ouvido
        draw.text((width // 2, height // 3 - 40), "", font=font_emoji, fill="white", anchor="mm")

        # Título (quebra em múltiplas linhas se necessário)
        titulo_limpo = _limpar_titulo(titulo)
        linhas_titulo = _quebrar_texto(titulo_limpo, font_titulo, width - 80)
        y_titulo = height // 2 - 20
        for i, linha in enumerate(linhas_titulo[:4]):  # máximo 4 linhas
            draw.text(
                (width // 2, y_titulo + i * 44),
                linha, font=font_titulo, fill="white", anchor="mm"
            )

        # Linha decorativa
        y_linha = y_titulo + len(linhas_titulo[:4]) * 44 + 15
        draw.line(
            [(width // 4, y_linha), (3 * width // 4, y_linha)],
            fill=(255, 255, 255, 180), width=2
        )

        # Autor / Narrador
        draw.text(
            (width // 2, y_linha + 30),
            f" {autor}", font=font_autor, fill=(200, 200, 200), anchor="mm"
        )

        # Badge "AUDIOBOOK"
        badge_y = height - 50
        draw.rounded_rectangle(
            [(width // 2 - 70, badge_y - 14), (width // 2 + 70, badge_y + 14)],
            radius=10, fill=(255, 255, 255, 40), outline=(255, 255, 255, 80)
        )
        font_badge = _carregar_fonte(14)
        draw.text(
            (width // 2, badge_y), "AUDIOBOOK", font=font_badge,
            fill=(220, 220, 220), anchor="mm"
        )

        # Salvar como JPEG
        buf = io.BytesIO()
        img.save(buf, format="JPEG", quality=COVER_QUALITY)
        logger.info(f" Capa padrão gerada para: {titulo_limpo} ({buf.tell()//1024}KB)")
        return buf.getvalue()

    except Exception as e:
        logger.warning(f"Erro ao gerar capa padrão: {e}")
        return b""


def obter_capa(
    caminho_epub: Optional[str] = None,
    titulo: str = "Audiobook",
    autor: str = "Audiobook",
    capa_custom: Optional[bytes] = None,
) -> Optional[bytes]:
    """
    Obtém a capa na ordem de prioridade:
    1. Capa customizada enviada pelo usuário
    2. Capa extraída do EPUB
    3. Capa gerada automaticamente

    Returns:
        bytes da imagem JPEG pronta para embeber no MP3
    """
    # 1. Capa customizada
    if capa_custom and len(capa_custom) > 1000:
        logger.info(" Usando capa customizada")
        return _processar_imagem(capa_custom)

    # 2. Capa do EPUB
    if caminho_epub and os.path.exists(caminho_epub):
        capa = extrair_capa_epub(caminho_epub)
        if capa:
            return capa

    # 3. Gerar capa padrão
    return gerar_capa_padrao(titulo, autor)


def salvar_capa_temp(capa_bytes: bytes, nome: str = "cover") -> str:
    """Salva capa temporariamente e retorna o caminho."""
    os.makedirs(TEMP_DIR, exist_ok=True)
    caminho = os.path.join(TEMP_DIR, f"{nome}.jpg")
    with open(caminho, "wb") as f:
        f.write(capa_bytes)
    return caminho


# ── Helpers Internos ──────────────────────────────────────────────────


def _processar_imagem(data: bytes) -> bytes:
    """Redimensiona e converte imagem para JPEG no tamanho padrão."""
    try:
        from PIL import Image
        img = Image.open(io.BytesIO(data))
        img = img.convert("RGB")  # garante RGB (remove alpha, CMYK, etc.)
        img.thumbnail(COVER_SIZE, Image.Resampling.LANCZOS)

        # Criar imagem quadrada com padding preto se necessário
        if img.size[0] != img.size[1]:
            bg = Image.new("RGB", COVER_SIZE, (20, 20, 20))
            offset = ((COVER_SIZE[0] - img.size[0]) // 2, (COVER_SIZE[1] - img.size[1]) // 2)
            bg.paste(img, offset)
            img = bg

        buf = io.BytesIO()
        img.save(buf, format="JPEG", quality=COVER_QUALITY)
        return buf.getvalue()
    except Exception as e:
        logger.warning(f"Erro ao processar imagem: {e}")
        return data  # fallback: retorna original


def _desenhar_gradiente(draw, width: int, height: int, hue: int):
    """Desenha um gradiente diagonal baseado em HSL."""
    from PIL import ImageColor
    for y in range(height):
        ratio = y / height
        # Escurece de cima para baixo, variando saturação
        s = int(40 + ratio * 30)  # 40-70%
        l = int(25 - ratio * 12)  # 25-13%
        try:
            color = ImageColor.getrgb(f"hsl({hue}, {s}%, {l}%)")
        except Exception:
            color = (30, 30, 50)
        draw.line([(0, y), (width, y)], fill=color)


def _carregar_fonte(tamanho: int):
    """Tenta carregar uma fonte de sistema, com fallback para a padrão."""
    from PIL import ImageFont
    fontes_system = [
        "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf",
        "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
        "/usr/share/fonts/truetype/liberation/LiberationSans-Bold.ttf",
        "/usr/share/fonts/truetype/freefont/FreeSansBold.ttf",
        "/usr/share/fonts/truetype/noto/NotoSans-Bold.ttf",
    ]
    for caminho in fontes_system:
        if os.path.exists(caminho):
            try:
                return ImageFont.truetype(caminho, tamanho)
            except Exception:
                continue
    return ImageFont.load_default()


def _limpar_titulo(titulo: str) -> str:
    """Remove extensão e limpa o título."""
    # Remove extensões de arquivo
    for ext in [".epub", ".pdf", ".txt", ".docx", ".md"]:
        if titulo.lower().endswith(ext):
            titulo = titulo[:-len(ext)]
    # Remove underscores e hifens extras
    titulo = titulo.replace("_", " ").replace("-", " - ")
    return titulo.strip()


def _quebrar_texto(texto: str, font, max_width: int) -> list:
    """Quebra texto em linhas que cabem na largura máxima."""
    from PIL import ImageDraw, Image
    # Criar draw temporário para medir texto
    img_temp = Image.new("RGB", (1, 1))
    draw_temp = ImageDraw.Draw(img_temp)

    palavras = texto.split()
    linhas = []
    linha_atual = ""

    for palavra in palavras:
        teste = f"{linha_atual} {palavra}".strip()
        bbox = draw_temp.textbbox((0, 0), teste, font=font)
        largura = bbox[2] - bbox[0]
        if largura <= max_width:
            linha_atual = teste
        else:
            if linha_atual:
                linhas.append(linha_atual)
            linha_atual = palavra

    if linha_atual:
        linhas.append(linha_atual)

    return linhas if linhas else [texto[:40]]

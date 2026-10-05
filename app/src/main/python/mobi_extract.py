"""
mobi_extract.py - Extrator de texto para .mobi (Kindle/Mobipocket).

Usa o pacote `mobi` (puro Python) para desempacotar o container proprietario
da Amazon. Livros modernos (KF8/AZW3, a maioria hoje em dia) sao desempacotados
como um .epub de verdade, que reaproveita toda a logica de extracao de EPUB ja
existente no app (audiobook_android._extrair_epub / utils.extrair_texto_limpo).
Livros antigos (Mobipocket KF7 puro) viram um unico arquivo HTML; alguns raros
("Print Replica") viram um PDF - tratados como fallback.

O diretorio temporario que o mobi.extract() cria e responsabilidade do
CHAMADOR limpar (shutil.rmtree) apos usar o arquivo retornado.
"""
import shutil


class MobiParseError(Exception):
    pass


def desempacotar(caminho: str):
    """Desempacota um .mobi/.azw3.

    Retorna (kind, path, tempdir) onde kind in {"epub", "html", "pdf"}.
    Lanca MobiParseError se o arquivo for invalido ou o formato de saida for
    inesperado. O chamador DEVE limpar `tempdir` com shutil.rmtree quando
    terminar de usar `path` (inclusive no caminho de erro, quando aplicavel).
    """
    import mobi  # import tardio: so carrega o pacote quando um .mobi aparece

    try:
        tempdir, filepath = mobi.extract(caminho)
    except Exception as e:
        raise MobiParseError(f"invalido: nao foi possivel desempacotar o .mobi ({e})") from e

    if filepath.endswith(".epub"):
        return "epub", filepath, tempdir
    if filepath.endswith(".pdf"):
        return "pdf", filepath, tempdir
    if filepath.endswith(".html"):
        return "html", filepath, tempdir

    shutil.rmtree(tempdir, ignore_errors=True)
    raise MobiParseError(f"invalido: formato de saida inesperado ({filepath})")

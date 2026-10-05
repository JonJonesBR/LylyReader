package com.jonjonesbr.audiobookgen.domain

import org.json.JSONObject
import java.text.Normalizer

/** De onde veio o livro da biblioteca. */
enum class OrigemLivro { ARQUIVO, GUTENBERG, WIKISOURCE, ARCHIVE }

/** Um livro guardado na biblioteca do app (`filesDir/livros/<id>/`). */
data class LivroBiblioteca(
    val id: String,
    val titulo: String,
    val autor: String?,
    val origem: OrigemLivro,
    /** Chave do livro na fonte de busca (ex.: "gutenberg-55752"), quando veio de lá. */
    val chaveOrigem: String?,
    /** Extensão: epub, pdf, txt, md, docx, doc ou mobi. */
    val formato: String,
    val nomeArquivo: String,
    val tamanhoBytes: Long,
    val adicionadoEm: Long,
    val abertoEm: Long?,
    val temCapa: Boolean
) {
    fun toJson(): String = JSONObject().apply {
        put("id", id)
        put("titulo", titulo)
        autor?.let { put("autor", it) }
        put("origem", origem.name)
        chaveOrigem?.let { put("chaveOrigem", it) }
        put("formato", formato)
        put("nomeArquivo", nomeArquivo)
        put("tamanhoBytes", tamanhoBytes)
        put("adicionadoEm", adicionadoEm)
        abertoEm?.let { put("abertoEm", it) }
        put("temCapa", temCapa)
    }.toString()

    companion object {
        /** JSON inválido, sem id ou sem nome de arquivo → null. Campos opcionais ausentes viram o padrão. */
        fun fromJson(json: String): LivroBiblioteca? = try {
            val o = JSONObject(json)
            val id = o.optString("id").trim()
            val nomeArquivo = o.optString("nomeArquivo").trim()
            if (id.isEmpty() || nomeArquivo.isEmpty()) {
                null
            } else {
                LivroBiblioteca(
                    id = id,
                    titulo = o.optString("titulo").ifBlank { nomeExibicaoDoArquivo(nomeArquivo) },
                    autor = o.optString("autor").ifBlank { null },
                    origem = runCatching { OrigemLivro.valueOf(o.optString("origem")) }.getOrDefault(OrigemLivro.ARQUIVO),
                    chaveOrigem = o.optString("chaveOrigem").ifBlank { null },
                    formato = o.optString("formato").ifBlank { nomeArquivo.substringAfterLast('.', "").lowercase() },
                    nomeArquivo = nomeArquivo,
                    tamanhoBytes = o.optLong("tamanhoBytes", 0L),
                    adicionadoEm = o.optLong("adicionadoEm", 0L),
                    abertoEm = if (o.has("abertoEm")) o.optLong("abertoEm") else null,
                    temCapa = o.optBoolean("temCapa", false)
                )
            }
        } catch (_: Exception) {
            null
        }
    }
}

/** Origem pela chave da busca de livros ("gutenberg-…", "wikisource-…", "archive-…"); qualquer outra → arquivo. */
fun origemDaChave(chave: String?): OrigemLivro = when {
    chave == null -> OrigemLivro.ARQUIVO
    chave.startsWith("gutenberg-") -> OrigemLivro.GUTENBERG
    chave.startsWith("wikisource-") -> OrigemLivro.WIKISOURCE
    chave.startsWith("archive-") -> OrigemLivro.ARCHIVE
    else -> OrigemLivro.ARQUIVO
}

private const val TAMANHO_ID = 16

/** Id do livro = primeiros 16 hex do SHA-256 do conteúdo (reimportar o mesmo arquivo dá o mesmo id). */
fun idDoConteudo(sha256Hex: String): String = sha256Hex.take(TAMANHO_ID).lowercase()

private val prefixoEntrada = Regex("^entrada_\\d+_")

/** Nome para mostrar a partir do nome do arquivo: sem prefixo `entrada_<ts>_`, sem extensão, `_` virando espaço. */
fun nomeExibicaoDoArquivo(nome: String): String {
    val semPrefixo = nome.substringAfterLast('/').replaceFirst(prefixoEntrada, "")
    val semExtensao = if ('.' in semPrefixo) semPrefixo.substringBeforeLast('.') else semPrefixo
    return semExtensao.replace('_', ' ').replace(Regex("\\s+"), " ").trim().ifBlank { nome }
}

private val extensoesSuportadas = setOf("epub", "pdf", "txt", "md", "docx", "doc", "mobi")

/** O arquivo tem uma extensão que o app sabe abrir? */
fun ehFormatoSuportado(nome: String): Boolean =
    nome.substringAfterLast('.', "").lowercase() in extensoesSuportadas

/** Tira o prefixo `entrada_<número>_` das cópias antigas em cache, preservando a extensão. */
fun nomeSemPrefixoDeEntrada(nome: String): String = nome.replaceFirst(prefixoEntrada, "")

/** Diferença de tamanho (fração) até a qual duas cópias de mesmo nome são tratadas como versões do mesmo livro. */
const val TOLERANCIA_TAMANHO_VERSAO = 0.10

/**
 * Duas cópias antigas do cache são o mesmo livro (ex.: o texto editado no leitor e salvo de novo)?
 * Mesmo nome (sem prefixo `entrada_`, sem `(1)`, sem maiúsculas) E tamanhos até [TOLERANCIA_TAMANHO_VERSAO] diferentes.
 */
fun mesmaObra(nomeA: String, tamanhoA: Long, nomeB: String, tamanhoB: Long): Boolean {
    fun chave(n: String) = nomeSemPrefixoDeEntrada(n).trim().lowercase().replace(Regex("\\s*\\(\\d+\\)(?=\\.[^.]+$|$)"), "")
    if (chave(nomeA) != chave(nomeB)) return false
    val maior = maxOf(tamanhoA, tamanhoB).coerceAtLeast(1L)
    return Math.abs(tamanhoA - tamanhoB).toDouble() / maior <= TOLERANCIA_TAMANHO_VERSAO
}

/** Nomes que a pasta de um livro usa para os próprios arquivos de controle. */
private val nomesReservados = setOf("livro.json", "capa.jpg")

/** Garante que o arquivo do livro não colida com `livro.json`/`capa.jpg` nem comece com ponto (arquivo oculto de importação). */
fun nomeSeguroNaPasta(nome: String): String {
    val base = nome.trim().ifEmpty { "livro" }
    return if (base.lowercase() in nomesReservados || base.startsWith(".")) "livro_${base.trimStart('.')}" else base
}

/** Minúsculas e sem acentos, para comparar/ordenar. */
fun semAcentos(texto: String): String =
    Normalizer.normalize(texto, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase()

enum class AbaBiblioteca { TODOS, LENDO, COM_AUDIO, BAIXADOS }

/** Fração de leitura concluída considerada "terminou" (a partir daqui o livro sai de Lendo/Continuar). */
const val PROGRESSO_CONCLUIDO = 0.98f

/**
 * Filtra por aba e por texto de busca (título OU autor, sem diferenciar maiúsculas nem acentos).
 * [progresso] devolve a fração lida (0..1) ou null se desconhecida.
 */
fun filtrar(
    livros: List<LivroBiblioteca>,
    aba: AbaBiblioteca,
    consulta: String,
    progresso: (LivroBiblioteca) -> Float?,
    temAudio: (LivroBiblioteca) -> Boolean
): List<LivroBiblioteca> {
    val termo = semAcentos(consulta).trim()
    return livros.filter { livro ->
        val naAba = when (aba) {
            AbaBiblioteca.TODOS -> true
            AbaBiblioteca.LENDO -> progresso(livro)?.let { it > 0f && it < PROGRESSO_CONCLUIDO } == true
            AbaBiblioteca.COM_AUDIO -> temAudio(livro)
            AbaBiblioteca.BAIXADOS -> livro.origem != OrigemLivro.ARQUIVO
        }
        naAba && (termo.isEmpty() ||
            semAcentos(livro.titulo).contains(termo) ||
            (livro.autor?.let { semAcentos(it).contains(termo) } == true))
    }
}

enum class OrdemBiblioteca { RECENTES, TITULO, AUTOR }

fun ordenar(livros: List<LivroBiblioteca>, ordem: OrdemBiblioteca): List<LivroBiblioteca> = when (ordem) {
    OrdemBiblioteca.RECENTES -> livros.sortedByDescending { it.abertoEm ?: it.adicionadoEm }
    OrdemBiblioteca.TITULO -> livros.sortedBy { semAcentos(it.titulo) }
    OrdemBiblioteca.AUTOR -> livros.sortedWith(
        compareBy<LivroBiblioteca> { it.autor.isNullOrBlank() }.thenBy { semAcentos(it.autor.orEmpty()) }
            .thenBy { semAcentos(it.titulo) }
    )
}

/** Livro do cartão "Continuar": o aberto há menos tempo, com progresso conhecido e ainda não concluído. */
fun continuar(livros: List<LivroBiblioteca>, progresso: (LivroBiblioteca) -> Float?): LivroBiblioteca? =
    livros.filter { it.abertoEm != null && (progresso(it) ?: return@filter false) < PROGRESSO_CONCLUIDO }
        .maxByOrNull { it.abertoEm!! }

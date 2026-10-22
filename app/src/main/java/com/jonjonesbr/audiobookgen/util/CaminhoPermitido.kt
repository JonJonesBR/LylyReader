package com.jonjonesbr.audiobookgen.util

/**
 * Decide se um caminho (já canônico, ou uma URI `content://`) está sob uma das raízes permitidas.
 * Rejeita qualquer segmento `..` (mesmo que o chamador tenha esquecido de canonicalizar) e exige
 * o separador após a raiz, para que `/data/files` não aceite `/data/files2/x`.
 */
fun caminhoPermitido(canonico: String, raizes: List<String>): Boolean {
    if (canonico.isBlank()) return false
    if (canonico.split('/', '\\').any { it == ".." }) return false
    return raizes.any { raiz ->
        val base = raiz.trimEnd('/', '\\')
        base.isNotEmpty() && canonico.startsWith("$base/")
    }
}

package com.jonjonesbr.audiobookgen.util

import com.jonjonesbr.audiobookgen.domain.TextoExtraido
object EditableTextPolicy {
    fun updateParagraph(texto: TextoExtraido, index: Int, newText: String): TextoExtraido {
        val paragraph = texto.paragrafos.getOrNull(index)
        if (paragraph == null || paragraph.texto == newText) return texto

        val paragraphs = texto.paragrafos.toMutableList()
        paragraphs[index] = paragraph.copy(texto = newText)
        return recalculateOffsets(texto.copy(paragrafos = paragraphs))
    }

    private fun recalculateOffsets(texto: TextoExtraido): TextoExtraido {
        var offset = 0
        val paragraphs = texto.paragrafos.map { paragraph ->
            paragraph.copy(charOffset = offset).also {
                offset += paragraph.texto.length + 2
            }
        }
        return texto.copy(paragrafos = paragraphs, totalChars = offset)
    }
}

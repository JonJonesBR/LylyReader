package com.jonjonesbr.audiobookgen

import com.jonjonesbr.audiobookgen.domain.Paragrafo
import com.jonjonesbr.audiobookgen.domain.TextoExtraido
import com.jonjonesbr.audiobookgen.util.EditableTextPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class EditableTextPolicyTest {
    @Test
    fun updatesParagraphAndFollowingOffsets() {
        val original = TextoExtraido(
            capitulos = emptyList(),
            paragrafos = listOf(
                Paragrafo(texto = "First", charOffset = 0, indiceCapitulo = 0),
                Paragrafo(texto = "Second", charOffset = 7, indiceCapitulo = 0)
            ),
            totalChars = 13
        )

        val updated = EditableTextPolicy.updateParagraph(original, 0, "Long first")

        assertEquals("Long first", updated.paragrafos[0].texto)
        assertEquals(12, updated.paragrafos[1].charOffset)
        assertEquals(20, updated.totalChars)
    }

    @Test
    fun ignoresUnknownParagraph() {
        val original = TextoExtraido(
            capitulos = emptyList(),
            paragrafos = listOf(Paragrafo(texto = "Only", charOffset = 0, indiceCapitulo = 0)),
            totalChars = 4
        )

        assertSame(original, EditableTextPolicy.updateParagraph(original, 10, "Ignored"))
    }
}

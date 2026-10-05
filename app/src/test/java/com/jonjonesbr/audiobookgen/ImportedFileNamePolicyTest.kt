package com.jonjonesbr.audiobookgen

import com.jonjonesbr.audiobookgen.domain.ImportedFileNamePolicy
import org.junit.Assert.assertEquals
import org.junit.Test

class ImportedFileNamePolicyTest {
    @Test
    fun keepsSupportedExtension() {
        assertEquals(
            "book.pdf",
            ImportedFileNamePolicy.safeCacheName("book.pdf", "fallback", "application/pdf")
        )
    }

    @Test
    fun restoresExtensionFromMimeType() {
        assertEquals(
            "shared_document.pdf",
            ImportedFileNamePolicy.safeCacheName("shared_document", "fallback", "application/pdf")
        )
    }

    @Test
    fun removesPathSegmentsAndUnsafeCharacters() {
        assertEquals(
            "my_book_.epub",
            ImportedFileNamePolicy.safeCacheName("../folder/my book?.epub", "fallback", null)
        )
    }

    @Test
    fun usesFallbackWhenDisplayNameIsBlank() {
        assertEquals(
            "documento.txt",
            ImportedFileNamePolicy.safeCacheName("", "documento", "text/plain")
        )
    }
}

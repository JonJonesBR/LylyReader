package com.jonjonesbr.audiobookgen.domain


object ImportedFileNamePolicy {
    private val supportedExtensions = setOf("epub", "pdf", "txt", "md", "docx", "doc", "mobi")

    fun safeCacheName(rawName: String, fallbackName: String, mimeType: String?): String {
        val base = rawName
            .ifBlank { fallbackName }
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .trim()
            .ifBlank { "documento" }
        val currentExtension = base.substringAfterLast('.', "").lowercase()
        if (currentExtension in supportedExtensions) return base

        val mimeExtension = when (mimeType) {
            "application/epub+zip" -> "epub"
            "application/pdf" -> "pdf"
            "text/plain" -> "txt"
            "text/markdown" -> "md"
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> "docx"
            "application/msword" -> "doc"
            "application/x-mobipocket-ebook" -> "mobi"
            else -> null
        }
        return if (mimeExtension != null) "$base.$mimeExtension" else base
    }
}

package com.jonjonesbr.audiobookgen.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class DocumentExportMimeTest {

    @Test
    fun mp4ComFtypEhAudioMp4() {
        val m4a = byteArrayOf(0, 0, 0, 0x20) + "ftypM4A ".toByteArray()
        assertEquals("audio/mp4", mimeDoAudioExportado(m4a))
    }

    @Test
    fun wavComNomeDeMp3EContinuaAudioMpeg() {
        val wav = "RIFF".toByteArray() + ByteArray(4) + "WAVE".toByteArray()
        assertEquals("audio/mpeg", mimeDoAudioExportado(wav))
    }

    @Test
    fun mp3ComId3EContinuaAudioMpeg() {
        assertEquals("audio/mpeg", mimeDoAudioExportado("ID3\u0004\u0000\u0000\u0000\u0000\u0000\u0000".toByteArray()))
    }

    @Test
    fun arquivoCurtoOuVazioEhAudioMpeg() {
        assertEquals("audio/mpeg", mimeDoAudioExportado(ByteArray(0)))
        assertEquals("audio/mpeg", mimeDoAudioExportado("ftyp".toByteArray()))
    }
}

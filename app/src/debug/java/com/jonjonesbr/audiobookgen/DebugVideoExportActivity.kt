package com.jonjonesbr.audiobookgen

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Bundle
import android.util.Log
import com.jonjonesbr.audiobookgen.domain.OpcoesVideo
import com.jonjonesbr.audiobookgen.domain.VideoExportUseCase
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * SÓ build debug: roda o exportador de vídeo num MP3 passado por extra (`mp3`) e mede, em `logcat -s VideoTeste`, a duração
 * de cada trilha do MP4 gerado. Existe porque o MIUI bloqueia toque por adb e o bug "vídeo cortado" precisa de reprodução.
 */
class DebugVideoExportActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mp3 = File(intent.getStringExtra("mp3") ?: return finish())
        if (intent.getBooleanExtra("bench", false)) {
            Thread { benchmark(mp3); runOnUiThread { finish() } }.start()
            return
        }
        Thread {
            val pasta = File(getExternalFilesDir(null), "videoteste").apply { deleteRecursively(); mkdirs() }
            val capa = Bitmap.createBitmap(320, 180, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.DKGRAY) }
            Log.i(TAG, "origem: ${mp3.length()} bytes, duracao do extrator: ${duracaoUs(mp3)} us")
            val inicio = System.currentTimeMillis()
            val r = runBlocking { VideoExportUseCase().exportar(mp3, capa, pasta, OpcoesVideo(duracaoMaxParteMin = intent.getIntExtra("maxmin", OpcoesVideo().duracaoMaxParteMin))) }
            r.onSuccess { arquivos ->
                arquivos.forEach { medir(it) }
                Log.i(TAG, "FIM ok em ${System.currentTimeMillis() - inicio} ms; pasta ${pasta.absolutePath}")
            }.onFailure { Log.e(TAG, "FIM erro: ${it.message}", it) }
            runOnUiThread { finish() }
        }.start()
    }

    private fun duracaoUs(f: File): Long {
        val e = MediaExtractor()
        return try {
            e.setDataSource(f.absolutePath)
            (0 until e.trackCount).map { e.getTrackFormat(it) }.firstOrNull { it.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
                ?.let { if (it.containsKey(MediaFormat.KEY_DURATION)) it.getLong(MediaFormat.KEY_DURATION) else -1L } ?: -2L
        } finally { e.release() }
    }

    private fun medir(f: File) {
        val e = MediaExtractor()
        e.setDataSource(f.absolutePath)
        for (i in 0 until e.trackCount) {
            val fmt = e.getTrackFormat(i)
            e.selectTrack(i)
            var n = 0; var ultimo = 0L; var bytes = 0L
            val buf = java.nio.ByteBuffer.allocate(1 shl 20)
            while (true) {
                val t = e.readSampleData(buf, 0)
                if (t < 0) break
                n++; bytes += t; ultimo = e.sampleTime
                e.advance()
            }
            e.unselectTrack(i)
            Log.i(TAG, "${f.name} trilha $i ${fmt.getString(MediaFormat.KEY_MIME)} amostras=$n ultimoPts=${ultimo / 1000} ms bytes=$bytes KEY_DURATION=${if (fmt.containsKey(MediaFormat.KEY_DURATION)) fmt.getLong(MediaFormat.KEY_DURATION) / 1000 else -1} ms")
        }
        e.release()
    }

    /** Mede decoder MP3 e encoder AAC isolados (sem o laço de integração). */
    private fun benchmark(mp3: File) {
        val ex = MediaExtractor(); ex.setDataSource(mp3.absolutePath)
        ex.selectTrack(0)
        val fmt = ex.getTrackFormat(0)
        val dec = android.media.MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
        dec.configure(fmt, null, null, 0); dec.start()
        val info = android.media.MediaCodec.BufferInfo()
        var eos = false; var saidaEos = false; var bytes = 0L; var entradas = 0
        val t0 = System.nanoTime()
        while (!saidaEos) {
            if (!eos) {
                val i = dec.dequeueInputBuffer(2000)
                if (i >= 0) {
                    val b = dec.getInputBuffer(i)!!
                    val n = ex.readSampleData(b, 0)
                    if (n < 0) { dec.queueInputBuffer(i, 0, 0, 0, android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM); eos = true }
                    else { dec.queueInputBuffer(i, 0, n, ex.sampleTime, 0); ex.advance(); entradas++ }
                }
            }
            val o = dec.dequeueOutputBuffer(info, 2000)
            if (o >= 0) { bytes += info.size; dec.releaseOutputBuffer(o, false); if (info.flags and android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) saidaEos = true }
        }
        Log.i(TAG, "BENCH decoder: ${(System.nanoTime() - t0) / 1_000_000} ms, entradas=$entradas, pcm=$bytes bytes (${bytes / 2 / 24000} s a 24k mono)")
        dec.stop(); dec.release(); ex.release()
        val sr = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val af = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sr, 1).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, 64000)
            setInteger(MediaFormat.KEY_AAC_PROFILE, android.media.MediaCodecInfo.CodecProfileLevel.AACObjectLC)
        }
        val enc = android.media.MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        enc.configure(af, null, null, android.media.MediaCodec.CONFIGURE_FLAG_ENCODE); enc.start()
        val total = bytes; var enviado = 0L; var fimEnviado = false; var saidas = 0; var fim = false
        val t1 = System.nanoTime()
        var cap = -1
        while (!fim) {
            if (!fimEnviado) {
                val i = enc.dequeueInputBuffer(2000)
                if (i >= 0) {
                    val b = enc.getInputBuffer(i)!!
                    cap = b.capacity()
                    if (enviado >= total) { enc.queueInputBuffer(i, 0, 0, 0, android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM); fimEnviado = true }
                    else { val n = minOf(cap.toLong(), total - enviado).toInt(); b.clear(); b.put(ByteArray(n)); enc.queueInputBuffer(i, 0, n, enviado * 1_000_000L / 2 / sr, 0); enviado += n }
                }
            }
            val o = enc.dequeueOutputBuffer(info, 2000)
            if (o >= 0) { saidas++; enc.releaseOutputBuffer(o, false); if (info.flags and android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) fim = true }
        }
        Log.i(TAG, "BENCH encoder: ${(System.nanoTime() - t1) / 1_000_000} ms, bufferEntrada=$cap, saidas=$saidas")
        enc.stop(); enc.release()
    }

    private companion object { const val TAG = "VideoTeste" }
}

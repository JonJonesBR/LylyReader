package com.jonjonesbr.audiobookgen.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.util.AttributeSet
import android.util.LruCache
import androidx.appcompat.widget.AppCompatImageView
import com.jonjonesbr.audiobookgen.domain.corDaCapa
import com.jonjonesbr.audiobookgen.domain.tamanhoRelativoDoTitulo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * Capa para livros sem imagem própria: fundo de lombada, título em serifa, filete e autor em versalete.
 * Mesmas cores em qualquer tema (como a capa de um livro físico).
 */
class CapaTipografica(
    private val titulo: String,
    private val autor: String?,
    id: String,
    serifa: Typeface? = null
) : Drawable() {

    private val fundo = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = corDaCapa(id) }
    private val tinta = 0xFFF4EFE6.toInt()
    private val paintTitulo = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = tinta
        typeface = Typeface.create(serifa ?: Typeface.SERIF, Typeface.BOLD)
    }
    private val paintAutor = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = tinta
        alpha = ALPHA_AUTOR
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        letterSpacing = 0.06f
    }
    private val paintFilete = Paint().apply {
        color = tinta
        alpha = ALPHA_FILETE
    }

    override fun draw(canvas: Canvas) {
        val area = bounds
        val largura = area.width().toFloat()
        val altura = area.height().toFloat()
        if (largura <= 0f || altura <= 0f) return
        val raio = largura * 0.04f
        canvas.drawRoundRect(area.left.toFloat(), area.top.toFloat(), area.right.toFloat(), area.bottom.toFloat(), raio, raio, fundo)

        val margem = largura * 0.10f
        val util = (largura - 2 * margem).toInt().coerceAtLeast(1)
        paintTitulo.textSize = largura * tamanhoRelativoDoTitulo(titulo)
        val layoutTitulo = montar(titulo, paintTitulo, util, MAX_LINHAS_TITULO, 1.05f)

        var alturaAutor = 0f
        var layoutAutor: StaticLayout? = null
        if (!autor.isNullOrBlank()) {
            paintAutor.textSize = largura * 0.075f
            layoutAutor = montar(autor.uppercase(Locale.getDefault()), paintAutor, util, MAX_LINHAS_AUTOR, 1f)
            alturaAutor = layoutAutor.height.toFloat()
        }

        canvas.save()
        canvas.translate(area.left + margem, area.top + margem)
        layoutTitulo.draw(canvas)
        canvas.restore()

        if (layoutAutor != null) {
            val yAutor = area.bottom - margem - alturaAutor
            val yFilete = yAutor - margem * 0.5f
            canvas.drawRect(area.left + margem, yFilete, area.left + margem + util, yFilete + maxOf(1f, largura * 0.006f), paintFilete)
            canvas.save()
            canvas.translate(area.left + margem, yAutor)
            layoutAutor.draw(canvas)
            canvas.restore()
        }
    }

    private fun montar(texto: String, paint: TextPaint, largura: Int, maxLinhas: Int, espaco: Float): StaticLayout =
        StaticLayout.Builder.obtain(texto, 0, texto.length, paint, largura)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setMaxLines(maxLinhas)
            .setEllipsize(TextUtils.TruncateAt.END)
            .setLineSpacing(0f, espaco)
            .build()

    override fun setAlpha(alpha: Int) { fundo.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { fundo.colorFilter = colorFilter }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    override fun getIntrinsicWidth(): Int = LARGURA_INTRINSECA
    override fun getIntrinsicHeight(): Int = LARGURA_INTRINSECA * 3 / 2

    private companion object {
        const val MAX_LINHAS_TITULO = 5
        const val MAX_LINHAS_AUTOR = 2
        const val ALPHA_AUTOR = 217
        const val ALPHA_FILETE = 128
        const val LARGURA_INTRINSECA = 200
    }
}

/** Capa com proporção fixa 2:3 (altura = 1,5 × largura), qualquer que seja a imagem. */
class CapaView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : AppCompatImageView(context, attrs) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        setMeasuredDimension(measuredWidth, measuredWidth * 3 / 2)
    }
}

/** Capas reais (`capa.jpg` da biblioteca) com cache em memória; falha = null (a tela usa a capa tipográfica). */
object CapasDaBiblioteca {
    private const val CACHE_KB = 6 * 1024
    private const val LADO_MAX_PX = 480

    private val cache = object : LruCache<String, Bitmap>(CACHE_KB) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount / 1024
    }

    fun emCache(arquivo: File): Bitmap? = cache.get(chave(arquivo))

    private fun chave(arquivo: File) = arquivo.absolutePath + "@" + arquivo.lastModified()

    @Suppress("TooGenericExceptionCaught")
    suspend fun carregar(arquivo: File): Bitmap? = emCache(arquivo) ?: withContext(Dispatchers.IO) {
        try {
            val limites = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(arquivo.absolutePath, limites)
            var amostra = 1
            while (limites.outWidth / (amostra * 2) >= LADO_MAX_PX && limites.outHeight / (amostra * 2) >= LADO_MAX_PX) amostra *= 2
            BitmapFactory.decodeFile(arquivo.absolutePath, BitmapFactory.Options().apply { inSampleSize = amostra })
                ?.also { cache.put(chave(arquivo), it) }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }
}

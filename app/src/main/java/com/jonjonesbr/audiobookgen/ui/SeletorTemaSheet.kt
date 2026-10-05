package com.jonjonesbr.audiobookgen.ui

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.jonjonesbr.audiobookgen.R
import com.jonjonesbr.audiobookgen.util.ThemePrefs

/**
 * Seletor de tema do app (Seguir o sistema · Claro · Escuro · Papel). É o MESMO componente na Biblioteca, em Ajustes, no menu
 * da tela inicial e no painel de aparência do leitor: todos gravam a mesma preferência ([ThemePrefs]).
 */
object SeletorTemaSheet {

    private class Opcao(val modo: Int, val nome: Int, val descricao: Int, val fundo: Int, val destaque: Int, val texto: Int)

    // Amostras das paletas aprovadas (fundo, destaque, texto); "Seguir o sistema" mostra Claro | Escuro lado a lado.
    private val CLARO = Triple(0xFFFDFBFF.toInt(), 0xFF6D28D9.toInt(), 0xFF1C1B1F.toInt())
    private val ESCURO = Triple(0xFF141218.toInt(), 0xFFD0BCFF.toInt(), 0xFFE6E0E9.toInt())
    private val PAPEL = Triple(0xFFF5EEDC.toInt(), 0xFF6B4226.toInt(), 0xFF3B2F20.toInt())

    private val opcoes = listOf(
        Opcao(ThemePrefs.SYSTEM, R.string.theme_system, R.string.tema_desc_sistema, CLARO.first, CLARO.second, CLARO.third),
        Opcao(ThemePrefs.LIGHT, R.string.theme_light, R.string.tema_desc_claro, CLARO.first, CLARO.second, CLARO.third),
        Opcao(ThemePrefs.DARK, R.string.theme_dark, R.string.tema_desc_escuro, ESCURO.first, ESCURO.second, ESCURO.third),
        Opcao(ThemePrefs.PAPEL, R.string.theme_papel, R.string.tema_desc_papel, PAPEL.first, PAPEL.second, PAPEL.third)
    )

    /** Nome do tema atual (para mostrar num item de lista). */
    fun nomeDoModo(modo: Int): Int = (opcoes.firstOrNull { it.modo == modo } ?: opcoes.first()).nome

    fun mostrar(activity: AppCompatActivity) {
        val atual = ThemePrefs.load(activity)
        val folha = BottomSheetDialog(activity)
        val raiz = LayoutInflater.from(activity).inflate(R.layout.bottom_sheet_tema, null) as LinearLayout
        val lista = raiz.findViewById<LinearLayout>(R.id.listaTemas)
        opcoes.forEach { opcao ->
            val linha = LayoutInflater.from(activity).inflate(R.layout.item_tema_opcao, lista, false)
            linha.findViewById<ImageView>(R.id.ivTemaAmostra).setImageDrawable(
                AmostraDeTema(opcao.fundo, opcao.destaque, opcao.texto, divididoComEscuro = opcao.modo == ThemePrefs.SYSTEM)
            )
            linha.findViewById<TextView>(R.id.tvTemaNome).setText(opcao.nome)
            linha.findViewById<TextView>(R.id.tvTemaDescricao).setText(opcao.descricao)
            linha.findViewById<View>(R.id.ivTemaMarca).visibility = if (opcao.modo == atual) View.VISIBLE else View.INVISIBLE
            linha.setOnClickListener {
                folha.dismiss()
                if (opcao.modo != atual) {
                    ThemePrefs.save(activity, opcao.modo)
                    ThemePrefs.apply(opcao.modo)
                    activity.recreate() // as outras telas abertas se recriam sozinhas ao voltar (ThemePrefs.instalar)
                }
            }
            lista.addView(linha)
        }
        folha.setContentView(raiz)
        folha.show()
    }
}

/** Mini tela de exemplo do tema: fundo, duas linhas de texto e uma pílula de destaque. */
private class AmostraDeTema(
    private val fundo: Int,
    private val destaque: Int,
    private val texto: Int,
    private val divididoComEscuro: Boolean
) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val contorno = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0x33808080
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        val w = b.width().toFloat()
        val h = b.height().toFloat()
        val raio = w * 0.22f
        canvas.save()
        val area = RectF(b)
        val recorte = android.graphics.Path().apply { addRoundRect(area, raio, raio, android.graphics.Path.Direction.CW) }
        canvas.clipPath(recorte)
        paint.color = fundo
        canvas.drawRect(area, paint)
        if (divididoComEscuro) {
            paint.color = 0xFF141218.toInt()
            canvas.drawRect(b.left + w / 2f, b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat(), paint)
        }
        canvas.restore()
        contorno.strokeWidth = maxOf(1f, w * 0.03f)
        canvas.drawRoundRect(area, raio, raio, contorno)

        val m = w * 0.2f
        paint.color = texto
        canvas.drawRoundRect(RectF(b.left + m, b.top + h * 0.28f, b.right - m * 1.6f, b.top + h * 0.28f + h * 0.09f), h, h, paint)
        canvas.drawRoundRect(RectF(b.left + m, b.top + h * 0.45f, b.right - m * 2.4f, b.top + h * 0.45f + h * 0.09f), h, h, paint)
        paint.color = destaque
        canvas.drawRoundRect(RectF(b.left + m, b.top + h * 0.64f, b.left + m + w * 0.34f, b.top + h * 0.64f + h * 0.14f), h, h, paint)
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

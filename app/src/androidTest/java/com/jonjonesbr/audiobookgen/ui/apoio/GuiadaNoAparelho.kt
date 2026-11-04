package com.jonjonesbr.audiobookgen.ui.apoio

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ActivityScenario
import com.jonjonesbr.audiobookgen.data.AppPrefs
import com.jonjonesbr.audiobookgen.ui.ReaderActivity

/**
 * Abre o livro de teste com a posição salva em [posicaoInicial] (padrão: o começo) e a leitura guiada pedida
 * ("Continuar lendo", sem diálogo). [textos] troca os parágrafos do livro (padrão: os de sempre). Quem chama já põe
 * a voz que quer ([RegrasDoLeitor.usarVoz]) antes.
 */
fun abrirLeitorComGuiada(
    regras: RegrasDoLeitor,
    contexto: Context,
    textos: List<String> = LivroNoAparelho.PARAGRAFOS,
    posicaoInicial: Int = 0,
): ActivityScenario<ReaderActivity> {
    val arquivo = LivroNoAparelho.gravarLivroNoCache(textos)
    AppPrefs(contexto).salvarPosicaoLeitura(arquivo.absolutePath, posicaoInicial, textos.size)
    val intent = Intent(contexto, ReaderActivity::class.java)
        .putExtra(ReaderActivity.EXTRA_CAMINHO, arquivo.absolutePath)
        .putExtra(ReaderActivity.EXTRA_CONTINUAR_GUIADA, true)
    return regras.abrir(intent)
}

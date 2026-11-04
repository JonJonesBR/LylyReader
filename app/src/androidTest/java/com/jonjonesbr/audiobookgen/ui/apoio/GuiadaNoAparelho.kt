package com.jonjonesbr.audiobookgen.ui.apoio

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ActivityScenario
import com.jonjonesbr.audiobookgen.data.AppPrefs
import com.jonjonesbr.audiobookgen.ui.ReaderActivity

/**
 * Abre o livro de teste com a posição salva no começo e a leitura guiada pedida ("Continuar lendo", sem diálogo).
 * Quem chama já põe a voz que quer ([RegrasDoLeitor.usarVoz]) antes.
 */
fun abrirLeitorComGuiada(regras: RegrasDoLeitor, contexto: Context): ActivityScenario<ReaderActivity> {
    val arquivo = LivroNoAparelho.gravarLivroNoCache()
    AppPrefs(contexto).salvarPosicaoLeitura(arquivo.absolutePath, 0, LivroNoAparelho.PARAGRAFOS.size)
    val intent = Intent(contexto, ReaderActivity::class.java)
        .putExtra(ReaderActivity.EXTRA_CAMINHO, arquivo.absolutePath)
        .putExtra(ReaderActivity.EXTRA_CONTINUAR_GUIADA, true)
    return regras.abrir(intent)
}

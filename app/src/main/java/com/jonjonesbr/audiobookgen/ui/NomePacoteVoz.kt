package com.jonjonesbr.audiobookgen.ui

import android.content.Context
import com.jonjonesbr.audiobookgen.R

/**
 * Nome exibido de um pacote de voz. O catálogo guarda um nome fixo (português); pacotes que têm
 * nome traduzido usam o recurso do idioma atual. Os demais mantêm o nome do catálogo.
 */
internal fun nomeExibidoDoPacote(context: Context, id: String, nomePadrao: String): String =
    if (id == "supertonic-v1") context.getString(R.string.pacote_nome_supertonic_ingles) else nomePadrao

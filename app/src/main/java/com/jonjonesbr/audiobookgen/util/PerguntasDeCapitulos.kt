package com.jonjonesbr.audiobookgen.util

/**
 * Se vale perguntar "qual capítulo?" antes de gerar, exportar ou começar a ler. Com um capítulo só (ex.: TXT
 * sem divisão, "Texto completo") ou nenhum, a pergunta não tem o que escolher: a ação vai direto ao livro inteiro.
 */
fun valePerguntarPorCapitulo(totalCapitulos: Int): Boolean = totalCapitulos > 1

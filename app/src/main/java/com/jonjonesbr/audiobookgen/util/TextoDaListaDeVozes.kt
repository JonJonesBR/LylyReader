package com.jonjonesbr.audiobookgen.util

/**
 * Textos da lista de vozes (folha "Selecionar voz"), em funções puras para testar sem o Android.
 * Cada voz ocupa duas linhas: o nome na primeira; motor, gênero e conexão na segunda.
 */
object TextoDaListaDeVozes {

    /** Nome que termina em "(…)": grupo 1 é o nome, grupo 2 o que está entre parênteses. */
    private val PARENTESES_FINAIS = Regex("^(.*\\S)\\s+\\(([^()]*)\\)$")

    /**
     * Tira o motor entre parênteses do nome, quando ele já aparece na linha de detalhe:
     * "Jeff (Piper media)" com motor "Piper" vira "Jeff". Um nome cujo parêntese não é o motor
     * ("Português (MMS)" com motor "Kokoro") fica como está. Motor em branco também não mexe no nome.
     */
    fun nomeSemMotor(nome: String, motor: String): String {
        val casamento = PARENTESES_FINAIS.find(nome)
        val dentroDosParenteses = casamento?.groupValues?.get(2).orEmpty()
        val tirarMotor = motor.isNotBlank() && casamento != null &&
            dentroDosParenteses.startsWith(motor, ignoreCase = true)
        return if (tirarMotor && casamento != null) casamento.groupValues[1] else nome
    }

    /** Número de cada item dentro do próprio idioma, na ordem da lista: [pt-BR, en-US, pt-BR] vira [1, 1, 2]. */
    fun numeroPorIdioma(idiomas: List<String>): List<Int> {
        val contagem = mutableMapOf<String, Int>()
        return idiomas.map { idioma ->
            val numero = (contagem[idioma] ?: 0) + 1
            contagem[idioma] = numero
            numero
        }
    }

    /** Junta as partes que existem (nulas ou em branco saem) com " · ". */
    fun linhaDeDetalhe(vararg partes: String?): String =
        partes.filterNotNull().filter { it.isNotBlank() }.joinToString(" · ")
}

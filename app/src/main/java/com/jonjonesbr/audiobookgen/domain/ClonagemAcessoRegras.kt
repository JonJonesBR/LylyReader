package com.jonjonesbr.audiobookgen.domain

/** Resposta do Hugging Face para uma consulta de acesso (identidade ou arquivo restrito). */
enum class AcessoHf { AUTORIZADO, NEGADO, NAO_ENCONTRADO, FALHA }

/** Resultado da verificação que o app mostra ao usuário. */
enum class ResultadoAcessoClonagem {
    LIBERADO,
    TOKEN_INVALIDO,
    FALTA_ACEITE_KYUTAI,
    FALTA_ACEITE_CODIFICADORES,
    ERRO_REDE,
    ERRO_CONFIGURACAO
}

/**
 * Regras da clonagem com acesso pela conta do usuário: o token precisa estar salvo e o acesso precisa
 * ter sido verificado. Puro (sem Android), para testar na JVM.
 */
object ClonagemAcessoRegras {

    private const val TAMANHO_MINIMO_TOKEN = 20

    /** 200/206 = conteúdo direto; 302 e parecidos = o Hugging Face mandou o download para o CDN. */
    private val CODIGOS_AUTORIZADOS = setOf(200, 206, 302, 303, 307, 308)

    fun classificarStatus(codigo: Int): AcessoHf = when (codigo) {
        in CODIGOS_AUTORIZADOS -> AcessoHf.AUTORIZADO
        401, 403 -> AcessoHf.NEGADO
        404 -> AcessoHf.NAO_ENCONTRADO
        else -> AcessoHf.FALHA
    }

    fun decidir(identidade: AcessoHf, kyutai: AcessoHf, codificador: AcessoHf): ResultadoAcessoClonagem = when {
        identidade == AcessoHf.NEGADO -> ResultadoAcessoClonagem.TOKEN_INVALIDO
        identidade == AcessoHf.FALHA -> ResultadoAcessoClonagem.ERRO_REDE
        identidade == AcessoHf.NAO_ENCONTRADO -> ResultadoAcessoClonagem.ERRO_CONFIGURACAO
        kyutai == AcessoHf.NEGADO -> ResultadoAcessoClonagem.FALTA_ACEITE_KYUTAI
        kyutai == AcessoHf.FALHA -> ResultadoAcessoClonagem.ERRO_REDE
        kyutai == AcessoHf.NAO_ENCONTRADO -> ResultadoAcessoClonagem.ERRO_CONFIGURACAO
        codificador == AcessoHf.NEGADO -> ResultadoAcessoClonagem.FALTA_ACEITE_CODIFICADORES
        codificador == AcessoHf.FALHA -> ResultadoAcessoClonagem.ERRO_REDE
        codificador == AcessoHf.NAO_ENCONTRADO -> ResultadoAcessoClonagem.ERRO_CONFIGURACAO
        else -> ResultadoAcessoClonagem.LIBERADO
    }

    /** Token colado: sem espaços por dentro e com tamanho de token (evita guardar um texto qualquer). */
    fun tokenPlausivel(texto: String): Boolean {
        val token = texto.trim()
        return token.length >= TAMANHO_MINIMO_TOKEN && token.none { it.isWhitespace() }
    }

    /** A clonagem nova só é oferecida com token salvo e acesso verificado. */
    fun podeClonar(temToken: Boolean, acessoVerificado: Boolean): Boolean = temToken && acessoVerificado
}

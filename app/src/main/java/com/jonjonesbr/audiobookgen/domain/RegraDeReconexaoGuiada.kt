package com.jonjonesbr.audiobookgen.domain

/**
 * Regra pura: decide se a tela do leitor deve reconectar à leitura guiada já em execução. Sem Android.
 * Reconecta só quando há um manager vivo, ele é do MESMO documento e ainda tem parágrafo ativo (index >= 0).
 * Senão devolve null e a tela cria um manager novo.
 */
object RegraDeReconexaoGuiada {

    /**
     * [managerExistente] é o manager publicado no bridge; [caminhoDaSessao] lê o documento dele
     * (só é chamado se houver manager, preservando a ordem de leituras do bridge); [caminhoAbrindo]
     * é o documento que a tela está abrindo; [indexDe] lê o índice atual do manager
     * (só é chamado quando as duas primeiras condições já passaram).
     */
    fun <T : Any> managerParaReconectar(
        managerExistente: T?,
        caminhoDaSessao: () -> String,
        caminhoAbrindo: String,
        indexDe: (T) -> Int
    ): T? {
        if (managerExistente == null) return null
        if (caminhoDaSessao() != caminhoAbrindo) return null
        return if (indexDe(managerExistente) >= 0) managerExistente else null
    }
}

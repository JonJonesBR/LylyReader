package com.jonjonesbr.audiobookgen.service

// Agrupa manager+token+trackName+caminho numa única referência imutável: trocar [session]
// é uma única escrita (volatile), então um leitor concorrente nunca vê uma combinação
// inconsistente (ex.: trackName do livro novo com caminhoArquivo do livro antigo).
private data class GuidedSession(
    val manager: GuidedPlayerManager,
    val token: Long,
    val trackName: String,
    val caminhoArquivo: String
)

/**
 * Ponte entre o [GuidedPlayerManager] (que vive na ReaderActivity) e o
 * [GuidedReadingService] (foreground service que mantém o processo vivo em 2º plano
 * e exibe a notificação com controles).
 *
 * O serviço observa [manager]?.state para atualizar a notificação e roteia os botões
 * da notificação de volta para o manager. Como leitura guiada e player de áudio
 * convertido são mutuamente exclusivos, basta uma única referência ativa.
 */
object GuidedPlaybackBridge {
    @Volatile private var session: GuidedSession? = null

    val manager: GuidedPlayerManager? get() = session?.manager
    val trackName: String get() = session?.trackName ?: "Leitura guiada"
    // Caminho do documento em leitura guiada — usado para reabrir a ReaderActivity na posição.
    val caminhoArquivo: String get() = session?.caminhoArquivo ?: ""
    // Token da sessão vigente ([GuidedPlayerManager.sessionToken]) — permite que observadores
    // assíncronos (SleepTimerManager, GuidedReadingService) confirmem que um evento antigo
    // ainda se refere à sessão ativa antes de agir sobre ela.
    val currentToken: Long? get() = session?.token

    /** Ativa [manager] como a sessão vigente, publicando token/trackName/caminho numa única escrita. */
    fun activate(manager: GuidedPlayerManager, trackName: String, caminhoArquivo: String) {
        session = GuidedSession(manager, manager.sessionToken, trackName, caminhoArquivo)
    }

    /** Encerra a sessão vigente — só se [manager] ainda for o dono, evitando que uma sessão
     *  antiga e já substituída apague os dados da sessão atual. */
    fun deactivate(manager: GuidedPlayerManager) {
        if (session?.manager === manager) session = null
    }

    // Estado espelhado do manager ativo, para indicadores fora da ReaderActivity (mini-bar).
    val activeState = kotlinx.coroutines.flow.MutableStateFlow<GuidedState?>(null)
}

package com.jonjonesbr.audiobookgen.domain

/**
 * Decide o novo valor de `isPlaying` do player de áudio a partir do `playWhenReady` do ExoPlayer.
 *
 * Usa `playWhenReady` (intenção de tocar) e não `isPlaying` (que exige READY): assim o estado
 * acompanha play/pause vindos de fora (notificação, fone, Bluetooth, tela de bloqueio) e não
 * cai durante BUFFERING. Fim do livro (STATE_ENDED) nunca vira "tocando".
 *
 * @return o novo valor, ou null quando nada muda (ou não há livro carregado).
 */
fun reconciliarIsPlaying(
    estadoIsPlaying: Boolean,
    playWhenReady: Boolean,
    playbackEnded: Boolean,
    caminhoVazio: Boolean,
): Boolean? {
    if (caminhoVazio) return null
    val desejado = if (playbackEnded) false else playWhenReady
    return if (desejado == estadoIsPlaying) null else desejado
}

/**
 * Decide se uma mudança de `playWhenReady` deve receber o bookkeeping de play/pause externo
 * (salvar progresso e marcar pausa na pausa; auto-rewind no play).
 *
 * Não vale para mudanças iniciadas pelo próprio app ([mudancaInterna]: já fazem o bookkeeping
 * em `alternarPlayPause`/foco), nem para fim do livro ([playbackEnded]) nem sem livro carregado.
 */
fun ehPlayPauseExterno(
    mudancaInterna: Boolean,
    playbackEnded: Boolean,
    caminhoVazio: Boolean,
): Boolean = !mudancaInterna && !playbackEnded && !caminhoVazio

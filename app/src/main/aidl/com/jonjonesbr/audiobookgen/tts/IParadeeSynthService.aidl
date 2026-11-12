// Runtime Paradee isolado; uma falha nativa do ORT ou do espeak não derruba a UI nem o player.
package com.jonjonesbr.audiobookgen.tts;

interface IParadeeSynthService {
    /**
     * Retorna string vazia em caso de sucesso e um texto de erro legível caso contrário.
     * paraReproducao: true quando a leitura guiada (mediaPlayback) está ativa no processo principal;
     * define o tipo do serviço em primeiro plano do motor.
     */
    String synthesize(String text, String voiceId, String outPath, float speed, boolean paraReproducao);
}

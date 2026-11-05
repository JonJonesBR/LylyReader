// Isolated Pocket runtime; a native ONNX crash must not terminate the UI or player process.
package com.jonjonesbr.audiobookgen.tts;

interface IPocketSynthService {
    /**
     * Returns an empty string on success and a readable error otherwise.
     * paraReproducao: true when the guided reading (mediaPlayback) is active in the main process;
     * it sets the type of the engine's foreground service (rb-37).
     */
    String synthesize(String text, String voiceId, String outPath, int lsdSteps, float temperature, int threads, boolean paraReproducao);

}

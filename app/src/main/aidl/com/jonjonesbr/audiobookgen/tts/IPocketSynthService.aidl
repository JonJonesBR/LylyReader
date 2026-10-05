// Isolated Pocket runtime; a native ONNX crash must not terminate the UI or player process.
package com.jonjonesbr.audiobookgen.tts;

interface IPocketSynthService {
    /** Returns an empty string on success and a readable error otherwise. */
    String synthesize(String text, String voiceId, String outPath, int lsdSteps);

}

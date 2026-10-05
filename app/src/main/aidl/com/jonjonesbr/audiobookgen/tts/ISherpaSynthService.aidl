// AIDL para síntese do motor Kokoro (Sherpa-ONNX) em processo isolado (:sherpa).
// Um abort()/segfault nativo do ONNX Runtime mata apenas o processo filho — o app
// principal sobrevive (mesmo princípio do :supertonic). Ver PLANO_MELHORIAS_V6 (RN-2).
package com.jonjonesbr.audiobookgen.tts;

interface ISherpaSynthService {
    /**
     * Sintetiza `text` com a voz `voiceId` e grava o WAV em `outPath`.
     * Retorna string vazia em sucesso, ou o motivo detalhado da falha (nunca null) caso
     * contrário — evita que o motivo real (ex.: exceção do ONNX Runtime) se perca ao cruzar
     * a fronteira do processo. Se o processo abortar durante a chamada, o cliente recebe
     * DeadObjectException (tratada como falha), sem derrubar o app principal.
     */
    String synthesize(String text, String voiceId, String outPath);
}

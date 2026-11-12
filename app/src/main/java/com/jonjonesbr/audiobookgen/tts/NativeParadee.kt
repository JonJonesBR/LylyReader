package com.jonjonesbr.audiobookgen.tts

/**
 * Ponte JNI do Paradee (`paradee_jni`): fonemização com espeak-ng 1.52.0 e sessão ONNX Runtime.
 *
 * A biblioteca carrega o ORT já empacotado para o Supertonic (`onnxruntime_supertonic`) e depois a
 * `paradee_jni`. Nenhuma chamada aqui é feita ainda; quem usa é o motor Paradee (fases seguintes).
 */
internal object NativeParadee {
    init {
        System.loadLibrary("onnxruntime_supertonic")
        System.loadLibrary("paradee_jni")
    }

    /**
     * Inicializa o espeak-ng com os dados em [dir] e a voz [voz] (ex.: "pt-br").
     * Não refaz a inicialização se [dir] e [voz] forem os mesmos da chamada anterior.
     *
     * @return "" em caso de sucesso; caso contrário, a mensagem de erro.
     */
    @JvmStatic
    external fun espeakIniciar(dir: String, voz: String): String

    /**
     * Converte [texto] UTF-8 em fonemas IPA (UTF-8), com cláusulas separadas por espaço.
     * Deve ser chamada depois de [espeakIniciar] com sucesso.
     *
     * @return os fonemas em UTF-8, ou null se o espeak não estiver pronto ou falhar.
     */
    @JvmStatic
    external fun espeakFonemas(texto: ByteArray): ByteArray?

    /**
     * Cria uma sessão ONNX do modelo em [modelo] (caminho do arquivo `.onnx`) com [threads] threads.
     *
     * @return um handle não zero, ou 0 se a sessão não pôde ser criada.
     */
    @JvmStatic
    external fun criarSessao(modelo: String, threads: Int): Long

    /**
     * Sintetiza a forma de onda a partir de [ids] (tokens do modelo) com a velocidade [velocidade]
     * e a voz [voz]. A entrada `voice` só é enviada se o modelo a declarar.
     *
     * @return as amostras em ponto flutuante, ou null se a sessão for inválida ou a inferência falhar.
     */
    @JvmStatic
    external fun sintetizar(h: Long, ids: LongArray, velocidade: Float, voz: Int): FloatArray?

    /** Libera a sessão criada por [criarSessao]. Handle 0 é ignorado. */
    @JvmStatic
    external fun fecharSessao(h: Long)
}

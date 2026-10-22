package com.jonjonesbr.audiobookgen.util

/** Pacotes do sistema/Android Auto que podem navegar a biblioteca mesmo sem serem "trusted" pelo media3. */
private val PACOTES_DE_NAVEGACAO_CONFIAVEIS = setOf(
    "com.google.android.projection.gearhead",
    "com.android.systemui",
    "com.google.android.googlequicksearchbox",
    "com.android.bluetooth",
    "com.google.android.wearable.app",
    "com.google.android.carassistant",
)

/**
 * Decide se um controlador de mídia pode navegar a biblioteca (listar e adicionar itens).
 * Play/pause e transporte continuam livres para qualquer controlador; só a navegação é restrita.
 *
 * @param pacote nome do pacote do controlador conectado.
 * @param uid UID do controlador conectado.
 * @param meuUid UID do próprio app (`Process.myUid()`).
 * @param confiavel `controller.isTrusted` do media3.
 */
fun podeNavegar(pacote: String, uid: Int, meuUid: Int, confiavel: Boolean): Boolean =
    confiavel || uid == meuUid || pacote in PACOTES_DE_NAVEGACAO_CONFIAVEIS

package com.jonjonesbr.audiobookgen.util

import java.net.URLEncoder

/**
 * Endereço da busca no Google pelo [consulta] (rb-44): o "Definir" abre esta página no navegador quando o sistema não
 * tem busca na web. Função pura (sem Uri) para ser testada na JVM; o codificador em UTF-8 cuida de acento e espaço.
 */
internal fun urlDeBuscaDoSignificado(consulta: String): String =
    "https://www.google.com/search?q=" + URLEncoder.encode(consulta, "UTF-8")

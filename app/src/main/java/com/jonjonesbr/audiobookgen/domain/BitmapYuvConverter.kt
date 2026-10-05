package com.jonjonesbr.audiobookgen.domain

import android.graphics.Bitmap
import android.media.Image

/**
 * Converte um [Bitmap] pra YUV420 (BT.601) uma única vez — como a imagem do vídeo é estática,
 * o mesmo Y/U/V pré-calculado é reescrito em todo frame codificado.
 */
private const val COR_RGB_MASK = 0xFF
private const val RGB_SHIFT_R = 16
private const val RGB_SHIFT_G = 8
private const val YUV_Y_R = 66
private const val YUV_Y_G = 129
private const val YUV_Y_B = 25
private const val YUV_Y_OFFSET = 16
private const val YUV_U_R = -38
private const val YUV_U_G = -74
private const val YUV_U_B = 112
private const val YUV_V_R = 112
private const val YUV_V_G = -94
private const val YUV_V_B = -18
private const val YUV_CHROMA_OFFSET = 128
private const val YUV_ROUNDING = 128
private const val YUV_SHIFT = 8

internal data class Yuv420(val largura: Int, val altura: Int, val y: ByteArray, val u: ByteArray, val v: ByteArray)

internal fun converterBitmapParaYuv420(bitmap: Bitmap): Yuv420 {
    val largura = bitmap.width
    val altura = bitmap.height
    val pixels = IntArray(largura * altura)
    bitmap.getPixels(pixels, 0, largura, 0, 0, largura, altura)

    val y = ByteArray(largura * altura)
    val chromaLargura = (largura + 1) / 2
    val chromaAltura = (altura + 1) / 2
    val u = ByteArray(chromaLargura * chromaAltura)
    val v = ByteArray(chromaLargura * chromaAltura)

    for (linha in 0 until altura) {
        for (coluna in 0 until largura) {
            val pixel = pixels[linha * largura + coluna]
            val r = (pixel shr RGB_SHIFT_R) and COR_RGB_MASK
            val g = (pixel shr RGB_SHIFT_G) and COR_RGB_MASK
            val b = pixel and COR_RGB_MASK
            y[linha * largura + coluna] = calcularY(r, g, b)
            if (linha % 2 == 0 && coluna % 2 == 0) {
                val chromaIdx = (linha / 2) * chromaLargura + (coluna / 2)
                u[chromaIdx] = calcularU(r, g, b)
                v[chromaIdx] = calcularV(r, g, b)
            }
        }
    }
    return Yuv420(largura, altura, y, u, v)
}

private fun calcularY(r: Int, g: Int, b: Int): Byte {
    val valor = ((YUV_Y_R * r + YUV_Y_G * g + YUV_Y_B * b + YUV_ROUNDING) shr YUV_SHIFT) + YUV_Y_OFFSET
    return valor.coerceIn(0, COR_RGB_MASK).toByte()
}

private fun calcularU(r: Int, g: Int, b: Int): Byte {
    val valor = ((YUV_U_R * r + YUV_U_G * g + YUV_U_B * b + YUV_ROUNDING) shr YUV_SHIFT) + YUV_CHROMA_OFFSET
    return valor.coerceIn(0, COR_RGB_MASK).toByte()
}

private fun calcularV(r: Int, g: Int, b: Int): Byte {
    val valor = ((YUV_V_R * r + YUV_V_G * g + YUV_V_B * b + YUV_ROUNDING) shr YUV_SHIFT) + YUV_CHROMA_OFFSET
    return valor.coerceIn(0, COR_RGB_MASK).toByte()
}

/**
 * Escreve o Y/U/V pré-calculado num [Image] de entrada do encoder, respeitando rowStride e
 * pixelStride de cada plano — o Image de um MediaCodec sempre reporta 3 planos (Y, U, V) mesmo
 * quando o layout físico é semiplanar (NV12/NV21), com pixelStride indicando o entrelaçamento.
 */
internal fun escreverYuvNaImagem(image: Image, yuv: Yuv420) {
    val planos = image.planes
    escreverPlanoY(planos[0], yuv.y, yuv.largura, yuv.altura)
    val chromaLargura = (yuv.largura + 1) / 2
    val chromaAltura = (yuv.altura + 1) / 2
    escreverPlanoChroma(planos[1], yuv.u, chromaLargura, chromaAltura)
    escreverPlanoChroma(planos[2], yuv.v, chromaLargura, chromaAltura)
}

private fun escreverPlanoY(plano: Image.Plane, y: ByteArray, largura: Int, altura: Int) {
    val buffer = plano.buffer
    val rowStride = plano.rowStride
    if (rowStride == largura) {
        buffer.put(y)
        return
    }
    for (linha in 0 until altura) {
        val inicio = linha * rowStride
        for (coluna in 0 until largura) {
            buffer.put(inicio + coluna, y[linha * largura + coluna])
        }
    }
}

private fun escreverPlanoChroma(plano: Image.Plane, dados: ByteArray, largura: Int, altura: Int) {
    val buffer = plano.buffer
    val rowStride = plano.rowStride
    val pixelStride = plano.pixelStride
    for (linha in 0 until altura) {
        val inicioLinha = linha * rowStride
        for (coluna in 0 until largura) {
            buffer.put(inicioLinha + coluna * pixelStride, dados[linha * largura + coluna])
        }
    }
}

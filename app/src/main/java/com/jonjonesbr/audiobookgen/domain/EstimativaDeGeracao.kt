package com.jonjonesbr.audiobookgen.domain

import org.json.JSONException
import org.json.JSONObject
import java.io.File
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds

/**
 * Estimativa de tempo da geração de audiolivro, com a velocidade medida neste aparelho.
 *
 * [medicoes] vem de `velocidade_motores.json` (gravado pelo lado Python): `ms_por_char` é o tempo
 * de parede da conversão dividido pelo número de caracteres. Motor sem medição usa valor de
 * referência aproximado ([REFERENCIA_MS_POR_CHAR]), marcado como aproximado para a UI.
 */
class EstimativaDeGeracao(private val medicoes: Map<String, MedicaoMotor>) {

    /** Duração estimada; [aproximada] é true quando veio do valor de referência, não de medição. */
    data class Estimativa(val duracao: Duration, val aproximada: Boolean)

    fun estimar(totalChars: Int, motor: String): Duration? {
        val medicao = medicoes[motor] ?: return null
        if (totalChars <= 0) return Duration.ZERO
        return (totalChars * medicao.msPorChar).milliseconds
    }

    /** Medição do aparelho quando existe; senão, a referência aproximada; null se o motor é desconhecido. */
    fun estimativa(totalChars: Int, motor: String): Estimativa? {
        estimar(totalChars, motor)?.let { return Estimativa(it, aproximada = false) }
        val referencia = REFERENCIA_MS_POR_CHAR[motor] ?: return null
        return Estimativa((totalChars.coerceAtLeast(0) * referencia).milliseconds, aproximada = true)
    }

    companion object {
        const val ARQUIVO = "velocidade_motores.json"

        /** Valores de referência (ms por caractere) usados quando o aparelho ainda não mediu o motor. */
        val REFERENCIA_MS_POR_CHAR: Map<String, Double> = mapOf(
            "edge" to 1.5,
            "android" to 15.0,
            "onnx" to 40.0,
            "piper" to 20.0,
            "kokoro" to 110.0,
            "pocket" to 60.0
        )

        /** Motores que rodam no aparelho (sem rede). Para estes, uma geração longa merece aviso. */
        private val MOTORES_LOCAIS = setOf("android", "onnx", "piper", "kokoro", "pocket")

        /**
         * Motor que sintetiza [voz], na mesma ordem de despacho de tts.py::converter_tts_auto.
         * "piper-" vem antes de "::" porque as vozes Piper são pacotes "piper-...::main-pt".
         * Null para motores de nuvem sem estimativa local (ElevenLabs, OpenRouter e Gemini).
         * Gemini não tem prefixo (as vozes são nomes como "Aoede"): quem chama informa com [ehGemini].
         */
        fun motorDaVoz(voz: String, ehGemini: Boolean = false): String? = when {
            ehGemini -> null
            voz.startsWith("android::") -> "android"
            voz.startsWith("supertonic-") -> "onnx"
            voz.startsWith("piper-") -> "piper"
            voz.startsWith("pocket-") -> "pocket"
            voz.startsWith("kokoro-") -> "kokoro"
            "::" in voz -> "kokoro"
            voz.startsWith("elevenlabs-") || voz.startsWith("openrouter-") -> null
            else -> "edge"
        }

        /** Aviso só para motor local com geração acima de uma hora. */
        fun deveAvisarLento(motor: String?, duracao: Duration): Boolean =
            motor != null && motor in MOTORES_LOCAIS && duracao > 1.hours

        /**
         * Texto da duração, sem abreviações (o TalkBack lê "horas" e "minutos" por extenso).
         * Arredonda para o minuto mais próximo, com mínimo de 1 minuto.
         */
        fun formatar(
            duracao: Duration,
            rotuloHoras: (Int) -> String,
            rotuloMinutos: (Int) -> String,
            juntar: (String, String) -> String
        ): String {
            val minutosTotais = ((duracao.inWholeSeconds + 30) / 60).toInt().coerceAtLeast(1)
            val horas = minutosTotais / 60
            val minutos = minutosTotais % 60
            return when {
                horas == 0 -> rotuloMinutos(minutos)
                minutos == 0 -> rotuloHoras(horas)
                else -> juntar(rotuloHoras(horas), rotuloMinutos(minutos))
            }
        }

        /** Lê o arquivo de medições; arquivo ausente, vazio ou corrompido vira "sem medições". */
        fun lerDoArquivo(arquivo: File): EstimativaDeGeracao {
            return try {
                if (!arquivo.isFile) EstimativaDeGeracao(emptyMap()) else parse(arquivo.readText())
            } catch (e: Exception) {
                EstimativaDeGeracao(emptyMap())
            }
        }

        /** Parse tolerante: entradas inválidas (sem número, zero, negativo) são ignoradas. */
        fun parse(json: String?): EstimativaDeGeracao {
            if (json.isNullOrBlank()) return EstimativaDeGeracao(emptyMap())
            val raiz = try {
                JSONObject(json)
            } catch (e: JSONException) {
                return EstimativaDeGeracao(emptyMap())
            }
            val medicoes = mutableMapOf<String, MedicaoMotor>()
            for (motor in raiz.keys()) {
                val entrada = raiz.optJSONObject(motor) ?: continue
                val msPorChar = entrada.optDouble("ms_por_char", Double.NaN)
                if (!msPorChar.isFinite() || msPorChar <= 0.0) continue
                medicoes[motor] = MedicaoMotor(msPorChar, entrada.optInt("amostras", 0).coerceAtLeast(0))
            }
            return EstimativaDeGeracao(medicoes)
        }
    }
}

/** Medição de um motor: ms de conversão por caractere e quantas conversões entraram na média. */
data class MedicaoMotor(val msPorChar: Double, val amostras: Int)

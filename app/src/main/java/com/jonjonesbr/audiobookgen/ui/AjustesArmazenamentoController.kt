package com.jonjonesbr.audiobookgen.ui

import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.lifecycle.lifecycleScope
import com.jonjonesbr.audiobookgen.R
import com.jonjonesbr.audiobookgen.data.AppPrefs
import com.jonjonesbr.audiobookgen.domain.PythonEngineUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Um GB em MB: o teto do cache aparece em MB até 1 GB e em GB a partir daí.
private const val MB_POR_GB = 1024

/**
 * Seção de armazenamento dos Ajustes (rb-11): teto do cache de áudio, "Liberar espaço" e o espaço dos livros.
 * Extraído da [SettingsActivity], com o mesmo padrão dos Reader*Controller: as views são achadas aqui e a
 * Activity só chama [configurar] (no onCreate) e [aoRetomar] (no onResume).
 */
class AjustesArmazenamentoController(
    private val activity: AppCompatActivity,
    private val pythonEngine: PythonEngineUseCase,
) {
    private val appPrefs by lazy { AppPrefs(activity) }
    private val tvCacheInfo: TextView = activity.findViewById(R.id.tvCacheInfo)
    private val btnLimparCache: Button = activity.findViewById(R.id.btnLimparCache)
    private val tvCacheAudioInfo: TextView = activity.findViewById(R.id.tvCacheAudioInfo)
    private val seekTetoCache: SeekBar = activity.findViewById(R.id.seekTetoCache)
    private val tvTetoCacheValor: TextView = activity.findViewById(R.id.tvTetoCacheValor)
    // Espaço dos livros na biblioteca (aba Dados, só informativo)
    private val tvLivrosEspaco: TextView = activity.findViewById(R.id.tvLivrosEspaco)
    // true enquanto "Liberar espaço" roda: a atualização da tela (onResume) não troca o "Liberando…"
    private var liberandoEspaco = false

    fun configurar() {
        btnLimparCache.setOnClickListener { limparCache() }
        configurarTetoCache()
    }

    /** Ao voltar para a tela: atualiza o total do cache e o espaço dos livros. */
    fun aoRetomar() {
        atualizarInfoCacheAudio()
        atualizarEspacoLivros()
    }

    private fun rotuloTetoCache(mb: Int): String = when {
        mb <= 0 -> activity.getString(R.string.cache_teto_sem_limite)
        mb >= MB_POR_GB -> activity.getString(R.string.cache_teto_gb, mb / MB_POR_GB)
        else -> activity.getString(R.string.cache_teto_mb, mb)
    }

    /** Slider do teto do cache de áudio das vozes (256 MB … 4 GB, ou sem limite). */
    private fun configurarTetoCache() {
        val opcoes = com.jonjonesbr.audiobookgen.tts.CachePolicy.TETOS_MB
        val atual = appPrefs.tetoCacheAudioMb
        val indice = opcoes.indexOf(atual).takeIf { it >= 0 }
            ?: opcoes.indexOf(com.jonjonesbr.audiobookgen.tts.CachePolicy.TETO_PADRAO_MB)
        seekTetoCache.progress = indice
        tvTetoCacheValor.text = rotuloTetoCache(opcoes[indice])
        ViewCompat.setStateDescription(seekTetoCache, tvTetoCacheValor.text)
        seekTetoCache.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                tvTetoCacheValor.text = rotuloTetoCache(opcoes[progress])
                ViewCompat.setStateDescription(sb, tvTetoCacheValor.text)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) = Unit
            override fun onStopTrackingTouch(sb: SeekBar?) {
                val mb = opcoes[seekTetoCache.progress]
                appPrefs.tetoCacheAudioMb = mb
                activity.lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        com.jonjonesbr.audiobookgen.tts.TtsChunkCache.aparar(
                            activity.applicationContext, com.jonjonesbr.audiobookgen.tts.CachePolicy.tetoEmBytes(mb)
                        )
                    }
                    atualizarInfoCacheAudio()
                }
            }
        })
        atualizarInfoCacheAudio()
    }

    /**
     * Total que "Liberar espaço" apaga (temporários do Python e falas das vozes; o TtsChunkCache já soma
     * a pasta audio_chunks do Python) e o limite atual.
     */
    private fun atualizarInfoCacheAudio() {
        activity.lifecycleScope.launch {
            val bytes = withContext(Dispatchers.IO) {
                com.jonjonesbr.audiobookgen.tts.TtsChunkCache.tamanhoBytes(activity.applicationContext)
            }
            if (!liberandoEspaco) { // durante a limpeza mantém "Liberando…"
                tvCacheInfo.text = if (bytes <= 0L) {
                    activity.getString(R.string.cache_empty)
                } else {
                    android.text.format.Formatter.formatShortFileSize(activity, bytes)
                }
            }
            tvCacheAudioInfo.text =
                activity.getString(R.string.aj_limite_valor, rotuloTetoCache(appPrefs.tetoCacheAudioMb))
        }
    }

    /** Tamanho da pasta dos livros da biblioteca, só para mostrar (sem botão). */
    private fun atualizarEspacoLivros() {
        activity.lifecycleScope.launch {
            val bytes = withContext(Dispatchers.IO) {
                com.jonjonesbr.audiobookgen.data.BibliotecaStore.espacoUsado(activity.applicationContext)
            }
            tvLivrosEspaco.text = android.text.format.Formatter.formatShortFileSize(activity, bytes)
        }
    }

    private fun limparCache() {
        btnLimparCache.isEnabled = false
        liberandoEspaco = true
        tvCacheInfo.text = activity.getString(R.string.cache_cleaning)
        activity.lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    pythonEngine.limparCachePython()
                    // Apaga também as falas guardadas do lado Kotlin (leitura guiada + conversão).
                    com.jonjonesbr.audiobookgen.tts.TtsChunkCache.clear(activity.applicationContext)
                }
                liberandoEspaco = false // antes da atualização final, senão ela não mostra o total
                atualizarInfoCacheAudio()
                btnLimparCache.isEnabled = true
                Toast.makeText(activity, R.string.toast_espaco_liberado, Toast.LENGTH_SHORT).show()
            } catch (_: Exception) {
                liberandoEspaco = false
                tvCacheInfo.text = activity.getString(R.string.cache_clean_error)
                btnLimparCache.isEnabled = true
            }
        }
    }
}

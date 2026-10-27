package com.jonjonesbr.audiobookgen.data

import android.content.Context
import android.content.SharedPreferences
import com.jonjonesbr.audiobookgen.domain.DOWNLOADS_SIMULTANEOS_MAX
import com.jonjonesbr.audiobookgen.domain.DOWNLOADS_SIMULTANEOS_MIN
import com.jonjonesbr.audiobookgen.domain.MARGEM_NIVEL_PADRAO
import com.jonjonesbr.audiobookgen.domain.TemaLeitura
import com.jonjonesbr.audiobookgen.util.AutoRewindMode

/**
 * Acesso tipado e centralizado às preferências de configuração do app (arquivo
 * "audiobookgen_prefs"). Substitui os acessos diretos a `getSharedPreferences` espalhados por
 * vários arquivos, com chaves e defaults definidos em um único lugar.
 *
 * Motivação concreta: o default de `ritmo` era **90** na UI e **87** na síntese ONNX, fazendo a
 * velocidade real divergir da exibida no primeiro uso. Centralizar elimina essa classe de bug.
 *
 * Stores especializados ([ThemePrefs][com.jonjonesbr.audiobookgen.util.ThemePrefs],
 * [PlaybackProgressStore][com.jonjonesbr.audiobookgen.service.PlaybackProgressStore],
 * [RecentReadingsStore], [TtsChunkCache][com.jonjonesbr.audiobookgen.tts.TtsChunkCache])
 * continuam separados; aqui ficam as preferências de configuração compartilhadas.
 */
class AppPrefs(context: Context) {

    private val p: SharedPreferences =
        context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    var ritmo: Int
        get() = p.getInt("ritmo", RITMO_DEFAULT)
        set(v) = p.edit().putInt("ritmo", v).apply()

    var motorTts: String
        get() = p.getString("motor_tts", "edge") ?: "edge"
        set(v) = p.edit().putString("motor_tts", v).apply()

    var vozSelecionada: String?
        get() = p.getString("voz_selecionada", null)
        set(v) = p.edit().putString("voz_selecionada", v).apply()

    var pausaMs: Int
        get() = p.getInt("pausa_ms", PAUSA_MS_PADRAO)
        set(v) = p.edit().putInt("pausa_ms", v).apply()

    /** Dicionário de pronúncia do usuário (JSON: [{"word","spoken"}]). Ver PronunciationDictionary. */
    var pronunciasJson: String
        get() = p.getString("pronuncias_json", "[]") ?: "[]"
        set(v) = p.edit().putString("pronuncias_json", v).apply()

    /** Teto do cache de áudio das vozes em MB (0 = sem limite). Ver CachePolicy. */
    var tetoCacheAudioMb: Int
        get() = p.getInt("teto_cache_audio_mb", com.jonjonesbr.audiobookgen.tts.CachePolicy.TETO_PADRAO_MB)
        set(v) = p.edit().putInt("teto_cache_audio_mb", v.coerceAtLeast(0)).apply()

    /**
     * Timbre das vozes (leitura guiada e audiobooks novos). Tom em meios-tons de semitom (−6…+6 = −3…+3
     * semitons; 0 = original) e corte de agudos em dB (0…8; 0 = desligado). Ver [TimbreVoz].
     */
    /** Ids de [com.jonjonesbr.audiobookgen.domain.EventoAprendizado] já ocorridos (progresso da central "Aprender o app"). */
    var eventosAprendizado: Set<String>
        get() = p.getStringSet("eventos_aprendizado", emptySet())?.toSet() ?: emptySet()
        set(v) = p.edit().putStringSet("eventos_aprendizado", v.toSet()).apply()

    fun registrarEvento(id: String) {
        val atuais = eventosAprendizado
        if (id !in atuais) eventosAprendizado = atuais + id
    }

    fun removerEvento(id: String) { eventosAprendizado = eventosAprendizado - id }

    /** Ids das dicas (Fase J) que a pessoa já viu; cada uma aparece uma vez. */
    var dicasVistas: Set<String>
        get() = p.getStringSet("dicas_vistas", emptySet())?.toSet() ?: emptySet()
        set(v) = p.edit().putStringSet("dicas_vistas", v.toSet()).apply()

    fun marcarDicaVista(id: String) { dicasVistas = dicasVistas + id }

    /** Id (na biblioteca) do livro de exemplo da Fase J, para não oferecê-lo de novo enquanto existir. */
    var livroExemploId: String?
        get() = p.getString("livro_exemplo_id", null)
        set(v) = p.edit().putString("livro_exemplo_id", v).apply()

    /** Ordem da biblioteca de livros (nome de [com.jonjonesbr.audiobookgen.domain.OrdemBiblioteca]). */
    var bibliotecaOrdem: String
        get() = p.getString("biblioteca_ordem", "RECENTES") ?: "RECENTES"
        set(v) = p.edit().putString("biblioteca_ordem", v).apply()

    /** A migração dos livros do cache para a biblioteca persistente (Fase N) já rodou. */
    var bibliotecaMigradaV1: Boolean
        get() = p.getBoolean("biblioteca_migrada_v1", false)
        set(v) = p.edit().putBoolean("biblioteca_migrada_v1", v).apply()
    /** Quantos pacotes a Central de downloads baixa ao mesmo tempo (mais que 2 esquenta o aparelho). */
    var downloadsSimultaneos: Int
        get() = p.getInt("downloads_simultaneos", DOWNLOADS_SIMULTANEOS_PADRAO)
            .coerceIn(DOWNLOADS_SIMULTANEOS_MIN, DOWNLOADS_SIMULTANEOS_MAX)
        set(v) = p.edit()
            .putInt("downloads_simultaneos", v.coerceIn(DOWNLOADS_SIMULTANEOS_MIN, DOWNLOADS_SIMULTANEOS_MAX)).apply()

    var tomVozMeiosTons: Int
        get() = p.getInt("timbre_tom_meios_tons", 0).coerceIn(TIMBRE_TOM_MIN, TIMBRE_TOM_MAX)
        set(v) = p.edit().putInt("timbre_tom_meios_tons", v.coerceIn(TIMBRE_TOM_MIN, TIMBRE_TOM_MAX)).apply()

    /** Mudança extra de tom (meios-tons) só nos trechos de fala/citação da leitura guiada; 0 = desligado. */
    var tomNasFalasMeiosTons: Int
        get() = p.getInt("timbre_tom_falas", 0).coerceIn(TIMBRE_TOM_MIN, TIMBRE_TOM_MAX)
        set(v) = p.edit().putInt("timbre_tom_falas", v.coerceIn(TIMBRE_TOM_MIN, TIMBRE_TOM_MAX)).apply()

    var suavizarAgudosDb: Int
        get() = p.getInt("timbre_agudos_db", 0).coerceIn(0, TIMBRE_AGUDOS_MAX)
        set(v) = p.edit().putInt("timbre_agudos_db", v.coerceIn(0, TIMBRE_AGUDOS_MAX)).apply()

    /**
     * Modelo Gemini usado na análise de personagens por IA. Vazio = automático (lista padrão, dos de
     * maior cota gratuita para os de menor). O usuário pode escolher na lista da API ou digitar o nome.
     */
    var geminiModeloAnalise: String
        get() = p.getString("gemini_modelo_analise", "") ?: ""
        set(v) = p.edit().putString("gemini_modelo_analise", v.trim()).apply()

    /** IA usada nas análises: "gemini" (chave própria), "pollinations" (grátis, sem chave) ou "custom" (compatível com OpenAI). */
    var iaProvedor: String
        get() = p.getString("ia_provedor", "gemini") ?: "gemini"
        set(v) = p.edit().putString("ia_provedor", v).apply()

    var iaPersonalizadaUrl: String
        get() = p.getString("ia_personalizada_url", "") ?: ""
        set(v) = p.edit().putString("ia_personalizada_url", v.trim()).apply()

    var iaPersonalizadaModelo: String
        get() = p.getString("ia_personalizada_modelo", "") ?: ""
        set(v) = p.edit().putString("ia_personalizada_modelo", v.trim()).apply()

    var supertonicSteps: Int
        get() = p.getInt("supertonic_steps", SUPERTONIC_STEPS_PADRAO)
        set(v) = p.edit().putInt("supertonic_steps", v).apply()

    /**
     * Passos de decodificação por quadro do Pocket TTS. 4 é o padrão (era 1): mais passos custam tempo de
     * síntese e melhoram a fidelidade.
     */
    var pocketPassos: Int
        get() {
            // Uma vez: quem estava no padrão antigo (1 gravado) passa para o novo padrão.
            if (!p.getBoolean("pocket_passos_padrao4", false)) {
                val e = p.edit().putBoolean("pocket_passos_padrao4", true)
                if (p.getInt("pocket_passos", POCKET_PASSOS_PADRAO) == 1) e.remove("pocket_passos")
                e.apply()
            }
            return p.getInt("pocket_passos", POCKET_PASSOS_PADRAO).coerceIn(POCKET_PASSOS_MIN, POCKET_PASSOS_MAX)
        }
        set(v) = p.edit().putInt("pocket_passos", v.coerceIn(POCKET_PASSOS_MIN, POCKET_PASSOS_MAX)).apply()

    /**
     * Expressividade do Pocket TTS: a temperatura da amostragem, em décimos (3 = 0,3). Mais alta deixa a
     * entonação mais viva e variada, com mais risco de falhas; mais baixa, mais contida e estável.
     */
    var pocketExpressividade: Int
        get() = p.getInt("pocket_expressividade", POCKET_EXPRESSIVIDADE_PADRAO)
            .coerceIn(POCKET_EXPRESSIVIDADE_MIN, POCKET_EXPRESSIVIDADE_MAX)
        set(v) = p.edit()
            .putInt("pocket_expressividade", v.coerceIn(POCKET_EXPRESSIVIDADE_MIN, POCKET_EXPRESSIVIDADE_MAX)).apply()

    /** Pausa entre orações (frases) na leitura guiada com kokoro (ms). Ver
     *  [GuidedPlayerManager][com.jonjonesbr.audiobookgen.service.GuidedPlayerManager]. */
    var pausaFinalFraseMs: Int
        get() = p.getInt("pausa_final_frase_ms", PAUSA_FINAL_FRASE_MS_PADRAO)
        set(v) = p.edit().putInt("pausa_final_frase_ms", v).apply()

    /**
     * Pausas naturais na leitura guiada (padrão): duração conforme a pontuação, o fôlego e o fim do parágrafo,
     * com variação aleatória (ver [com.jonjonesbr.audiobookgen.domain.PausasNaturais]). Desligado volta à pausa
     * fixa de [pausaFinalFraseMs] entre frases das vozes locais e nenhuma pausa entre parágrafos.
     */
    var pausasNaturais: Boolean
        get() = p.getBoolean("pausas_naturais", true)
        set(v) = p.edit().putBoolean("pausas_naturais", v).apply()

    // ── Naturalidade da voz (experimental): começam desligadas, para o usuário comparar ──

    /** Leitura guiada no Supertonic e no Pocket: frases seguidas vão juntas ao motor, com entonação corrida. */
    var entonacaoContinua: Boolean
        get() = p.getBoolean("entonacao_continua", false)
        set(v) = p.edit().putBoolean("entonacao_continua", v).apply()

    /** Motores locais: volume igual entre trechos e, na leitura guiada, sem o silêncio extra das bordas. */
    var acabamentoAudio: Boolean
        get() = p.getBoolean("acabamento_audio", false)
        set(v) = p.edit().putBoolean("acabamento_audio", v).apply()

    /** Leitura guiada: falas de personagem um pouco mais agudas e rápidas que a narração. */
    var dialogosVivos: Boolean
        get() = p.getBoolean("dialogos_vivos", false)
        set(v) = p.edit().putBoolean("dialogos_vivos", v).apply()

    /** True depois que o aviso "personagens encontrados" já foi mostrado para este livro. */
    fun avisoPersonagensVisto(caminhoLivro: String): Boolean =
        p.getBoolean("aviso_personagens_visto_$caminhoLivro", false)

    fun marcarAvisoPersonagensVisto(caminhoLivro: String) {
        p.edit().putBoolean("aviso_personagens_visto_$caminhoLivro", true).apply()
    }

    var pastaDestino: String?
        get() = p.getString("pasta_destino", null)
        set(v) = p.edit().putString("pasta_destino", v).apply()

    var playerEngine: String
        get() = p.getString("player_engine", "media_player") ?: "media_player"
        set(v) = p.edit().putString("player_engine", v).apply()

    var guidedSpeedMult: Float
        get() = p.getFloat("guided_speed_mult", 1.0f)
        set(v) = p.edit().putFloat("guided_speed_mult", v).apply()

    /** Recuo automático ao retomar audiolivro pausado (off/curto/longo). Padrão: curto. */
    var autoRewindMode: AutoRewindMode
        get() = AutoRewindMode.entries.firstOrNull {
            it.name == p.getString("auto_rewind_mode", AutoRewindMode.CURTO.name)
        } ?: AutoRewindMode.CURTO
        set(v) = p.edit().putString("auto_rewind_mode", v.name).apply()

    var notificationPermissionPrompted: Boolean
        get() = p.getBoolean("notification_permission_prompted", false)
        set(v) = p.edit().putBoolean("notification_permission_prompted", v).apply()

    /** O pedido de acesso aos áudios do aparelho já foi feito (depois de recusado de vez, só pela tela do app). */
    var permissaoAudiosPedida: Boolean
        get() = p.getBoolean("permissao_audios_pedida", false)
        set(v) = p.edit().putBoolean("permissao_audios_pedida", v).apply()

    /** As permissões já foram oferecidas (página das boas-vindas ou o aviso único na Biblioteca). */
    var avisoNotificacoesVisto: Boolean
        get() = p.getBoolean("aviso_notificacoes_visto", false)
        set(v) = p.edit().putBoolean("aviso_notificacoes_visto", v).apply()

    /** O aviso de notificações desligadas ao iniciar uma conversão já apareceu. */
    var dicaNotificacaoConversaoVista: Boolean
        get() = p.getBoolean("dica_notificacao_conversao_vista", false)
        set(v) = p.edit().putBoolean("dica_notificacao_conversao_vista", v).apply()

    var onboardingDone: Boolean
        get() = p.getBoolean("onboarding_done", false)
        set(v) = p.edit().putBoolean("onboarding_done", v).apply()

    var voiceDownloadOnboardingDone: Boolean
        get() = p.getBoolean("voice_download_onboarding_done", false)
        set(v) = p.edit().putBoolean("voice_download_onboarding_done", v).apply()

    /** O download offline silencioso do primeiro acesso (pacote recomendado) já foi enfileirado. Ver PoliticaDownloadSilencioso. */
    /** Aviso de que o log de erro pode conter nomes de livros já foi mostrado (aparece só no primeiro compartilhamento). */
    var avisoCompartilharLogVisto: Boolean
        get() = p.getBoolean("aviso_compartilhar_log_visto", false)
        set(v) = p.edit().putBoolean("aviso_compartilhar_log_visto", v).apply()

    var downloadOfflineSilenciosoIniciado: Boolean
        get() = p.getBoolean("download_offline_silencioso_iniciado", false)
        set(v) = p.edit().putBoolean("download_offline_silencioso_iniciado", v).apply()

    /** "Não mostrar mais" do aviso sobre a voz genérica de motores de terceiros (ex.: MultiTTS). */
    var avisoVozGenericaDispensado: Boolean
        get() = p.getBoolean("aviso_voz_generica_dispensado", false)
        set(v) = p.edit().putBoolean("aviso_voz_generica_dispensado", v).apply()

    // Nota: `battery_opt_dismissed` NÃO entra aqui — vive em outro arquivo de prefs
    // ("lylyreader_prefs", ver BatteryOptimizationHelper). As chaves `reader_*` (aparência do
    // leitor) estão logo abaixo, nos métodos tipados.

    /** A dica de otimização de bateria (sugerida ao iniciar uma conversão) já foi mostrada. Ver DicaDeBateria. */
    var dicaBateriaVista: Boolean
        get() = p.getBoolean("dica_bateria_vista", false)
        set(v) = p.edit().putBoolean("dica_bateria_vista", v).apply()

    var blacklistWordsJson: String
        get() = p.getString("blacklist_words", "[]") ?: "[]"
        set(v) = p.edit().putString("blacklist_words", v).apply()

    /** Player usado ao tocar na notificação de conversão concluída: null = "perguntar na
     * próxima vez" (ainda não escolhido), "app" = sempre abrir no player do LylyReader,
     * "sistema" = sempre mostrar o seletor do Android ("Abrir com..."). */
    var playerConversaoConcluida: String?
        get() = p.getString("player_conversao_concluida", null)
        set(v) = p.edit().putString("player_conversao_concluida", v).apply()

    /** Último `versionCode` para o qual a nota de novidades pós-atualização já foi mostrada
     * (ou registrada silenciosamente, na instalação nova). 0 = nunca aberto ainda. Ver
     * [ReleaseNotesDialog][com.jonjonesbr.audiobookgen.ui.ReleaseNotesDialog]. */
    var ultimaVersaoNotasVista: Int
        get() = p.getInt("ultima_versao_notas_vista", 0)
        set(v) = p.edit().putInt("ultima_versao_notas_vista", v).apply()

    /** Quantas unidades (orações ou parágrafos, conforme o motor/modo) a leitura guiada
     *  sintetiza à frente da posição atual, substituindo o padrão interno de CADA motor —
     *  [BUFFER_ADIANTE_AUTOMATICO] mantém o comportamento de sempre (nenhuma mudança até o
     *  usuário mexer nisso em Ajustes). Ver uso em
     *  [GuidedPlayerManager][com.jonjonesbr.audiobookgen.service.GuidedPlayerManager]. */
    var bufferParagrafosAdiante: Int
        get() = p.getInt("buffer_paragrafos_adiante", BUFFER_ADIANTE_AUTOMATICO)
        set(v) = p.edit().putInt("buffer_paragrafos_adiante", v).apply()

    /** Quando true, limita o kokoro a 1 síntese por vez em vez do padrão de 2 concorrentes
     *  (único valor validado em device — ver
     *  [SherpaProcessClient][com.jonjonesbr.audiobookgen.tts.SherpaProcessClient]). Mais
     *  lento, porém mais conservador; não afeta os demais motores (só o kokoro tem essa
     *  forma de paralelismo real hoje). */
    var paralelismoKokoroReduzido: Boolean
        get() = p.getBoolean("paralelismo_kokoro_reduzido", false)
        set(v) = p.edit().putBoolean("paralelismo_kokoro_reduzido", v).apply()

    /** Quando true, permite ao Supertonic (onnx) processar 2 sínteses concorrentes em vez
     *  do padrão histórico de 1 (sequencial, via mutex) — ver
     *  [SupertonicProcessClient][com.jonjonesbr.audiobookgen.tts.SupertonicProcessClient].
     *  EXPERIMENTAL: ao contrário do kokoro, a thread-safety do backend nativo do
     *  Supertonic para chamadas concorrentes nunca foi validada em device. Default false
     *  preserva o comportamento de sempre. */
    var paralelismoSupertonicoAumentado: Boolean
        get() = p.getBoolean("paralelismo_supertonico_aumentado", false)
        set(v) = p.edit().putBoolean("paralelismo_supertonico_aumentado", v).apply()

    /**
     * "Restaurar o padrão do som" (Ajustes › Som): pausas, tom da voz, repetir ao retomar, qualidade do
     * áudio, passos das vozes e buffering voltam ao padrão. Vozes, livros e pronúncias não mudam.
     */
    fun restaurarPadroesDeSom() {
        p.edit()
            .remove("pausas_naturais")
            .remove("pausa_final_frase_ms")
            .remove("pausa_ms")
            .remove("bitrate") // chave antiga: o controle de bitrate saiu (não tinha efeito)
            .remove("supertonic_steps")
            .remove("pocket_passos")
            .remove("pocket_expressividade")
            .remove("entonacao_continua")
            .remove("acabamento_audio")
            .remove("dialogos_vivos")
            .remove("auto_rewind_mode")
            .remove("timbre_tom_meios_tons")
            .remove("timbre_tom_falas")
            .remove("timbre_agudos_db")
            .apply()
        resetarBufferingLeituraGuiada()
    }

    /** Restaura buffer/paralelismo da leitura guiada para o padrão de fábrica (por motor). */
    fun resetarBufferingLeituraGuiada() {
        p.edit()
            .remove("buffer_paragrafos_adiante")
            .remove("paralelismo_kokoro_reduzido")
            .remove("paralelismo_supertonico_aumentado")
            .apply()
    }

    /** Tamanho da fonte do leitor (sp). */
    var readerFonte: Float
        get() = p.getFloat("reader_fonte", READER_FONTE_PADRAO)
        set(v) = p.edit().putFloat("reader_fonte", v).apply()

    /** Espaçamento entre linhas do leitor. */
    var readerEspacamento: Float
        get() = p.getFloat("reader_espacamento", READER_ESPACAMENTO_PADRAO)
        set(v) = p.edit().putFloat("reader_espacamento", v).apply()

    /** Tema do leitor, como ordinal de [TemaLeitura] (o ordinal é persistido: não reordenar o enum). */
    var readerTema: Int
        get() = p.getInt("reader_tema", TemaLeitura.CLARO.ordinal)
        set(v) = p.edit().putInt("reader_tema", v).apply()

    /** Fonte serifada no texto do leitor. */
    var readerFonteSerifada: Boolean
        get() = p.getBoolean("reader_fonte_serifada", false)
        set(v) = p.edit().putBoolean("reader_fonte_serifada", v).apply()

    /** Nível da margem horizontal do leitor (0=estreita, 1=média, 2=larga). */
    var readerMargemNivel: Int
        get() = p.getInt("reader_margem_nivel", MARGEM_NIVEL_PADRAO)
        set(v) = p.edit().putInt("reader_margem_nivel", v).apply()

    /** Alinhamento do texto do leitor: nome de [com.jonjonesbr.audiobookgen.domain.AlinhamentoTexto]. */
    var readerAlinhamento: String
        get() = p.getString("reader_alinhamento", null) ?: "JUSTIFICADO"
        set(v) = p.edit().putString("reader_alinhamento", v).apply()

    /** Rolagem horizontal (paginada) no lugar do scroll vertical contínuo no leitor. */
    var readerRolagemHorizontal: Boolean
        get() = p.getBoolean("reader_rolagem_horizontal", false)
        set(v) = p.edit().putBoolean("reader_rolagem_horizontal", v).apply()

    /** Grava toda a aparência do leitor de uma vez (um único apply). */
    fun salvarAparenciaLeitor(
        fonte: Float,
        espacamento: Float,
        temaOrdinal: Int,
        fonteSerifada: Boolean,
        margemNivel: Int,
        rolagemHorizontal: Boolean
    ) {
        p.edit()
            .putFloat("reader_fonte", fonte)
            .putFloat("reader_espacamento", espacamento)
            .putInt("reader_tema", temaOrdinal)
            .putBoolean("reader_fonte_serifada", fonteSerifada)
            .putInt("reader_margem_nivel", margemNivel)
            .putBoolean("reader_rolagem_horizontal", rolagemHorizontal)
            .apply()
    }

    /** Posição de leitura (índice do primeiro parágrafo visível) gravada pelo leitor para o livro; [padrao] se nunca gravada. */
    fun posicaoLeitura(caminhoLivro: String, padrao: Int): Int =
        p.getInt("reader_scroll_$caminhoLivro", padrao)

    /** Grava a posição de leitura e o total de parágrafos do livro (progresso = posição ÷ total na biblioteca). */
    fun salvarPosicaoLeitura(caminhoLivro: String, posicao: Int, totalParagrafos: Int) {
        p.edit()
            .putInt("reader_scroll_$caminhoLivro", posicao)
            .putInt("reader_total_$caminhoLivro", totalParagrafos)
            .apply()
    }

    /** Total de parágrafos gravado pelo leitor para o livro; 0 se nunca gravado. */
    fun totalParagrafosLeitura(caminhoLivro: String): Int = p.getInt("reader_total_$caminhoLivro", 0)

    fun getPrefs(): SharedPreferences = p

    companion object {
        const val NAME = "audiobookgen_prefs"
        /** Velocidade de narração canônica (a UI mostra este valor; a síntese deve casar). */
        const val RITMO_DEFAULT = 100

        /** Pausa entre blocos da conversão (ms). */
        private const val PAUSA_MS_PADRAO = 600
        const val POCKET_PASSOS_MIN = 1
        const val POCKET_PASSOS_MAX = 10
        const val DOWNLOADS_SIMULTANEOS_PADRAO = 2
        const val TIMBRE_TOM_MIN = -6
        const val TIMBRE_TOM_MAX = 6
        const val TIMBRE_AGUDOS_MAX = 8
        const val POCKET_PASSOS_PADRAO = 4
        /** Temperatura do Pocket em décimos: 3 (0,3) é o valor estável no aparelho; 7 é o padrão da Kyutai. */
        /** 0,1: no uso, a expressividade baixa é a que menos suprime palavras. */
        const val POCKET_EXPRESSIVIDADE_PADRAO = 1
        const val POCKET_EXPRESSIVIDADE_MIN = 1
        const val POCKET_EXPRESSIVIDADE_MAX = 10
        /** Tamanho de fonte padrão do leitor (sp). */
        const val READER_FONTE_PADRAO = 16f
        /** Espaçamento padrão entre linhas do leitor. */
        const val READER_ESPACAMENTO_PADRAO = 1.5f
        /** Passos padrão do modelo Supertonic. */
        const val SUPERTONIC_STEPS_PADRAO = 5
        const val SUPERTONIC_STEPS_MIN = 2
        const val SUPERTONIC_STEPS_MAX = 20
        /** Pausa padrão entre orações na leitura guiada com kokoro (ms). */
        private const val PAUSA_FINAL_FRASE_MS_PADRAO = 500
        /** Sentinela: usa o padrão interno de cada motor (nunca sobrescreve). */
        const val BUFFER_ADIANTE_AUTOMATICO = -1
        /** Faixa segura exposta ao usuário para [bufferParagrafosAdiante]. */
        const val BUFFER_ADIANTE_MIN = 1
        const val BUFFER_ADIANTE_MAX = 8
    }
}

package com.jonjonesbr.audiobookgen.ui

import com.jonjonesbr.audiobookgen.util.VoiceOption
import com.jonjonesbr.audiobookgen.R
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.widget.addTextChangedListener
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import java.util.Locale

class VoiceBottomSheet : BottomSheetDialogFragment() {

    companion object {
        private const val PREVIEW_ICON_IDLE = "▶"
        private const val PREVIEW_ICON_LOADING = "⏳"
        private const val MAX_HEIGHT_DP = 720
        private const val TYPE_SECTION = 0
        private const val TYPE_VOICE = 1
        internal const val RESULT_KEY = "voice_bottom_sheet_result"
        internal const val PREVIEW_FINISHED_KEY = "voice_bottom_sheet_preview_finished"
        internal const val ARG_HAS_PREVIEW = "voice_sheet_has_preview"
        private const val ARG_VOICES = "voice_sheet_voices"
        private const val ARG_SELECTED_VOICE = "voice_sheet_selected_voice"
        private const val ARG_HAS_ANDROID_ACTION = "voice_sheet_has_android_action"
        private const val ARG_HAS_CLONE_ACTION = "voice_sheet_has_clone_action"
        private const val ARG_RESTORABLE = "voice_sheet_restorable"
        private const val STATE_FILTER = "voice_sheet_filter"
        private const val STATE_SELECTED_VOICE = "voice_sheet_selected_voice_state"
        private const val STATE_OTHER_LANGUAGES_EXPANDED = "voice_sheet_other_languages_expanded"
        internal const val RESULT_ACTION = "action"
        internal const val ACTION_SELECT = "select"
        internal const val ACTION_PREVIEW = "preview"
        internal const val ACTION_ANDROID = "android_voices"
        internal const val ACTION_CLONE = "clone_voice"
        internal const val RESULT_VOICE = "voice"

        internal fun voiceToBundle(voice: VoiceOption) = Bundle().apply {
            putString("id", voice.id)
            putString("name", voice.name)
            putString("language", voice.language)
            putString("engine", voice.engine)
            putString("description", voice.description)
            putBoolean("male", voice.isMale)
            putBoolean("gender_known", voice.generoConhecido)
            putBoolean("generic", voice.isGenerico)
            putBoolean("needs_system_download", voice.precisaBaixarNoSistema)
        }

        internal fun voiceFromBundle(bundle: Bundle?): VoiceOption? {
            bundle ?: return null
            return VoiceOption(
                id = bundle.getString("id") ?: return null,
                name = bundle.getString("name") ?: return null,
                language = bundle.getString("language") ?: return null,
                isMale = bundle.getBoolean("male"),
                engine = bundle.getString("engine") ?: return null,
                description = bundle.getString("description").orEmpty(),
                isGenerico = bundle.getBoolean("generic"),
                precisaBaixarNoSistema = bundle.getBoolean("needs_system_download"),
                generoConhecido = bundle.getBoolean("gender_known", true)
            )
        }
    }

    private var voices: List<VoiceOption> = emptyList()
    private var selectedVoiceId: String? = null
    private var onVoiceSelected: ((VoiceOption) -> Unit)? = null
    // onFinished deve ser chamado pelo caller quando a amostra terminar de gerar/tocar, pra
    // reverter o botão ▶ — sem isso o clique não dava nenhum feedback visual de que algo
    // estava acontecendo.
    private var onVoicePreview: ((VoiceOption, onFinished: () -> Unit) -> Unit)? = null
    private var onAndroidVoiceRequested: (() -> Unit)? = null
    private var onCloneVoiceRequested: (() -> Unit)? = null

    fun configure(
        voices: List<VoiceOption>,
        selectedVoiceId: String?,
        onVoiceSelected: (VoiceOption) -> Unit,
        onVoicePreview: ((VoiceOption, onFinished: () -> Unit) -> Unit)? = null,
        onAndroidVoiceRequested: (() -> Unit)? = null,
        onCloneVoiceRequested: (() -> Unit)? = null,
        restorable: Boolean = true
    ) {
        this.voices = voices
        this.selectedVoiceId = selectedVoiceId
        this.onVoiceSelected = onVoiceSelected
        this.onVoicePreview = onVoicePreview
        this.onAndroidVoiceRequested = onAndroidVoiceRequested
        this.onCloneVoiceRequested = onCloneVoiceRequested
        arguments = Bundle().apply {
            putParcelableArrayList(ARG_VOICES, ArrayList(voices.map(::voiceToBundle)))
            putString(ARG_SELECTED_VOICE, selectedVoiceId)
            putBoolean(ARG_HAS_ANDROID_ACTION, onAndroidVoiceRequested != null)
            putBoolean(ARG_HAS_CLONE_ACTION, onCloneVoiceRequested != null)
            putBoolean(ARG_HAS_PREVIEW, onVoicePreview != null)
            putBoolean(ARG_RESTORABLE, restorable)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (voices.isEmpty()) {
            @Suppress("DEPRECATION")
            val savedVoices = arguments?.getParcelableArrayList<Bundle>(ARG_VOICES).orEmpty()
            voices = savedVoices.mapNotNull(::voiceFromBundle)
        }
        selectedVoiceId = savedInstanceState?.getString(STATE_SELECTED_VOICE)
            ?: selectedVoiceId ?: arguments?.getString(ARG_SELECTED_VOICE)
    }

    private var filtroVozes = ""
    private var outrasVozesExpandidas = false
    private val callbacksPreviewPorVoz = mutableMapOf<String, () -> Unit>()

    override fun onSaveInstanceState(outState: Bundle) {
        view?.findViewById<EditText>(R.id.etFiltroVozes)?.let { filtroVozes = it.text.toString() }
        outState.putString(STATE_FILTER, filtroVozes)
        outState.putString(STATE_SELECTED_VOICE, selectedVoiceId)
        outState.putBoolean(STATE_OTHER_LANGUAGES_EXPANDED, outrasVozesExpandidas)
        super.onSaveInstanceState(outState)
    }

    override fun onStart() {
        super.onStart()
        val maxHeight = minOf(
            (resources.displayMetrics.heightPixels * 0.85f).toInt(),
            (MAX_HEIGHT_DP * resources.displayMetrics.density).toInt()
        )
        dialog?.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, maxHeight)
        val sheet = dialog?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            ?: return
        BottomSheetBehavior.from(sheet).apply {
            state = BottomSheetBehavior.STATE_EXPANDED
            skipCollapsed = true
            peekHeight = maxHeight
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.bottom_sheet_vozes, container, false)

    // Prontidão de download por raiz de modelo (supertonic-m1…), calculada no
    // máximo UMA vez por abertura do sheet. Antes, cada bind de item instanciava um
    // OnnxModelManager e fazia stat de ~16 arquivos na main thread — com 15 vozes offline
    // na lista, eram ~200 stats por abertura/rolagem.
    private val prontidaoPorModelo = mutableMapOf<String, Boolean>()

    // Prontidão centralizada no catálogo (V6 T2.1/RN-3): delega ao PACOTE da voz — um
    // download habilita N vozes (kokoro/supertonic); voz de nuvem (sem pacote) = pronto.
    private fun isModeloPronto(voice: VoiceOption): Boolean =
        prontidaoPorModelo.getOrPut(
            com.jonjonesbr.audiobookgen.util.VoiceCatalog.modelRootId(voice.id)
        ) {
            com.jonjonesbr.audiobookgen.util.VoiceCatalog.modeloProntoParaVoz(voice.id, requireContext())
        }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        if (savedInstanceState != null && arguments?.getBoolean(ARG_RESTORABLE, true) == false) {
            dismissAllowingStateLoss()
            return
        }
        if (voices.isEmpty()) {
            dismissAllowingStateLoss()
            return
        }
        view.findViewById<Button>(R.id.btnClonarVoz).apply {
            val hasCloneAction = onCloneVoiceRequested != null ||
                arguments?.getBoolean(ARG_HAS_CLONE_ACTION) == true
            visibility = if (hasCloneAction) View.VISIBLE else View.GONE
            setOnClickListener {
                dismiss()
                if (onCloneVoiceRequested != null) onCloneVoiceRequested?.invoke()
                else parentFragmentManager.setFragmentResult(RESULT_KEY, Bundle().apply {
                    putString(RESULT_ACTION, ACTION_CLONE)
                })
            }
        }
        view.findViewById<Button>(R.id.btnVozesDispositivo).apply {
            val hasAndroidAction = onAndroidVoiceRequested != null ||
                arguments?.getBoolean(ARG_HAS_ANDROID_ACTION) == true
            visibility = if (hasAndroidAction) View.VISIBLE else View.GONE
            setOnClickListener {
                dismiss()
                if (onAndroidVoiceRequested != null) onAndroidVoiceRequested?.invoke()
                else parentFragmentManager.setFragmentResult(RESULT_KEY, Bundle().apply {
                    putString(RESULT_ACTION, ACTION_ANDROID)
                })
            }
        }
        parentFragmentManager.setFragmentResultListener(PREVIEW_FINISHED_KEY, viewLifecycleOwner) { _, result ->
            result.getString(RESULT_VOICE)?.let { voiceId -> callbacksPreviewPorVoz.remove(voiceId)?.invoke() }
        }
        val idiomaDaTela = resources.configuration.locales[0] ?: Locale.getDefault()
        val grupos = com.jonjonesbr.audiobookgen.util.VoiceCatalog.agruparPorIdiomaPreferido(
            voices,
            idiomaDaTela.toLanguageTag()
        )
        val vozEscolhidaEstaEmOutrosIdiomas = grupos.outras.any { it.id == selectedVoiceId }
        outrasVozesExpandidas = savedInstanceState?.getBoolean(STATE_OTHER_LANGUAGES_EXPANDED)
            ?: (vozEscolhidaEstaEmOutrosIdiomas || grupos.recomendadas.isEmpty())
        val adapter = VoiceAdapter(grupos, outrasVozesExpandidas) { expanded ->
            outrasVozesExpandidas = expanded
        }
        view.findViewById<RecyclerView>(R.id.rvVozes).apply {
            layoutManager = LinearLayoutManager(requireContext())
            this.adapter = adapter
            addItemDecoration(DividerItemDecoration(requireContext(), DividerItemDecoration.VERTICAL))
        }
        val filtro = view.findViewById<EditText>(R.id.etFiltroVozes)
        filtroVozes = (savedInstanceState?.getString(STATE_FILTER) ?: filtro.text.toString()).trim()
        filtro.setText(filtroVozes)
        filtro.addTextChangedListener { editable ->
            val query = editable?.toString()?.trim().orEmpty()
            filtroVozes = query
            adapter.submitFilter(query)
        }
        adapter.submitFilter(filtroVozes)
    }

    private fun nomeDoIdioma(tag: String?): String {
        val locale = tag?.let(Locale::forLanguageTag) ?: return tag.orEmpty()
        val idiomaDaTela = resources.configuration.locales[0] ?: Locale.getDefault()
        return locale.getDisplayName(idiomaDaTela).ifBlank { tag }
    }

    private sealed class VoiceRow {
        data class Section(
            val label: String,
            val expandable: Boolean = false,
            val expanded: Boolean = false
        ) : VoiceRow()

        data class Voice(val option: VoiceOption) : VoiceRow()
    }

    private inner class VoiceAdapter(
        private val grupos: com.jonjonesbr.audiobookgen.util.GruposVozesPorIdioma,
        inicialmenteExpandido: Boolean,
        private val aoAlterarExpansao: (Boolean) -> Unit
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        private var outrasExpandidas = inicialmenteExpandido
        private var query = ""
        private var items: List<VoiceRow> = emptyList()

        inner class SectionVH(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val title: TextView = itemView.findViewById(R.id.tvVoiceSectionTitle)
            val chevron: TextView = itemView.findViewById(R.id.tvVoiceSectionChevron)
        }

        inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val name: TextView = itemView.findViewById(R.id.tvVoiceName)
            val meta: TextView = itemView.findViewById(R.id.tvVoiceMeta)
            val preview: Button = itemView.findViewById(R.id.btnVoicePreview)
        }

        override fun getItemViewType(position: Int): Int = when (items[position]) {
            is VoiceRow.Section -> TYPE_SECTION
            is VoiceRow.Voice -> TYPE_VOICE
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
            if (viewType == TYPE_SECTION) {
                SectionVH(layoutInflater.inflate(R.layout.item_voice_section, parent, false))
            } else {
                VH(layoutInflater.inflate(R.layout.item_voz, parent, false))
            }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val row = items[position]) {
                is VoiceRow.Section -> bindSection(holder as SectionVH, row)
                is VoiceRow.Voice -> bindVoice(holder as VH, row.option)
            }
        }

        private fun bindSection(holder: SectionVH, section: VoiceRow.Section) {
            holder.title.text = section.label
            holder.chevron.visibility = if (section.expandable) View.VISIBLE else View.GONE
            holder.chevron.text = if (section.expanded) "⌄" else "›"
            holder.itemView.isClickable = section.expandable
            holder.itemView.isFocusable = section.expandable
            holder.itemView.contentDescription = if (section.expandable) {
                val action = if (section.expanded) R.string.voice_collapse_section
                else R.string.voice_expand_section
                "${section.label}. ${getString(action)}"
            } else {
                null
            }
            holder.itemView.setOnClickListener {
                if (section.expandable) {
                    outrasExpandidas = !outrasExpandidas
                    aoAlterarExpansao(outrasExpandidas)
                    rebuildRows()
                }
            }
        }

        fun submitFilter(value: String) {
            query = value
            rebuildRows()
        }

        private fun rebuildRows() {
            val recomendadas = filtrar(grupos.recomendadas)
            val alternativas = filtrar(grupos.outras)
            val result = mutableListOf<VoiceRow>()

            if (query.isBlank() && recomendadas.isEmpty() && grupos.recomendadas.isEmpty() &&
                alternativas.isNotEmpty()
            ) {
                result += VoiceRow.Section(
                    getString(
                        R.string.voice_no_recommended_for_language,
                        nomeDoIdioma(grupos.idiomaPreferido)
                    )
                )
            } else if (recomendadas.isNotEmpty()) {
                result += VoiceRow.Section(
                    getString(
                        R.string.voice_recommended_for_language,
                        nomeDoIdioma(grupos.idiomaPreferido)
                    )
                )
            }
            result += recomendadas.map { VoiceRow.Voice(it) }

            if (alternativas.isNotEmpty()) {
                val expandido = query.isNotBlank() || outrasExpandidas
                result += VoiceRow.Section(
                    label = getString(R.string.voice_other_languages_count, alternativas.size),
                    // Search expands matching alternatives automatically. Hide the toggle
                    // while filtering so the header doesn't promise a collapse action that
                    // cannot hide the search results.
                    expandable = query.isBlank(),
                    expanded = expandido
                )
                if (expandido) result += alternativas.map { VoiceRow.Voice(it) }
            }

            items = result
            notifyDataSetChanged()
        }

        private fun filtrar(list: List<VoiceOption>): List<VoiceOption> =
            if (query.isBlank()) list
            else list.filter {
                it.displayLabel(requireContext()).contains(query, ignoreCase = true) ||
                    nomeDoIdioma(it.language).contains(query, ignoreCase = true)
            }

        private fun statusTagPara(voice: VoiceOption): String = when {
            voice.isGenerico -> ""
            voice.engine == "onnx" -> {
                val ready = isModeloPronto(voice)
                val status = if (ready) getString(R.string.voice_status_offline_ready)
                else getString(R.string.voice_status_offline_download)
                "$status ${getString(R.string.voice_speed_offline)}"
            }
            voice.engine == "kokoro" -> {
                val ready = isModeloPronto(voice)
                val status = if (ready) getString(R.string.voice_status_offline_ready)
                else getString(R.string.voice_status_offline_download)
                "$status ${getString(R.string.voice_speed_offline)}"
            }
            voice.engine == "pocket" -> {
                val ready = isModeloPronto(voice)
                val status = if (ready) getString(R.string.voice_status_offline_ready)
                else getString(R.string.voice_status_offline_download)
                "$status ${getString(R.string.voice_speed_offline)}"
            }
            voice.engine == "gemini" -> "Gemini"
            voice.engine == "android" && voice.precisaBaixarNoSistema ->
                getString(R.string.voice_status_download_needed)
            voice.engine == "android" -> getString(R.string.voice_status_device)
            voice.engine == "elevenlabs" -> "ElevenLabs"
            else -> "Edge · ${getString(R.string.voice_speed_edge)}"
        }

        private fun bindVoice(holder: VH, voice: VoiceOption) {
            val statusTag = statusTagPara(voice)

            val isSelected = voice.id == selectedVoiceId
            holder.name.text = if (isSelected) "✓ ${voice.name}" else voice.name
            holder.name.setTextColor(
                ContextCompat.getColor(
                    requireContext(),
                    if (isSelected) R.color.color_primary else R.color.color_on_surface
                )
            )

            if (voice.isGenerico) {
                holder.meta.visibility = View.GONE
            } else {
                holder.meta.visibility = View.VISIBLE
                holder.meta.text = "${voice.metaLabel(requireContext())} · $statusTag"
            }

            holder.itemView.setOnClickListener {
                if (onVoiceSelected != null) onVoiceSelected?.invoke(voice)
                else parentFragmentManager.setFragmentResult(RESULT_KEY, Bundle().apply {
                    putString(RESULT_ACTION, ACTION_SELECT)
                    putBundle(RESULT_VOICE, voiceToBundle(voice))
                })
                dismiss()
            }
            val canPreview = onVoicePreview != null || arguments?.getBoolean(ARG_HAS_PREVIEW) == true
            holder.preview.visibility = if (canPreview) View.VISIBLE else View.GONE
            // Reseta o botão ao estado padrão sempre que a view é (re)vinculada — evita que um
            // botão fique preso em "⏳" depois de reciclado pelo scroll do RecyclerView.
            holder.preview.isEnabled = true
            holder.preview.text = PREVIEW_ICON_IDLE
            holder.preview.setOnClickListener {
                val precisaBaixarAntes = ((voice.engine == "onnx" || voice.engine == "kokoro" || voice.engine == "pocket") && !isModeloPronto(voice)) ||
                    (voice.engine == "android" && voice.precisaBaixarNoSistema)
                if (precisaBaixarAntes) {
                    android.widget.Toast.makeText(
                        requireContext(),
                        R.string.toast_download_voice_first,
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                } else {
                    holder.preview.isEnabled = false
                    holder.preview.text = PREVIEW_ICON_LOADING
                    // O voiceId como tag identifica a voz que este clique pediu — se a view
                    // já tiver sido reciclada pra outra voz quando o callback disparar, o
                    // reset abaixo é ignorado (evita mexer no item errado).
                    holder.itemView.tag = voice.id
                    val onFinished = {
                        if (holder.itemView.tag == voice.id) {
                            holder.preview.isEnabled = true
                            holder.preview.text = PREVIEW_ICON_IDLE
                        }
                    }
                    if (onVoicePreview != null) onVoicePreview?.invoke(voice, onFinished)
                    else {
                        callbacksPreviewPorVoz[voice.id] = onFinished
                        parentFragmentManager.setFragmentResult(RESULT_KEY, Bundle().apply {
                            putString(RESULT_ACTION, ACTION_PREVIEW)
                            putBundle(RESULT_VOICE, voiceToBundle(voice))
                        })
                    }
                }
            }
        }

        init {
            rebuildRows()
        }

    }
}

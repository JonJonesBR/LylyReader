package com.jonjonesbr.audiobookgen.ui.apoio

import android.graphics.PointF
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import com.jonjonesbr.audiobookgen.ui.ReaderActivity

/**
 * Diagnóstico (rb-46diag) da primeira tentativa de um arraste da alça: anota e captura durante o gesto. Opcional: o
 * teste que quer o diagnóstico passa uma implementação; os outros passam nulo.
 */
interface DiagnosticoDoArraste {
    /** Antes do arraste: posições da alça e do fim da seleção, em pixels de tela. */
    fun antesDoArraste(alca: PointF, fim: PointF)

    /** Logo depois de cada MOVE injetado, com o número do passo e a posição do dedo. */
    fun aoMover(inicioDoArraste: Long, passo: Int, x: Float, y: Float)

    /** Logo antes do UP, com o tempo desde o início do arraste. */
    fun antesDeSoltar(inicioDoArraste: Long)
}

/**
 * Resultado do laço de tentativas: a seleção depois do arraste que ampliou (nula se nenhum ampliou), o relato de
 * cada tentativa e a descrição da que deu certo.
 */
data class ResultadoDoArraste(val depois: Selecao?, val tentativas: List<String>, val sucesso: String?)

/** Distâncias abaixo da linha do fim (em dp) em que a alça é pega, na ordem das tentativas (rb-46 U4). */
private val DESLOCAMENTOS_DA_ALCA_DP = listOf(16f, 12f, 8f)

/**
 * Folgas extras do arraste, em fração da altura da linha, somadas ao deslocamento vertical. A primeira (0,8) vai além
 * da linha, como uma pessoa faz: 0,4 ficava no limite da folga de troca de linha do Editor (rb-53).
 */
private val FOLGAS_EXTRAS_DA_LINHA = listOf(0.8f, 0.4f, 1.2f)

/**
 * Arrasta a alça do fim da seleção do parágrafo [indiceParagrafo] até a palavra [palavraAlvo], com o dedo
 * andando o quanto a alça tem de andar (rb-48). Soltar o dedo direto no alvo não funciona, porque o Android mantém
 * a distância entre o dedo e a ponta da alça: por isso o dedo desce o deslocamento vertical até o alvo mais a folga
 * (0,8 linha na primeira tentativa), que é o que troca de linha. Tenta a alça a 16, 12 e 8 dp abaixo da linha, e cada
 * uma com a folga de 0,8, de 0,4 e de 1,2 linha, nessa ordem. Para no primeiro arraste que amplia a seleção
 * (início e fim de [antes] são a referência).
 *
 * Antes de cada tentativa, [reselecionar] refaz a seleção se a anterior mudou (ela pode ter virado outra). [regras]
 * fecha os diálogos do sistema antes de cada gesto. [anotar] recebe o relato de cada tentativa. [diagnostico] é
 * chamado só na primeira tentativa, para o diagnóstico do C03.
 */
fun arrastarAlcaFinalAte(
    scenario: ActivityScenario<ReaderActivity>,
    indiceParagrafo: Int,
    palavraAlvo: String,
    antes: Selecao,
    regras: RegrasDoLeitor,
    reselecionar: () -> Unit,
    anotar: (String) -> Unit,
    diagnostico: DiagnosticoDoArraste? = null,
): ResultadoDoArraste {
    val tentativas = mutableListOf<String>()
    val combinacoes = DESLOCAMENTOS_DA_ALCA_DP.flatMap { abaixo -> FOLGAS_EXTRAS_DA_LINHA.map { abaixo to it } }
    for ((indice, combinacao) in combinacoes.withIndex()) {
        val (abaixoDp, folga) = combinacao
        // Uma tentativa que falhou pode ter mudado a seleção (antes virava 'c'): se não é a inicial, reselecionar.
        val atual = selecaoAtual(scenario, indiceParagrafo)
        if (atual == null || atual.inicio != antes.inicio || atual.fim != antes.fim) reselecionar()
        // A lista pode ter descido (rb-48): alvo, alça e fim são medidos de novo em cada tentativa.
        val alvo = Gestos.pontoDaPalavra(scenario, indiceParagrafo, palavraAlvo)
        val alca = Gestos.pontoDaAlcaFinal(scenario, indiceParagrafo, abaixoDp)
        val fim = Gestos.pontoDoFimDaSelecao(scenario, indiceParagrafo)
        val altura = Gestos.alturaDaLinha(scenario, indiceParagrafo)
        val dx = alvo.x - fim.x
        val dy = alvo.y - fim.y
        val extra = folga * altura
        regras.fecharDialogosDoSistema()
        // O diagnóstico vale só para a primeira tentativa.
        val diagnosticoDaVez = if (indice == 0) diagnostico else null
        diagnosticoDaVez?.antesDoArraste(alca, fim)
        val inicioDoArraste = SystemClock.uptimeMillis()
        Gestos.arrastar(
            alca.x,
            alca.y,
            alca.x + dx,
            alca.y + dy + extra,
            aoMover = diagnosticoDaVez?.let { d ->
                { passo: Int, x: Float, y: Float -> d.aoMover(inicioDoArraste, passo, x, y) }
            },
            antesDeSoltar = diagnosticoDaVez?.let { d -> { d.antesDeSoltar(inicioDoArraste) } },
        )
        val duracaoDoGesto = SystemClock.uptimeMillis() - inicioDoArraste
        val achado = esperarFimMaiorQue(scenario, indiceParagrafo, antes.fim)
        val selecaoDepois = achado ?: selecaoAtual(scenario, indiceParagrafo)
        val descricao = "abaixo=${abaixoDp}dp folga=$folga dx=${dx.toInt()} dy=${dy.toInt()} " +
            "extra=${extra.toInt()}px gesto=${duracaoDoGesto}ms seleção=$selecaoDepois"
        tentativas += descricao
        anotar("tentativa: $descricao")
        if (achado != null) return ResultadoDoArraste(achado, tentativas, descricao)
    }
    return ResultadoDoArraste(null, tentativas, null)
}

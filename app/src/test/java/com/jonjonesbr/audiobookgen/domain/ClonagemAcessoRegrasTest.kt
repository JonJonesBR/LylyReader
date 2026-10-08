package com.jonjonesbr.audiobookgen.domain

import com.jonjonesbr.audiobookgen.domain.AcessoHf.AUTORIZADO
import com.jonjonesbr.audiobookgen.domain.AcessoHf.FALHA
import com.jonjonesbr.audiobookgen.domain.AcessoHf.NAO_ENCONTRADO
import com.jonjonesbr.audiobookgen.domain.AcessoHf.NEGADO
import com.jonjonesbr.audiobookgen.domain.ResultadoAcessoClonagem.ERRO_CONFIGURACAO
import com.jonjonesbr.audiobookgen.domain.ResultadoAcessoClonagem.ERRO_REDE
import com.jonjonesbr.audiobookgen.domain.ResultadoAcessoClonagem.FALTA_ACEITE_CODIFICADORES
import com.jonjonesbr.audiobookgen.domain.ResultadoAcessoClonagem.FALTA_ACEITE_KYUTAI
import com.jonjonesbr.audiobookgen.domain.ResultadoAcessoClonagem.LIBERADO
import com.jonjonesbr.audiobookgen.domain.ResultadoAcessoClonagem.TOKEN_INVALIDO
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClonagemAcessoRegrasTest {

    @Test
    fun `codigos de sucesso e redirecionamento para o CDN contam como acesso`() {
        listOf(200, 206, 302, 307).forEach { assertEquals(AUTORIZADO, ClonagemAcessoRegras.classificarStatus(it)) }
    }

    @Test
    fun `401 e 403 significam token recusado ou aceite faltando`() {
        assertEquals(NEGADO, ClonagemAcessoRegras.classificarStatus(401))
        assertEquals(NEGADO, ClonagemAcessoRegras.classificarStatus(403))
    }

    @Test
    fun `404 e erros de servidor ou de rede nao viram acesso`() {
        assertEquals(NAO_ENCONTRADO, ClonagemAcessoRegras.classificarStatus(404))
        assertEquals(FALHA, ClonagemAcessoRegras.classificarStatus(500))
        assertEquals(FALHA, ClonagemAcessoRegras.classificarStatus(-1))
    }

    @Test
    fun `tudo autorizado libera a clonagem`() {
        assertEquals(LIBERADO, ClonagemAcessoRegras.decidir(AUTORIZADO, AUTORIZADO, AUTORIZADO))
    }

    @Test
    fun `token recusado tem prioridade sobre os aceites`() {
        assertEquals(TOKEN_INVALIDO, ClonagemAcessoRegras.decidir(NEGADO, NEGADO, NEGADO))
    }

    @Test
    fun `falta o aceite da Kyutai antes do aceite do repositorio dos codificadores`() {
        assertEquals(FALTA_ACEITE_KYUTAI, ClonagemAcessoRegras.decidir(AUTORIZADO, NEGADO, NEGADO))
    }

    @Test
    fun `falta so o aceite do repositorio dos codificadores`() {
        assertEquals(FALTA_ACEITE_CODIFICADORES, ClonagemAcessoRegras.decidir(AUTORIZADO, AUTORIZADO, NEGADO))
    }

    @Test
    fun `sem rede aparece como erro de rede e nao como falta de aceite`() {
        assertEquals(ERRO_REDE, ClonagemAcessoRegras.decidir(AUTORIZADO, FALHA, AUTORIZADO))
        assertEquals(ERRO_REDE, ClonagemAcessoRegras.decidir(FALHA, AUTORIZADO, AUTORIZADO))
    }

    @Test
    fun `arquivo inexistente no repositorio e erro de configuracao`() {
        assertEquals(ERRO_CONFIGURACAO, ClonagemAcessoRegras.decidir(AUTORIZADO, AUTORIZADO, NAO_ENCONTRADO))
    }

    @Test
    fun `token colado precisa ter tamanho de token e nenhum espaco por dentro`() {
        assertFalse(ClonagemAcessoRegras.tokenPlausivel(""))
        assertFalse(ClonagemAcessoRegras.tokenPlausivel("   "))
        assertFalse(ClonagemAcessoRegras.tokenPlausivel("hf_curto"))
        assertFalse(ClonagemAcessoRegras.tokenPlausivel("hf_com espaco no meio do token 123"))
        assertTrue(ClonagemAcessoRegras.tokenPlausivel("  hf_abcdefghijklmnopqrstuvwxyz0123  "))
    }

    @Test
    fun `clonagem so e oferecida com token salvo e acesso verificado`() {
        assertTrue(ClonagemAcessoRegras.podeClonar(temToken = true, acessoVerificado = true))
        assertFalse(ClonagemAcessoRegras.podeClonar(temToken = true, acessoVerificado = false))
        assertFalse(ClonagemAcessoRegras.podeClonar(temToken = false, acessoVerificado = true))
    }
}

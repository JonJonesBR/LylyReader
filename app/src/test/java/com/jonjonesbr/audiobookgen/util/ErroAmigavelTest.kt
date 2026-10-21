package com.jonjonesbr.audiobookgen.util

import com.jonjonesbr.audiobookgen.R
import java.io.FileNotFoundException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import org.json.JSONException
import org.junit.Assert.assertEquals
import org.junit.Test

class ErroAmigavelTest {

    @Test
    fun `falta de rede vira mensagem de conexao`() {
        assertEquals(R.string.erro_amigavel_sem_conexao, mensagemDeErro(UnknownHostException("host")))
        assertEquals(R.string.erro_amigavel_sem_conexao, mensagemDeErro(ConnectException("recusada")))
        assertEquals(R.string.erro_amigavel_sem_conexao, mensagemDeErro(SocketTimeoutException("tempo")))
        assertEquals(R.string.erro_amigavel_sem_conexao, mensagemDeErro(SSLException("handshake")))
    }

    @Test
    fun `IOException sem espaco no aparelho vira mensagem de espaco`() {
        assertEquals(
            R.string.erro_amigavel_sem_espaco,
            mensagemDeErro(IOException("write failed: ENOSPC (No space available)"))
        )
        assertEquals(
            R.string.erro_amigavel_sem_espaco,
            mensagemDeErro(IOException("No space left on device"))
        )
    }

    @Test
    fun `IOException comum nao vira mensagem de espaco`() {
        assertEquals(R.string.erro_amigavel_generico, mensagemDeErro(IOException("falha qualquer")))
    }

    @Test
    fun `arquivo nao encontrado vira mensagem de arquivo ilegivel`() {
        assertEquals(
            R.string.erro_amigavel_arquivo_ilegivel,
            mensagemDeErro(FileNotFoundException("/sdcard/livro.epub (Permission denied)"))
        )
    }

    @Test
    fun `JSON invalido vira mensagem de formato`() {
        assertEquals(R.string.erro_amigavel_formato, mensagemDeErro(JSONException("Value abc of type")))
    }

    @Test
    fun `mensagem Unexpected token vira mensagem de formato`() {
        assertEquals(
            R.string.erro_amigavel_formato,
            mensagemDeErro(IllegalStateException("Unexpected token < in JSON at position 0"))
        )
    }

    @Test
    fun `erro desconhecido vira mensagem generica`() {
        assertEquals(R.string.erro_amigavel_generico, mensagemDeErro(IllegalStateException("bug estranho")))
        assertEquals(R.string.erro_amigavel_generico, mensagemDeErro(RuntimeException()))
    }

    @Test
    fun `causa de rede dentro de excecao generica e encontrada`() {
        val erro = RuntimeException("falhou", UnknownHostException("api.exemplo.com"))
        assertEquals(R.string.erro_amigavel_sem_conexao, mensagemDeErro(erro))
    }

    @Test
    fun `causa encadeada muito funda cai na mensagem generica`() {
        var erro: Throwable = IllegalStateException("raiz")
        repeat(10) { erro = RuntimeException("camada $it", erro) }
        assertEquals(R.string.erro_amigavel_generico, mensagemDeErro(erro))
    }

    @Test
    fun `causa que aponta para si mesma nao trava a busca`() {
        val erro = object : RuntimeException("circular") {
            override val cause: Throwable get() = this
        }
        assertEquals(R.string.erro_amigavel_generico, mensagemDeErro(erro))
    }
}

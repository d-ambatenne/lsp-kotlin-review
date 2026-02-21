package dev.review.lsp

import org.eclipse.lsp4j.*
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class KotlinLanguageServerTest {

    @Test
    fun `initialize returns server capabilities with full text sync`() {
        val server = KotlinLanguageServer()
        val params = InitializeParams().apply {
            capabilities = ClientCapabilities()
        }

        val result = server.initialize(params).get()

        assertNotNull(result.capabilities)
        assertEquals(TextDocumentSyncKind.Full, result.capabilities.textDocumentSync.left)
    }

    @Test
    fun `initialize returns server info`() {
        val server = KotlinLanguageServer()
        val params = InitializeParams().apply {
            capabilities = ClientCapabilities()
        }

        val result = server.initialize(params).get()

        assertNotNull(result.serverInfo)
        assertEquals("kotlin-review-lsp", result.serverInfo.name)
        assertEquals("0.1.0", result.serverInfo.version)
    }

    @Test
    fun `shutdown completes without error`() {
        val server = KotlinLanguageServer()
        val result = server.shutdown().get()
        // shutdown should complete normally (null result is fine)
    }
}

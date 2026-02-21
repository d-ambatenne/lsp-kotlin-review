package dev.review.lsp.analysis

import dev.review.lsp.compiler.*
import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.services.LanguageClient
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.CompletableFuture
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DiagnosticsPublisherTest {

    private val testPath = Paths.get("/test/File.kt")
    private val testUri = "file:///test/File.kt"

    @Test
    fun `publishes diagnostics from facade`() {
        val diag = DiagnosticInfo(
            severity = Severity.ERROR,
            message = "Type mismatch",
            range = SourceRange(testPath, 0, 0, 0, 10),
            code = "TYPE_MISMATCH",
            quickFixes = emptyList()
        )

        val facade = object : StubCompilerFacade() {
            override fun getDiagnostics(file: Path): List<DiagnosticInfo> = listOf(diag)
        }

        val published = mutableListOf<PublishDiagnosticsParams>()
        val client = testClient { published.add(it) }

        val publisher = DiagnosticsPublisher(facade, client)
        publisher.publishDiagnostics(testPath, testUri)

        assertEquals(1, published.size)
        assertEquals(testUri, published[0].uri)
        assertEquals(1, published[0].diagnostics.size)
        assertEquals("Type mismatch", published[0].diagnostics[0].message)
        assertEquals(DiagnosticSeverity.Error, published[0].diagnostics[0].severity)
    }

    @Test
    fun `clears diagnostics`() {
        val facade = StubCompilerFacade()
        val published = mutableListOf<PublishDiagnosticsParams>()
        val client = testClient { published.add(it) }

        val publisher = DiagnosticsPublisher(facade, client)
        publisher.clearDiagnostics(testUri)

        assertEquals(1, published.size)
        assertTrue(published[0].diagnostics.isEmpty())
    }

    private fun testClient(onPublish: (PublishDiagnosticsParams) -> Unit): LanguageClient {
        return object : LanguageClient {
            override fun telemetryEvent(obj: Any?) {}
            override fun publishDiagnostics(params: PublishDiagnosticsParams) { onPublish(params) }
            override fun showMessage(params: MessageParams) {}
            override fun showMessageRequest(params: ShowMessageRequestParams): CompletableFuture<MessageActionItem> =
                CompletableFuture.completedFuture(null)
            override fun logMessage(params: MessageParams) {}
        }
    }
}

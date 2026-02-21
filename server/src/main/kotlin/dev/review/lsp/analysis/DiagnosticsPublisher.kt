package dev.review.lsp.analysis

import dev.review.lsp.compiler.CompilerFacade
import dev.review.lsp.compiler.DiagnosticInfo
import dev.review.lsp.compiler.Severity
import dev.review.lsp.util.PositionConverter
import dev.review.lsp.util.UriUtil
import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.services.LanguageClient
import java.nio.file.Path

class DiagnosticsPublisher(
    private val facade: CompilerFacade,
    private val client: LanguageClient
) {
    /**
     * Publish diagnostics for a file. If [requestVersion] and [currentVersionSupplier] are
     * provided, the result is discarded when the document has been modified since the request
     * was initiated (stale analysis cancellation).
     */
    fun publishDiagnostics(
        file: Path,
        uri: String,
        requestVersion: Int? = null,
        currentVersionSupplier: (() -> Int?)? = null
    ) {
        val diagnostics = try {
            facade.getDiagnostics(file)
        } catch (e: Exception) {
            // Corrupt .kt files or analysis errors: skip gracefully
            client.logMessage(MessageParams(MessageType.Warning, "Diagnostics failed for $uri: ${e.message}"))
            emptyList()
        }

        // Discard stale results if the document version has advanced
        if (requestVersion != null && currentVersionSupplier != null) {
            val currentVersion = currentVersionSupplier()
            if (currentVersion != null && currentVersion != requestVersion) {
                return
            }
        }

        val lspDiagnostics = diagnostics.map { it.toLsp() }
        client.publishDiagnostics(PublishDiagnosticsParams(uri, lspDiagnostics))
    }

    fun clearDiagnostics(uri: String) {
        client.publishDiagnostics(PublishDiagnosticsParams(uri, emptyList()))
    }

    private fun DiagnosticInfo.toLsp(): Diagnostic = Diagnostic(
        PositionConverter.toLspRange(range),
        message,
        toLspSeverity(severity),
        "kotlin-review",
        code
    )

    private fun toLspSeverity(severity: Severity): DiagnosticSeverity = when (severity) {
        Severity.ERROR -> DiagnosticSeverity.Error
        Severity.WARNING -> DiagnosticSeverity.Warning
        Severity.INFO -> DiagnosticSeverity.Information
    }
}

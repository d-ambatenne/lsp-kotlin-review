package dev.review.lsp.analysis

import dev.review.lsp.compiler.CompilerFacade
import dev.review.lsp.compiler.DiagnosticInfo
import dev.review.lsp.compiler.Severity
import dev.review.lsp.util.PositionConverter
import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.services.LanguageClient
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CompletableFuture

class DiagnosticsPublisher(
    private val facade: CompilerFacade,
    private val client: LanguageClient
) {
    // Cache diagnostics per file — avoids recomputing when session hasn't changed
    private val cache = ConcurrentHashMap<Path, List<DiagnosticInfo>>()

    /**
     * Publish diagnostics for a file asynchronously.
     * Uses cached results if available; computes fresh diagnostics otherwise.
     */
    fun publishDiagnosticsAsync(
        file: Path,
        uri: String,
        requestVersion: Int? = null,
        currentVersionSupplier: (() -> Int?)? = null
    ) {
        // Serve from cache immediately if available
        val cached = cache[file]
        if (cached != null) {
            publishToClient(uri, cached)
        }

        // Compute fresh diagnostics in background (non-blocking)
        CompletableFuture.runAsync {
            publishDiagnostics(file, uri, requestVersion, currentVersionSupplier)
        }
    }

    /**
     * Publish diagnostics synchronously (used after session rebuild on save).
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

        cache[file] = diagnostics
        publishToClient(uri, diagnostics)
    }

    /** Invalidate cache for all files (called after session rebuild). */
    fun invalidateCache() {
        cache.clear()
    }

    fun clearDiagnostics(uri: String) {
        client.publishDiagnostics(PublishDiagnosticsParams(uri, emptyList()))
    }

    private fun publishToClient(uri: String, diagnostics: List<DiagnosticInfo>) {
        val lspDiagnostics = diagnostics.map { it.toLsp() }
        client.publishDiagnostics(PublishDiagnosticsParams(uri, lspDiagnostics))
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

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
import java.util.concurrent.atomic.AtomicLong

class DiagnosticsPublisher(
    private val facade: CompilerFacade,
    private val client: LanguageClient
) {
    // Cache diagnostics per file — avoids recomputing when session hasn't changed
    private val cache = ConcurrentHashMap<Path, List<DiagnosticInfo>>()

    // Epoch counter per file — prevents stale tier results from overwriting newer ones
    private val diagnosticEpoch = ConcurrentHashMap<Path, AtomicLong>()

    /** Increment and return new epoch for a file. */
    fun bumpEpoch(file: Path): Long {
        return diagnosticEpoch.getOrPut(file) { AtomicLong(0) }.incrementAndGet()
    }

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

    /**
     * Tier 1: Merge syntax errors with cached semantic diagnostics and publish.
     * Only publishes if the epoch is still current (not superseded by a newer analysis).
     */
    /**
     * Tier 1: Merge syntax errors with cached semantic diagnostics and publish.
     * Filters out stale cached diagnostics that reference deleted/changed lines.
     * @param currentContent the current buffer text (for line-level staleness check)
     * @param previousContent the last-saved text (what the cached diagnostics were computed against)
     */
    fun publishMerged(
        file: Path, uri: String, syntaxErrors: List<DiagnosticInfo>, epoch: Long,
        currentContent: String?, previousContent: String?
    ) {
        if (currentEpoch(file) != epoch) return

        val cachedSemantic = cache[file] ?: emptyList()
        val currentLines = currentContent?.lines()
        val previousLines = previousContent?.lines()
        val currentLineCount = currentLines?.size ?: Int.MAX_VALUE

        // Build line ranges covered by syntax errors
        val syntaxLines = syntaxErrors.map { it.range.startLine..it.range.endLine }.toSet()

        val filteredSemantic = cachedSemantic.filter { diag ->
            val startLine = diag.range.startLine
            val endLine = diag.range.endLine

            // Drop diagnostics referencing lines beyond current file length
            if (endLine >= currentLineCount) return@filter false

            // Drop diagnostics on lines whose content has changed
            if (currentLines != null && previousLines != null) {
                for (line in startLine..endLine.coerceAtMost(currentLineCount - 1)) {
                    val cur = currentLines.getOrNull(line) ?: return@filter false
                    val prev = previousLines.getOrNull(line)
                    if (cur != prev) return@filter false
                }
            }

            // Drop diagnostics that overlap with syntax error lines
            val diagRange = startLine..endLine
            syntaxLines.none { syntaxRange ->
                diagRange.first <= syntaxRange.last && diagRange.last >= syntaxRange.first
            }
        }

        val merged = syntaxErrors + filteredSemantic
        if (currentEpoch(file) == epoch) {
            publishToClient(uri, merged)
        }
    }

    /**
     * Tier 2: Update cache with fresh semantic diagnostics and publish.
     * Only publishes if the epoch is still current.
     */
    fun updateCacheAndPublish(file: Path, uri: String, diagnostics: List<DiagnosticInfo>, epoch: Long) {
        if (currentEpoch(file) != epoch) return
        cache[file] = diagnostics
        if (currentEpoch(file) == epoch) {
            publishToClient(uri, diagnostics)
        }
    }

    /** Invalidate cache for a single file. */
    fun invalidateCacheForFile(file: Path) {
        cache.remove(file)
    }

    /** Invalidate cache for all files (called after session rebuild). */
    fun invalidateCache() {
        cache.clear()
    }

    fun clearDiagnostics(uri: String) {
        client.publishDiagnostics(PublishDiagnosticsParams(uri, emptyList()))
    }

    private fun currentEpoch(file: Path): Long {
        return diagnosticEpoch[file]?.get() ?: 0
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

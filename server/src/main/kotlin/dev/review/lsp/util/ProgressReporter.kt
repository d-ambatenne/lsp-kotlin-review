package dev.review.lsp.util

import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.eclipse.lsp4j.services.LanguageClient
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Thin wrapper around the LSP `window/workDoneProgress` protocol.
 * All methods are safe to call even if the client doesn't support progress —
 * failures are silently logged to stderr.
 */
class ProgressReporter(private val client: LanguageClient) {

    companion object {
        private val tokenCounter = AtomicInteger(0)
    }

    private var token: Either<String, Int>? = null

    /**
     * Register a progress token with the client. Must be called before [begin].
     */
    fun create(tokenName: String? = null) {
        val name = tokenName ?: "kotlin-review-${tokenCounter.incrementAndGet()}"
        val t = Either.forLeft<String, Int>(name)
        try {
            client.createProgress(WorkDoneProgressCreateParams(t)).get(5, TimeUnit.SECONDS)
            token = t
        } catch (e: Exception) {
            System.err.println("[progress] Failed to create progress token: ${e.message}")
        }
    }

    fun begin(title: String, message: String? = null, percentage: Int? = null) {
        val t = token ?: return
        val value = WorkDoneProgressBegin().apply {
            this.title = title
            this.message = message
            this.cancellable = false
            if (percentage != null) this.percentage = percentage
        }
        notify(t, value)
    }

    fun report(message: String, percentage: Int? = null) {
        val t = token ?: return
        val value = WorkDoneProgressReport().apply {
            this.message = message
            if (percentage != null) this.percentage = percentage
        }
        notify(t, value)
    }

    fun end(message: String? = null) {
        val t = token ?: return
        val value = WorkDoneProgressEnd().apply {
            this.message = message
        }
        notify(t, value)
        token = null
    }

    private fun notify(token: Either<String, Int>, value: WorkDoneProgressNotification) {
        try {
            client.notifyProgress(ProgressParams(token, Either.forLeft(value)))
        } catch (e: Exception) {
            System.err.println("[progress] Failed to send progress notification: ${e.message}")
        }
    }
}

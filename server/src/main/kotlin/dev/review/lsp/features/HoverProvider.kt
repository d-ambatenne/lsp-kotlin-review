package dev.review.lsp.features

import dev.review.lsp.compiler.CompilerFacade
import dev.review.lsp.util.PositionConverter
import dev.review.lsp.util.UriUtil
import org.eclipse.lsp4j.*
import java.util.concurrent.CompletableFuture

class HoverProvider(private val facade: CompilerFacade) {

    fun hover(params: HoverParams): CompletableFuture<Hover?> {
        return CompletableFuture.supplyAsync {
            val path = UriUtil.toPath(params.textDocument.uri)
            val (line, col) = PositionConverter.fromLspPosition(params.position)

            val symbol = facade.resolveAtPosition(path, line, col)
            val typeInfo = facade.getType(path, line, col)

            if (symbol == null && typeInfo == null) return@supplyAsync null

            val parts = mutableListOf<String>()

            // Signature from resolved symbol
            symbol?.signature?.let { parts.add("```kotlin\n$it\n```") }

            // Type info (if no signature available)
            if (symbol?.signature == null && typeInfo != null) {
                parts.add("**Type**: `${typeInfo.shortName}`")
            }

            // Documentation
            if (symbol != null) {
                facade.getDocumentation(symbol)?.let { parts.add(it) }
            }

            if (parts.isEmpty()) return@supplyAsync null

            Hover(MarkupContent(MarkupKind.MARKDOWN, parts.joinToString("\n\n")))
        }
    }
}

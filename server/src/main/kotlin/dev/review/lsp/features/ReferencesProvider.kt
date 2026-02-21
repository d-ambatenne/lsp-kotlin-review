package dev.review.lsp.features

import dev.review.lsp.compiler.CompilerFacade
import dev.review.lsp.util.PositionConverter
import dev.review.lsp.util.UriUtil
import org.eclipse.lsp4j.*
import java.util.concurrent.CompletableFuture

class ReferencesProvider(private val facade: CompilerFacade) {

    fun references(params: ReferenceParams): CompletableFuture<List<Location>> {
        return CompletableFuture.supplyAsync {
            val path = UriUtil.toPath(params.textDocument.uri)
            val (line, col) = PositionConverter.fromLspPosition(params.position)
            val symbol = facade.resolveAtPosition(path, line, col)
                ?: return@supplyAsync emptyList()

            facade.findReferences(symbol).map { PositionConverter.toLspLocation(it) }
        }
    }
}

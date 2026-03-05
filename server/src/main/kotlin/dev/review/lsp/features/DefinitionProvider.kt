package dev.review.lsp.features

import dev.review.lsp.compiler.CompilerFacade
import dev.review.lsp.util.PositionConverter
import dev.review.lsp.util.UriUtil
import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.jsonrpc.messages.Either
import java.util.concurrent.CompletableFuture

class DefinitionProvider(private val facade: CompilerFacade) {

    fun definition(params: DefinitionParams): CompletableFuture<Either<List<Location>, List<LocationLink>>> {
        return CompletableFuture.supplyAsync {
            try {
                val path = UriUtil.toPath(params.textDocument.uri)
                val (line, col) = PositionConverter.fromLspPosition(params.position)
                val symbol = facade.resolveAtPosition(path, line, col)
                    ?: return@supplyAsync Either.forLeft(emptyList())

                // Skip if the definition points back to the usage site (library symbol with no source)
                val usageLoc = dev.review.lsp.compiler.SourceLocation(path, line, col)
                if (symbol.location == usageLoc) {
                    return@supplyAsync Either.forLeft(emptyList())
                }

                val location = PositionConverter.toLspLocation(symbol.location)
                Either.forLeft(listOf(location))
            } catch (e: Exception) {
                System.err.println("[provider] Error in definition: ${e.message}")
                Either.forLeft(emptyList())
            }
        }
    }
}

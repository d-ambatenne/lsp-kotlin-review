package dev.review.lsp.features

import dev.review.lsp.compiler.CompilerFacade
import dev.review.lsp.util.PositionConverter
import dev.review.lsp.util.UriUtil
import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.jsonrpc.messages.Either
import java.util.concurrent.CompletableFuture

class ImplementationProvider(private val facade: CompilerFacade) {

    fun implementation(params: ImplementationParams): CompletableFuture<Either<List<Location>, List<LocationLink>>> {
        return CompletableFuture.supplyAsync {
            val path = UriUtil.toPath(params.textDocument.uri)
            val (line, col) = PositionConverter.fromLspPosition(params.position)
            val symbol = facade.resolveAtPosition(path, line, col)
                ?: return@supplyAsync Either.forLeft(emptyList())

            val results = mutableListOf<Location>()

            // Check for expect/actual counterparts (KMP cross-platform navigation)
            val counterparts = facade.findExpectActualCounterparts(path, line, col)
            results.addAll(counterparts.map { PositionConverter.toLspLocation(it.location) })

            // Also check for class/interface implementations
            val impls = facade.findImplementations(symbol)
            results.addAll(impls.map { PositionConverter.toLspLocation(it) })

            Either.forLeft(results)
        }
    }
}

package dev.review.lsp.features

import dev.review.lsp.compiler.CompilerFacade
import dev.review.lsp.util.PositionConverter
import dev.review.lsp.util.UriUtil
import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.jsonrpc.messages.Either
import java.util.concurrent.CompletableFuture

class TypeDefinitionProvider(private val facade: CompilerFacade) {

    fun typeDefinition(params: TypeDefinitionParams): CompletableFuture<Either<List<Location>, List<LocationLink>>> {
        return CompletableFuture.supplyAsync {
            val path = UriUtil.toPath(params.textDocument.uri)
            val (line, col) = PositionConverter.fromLspPosition(params.position)
            val location = facade.getTypeDefinitionLocation(path, line, col)
                ?: return@supplyAsync Either.forLeft(emptyList())

            Either.forLeft(listOf(PositionConverter.toLspLocation(location)))
        }
    }
}

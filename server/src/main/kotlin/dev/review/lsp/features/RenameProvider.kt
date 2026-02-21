package dev.review.lsp.features

import dev.review.lsp.compiler.CompilerFacade
import dev.review.lsp.util.PositionConverter
import dev.review.lsp.util.UriUtil
import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.jsonrpc.messages.Either3
import java.util.concurrent.CompletableFuture

class RenameProvider(private val facade: CompilerFacade) {

    fun prepareRename(params: PrepareRenameParams): CompletableFuture<Either3<Range, PrepareRenameResult, PrepareRenameDefaultBehavior>?> {
        return CompletableFuture.supplyAsync {
            val path = UriUtil.toPath(params.textDocument.uri)
            val (line, col) = PositionConverter.fromLspPosition(params.position)
            val ctx = facade.prepareRename(path, line, col) ?: return@supplyAsync null
            val range = PositionConverter.toLspRange(ctx.range)
            Either3.forSecond(PrepareRenameResult(range, ctx.symbol.name))
        }
    }

    fun rename(params: RenameParams): CompletableFuture<WorkspaceEdit?> {
        return CompletableFuture.supplyAsync {
            val path = UriUtil.toPath(params.textDocument.uri)
            val (line, col) = PositionConverter.fromLspPosition(params.position)
            val ctx = facade.prepareRename(path, line, col) ?: return@supplyAsync null

            val edits = facade.computeRename(ctx, params.newName)
            if (edits.isEmpty()) return@supplyAsync null

            val changes = edits.groupBy { UriUtil.toUri(it.path) }
                .mapValues { (_, fileEdits) ->
                    fileEdits.map { edit ->
                        TextEdit(PositionConverter.toLspRange(edit.range), edit.newText)
                    }
                }

            WorkspaceEdit(changes)
        }
    }
}

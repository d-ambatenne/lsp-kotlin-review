package dev.review.lsp.features

import dev.review.lsp.compiler.CompilerFacade
import dev.review.lsp.util.PositionConverter
import dev.review.lsp.util.UriUtil
import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.jsonrpc.messages.Either
import java.util.concurrent.CompletableFuture

class CodeActionProvider(private val facade: CompilerFacade) {

    fun codeAction(params: CodeActionParams): CompletableFuture<List<Either<Command, CodeAction>>> {
        return CompletableFuture.supplyAsync {
            val path = UriUtil.toPath(params.textDocument.uri)
            val diagnostics = facade.getDiagnostics(path)

            val result = mutableListOf<Either<Command, CodeAction>>()

            for (diag in diagnostics) {
                val diagRange = PositionConverter.toLspRange(diag.range)
                if (!rangesOverlap(params.range, diagRange)) continue

                for (fix in diag.quickFixes) {
                    val changes = fix.edits.groupBy { UriUtil.toUri(it.path) }
                        .mapValues { (_, fileEdits) ->
                            fileEdits.map { edit ->
                                TextEdit(PositionConverter.toLspRange(edit.range), edit.newText)
                            }
                        }

                    val action = CodeAction(fix.title).apply {
                        kind = CodeActionKind.QuickFix
                        edit = WorkspaceEdit(changes)
                        this.diagnostics = listOf(Diagnostic(
                            diagRange,
                            diag.message,
                            null,
                            "kotlin-review",
                            diag.code
                        ))
                    }
                    result.add(Either.forRight(action))
                }
            }

            result
        }
    }

    private fun rangesOverlap(a: Range, b: Range): Boolean {
        if (a.end.line < b.start.line) return false
        if (b.end.line < a.start.line) return false
        if (a.end.line == b.start.line && a.end.character < b.start.character) return false
        if (b.end.line == a.start.line && b.end.character < a.start.character) return false
        return true
    }
}

package dev.review.lsp.features

import dev.review.lsp.compiler.CompilerFacade
import dev.review.lsp.compiler.SymbolKind as OurSymbolKind
import dev.review.lsp.util.PositionConverter
import dev.review.lsp.util.UriUtil
import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.jsonrpc.messages.Either
import java.util.concurrent.CompletableFuture

class CompletionProvider(private val facade: CompilerFacade) {

    fun completion(params: CompletionParams): CompletableFuture<Either<List<CompletionItem>, CompletionList>> {
        return CompletableFuture.supplyAsync {
            val path = UriUtil.toPath(params.textDocument.uri)
            val (line, col) = PositionConverter.fromLspPosition(params.position)
            val candidates = facade.getCompletions(path, line, col)

            val items = candidates.map { candidate ->
                CompletionItem(candidate.label).apply {
                    kind = toLspCompletionKind(candidate.kind)
                    detail = candidate.detail
                    insertText = candidate.insertText
                    if (candidate.isDeprecated) {
                        tags = listOf(CompletionItemTag.Deprecated)
                    }
                }
            }

            Either.forLeft(items)
        }
    }

    private fun toLspCompletionKind(kind: OurSymbolKind): CompletionItemKind = when (kind) {
        OurSymbolKind.CLASS -> CompletionItemKind.Class
        OurSymbolKind.INTERFACE -> CompletionItemKind.Interface
        OurSymbolKind.OBJECT -> CompletionItemKind.Class
        OurSymbolKind.ENUM -> CompletionItemKind.Enum
        OurSymbolKind.ENUM_ENTRY -> CompletionItemKind.EnumMember
        OurSymbolKind.FUNCTION -> CompletionItemKind.Function
        OurSymbolKind.PROPERTY -> CompletionItemKind.Property
        OurSymbolKind.CONSTRUCTOR -> CompletionItemKind.Constructor
        OurSymbolKind.TYPE_ALIAS -> CompletionItemKind.TypeParameter
        OurSymbolKind.TYPE_PARAMETER -> CompletionItemKind.TypeParameter
        OurSymbolKind.PACKAGE -> CompletionItemKind.Module
        OurSymbolKind.FILE -> CompletionItemKind.File
        OurSymbolKind.LOCAL_VARIABLE -> CompletionItemKind.Variable
        OurSymbolKind.PARAMETER -> CompletionItemKind.Variable
    }
}

package dev.review.lsp.features

import dev.review.lsp.compiler.CompilerFacade
import dev.review.lsp.compiler.SymbolKind as OurSymbolKind
import dev.review.lsp.util.PositionConverter
import dev.review.lsp.util.UriUtil
import org.eclipse.lsp4j.*
import java.util.concurrent.CompletableFuture

class DocumentSymbolProvider(private val facade: CompilerFacade) {

    fun documentSymbol(params: DocumentSymbolParams): CompletableFuture<List<DocumentSymbol>> {
        return CompletableFuture.supplyAsync {
            try {
                val path = UriUtil.toPath(params.textDocument.uri)
                val symbols = facade.getFileSymbols(path)

                symbols.map { sym ->
                    val pos = Position(sym.location.line, sym.location.column)
                    val range = Range(pos, pos)
                    DocumentSymbol(
                        sym.name,
                        toLspSymbolKind(sym.kind),
                        range,
                        range,
                        sym.signature
                    )
                }
            } catch (e: Exception) {
                System.err.println("documentSymbol failed: ${e.message}")
                emptyList()
            }
        }
    }

    private fun toLspSymbolKind(kind: OurSymbolKind): SymbolKind = when (kind) {
        OurSymbolKind.CLASS -> SymbolKind.Class
        OurSymbolKind.INTERFACE -> SymbolKind.Interface
        OurSymbolKind.OBJECT -> SymbolKind.Object
        OurSymbolKind.ENUM -> SymbolKind.Enum
        OurSymbolKind.ENUM_ENTRY -> SymbolKind.EnumMember
        OurSymbolKind.FUNCTION -> SymbolKind.Function
        OurSymbolKind.PROPERTY -> SymbolKind.Property
        OurSymbolKind.CONSTRUCTOR -> SymbolKind.Constructor
        OurSymbolKind.TYPE_ALIAS -> SymbolKind.TypeParameter
        OurSymbolKind.TYPE_PARAMETER -> SymbolKind.TypeParameter
        OurSymbolKind.PACKAGE -> SymbolKind.Package
        OurSymbolKind.FILE -> SymbolKind.File
        OurSymbolKind.LOCAL_VARIABLE -> SymbolKind.Variable
        OurSymbolKind.PARAMETER -> SymbolKind.Variable
    }
}

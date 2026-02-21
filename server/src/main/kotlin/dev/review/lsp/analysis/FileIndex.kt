package dev.review.lsp.analysis

import dev.review.lsp.compiler.CompilerFacade
import dev.review.lsp.compiler.ResolvedSymbol
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

class FileIndex {

    private val symbolsByFile = ConcurrentHashMap<Path, List<ResolvedSymbol>>()
    private val symbolsByName = ConcurrentHashMap<String, MutableSet<Path>>()

    fun updateFile(file: Path, facade: CompilerFacade) {
        val symbols = facade.getFileSymbols(file)
        val oldSymbols = symbolsByFile.put(file, symbols)

        oldSymbols?.forEach { sym ->
            symbolsByName[sym.name]?.remove(file)
        }

        symbols.forEach { sym ->
            symbolsByName.getOrPut(sym.name) { ConcurrentHashMap.newKeySet() }.add(file)
        }
    }

    fun removeFile(file: Path) {
        val old = symbolsByFile.remove(file)
        old?.forEach { sym ->
            symbolsByName[sym.name]?.remove(file)
        }
    }

    fun getSymbols(file: Path): List<ResolvedSymbol> =
        symbolsByFile[file] ?: emptyList()

    fun findFilesBySymbolName(name: String): Set<Path> =
        symbolsByName[name] ?: emptySet()

    fun allFiles(): Set<Path> = symbolsByFile.keys.toSet()
}

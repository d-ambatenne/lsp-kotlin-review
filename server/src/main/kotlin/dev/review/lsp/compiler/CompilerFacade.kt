package dev.review.lsp.compiler

import java.nio.file.Path

interface CompilerFacade {

    fun resolveAtPosition(file: Path, line: Int, column: Int): ResolvedSymbol?

    fun getType(file: Path, line: Int, column: Int): TypeInfo?

    fun getDiagnostics(file: Path): List<DiagnosticInfo>

    fun getCompletions(file: Path, line: Int, column: Int): List<CompletionCandidate>

    fun findReferences(symbol: ResolvedSymbol): List<SourceLocation>

    fun findImplementations(symbol: ResolvedSymbol): List<SourceLocation>

    fun getDocumentation(symbol: ResolvedSymbol): String?

    fun getFileSymbols(file: Path): List<ResolvedSymbol>

    fun prepareRename(file: Path, line: Int, column: Int): RenameContext?

    fun computeRename(context: RenameContext, newName: String): List<FileEdit>

    fun getTypeDefinitionLocation(file: Path, line: Int, column: Int): SourceLocation? = null

    fun updateFileContent(file: Path, content: String)

    /** Rebuild the analysis session from disk. Called on file save. */
    fun refreshAnalysis() {}

    fun dispose()
}

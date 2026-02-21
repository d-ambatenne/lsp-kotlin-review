package dev.review.lsp.compiler

import java.nio.file.Path

open class StubCompilerFacade : CompilerFacade {

    override fun resolveAtPosition(file: Path, line: Int, column: Int): ResolvedSymbol? = null

    override fun getType(file: Path, line: Int, column: Int): TypeInfo? = null

    override fun getDiagnostics(file: Path): List<DiagnosticInfo> = emptyList()

    override fun getCompletions(file: Path, line: Int, column: Int): List<CompletionCandidate> = emptyList()

    override fun findReferences(symbol: ResolvedSymbol): List<SourceLocation> = emptyList()

    override fun findImplementations(symbol: ResolvedSymbol): List<SourceLocation> = emptyList()

    override fun getDocumentation(symbol: ResolvedSymbol): String? = null

    override fun getFileSymbols(file: Path): List<ResolvedSymbol> = emptyList()

    override fun prepareRename(file: Path, line: Int, column: Int): RenameContext? = null

    override fun computeRename(context: RenameContext, newName: String): List<FileEdit> = emptyList()

    override fun updateFileContent(file: Path, content: String) {}

    override fun dispose() {}
}

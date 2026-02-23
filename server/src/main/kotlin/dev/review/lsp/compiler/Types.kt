package dev.review.lsp.compiler

import java.nio.file.Path

enum class SymbolKind {
    CLASS, INTERFACE, OBJECT, ENUM, ENUM_ENTRY,
    FUNCTION, PROPERTY, CONSTRUCTOR,
    TYPE_ALIAS, TYPE_PARAMETER,
    PACKAGE, FILE, LOCAL_VARIABLE, PARAMETER
}

enum class Severity {
    ERROR, WARNING, INFO
}

data class SourceLocation(
    val path: Path,
    val line: Int,      // 0-based
    val column: Int     // 0-based
)

data class SourceRange(
    val path: Path,
    val startLine: Int,
    val startColumn: Int,
    val endLine: Int,
    val endColumn: Int
)

data class ResolvedSymbol(
    val name: String,
    val kind: SymbolKind,
    val location: SourceLocation,
    val containingClass: String?,
    val signature: String?,
    val fqName: String?
)

data class TypeInfo(
    val fqName: String,
    val shortName: String,
    val nullable: Boolean,
    val typeArguments: List<TypeInfo>
)

data class DiagnosticInfo(
    val severity: Severity,
    val message: String,
    val range: SourceRange,
    val code: String?,
    val quickFixes: List<QuickFix>
)

data class QuickFix(
    val title: String,
    val edits: List<FileEdit>
)

data class FileEdit(
    val path: Path,
    val range: SourceRange,
    val newText: String
)

data class CompletionCandidate(
    val label: String,
    val kind: SymbolKind,
    val detail: String?,
    val insertText: String,
    val isDeprecated: Boolean,
    val sortPriority: Int = 0
)

data class RenameContext(
    val symbol: ResolvedSymbol,
    val range: SourceRange
)

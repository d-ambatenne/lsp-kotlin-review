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
                    sortText = String.format("%d_%s", candidate.sortPriority, candidate.label.lowercase())
                }
            }.toMutableList()

            // Add Kotlin keyword completions (only for non-dot completion)
            val isDotCompletion = candidates.any { it.sortPriority < 0 } // dot completions have negative priority
                || params.context?.triggerCharacter == "."
            if (!isDotCompletion) {
                val prefix = extractPrefix(params)
                items.addAll(keywordCompletions(prefix))
            }

            Either.forLeft(items as List<CompletionItem>)
        }
    }

    private fun extractPrefix(params: CompletionParams): String {
        // The prefix is whatever the user has typed before the cursor.
        // VS Code sends completions on each keystroke — we can use the
        // position to approximate by checking if there's a trigger character.
        // For keyword completion, we just need to match against the typed chars.
        // The actual prefix filtering is done by VS Code client-side.
        return ""
    }

    private fun keywordCompletions(prefix: String): List<CompletionItem> {
        return KOTLIN_KEYWORDS.map { (keyword, detail, insertText) ->
            CompletionItem(keyword).apply {
                kind = CompletionItemKind.Keyword
                this.detail = "Kotlin · $detail"
                this.insertText = insertText
                sortText = "0_keyword_$keyword" // top of the list for Kotlin projects
            }
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

    companion object {
        // (keyword, detail, insertText)
        private val KOTLIN_KEYWORDS = listOf(
            // Declarations
            Triple("val", "Immutable variable", "val "),
            Triple("var", "Mutable variable", "var "),
            Triple("fun", "Function declaration", "fun "),
            Triple("class", "Class declaration", "class "),
            Triple("interface", "Interface declaration", "interface "),
            Triple("object", "Object declaration", "object "),
            Triple("enum", "Enum class", "enum class "),
            Triple("data", "Data class modifier", "data class "),
            Triple("sealed", "Sealed class/interface", "sealed "),
            Triple("abstract", "Abstract declaration", "abstract "),
            Triple("open", "Open declaration", "open "),
            Triple("annotation", "Annotation class", "annotation class "),
            Triple("typealias", "Type alias", "typealias "),
            Triple("companion", "Companion object", "companion object "),
            Triple("constructor", "Constructor", "constructor"),
            Triple("init", "Initializer block", "init {\n    \n}"),

            // Control flow
            Triple("if", "If expression", "if ("),
            Triple("else", "Else branch", "else "),
            Triple("when", "When expression", "when "),
            Triple("for", "For loop", "for ("),
            Triple("while", "While loop", "while ("),
            Triple("do", "Do-while loop", "do {\n    \n} while ("),
            Triple("return", "Return statement", "return "),
            Triple("break", "Break loop", "break"),
            Triple("continue", "Continue loop", "continue"),
            Triple("throw", "Throw exception", "throw "),
            Triple("try", "Try-catch block", "try {\n    \n} catch (e: Exception) {\n    \n}"),
            Triple("catch", "Catch block", "catch ("),

            // Modifiers
            Triple("override", "Override modifier", "override "),
            Triple("private", "Private visibility", "private "),
            Triple("protected", "Protected visibility", "protected "),
            Triple("internal", "Internal visibility", "internal "),
            Triple("public", "Public visibility", "public "),
            Triple("lateinit", "Late initialization", "lateinit "),
            Triple("const", "Compile-time constant", "const "),
            Triple("suspend", "Suspend function", "suspend "),
            Triple("inline", "Inline modifier", "inline "),
            Triple("operator", "Operator overload", "operator "),
            Triple("infix", "Infix function", "infix "),
            Triple("tailrec", "Tail-recursive", "tailrec "),
            Triple("external", "External declaration", "external "),
            Triple("vararg", "Variable arguments", "vararg "),

            // Expressions & operators
            Triple("is", "Type check", "is "),
            Triple("as", "Type cast", "as "),
            Triple("in", "In operator", "in "),
            Triple("null", "Null literal", "null"),
            Triple("true", "Boolean true", "true"),
            Triple("false", "Boolean false", "false"),
            Triple("this", "Current instance", "this"),
            Triple("super", "Super class", "super"),
            Triple("typeof", "Type of", "typeof"),

            // Imports & packages
            Triple("import", "Import declaration", "import "),
            Triple("package", "Package declaration", "package "),

            // Special
            Triple("by", "Delegation", "by "),
            Triple("where", "Type constraint", "where "),
            Triple("expect", "Expect declaration (KMP)", "expect "),
            Triple("actual", "Actual declaration (KMP)", "actual "),
        )
    }
}

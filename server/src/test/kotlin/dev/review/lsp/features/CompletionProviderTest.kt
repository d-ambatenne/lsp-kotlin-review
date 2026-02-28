package dev.review.lsp.features

import dev.review.lsp.compiler.*
import dev.review.lsp.compiler.SymbolKind
import org.eclipse.lsp4j.*
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CompletionProviderTest {

    private val testUri = "file:///test/File.kt"

    @Test
    fun `returns completion items from facade`() {
        val candidates = listOf(
            CompletionCandidate("println", SymbolKind.FUNCTION, "fun println(message: Any?)", "println", false),
            CompletionCandidate("print", SymbolKind.FUNCTION, "fun print(message: Any?)", "print", false)
        )

        val facade = object : StubCompilerFacade() {
            override fun getCompletions(file: Path, line: Int, column: Int) = candidates
        }

        val provider = CompletionProvider(facade)
        val params = CompletionParams(TextDocumentIdentifier(testUri), Position(5, 6))
        val result = provider.completion(params).get()
        val items = result.left

        // Should contain the facade candidates + keywords
        assertTrue(items.size >= 2, "Expected at least 2 items, got ${items.size}")
        val facadeItems = items.filter { it.kind == CompletionItemKind.Function }
        assertEquals(2, facadeItems.size)
        assertEquals("println", facadeItems[0].label)
        assertEquals("fun println(message: Any?)", facadeItems[0].detail)
    }

    @Test
    fun `marks deprecated items`() {
        val candidates = listOf(
            CompletionCandidate("oldMethod", SymbolKind.FUNCTION, "fun oldMethod()", "oldMethod", true)
        )

        val facade = object : StubCompilerFacade() {
            override fun getCompletions(file: Path, line: Int, column: Int) = candidates
        }

        val provider = CompletionProvider(facade)
        val params = CompletionParams(TextDocumentIdentifier(testUri), Position(5, 6))
        val result = provider.completion(params).get()
        val items = result.left

        val deprecated = items.filter { it.tags?.contains(CompletionItemTag.Deprecated) == true }
        assertEquals(1, deprecated.size)
        assertEquals("oldMethod", deprecated[0].label)
    }

    @Test
    fun `returns keywords when no facade completions`() {
        val facade = StubCompilerFacade()
        val provider = CompletionProvider(facade)
        val params = CompletionParams(TextDocumentIdentifier(testUri), Position(5, 6))
        val result = provider.completion(params).get()
        val items = result.left

        // Should contain keyword completions
        assertTrue(items.isNotEmpty(), "Expected keyword completions")
        val keywords = items.filter { it.kind == CompletionItemKind.Keyword }
        assertTrue(keywords.isNotEmpty(), "Expected keyword items")
        assertTrue(keywords.any { it.label == "val" })
        assertTrue(keywords.any { it.label == "fun" })
        assertTrue(keywords.any { it.label == "class" })
    }

    @Test
    fun `keywords have correct insert text`() {
        val facade = StubCompilerFacade()
        val provider = CompletionProvider(facade)
        val params = CompletionParams(TextDocumentIdentifier(testUri), Position(5, 6))
        val result = provider.completion(params).get()
        val items = result.left

        val valItem = items.first { it.label == "val" }
        assertEquals("val ", valItem.insertText)
        assertEquals("Kotlin · Immutable variable", valItem.detail)

        val ifItem = items.first { it.label == "if" }
        assertEquals("if (", ifItem.insertText)
    }
}

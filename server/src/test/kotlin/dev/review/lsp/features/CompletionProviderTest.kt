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

        assertEquals(2, items.size)
        assertEquals("println", items[0].label)
        assertEquals(CompletionItemKind.Function, items[0].kind)
        assertEquals("fun println(message: Any?)", items[0].detail)
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

        assertEquals(1, items.size)
        assertTrue(items[0].tags.contains(CompletionItemTag.Deprecated))
    }

    @Test
    fun `returns empty when no completions`() {
        val facade = StubCompilerFacade()
        val provider = CompletionProvider(facade)
        val params = CompletionParams(TextDocumentIdentifier(testUri), Position(5, 6))
        val result = provider.completion(params).get()

        assertTrue(result.left.isEmpty())
    }
}

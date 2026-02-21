package dev.review.lsp.features

import dev.review.lsp.compiler.*
import dev.review.lsp.compiler.SymbolKind
import org.eclipse.lsp4j.*
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HoverProviderTest {

    private val testPath = Paths.get("/test/File.kt")
    private val testUri = "file:///test/File.kt"

    private val symbol = ResolvedSymbol(
        name = "greet",
        kind = SymbolKind.FUNCTION,
        location = SourceLocation(testPath, 5, 0),
        containingClass = null,
        signature = "fun greet(name: String): String",
        fqName = "com.example.greet"
    )

    @Test
    fun `returns hover with signature`() {
        val facade = object : StubCompilerFacade() {
            override fun resolveAtPosition(file: Path, line: Int, column: Int) = symbol
            override fun getType(file: Path, line: Int, column: Int): TypeInfo? = null
            override fun getDocumentation(symbol: ResolvedSymbol): String? = null
        }

        val provider = HoverProvider(facade)
        val result = provider.hover(HoverParams(TextDocumentIdentifier(testUri), Position(5, 4))).get()

        assertNotNull(result)
        assertTrue(result.contents.right.value.contains("fun greet(name: String): String"))
        assertEquals(MarkupKind.MARKDOWN, result.contents.right.kind)
    }

    @Test
    fun `returns hover with type when no signature`() {
        val noSigSymbol = symbol.copy(signature = null)
        val facade = object : StubCompilerFacade() {
            override fun resolveAtPosition(file: Path, line: Int, column: Int) = noSigSymbol
            override fun getType(file: Path, line: Int, column: Int) = TypeInfo("kotlin.String", "String", false, emptyList())
            override fun getDocumentation(symbol: ResolvedSymbol): String? = null
        }

        val provider = HoverProvider(facade)
        val result = provider.hover(HoverParams(TextDocumentIdentifier(testUri), Position(5, 4))).get()

        assertNotNull(result)
        assertTrue(result.contents.right.value.contains("String"))
    }

    @Test
    fun `returns hover with documentation`() {
        val facade = object : StubCompilerFacade() {
            override fun resolveAtPosition(file: Path, line: Int, column: Int) = symbol
            override fun getType(file: Path, line: Int, column: Int): TypeInfo? = null
            override fun getDocumentation(symbol: ResolvedSymbol) = "Greets the given person."
        }

        val provider = HoverProvider(facade)
        val result = provider.hover(HoverParams(TextDocumentIdentifier(testUri), Position(5, 4))).get()

        assertNotNull(result)
        assertTrue(result.contents.right.value.contains("Greets the given person."))
    }

    @Test
    fun `returns null when no symbol resolved`() {
        val facade = StubCompilerFacade()
        val provider = HoverProvider(facade)
        val result = provider.hover(HoverParams(TextDocumentIdentifier(testUri), Position(5, 4))).get()

        assertNull(result)
    }
}

package dev.review.lsp.features

import dev.review.lsp.compiler.*
import dev.review.lsp.compiler.SymbolKind
import org.eclipse.lsp4j.*
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReferencesProviderTest {

    private val testPath = Paths.get("/test/File.kt")
    private val testUri = "file:///test/File.kt"
    private val otherPath = Paths.get("/test/Other.kt")

    private val symbol = ResolvedSymbol(
        name = "greet",
        kind = SymbolKind.FUNCTION,
        location = SourceLocation(testPath, 5, 0),
        containingClass = null,
        signature = "fun greet(name: String): String",
        fqName = "com.example.greet"
    )

    @Test
    fun `returns reference locations`() {
        val refs = listOf(
            SourceLocation(testPath, 10, 4),
            SourceLocation(otherPath, 3, 8)
        )
        val facade = object : StubCompilerFacade() {
            override fun resolveAtPosition(file: Path, line: Int, column: Int) = symbol
            override fun findReferences(symbol: ResolvedSymbol) = refs
        }

        val provider = ReferencesProvider(facade)
        val params = ReferenceParams(
            TextDocumentIdentifier(testUri),
            Position(5, 0),
            ReferenceContext(true)
        )

        val result = provider.references(params).get()
        assertEquals(2, result.size)
        assertEquals("file:///test/File.kt", result[0].uri)
        assertEquals(10, result[0].range.start.line)
        assertEquals("file:///test/Other.kt", result[1].uri)
        assertEquals(3, result[1].range.start.line)
    }

    @Test
    fun `returns empty when symbol not resolved`() {
        val facade = StubCompilerFacade()
        val provider = ReferencesProvider(facade)
        val params = ReferenceParams(
            TextDocumentIdentifier(testUri),
            Position(5, 0),
            ReferenceContext(true)
        )

        val result = provider.references(params).get()
        assertTrue(result.isEmpty())
    }
}

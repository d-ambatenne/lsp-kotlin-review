package dev.review.lsp.features

import dev.review.lsp.compiler.*
import dev.review.lsp.compiler.SymbolKind
import org.eclipse.lsp4j.*
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DefinitionProviderTest {

    private val testPath = Paths.get("/test/File.kt")
    private val testUri = "file:///test/File.kt"
    private val targetPath = Paths.get("/test/Target.kt")

    @Test
    fun `returns location when symbol resolves`() {
        val expectedLocation = SourceLocation(targetPath, 10, 4)
        val facade = object : StubCompilerFacade() {
            override fun resolveAtPosition(file: Path, line: Int, column: Int): ResolvedSymbol {
                return ResolvedSymbol(
                    name = "greet",
                    kind = SymbolKind.FUNCTION,
                    location = expectedLocation,
                    containingClass = null,
                    signature = "fun greet(name: String): String",
                    fqName = "com.example.greet"
                )
            }
        }

        val provider = DefinitionProvider(facade)
        val params = DefinitionParams(
            TextDocumentIdentifier(testUri),
            Position(5, 10)
        )

        val result = provider.definition(params).get()
        val locations = result.left
        assertEquals(1, locations.size)
        assertEquals("file:///test/Target.kt", locations[0].uri)
        assertEquals(10, locations[0].range.start.line)
        assertEquals(4, locations[0].range.start.character)
    }

    @Test
    fun `returns empty when symbol does not resolve`() {
        val facade = StubCompilerFacade()
        val provider = DefinitionProvider(facade)
        val params = DefinitionParams(
            TextDocumentIdentifier(testUri),
            Position(5, 10)
        )

        val result = provider.definition(params).get()
        assertTrue(result.left.isEmpty())
    }
}

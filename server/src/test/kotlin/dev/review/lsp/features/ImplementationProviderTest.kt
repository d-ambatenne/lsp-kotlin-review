package dev.review.lsp.features

import dev.review.lsp.compiler.*
import dev.review.lsp.compiler.SymbolKind
import org.eclipse.lsp4j.*
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ImplementationProviderTest {

    private val testPath = Paths.get("/test/File.kt")
    private val testUri = "file:///test/File.kt"
    private val implPath = Paths.get("/test/Impl.kt")

    private val interfaceSymbol = ResolvedSymbol(
        name = "Repository",
        kind = SymbolKind.INTERFACE,
        location = SourceLocation(testPath, 2, 0),
        containingClass = null,
        signature = "interface Repository",
        fqName = "com.example.Repository"
    )

    @Test
    fun `returns implementation locations`() {
        val impls = listOf(
            SourceLocation(implPath, 5, 0),
            SourceLocation(implPath, 20, 0)
        )
        val facade = object : StubCompilerFacade() {
            override fun resolveAtPosition(file: Path, line: Int, column: Int) = interfaceSymbol
            override fun findImplementations(symbol: ResolvedSymbol) = impls
        }

        val provider = ImplementationProvider(facade)
        val params = ImplementationParams(
            TextDocumentIdentifier(testUri),
            Position(2, 10)
        )

        val result = provider.implementation(params).get()
        val locations = result.left
        assertEquals(2, locations.size)
        assertEquals("file:///test/Impl.kt", locations[0].uri)
        assertEquals(5, locations[0].range.start.line)
    }

    @Test
    fun `returns empty when symbol not resolved`() {
        val facade = StubCompilerFacade()
        val provider = ImplementationProvider(facade)
        val params = ImplementationParams(
            TextDocumentIdentifier(testUri),
            Position(2, 10)
        )

        val result = provider.implementation(params).get()
        assertTrue(result.left.isEmpty())
    }

    @Test
    fun `returns empty when no implementations found`() {
        val facade = object : StubCompilerFacade() {
            override fun resolveAtPosition(file: Path, line: Int, column: Int) = interfaceSymbol
        }

        val provider = ImplementationProvider(facade)
        val params = ImplementationParams(
            TextDocumentIdentifier(testUri),
            Position(2, 10)
        )

        val result = provider.implementation(params).get()
        assertTrue(result.left.isEmpty())
    }
}

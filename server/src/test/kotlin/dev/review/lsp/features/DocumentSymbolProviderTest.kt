package dev.review.lsp.features

import dev.review.lsp.compiler.*
import dev.review.lsp.compiler.SymbolKind
import org.eclipse.lsp4j.*
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DocumentSymbolProviderTest {

    private val testPath = Paths.get("/test/File.kt")
    private val testUri = "file:///test/File.kt"

    @Test
    fun `returns document symbols mapped from facade`() {
        val symbols = listOf(
            ResolvedSymbol("MyClass", SymbolKind.CLASS, SourceLocation(testPath, 0, 0), null, "class MyClass", "com.example.MyClass"),
            ResolvedSymbol("doWork", SymbolKind.FUNCTION, SourceLocation(testPath, 5, 4), "MyClass", "fun doWork(): Unit", "com.example.MyClass.doWork"),
            ResolvedSymbol("count", SymbolKind.PROPERTY, SourceLocation(testPath, 3, 4), "MyClass", "val count: Int", "com.example.MyClass.count")
        )

        val facade = object : StubCompilerFacade() {
            override fun getFileSymbols(file: Path) = symbols
        }

        val provider = DocumentSymbolProvider(facade)
        val params = DocumentSymbolParams(TextDocumentIdentifier(testUri))
        val result = provider.documentSymbol(params).get()

        assertEquals(3, result.size)

        assertEquals("MyClass", result[0].name)
        assertEquals(org.eclipse.lsp4j.SymbolKind.Class, result[0].kind)

        assertEquals("doWork", result[1].name)
        assertEquals(org.eclipse.lsp4j.SymbolKind.Function, result[1].kind)

        assertEquals("count", result[2].name)
        assertEquals(org.eclipse.lsp4j.SymbolKind.Property, result[2].kind)
    }

    @Test
    fun `returns empty when no symbols`() {
        val facade = StubCompilerFacade()
        val provider = DocumentSymbolProvider(facade)
        val params = DocumentSymbolParams(TextDocumentIdentifier(testUri))
        val result = provider.documentSymbol(params).get()

        assertTrue(result.isEmpty())
    }

    @Test
    fun `maps all symbol kinds correctly`() {
        val allKinds: List<Pair<SymbolKind, org.eclipse.lsp4j.SymbolKind>> = listOf(
            Pair(SymbolKind.CLASS, org.eclipse.lsp4j.SymbolKind.Class),
            Pair(SymbolKind.INTERFACE, org.eclipse.lsp4j.SymbolKind.Interface),
            Pair(SymbolKind.OBJECT, org.eclipse.lsp4j.SymbolKind.Object),
            Pair(SymbolKind.ENUM, org.eclipse.lsp4j.SymbolKind.Enum),
            Pair(SymbolKind.ENUM_ENTRY, org.eclipse.lsp4j.SymbolKind.EnumMember),
            Pair(SymbolKind.FUNCTION, org.eclipse.lsp4j.SymbolKind.Function),
            Pair(SymbolKind.PROPERTY, org.eclipse.lsp4j.SymbolKind.Property),
            Pair(SymbolKind.CONSTRUCTOR, org.eclipse.lsp4j.SymbolKind.Constructor),
        )

        for ((ourKind, lspKind) in allKinds) {
            val sym = ResolvedSymbol("sym", ourKind, SourceLocation(testPath, 0, 0), null, null, null)
            val facade = object : StubCompilerFacade() {
                override fun getFileSymbols(file: Path) = listOf(sym)
            }

            val provider = DocumentSymbolProvider(facade)
            val result = provider.documentSymbol(DocumentSymbolParams(TextDocumentIdentifier(testUri))).get()
            assertEquals(lspKind, result[0].kind, "SymbolKind.$ourKind should map to LSP $lspKind")
        }
    }
}

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

class RenameProviderTest {

    private val testPath = Paths.get("/test/File.kt")
    private val testUri = "file:///test/File.kt"

    private val symbol = ResolvedSymbol(
        name = "oldName",
        kind = SymbolKind.FUNCTION,
        location = SourceLocation(testPath, 5, 4),
        containingClass = null,
        signature = "fun oldName()",
        fqName = "com.example.oldName"
    )

    private val renameRange = SourceRange(testPath, 5, 4, 5, 11)

    @Test
    fun `prepareRename returns range and placeholder`() {
        val facade = object : StubCompilerFacade() {
            override fun prepareRename(file: Path, line: Int, column: Int) =
                RenameContext(symbol, renameRange)
        }

        val provider = RenameProvider(facade)
        val params = PrepareRenameParams(TextDocumentIdentifier(testUri), Position(5, 6))
        val result = provider.prepareRename(params).get()

        assertNotNull(result)
        val prepareResult = result.second
        assertEquals("oldName", prepareResult.placeholder)
        assertEquals(5, prepareResult.range.start.line)
        assertEquals(4, prepareResult.range.start.character)
    }

    @Test
    fun `prepareRename returns null when not renameable`() {
        val facade = StubCompilerFacade()
        val provider = RenameProvider(facade)
        val params = PrepareRenameParams(TextDocumentIdentifier(testUri), Position(5, 6))
        val result = provider.prepareRename(params).get()

        assertNull(result)
    }

    @Test
    fun `rename returns workspace edit with changes`() {
        val edits = listOf(
            FileEdit(testPath, SourceRange(testPath, 5, 4, 5, 11), "newName"),
            FileEdit(testPath, SourceRange(testPath, 10, 8, 10, 15), "newName")
        )

        val facade = object : StubCompilerFacade() {
            override fun prepareRename(file: Path, line: Int, column: Int) =
                RenameContext(symbol, renameRange)
            override fun computeRename(context: RenameContext, newName: String) = edits
        }

        val provider = RenameProvider(facade)
        val params = RenameParams(TextDocumentIdentifier(testUri), Position(5, 6), "newName")
        val result = provider.rename(params).get()

        assertNotNull(result)
        val changes = result.changes
        assertNotNull(changes)
        assertEquals(1, changes.size) // all edits in same file
        assertEquals(2, changes.values.first().size)
    }

    @Test
    fun `rename returns null when not renameable`() {
        val facade = StubCompilerFacade()
        val provider = RenameProvider(facade)
        val params = RenameParams(TextDocumentIdentifier(testUri), Position(5, 6), "newName")
        val result = provider.rename(params).get()

        assertNull(result)
    }
}

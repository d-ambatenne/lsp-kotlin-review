package dev.review.lsp.analysis

import dev.review.lsp.compiler.*
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FileIndexTest {

    private val filePath = Paths.get("/test/File.kt")
    private val otherPath = Paths.get("/test/Other.kt")

    private val symbols = listOf(
        ResolvedSymbol("MyClass", SymbolKind.CLASS, SourceLocation(filePath, 0, 0), null, "class MyClass", "com.example.MyClass"),
        ResolvedSymbol("doWork", SymbolKind.FUNCTION, SourceLocation(filePath, 5, 4), "MyClass", "fun doWork()", null)
    )

    @Test
    fun `updateFile stores symbols`() {
        val facade = object : StubCompilerFacade() {
            override fun getFileSymbols(file: Path) = symbols
        }

        val index = FileIndex()
        index.updateFile(filePath, facade)

        assertEquals(2, index.getSymbols(filePath).size)
        assertEquals("MyClass", index.getSymbols(filePath)[0].name)
    }

    @Test
    fun `findFilesBySymbolName returns matching files`() {
        val facade = object : StubCompilerFacade() {
            override fun getFileSymbols(file: Path): List<ResolvedSymbol> = when (file) {
                filePath -> symbols
                otherPath -> listOf(
                    ResolvedSymbol("MyClass", SymbolKind.CLASS, SourceLocation(otherPath, 0, 0), null, null, null)
                )
                else -> emptyList()
            }
        }

        val index = FileIndex()
        index.updateFile(filePath, facade)
        index.updateFile(otherPath, facade)

        val files = index.findFilesBySymbolName("MyClass")
        assertEquals(2, files.size)
        assertTrue(files.contains(filePath))
        assertTrue(files.contains(otherPath))
    }

    @Test
    fun `removeFile clears symbols and name index`() {
        val facade = object : StubCompilerFacade() {
            override fun getFileSymbols(file: Path) = symbols
        }

        val index = FileIndex()
        index.updateFile(filePath, facade)
        assertEquals(2, index.getSymbols(filePath).size)

        index.removeFile(filePath)
        assertTrue(index.getSymbols(filePath).isEmpty())
        assertTrue(index.findFilesBySymbolName("MyClass").isEmpty())
    }

    @Test
    fun `allFiles returns tracked files`() {
        val facade = object : StubCompilerFacade() {
            override fun getFileSymbols(file: Path): List<ResolvedSymbol> = emptyList()
        }

        val index = FileIndex()
        index.updateFile(filePath, facade)
        index.updateFile(otherPath, facade)

        val all = index.allFiles()
        assertEquals(2, all.size)
        assertTrue(all.contains(filePath))
        assertTrue(all.contains(otherPath))
    }
}

package dev.review.lsp.util

import dev.review.lsp.compiler.SourceLocation
import dev.review.lsp.compiler.SourceRange
import org.junit.jupiter.api.Test
import java.nio.file.Paths
import kotlin.test.assertEquals

class PositionConverterTest {

    private val testPath = Paths.get("/test/File.kt")

    @Test
    fun `converts SourceLocation to LSP Position`() {
        val location = SourceLocation(testPath, 5, 10)
        val position = PositionConverter.toLspPosition(location)
        assertEquals(5, position.line)
        assertEquals(10, position.character)
    }

    @Test
    fun `converts SourceRange to LSP Range`() {
        val range = SourceRange(testPath, 1, 2, 3, 4)
        val lspRange = PositionConverter.toLspRange(range)
        assertEquals(1, lspRange.start.line)
        assertEquals(2, lspRange.start.character)
        assertEquals(3, lspRange.end.line)
        assertEquals(4, lspRange.end.character)
    }

    @Test
    fun `converts SourceLocation to LSP Location with URI`() {
        val location = SourceLocation(testPath, 5, 10)
        val lspLocation = PositionConverter.toLspLocation(location)
        assertEquals("file:///test/File.kt", lspLocation.uri)
        assertEquals(5, lspLocation.range.start.line)
        assertEquals(10, lspLocation.range.start.character)
    }

    @Test
    fun `decomposes LSP Position to line and column`() {
        val position = org.eclipse.lsp4j.Position(7, 3)
        val (line, col) = PositionConverter.fromLspPosition(position)
        assertEquals(7, line)
        assertEquals(3, col)
    }
}

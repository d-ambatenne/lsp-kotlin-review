package dev.review.lsp.features

import dev.review.lsp.compiler.*
import org.eclipse.lsp4j.*
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CodeActionProviderTest {

    private val testPath = Paths.get("/test/File.kt")
    private val testUri = "file:///test/File.kt"

    @Test
    fun `returns quick fixes for diagnostics in range`() {
        val diag = DiagnosticInfo(
            severity = Severity.ERROR,
            message = "Type mismatch",
            range = SourceRange(testPath, 5, 0, 5, 20),
            code = "TYPE_MISMATCH",
            quickFixes = listOf(
                QuickFix(
                    title = "Change type to Int",
                    edits = listOf(
                        FileEdit(testPath, SourceRange(testPath, 5, 4, 5, 10), "Int")
                    )
                )
            )
        )

        val facade = object : StubCompilerFacade() {
            override fun getDiagnostics(file: Path) = listOf(diag)
        }

        val provider = CodeActionProvider(facade)
        val params = CodeActionParams(
            TextDocumentIdentifier(testUri),
            Range(Position(5, 0), Position(5, 20)),
            CodeActionContext(emptyList())
        )

        val result = provider.codeAction(params).get()
        assertEquals(1, result.size)

        val action = result[0].right
        assertEquals("Change type to Int", action.title)
        assertEquals(CodeActionKind.QuickFix, action.kind)
    }

    @Test
    fun `returns empty when diagnostics have no quick fixes`() {
        val diag = DiagnosticInfo(
            severity = Severity.ERROR,
            message = "Unresolved reference",
            range = SourceRange(testPath, 5, 0, 5, 10),
            code = "UNRESOLVED_REFERENCE",
            quickFixes = emptyList()
        )

        val facade = object : StubCompilerFacade() {
            override fun getDiagnostics(file: Path) = listOf(diag)
        }

        val provider = CodeActionProvider(facade)
        val params = CodeActionParams(
            TextDocumentIdentifier(testUri),
            Range(Position(5, 0), Position(5, 10)),
            CodeActionContext(emptyList())
        )

        val result = provider.codeAction(params).get()
        assertTrue(result.isEmpty())
    }

    @Test
    fun `ignores diagnostics outside requested range`() {
        val diag = DiagnosticInfo(
            severity = Severity.ERROR,
            message = "Error on line 10",
            range = SourceRange(testPath, 10, 0, 10, 10),
            code = "SOME_ERROR",
            quickFixes = listOf(
                QuickFix("Fix it", listOf(FileEdit(testPath, SourceRange(testPath, 10, 0, 10, 10), "fixed")))
            )
        )

        val facade = object : StubCompilerFacade() {
            override fun getDiagnostics(file: Path) = listOf(diag)
        }

        val provider = CodeActionProvider(facade)
        val params = CodeActionParams(
            TextDocumentIdentifier(testUri),
            Range(Position(0, 0), Position(0, 10)),
            CodeActionContext(emptyList())
        )

        val result = provider.codeAction(params).get()
        assertTrue(result.isEmpty())
    }
}

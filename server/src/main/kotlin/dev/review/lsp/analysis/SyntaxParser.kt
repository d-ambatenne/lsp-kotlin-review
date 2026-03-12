package dev.review.lsp.analysis

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.util.PsiTreeUtil
import dev.review.lsp.compiler.DiagnosticInfo
import dev.review.lsp.compiler.Severity
import dev.review.lsp.compiler.SourceRange
import org.jetbrains.kotlin.psi.KtPsiFactory
import java.nio.file.Path

/**
 * Lightweight syntax error parser using KtPsiFactory.
 * Creates a detached PSI tree from raw text (no FIR, no Analysis API session).
 * Thread-safe — can run off the analysis thread.
 */
class SyntaxParser {

    @Volatile
    private var project: Project? = null

    fun setProject(project: Project) {
        this.project = project
    }

    fun isReady(): Boolean = project != null

    /**
     * Parse raw Kotlin source text and return syntax error diagnostics.
     * Returns empty list if the parser is not yet initialized.
     */
    fun parseSyntaxErrors(content: String, filePath: Path): List<DiagnosticInfo> {
        val proj = project ?: return emptyList()
        return try {
            val factory = KtPsiFactory(proj)
            val ktFile = factory.createFile("syntax-check.kt", content)
            val errors = PsiTreeUtil.collectElementsOfType(ktFile, PsiErrorElement::class.java)
            errors.mapNotNull { error ->
                val offset = error.textOffset
                val (line, col) = offsetToLineCol(content, offset)
                val endOffset = offset + (error.textLength.coerceAtLeast(1))
                val (endLine, endCol) = offsetToLineCol(content, endOffset)
                DiagnosticInfo(
                    severity = Severity.ERROR,
                    message = error.errorDescription,
                    range = SourceRange(filePath, line, col, endLine, endCol),
                    code = "SYNTAX_ERROR",
                    quickFixes = emptyList()
                )
            }
        } catch (e: Exception) {
            System.err.println("[syntax] Parse failed for $filePath: ${e.message}")
            emptyList()
        }
    }

    private fun offsetToLineCol(content: String, offset: Int): Pair<Int, Int> {
        val safeOffset = offset.coerceIn(0, content.length)
        var line = 0
        var col = 0
        for (i in 0 until safeOffset) {
            if (content[i] == '\n') {
                line++
                col = 0
            } else {
                col++
            }
        }
        return Pair(line, col)
    }
}

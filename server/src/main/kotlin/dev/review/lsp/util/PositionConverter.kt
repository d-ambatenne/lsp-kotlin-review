package dev.review.lsp.util

import dev.review.lsp.compiler.SourceLocation
import dev.review.lsp.compiler.SourceRange
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.Location
import java.nio.file.Path

object PositionConverter {

    fun toLspPosition(location: SourceLocation): Position =
        Position(location.line, location.column)

    fun toLspRange(range: SourceRange): Range =
        Range(
            Position(range.startLine, range.startColumn),
            Position(range.endLine, range.endColumn)
        )

    fun toLspLocation(location: SourceLocation): Location =
        Location(
            UriUtil.toUri(location.path),
            Range(Position(location.line, location.column), Position(location.line, location.column))
        )

    fun toLspLocation(range: SourceRange): Location =
        Location(UriUtil.toUri(range.path), toLspRange(range))

    fun fromLspPosition(position: Position): Pair<Int, Int> =
        position.line to position.character
}

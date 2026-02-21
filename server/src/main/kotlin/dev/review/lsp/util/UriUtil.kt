package dev.review.lsp.util

import java.net.URI
import java.nio.file.Path
import java.nio.file.Paths

object UriUtil {

    fun toPath(uri: String): Path =
        Paths.get(URI.create(uri))

    fun toUri(path: Path): String =
        path.toUri().toString()
}

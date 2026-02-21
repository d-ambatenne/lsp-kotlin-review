package dev.review.lsp.util

import org.junit.jupiter.api.Test
import java.nio.file.Paths
import kotlin.test.assertEquals

class UriUtilTest {

    @Test
    fun `toPath converts file URI to path`() {
        val path = UriUtil.toPath("file:///test/File.kt")
        assertEquals(Paths.get("/test/File.kt"), path)
    }

    @Test
    fun `toUri converts path to file URI`() {
        val path = Paths.get("/test/File.kt")
        val uri = UriUtil.toUri(path)
        assertEquals("file:///test/File.kt", uri)
    }

    @Test
    fun `round trip preserves path`() {
        val original = Paths.get("/test/some/path/File.kt")
        val uri = UriUtil.toUri(original)
        val roundTripped = UriUtil.toPath(uri)
        assertEquals(original, roundTripped)
    }
}

package dev.review.lsp.buildsystem

import dev.review.lsp.buildsystem.manual.ManualProvider
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ManualProviderTest {

    @TempDir
    lateinit var tempDir: Path

    private val provider = ManualProvider()

    @Test
    fun `detects existing source directories`() = runTest {
        Files.createDirectories(tempDir.resolve("src/main/kotlin"))
        Files.createDirectories(tempDir.resolve("src/test/kotlin"))

        val model = provider.resolve(tempDir)
        assertEquals(1, model.modules.size)

        val module = model.modules.first()
        assertTrue(module.sourceRoots.any { it.endsWith("src/main/kotlin") })
        assertTrue(module.testSourceRoots.any { it.endsWith("src/test/kotlin") })
    }

    @Test
    fun `returns empty source roots when no dirs exist`() = runTest {
        val model = provider.resolve(tempDir)
        val module = model.modules.first()
        assertTrue(module.sourceRoots.isEmpty())
        assertTrue(module.testSourceRoots.isEmpty())
    }

    @Test
    fun `returns empty classpath`() = runTest {
        val model = provider.resolve(tempDir)
        val module = model.modules.first()
        assertTrue(module.classpath.isEmpty())
    }

    @Test
    fun `uses directory name as module name`() = runTest {
        val model = provider.resolve(tempDir)
        assertEquals(tempDir.fileName.toString(), model.modules.first().name)
    }
}

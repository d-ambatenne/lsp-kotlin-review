package dev.review.lsp.analysis

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WorkspaceManagerTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `routes file to deepest containing build root`() {
        // Create two nested build roots
        val outerRoot = Files.createDirectory(tempDir.resolve("outer"))
        Files.createFile(outerRoot.resolve("settings.gradle.kts"))
        Files.createFile(outerRoot.resolve("build.gradle.kts"))

        val innerRoot = Files.createDirectories(tempDir.resolve("inner").resolve("nested"))
        Files.createFile(innerRoot.resolve("settings.gradle.kts"))
        Files.createFile(innerRoot.resolve("build.gradle.kts"))

        val wm = WorkspaceManager(tempDir, "debug")
        wm.discover()

        // File under outer root
        val outerFile = outerRoot.resolve("src/main/kotlin/Foo.kt")
        val outerResult = wm.buildRootForFile(outerFile)
        assertNotNull(outerResult)
        assertEquals(outerRoot.normalize(), outerResult)

        // File under inner root
        val innerFile = innerRoot.resolve("src/main/kotlin/Bar.kt")
        val innerResult = wm.buildRootForFile(innerFile)
        assertNotNull(innerResult)
        assertEquals(innerRoot.normalize(), innerResult)
    }

    @Test
    fun `returns null for file outside any root`() {
        val buildRoot = Files.createDirectory(tempDir.resolve("project"))
        Files.createFile(buildRoot.resolve("settings.gradle.kts"))
        Files.createFile(buildRoot.resolve("build.gradle.kts"))

        val wm = WorkspaceManager(tempDir, "debug")
        wm.discover()

        // File completely outside any build root
        val outsideFile = Path.of("/some/other/place/Baz.kt")
        val result = wm.buildRootForFile(outsideFile)
        assertNull(result)
    }

    @Test
    fun `isSingleRoot returns true for one root`() {
        Files.createFile(tempDir.resolve("settings.gradle.kts"))
        Files.createFile(tempDir.resolve("build.gradle.kts"))

        val wm = WorkspaceManager(tempDir, "debug")
        wm.discover()

        assertTrue(wm.isSingleRoot())
        assertEquals(1, wm.allRoots().size)
    }

    @Test
    fun `isSingleRoot returns false for multiple roots`() {
        val rootA = Files.createDirectory(tempDir.resolve("a"))
        Files.createFile(rootA.resolve("settings.gradle.kts"))
        Files.createFile(rootA.resolve("build.gradle.kts"))

        val rootB = Files.createDirectory(tempDir.resolve("b"))
        Files.createFile(rootB.resolve("settings.gradle.kts"))
        Files.createFile(rootB.resolve("build.gradle.kts"))

        val wm = WorkspaceManager(tempDir, "debug")
        wm.discover()

        assertTrue(!wm.isSingleRoot())
        assertEquals(2, wm.allRoots().size)
    }

    @Test
    fun `file-to-root cache returns consistent results`() {
        val buildRoot = Files.createDirectory(tempDir.resolve("project"))
        Files.createFile(buildRoot.resolve("settings.gradle.kts"))
        Files.createFile(buildRoot.resolve("build.gradle.kts"))

        val wm = WorkspaceManager(tempDir, "debug")
        wm.discover()

        val file = buildRoot.resolve("src/main/kotlin/Test.kt")

        // First call populates cache
        val first = wm.buildRootForFile(file)
        // Second call should return same result from cache
        val second = wm.buildRootForFile(file)
        assertEquals(first, second)
        assertNotNull(first)
    }

    @Test
    fun `dispose clears all state`() {
        Files.createFile(tempDir.resolve("settings.gradle.kts"))
        Files.createFile(tempDir.resolve("build.gradle.kts"))

        val wm = WorkspaceManager(tempDir, "debug")
        wm.discover()
        assertEquals(1, wm.allRoots().size)

        wm.dispose()
        assertEquals(0, wm.allRoots().size)
    }
}

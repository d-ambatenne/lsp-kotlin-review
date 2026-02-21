package dev.review.lsp.buildsystem

import dev.review.lsp.buildsystem.manual.ManualProvider
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals

class BuildSystemResolverTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `falls back to manual when no markers found`() {
        val resolver = BuildSystemResolver()
        val provider = resolver.detect(tempDir)
        assertEquals("manual", provider.id)
    }

    @Test
    fun `detects provider by marker file`() {
        val fakeProvider = object : BuildSystemProvider {
            override val id = "fake"
            override val markerFiles = listOf("build.fake")
            override val priority = 10
            override suspend fun resolve(workspaceRoot: Path) = ProjectModel(emptyList())
            override suspend fun resolveModule(workspaceRoot: Path, moduleName: String) =
                ModuleInfo(moduleName, emptyList(), emptyList(), emptyList(), emptyList(), null, null)
        }

        Files.createFile(tempDir.resolve("build.fake"))

        val resolver = BuildSystemResolver(listOf(fakeProvider))
        val provider = resolver.detect(tempDir)
        assertEquals("fake", provider.id)
    }

    @Test
    fun `picks highest priority when multiple match`() {
        val low = object : BuildSystemProvider {
            override val id = "low"
            override val markerFiles = listOf("marker.txt")
            override val priority = 1
            override suspend fun resolve(workspaceRoot: Path) = ProjectModel(emptyList())
            override suspend fun resolveModule(workspaceRoot: Path, moduleName: String) =
                ModuleInfo(moduleName, emptyList(), emptyList(), emptyList(), emptyList(), null, null)
        }
        val high = object : BuildSystemProvider {
            override val id = "high"
            override val markerFiles = listOf("marker.txt")
            override val priority = 10
            override suspend fun resolve(workspaceRoot: Path) = ProjectModel(emptyList())
            override suspend fun resolveModule(workspaceRoot: Path, moduleName: String) =
                ModuleInfo(moduleName, emptyList(), emptyList(), emptyList(), emptyList(), null, null)
        }

        Files.createFile(tempDir.resolve("marker.txt"))

        val resolver = BuildSystemResolver(listOf(low, high))
        val provider = resolver.detect(tempDir)
        assertEquals("high", provider.id)
    }

    @Test
    fun `resolve returns provider and model`() = runTest {
        val resolver = BuildSystemResolver()
        val (provider, model) = resolver.resolve(tempDir)
        assertEquals("manual", provider.id)
        assertEquals(1, model.modules.size)
    }
}

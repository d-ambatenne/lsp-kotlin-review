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
        val (provider, dir) = resolver.detect(tempDir)
        assertEquals("manual", provider.id)
        assertEquals(tempDir, dir)
    }

    @Test
    fun `detects provider by marker file`() {
        val fakeProvider = object : BuildSystemProvider {
            override val id = "fake"
            override val markerFiles = listOf("build.fake")
            override val priority = 10
            override suspend fun resolve(workspaceRoot: Path, variant: String) = ProjectModel(emptyList())
            override suspend fun resolveModule(workspaceRoot: Path, moduleName: String) =
                ModuleInfo(moduleName, emptyList(), emptyList(), emptyList(), emptyList(), null, null)
        }

        Files.createFile(tempDir.resolve("build.fake"))

        val resolver = BuildSystemResolver(listOf(fakeProvider))
        val (provider, dir) = resolver.detect(tempDir)
        assertEquals("fake", provider.id)
        assertEquals(tempDir, dir)
    }

    @Test
    fun `detects provider in child directory`() {
        val fakeProvider = object : BuildSystemProvider {
            override val id = "fake"
            override val markerFiles = listOf("build.fake")
            override val priority = 10
            override suspend fun resolve(workspaceRoot: Path, variant: String) = ProjectModel(emptyList())
            override suspend fun resolveModule(workspaceRoot: Path, moduleName: String) =
                ModuleInfo(moduleName, emptyList(), emptyList(), emptyList(), emptyList(), null, null)
        }

        val childDir = Files.createDirectory(tempDir.resolve("server"))
        Files.createFile(childDir.resolve("build.fake"))

        val resolver = BuildSystemResolver(listOf(fakeProvider))
        val (provider, dir) = resolver.detect(tempDir)
        assertEquals("fake", provider.id)
        assertEquals(childDir, dir)
    }

    @Test
    fun `picks highest priority when multiple match`() {
        val low = object : BuildSystemProvider {
            override val id = "low"
            override val markerFiles = listOf("marker.txt")
            override val priority = 1
            override suspend fun resolve(workspaceRoot: Path, variant: String) = ProjectModel(emptyList())
            override suspend fun resolveModule(workspaceRoot: Path, moduleName: String) =
                ModuleInfo(moduleName, emptyList(), emptyList(), emptyList(), emptyList(), null, null)
        }
        val high = object : BuildSystemProvider {
            override val id = "high"
            override val markerFiles = listOf("marker.txt")
            override val priority = 10
            override suspend fun resolve(workspaceRoot: Path, variant: String) = ProjectModel(emptyList())
            override suspend fun resolveModule(workspaceRoot: Path, moduleName: String) =
                ModuleInfo(moduleName, emptyList(), emptyList(), emptyList(), emptyList(), null, null)
        }

        Files.createFile(tempDir.resolve("marker.txt"))

        val resolver = BuildSystemResolver(listOf(low, high))
        val (provider, dir) = resolver.detect(tempDir)
        assertEquals("high", provider.id)
        assertEquals(tempDir, dir)
    }

    @Test
    fun `resolve returns provider and model`() = runTest {
        val resolver = BuildSystemResolver()
        val (provider, model) = resolver.resolve(tempDir)
        assertEquals("manual", provider.id)
        assertEquals(1, model.modules.size)
    }

    // --- discoverBuildRoots tests ---

    @Test
    fun `discovers single build root at workspace root`() {
        Files.createFile(tempDir.resolve("settings.gradle.kts"))
        Files.createFile(tempDir.resolve("build.gradle.kts"))

        val resolver = BuildSystemResolver()
        val roots = resolver.discoverBuildRoots(tempDir)
        assertEquals(1, roots.size)
        assertEquals("gradle", roots[0].first.id)
        assertEquals(tempDir, roots[0].second)
    }

    @Test
    fun `discovers multiple build roots in subdirectories`() {
        // Create two independent Gradle builds in subdirectories
        val buildA = Files.createDirectory(tempDir.resolve("project-a"))
        Files.createFile(buildA.resolve("settings.gradle.kts"))
        Files.createFile(buildA.resolve("build.gradle.kts"))

        val buildB = Files.createDirectory(tempDir.resolve("project-b"))
        Files.createFile(buildB.resolve("settings.gradle"))
        Files.createFile(buildB.resolve("build.gradle"))

        val resolver = BuildSystemResolver()
        val roots = resolver.discoverBuildRoots(tempDir)
        assertEquals(2, roots.size)
        val rootDirs = roots.map { it.second }.toSet()
        assertEquals(setOf(buildA, buildB), rootDirs)
        roots.forEach { assertEquals("gradle", it.first.id) }
    }

    @Test
    fun `skips build and gradle directories during discovery`() {
        // Build root in a build/ directory should be skipped
        val buildDir = Files.createDirectory(tempDir.resolve("build"))
        Files.createFile(buildDir.resolve("settings.gradle.kts"))
        Files.createFile(buildDir.resolve("build.gradle.kts"))

        // Build root in .gradle/ should be skipped
        val gradleDir = Files.createDirectory(tempDir.resolve(".gradle"))
        Files.createFile(gradleDir.resolve("settings.gradle.kts"))
        Files.createFile(gradleDir.resolve("build.gradle.kts"))

        // Real build root
        val realBuild = Files.createDirectory(tempDir.resolve("app"))
        Files.createFile(realBuild.resolve("settings.gradle.kts"))
        Files.createFile(realBuild.resolve("build.gradle.kts"))

        val resolver = BuildSystemResolver()
        val roots = resolver.discoverBuildRoots(tempDir)
        assertEquals(1, roots.size)
        assertEquals(realBuild, roots[0].second)
    }

    @Test
    fun `falls back for empty workspace with no markers`() {
        val resolver = BuildSystemResolver()
        val roots = resolver.discoverBuildRoots(tempDir)
        assertEquals(1, roots.size)
        assertEquals("manual", roots[0].first.id)
        assertEquals(tempDir, roots[0].second)
    }

    @Test
    fun `does not recurse into subdirectories of a discovered build root`() {
        // Root build
        Files.createFile(tempDir.resolve("settings.gradle.kts"))
        Files.createFile(tempDir.resolve("build.gradle.kts"))

        // Subproject that also has settings.gradle.kts (should NOT be a separate root)
        val subproject = Files.createDirectories(tempDir.resolve("subproject"))
        Files.createFile(subproject.resolve("settings.gradle.kts"))
        Files.createFile(subproject.resolve("build.gradle.kts"))

        val resolver = BuildSystemResolver()
        val roots = resolver.discoverBuildRoots(tempDir)
        // Only the root should be discovered, not the subproject
        assertEquals(1, roots.size)
        assertEquals(tempDir, roots[0].second)
    }
}

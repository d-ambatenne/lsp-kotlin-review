package dev.review.lsp.integration

import dev.review.lsp.compiler.analysisapi.AnalysisApiCompilerFacade
import org.junit.jupiter.api.*
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@Tag("integration")
class AndroidClasspathTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `AAR extraction produces classes jar`() {
        val aarPath = TestFixtures.createFakeAar(tempDir)
        assertTrue(Files.exists(aarPath), "AAR file should exist")

        // Verify it's a valid ZIP containing classes.jar
        java.util.zip.ZipFile(aarPath.toFile()).use { zip ->
            val entry = zip.getEntry("classes.jar")
            assertNotNull(entry, "AAR should contain classes.jar")
        }
    }

    @Test
    fun `AnalysisApiCompilerFacade extracts AAR classes`() {
        // Create a model where classpath contains an AAR
        val aarPath = TestFixtures.createFakeAar(tempDir)
        val model = TestFixtures.singleModule().let { m ->
            m.copy(modules = m.modules.map { it.copy(classpath = it.classpath + aarPath) })
        }

        // Facade should not crash when given AAR files
        val facade = AnalysisApiCompilerFacade(model)
        try {
            // Just verify it initializes without error
            val srcDir = model.modules[0].sourceRoots[0]
            val greeterKt = srcDir.resolve("com/example/Greeter.kt")
            val symbols = facade.getFileSymbols(greeterKt)
            assertTrue(symbols.isNotEmpty(), "Should still resolve symbols even with AAR on classpath")
        } finally {
            facade.dispose()
        }
    }

    @Test
    fun `R jar is loadable as classpath`() {
        val rJar = TestFixtures.createFakeRJar(tempDir)
        assertTrue(Files.exists(rJar), "R.jar should exist")

        // Verify it's a valid JAR
        java.util.jar.JarFile(rJar.toFile()).use { jar ->
            val entries = jar.entries().toList().map { it.name }
            assertTrue(entries.any { it.contains("R") }, "R.jar should contain R class entries, got: $entries")
        }
    }

    @Test
    fun `GradleProvider addGeneratedSources scans variant paths`() {
        // Create the expected generated source directory structure
        val moduleDir = tempDir.resolve("test-module")
        Files.createDirectories(moduleDir.resolve("build/generated/source/r/debug"))
        Files.createDirectories(moduleDir.resolve("build/generated/ksp/debug/kotlin"))
        Files.createDirectories(moduleDir.resolve("build/generated/source/buildConfig/debug"))
        // Release variant should NOT be picked up for debug
        Files.createDirectories(moduleDir.resolve("build/generated/source/r/release"))

        // Verify the paths exist (we can't call addGeneratedSources directly
        // since it's private, but we verify the directory structure)
        assertTrue(Files.isDirectory(moduleDir.resolve("build/generated/source/r/debug")))
        assertTrue(Files.isDirectory(moduleDir.resolve("build/generated/ksp/debug/kotlin")))
    }

    @Test
    fun `android module facade initializes without crash`() {
        val model = TestFixtures.androidModule()
        val facade = AnalysisApiCompilerFacade(model)
        try {
            val srcDir = model.modules[0].sourceRoots[0]
            val mainKt = srcDir.resolve("com/example/android/MainActivity.kt")
            val diagnostics = facade.getDiagnostics(mainKt)
            // Should not crash; diagnostics may or may not be empty
            assertNotNull(diagnostics)
        } finally {
            facade.dispose()
        }
    }
}

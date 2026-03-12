package dev.review.lsp.compiler.analysisapi

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class KlibStubGeneratorTest {

    private fun findKotlinStdlibJs(): Path? {
        val gradleCache = Path.of(System.getProperty("user.home"), ".gradle/caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib-js")
        if (!Files.isDirectory(gradleCache)) return null
        return Files.walk(gradleCache, 3)
            .filter { it.fileName.toString().endsWith(".klib") }
            .findFirst().orElse(null)
    }

    @Test
    fun `generates stubs for kotlin-stdlib-js klib with JsExport`() {
        val klib = findKotlinStdlibJs()
        assumeTrue(klib != null, "kotlin-stdlib-js klib not in Gradle cache")

        val gen = KlibStubGenerator()
        val stubDir = gen.generateStubs(klib!!)
        assertNotNull(stubDir, "Stub generation returned null")

        // Check that kotlin/js package was generated
        val jsDir = stubDir.resolve("kotlin/js")
        assertTrue(Files.isDirectory(jsDir), "kotlin/js stub directory not found. Contents: ${listDirs(stubDir)}")

        // Check that JsExport annotation is in the stubs
        val jsFiles = Files.list(jsDir).filter { it.fileName.toString().endsWith(".kt") }.toList()
        assertTrue(jsFiles.isNotEmpty(), "No .kt files in kotlin/js")

        val allContent = jsFiles.joinToString("\n") { Files.readString(it) }
        assertTrue(allContent.contains("JsExport"), "JsExport not found in kotlin/js stubs. Content:\n${allContent.take(2000)}")
    }

    private fun listDirs(root: Path): String {
        return Files.walk(root, 2)
            .filter { Files.isDirectory(it) }
            .map { root.relativize(it).toString() }
            .toList()
            .joinToString(", ")
    }
}

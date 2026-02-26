package dev.review.lsp.integration

import dev.review.lsp.buildsystem.KmpPlatform
import dev.review.lsp.buildsystem.KmpTarget
import dev.review.lsp.buildsystem.ModuleInfo
import dev.review.lsp.buildsystem.ProjectModel
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Test fixture helpers for integration tests.
 * Builds ProjectModel instances from test resource directories
 * without requiring Gradle Tooling API.
 */
object TestFixtures {

    private fun resourcePath(name: String): Path {
        val url = TestFixtures::class.java.classLoader.getResource(name)
            ?: throw IllegalStateException("Test resource not found: $name")
        return Path.of(url.toURI())
    }

    fun singleModule(): ProjectModel {
        val root = resourcePath("single-module")
        return ProjectModel(
            modules = listOf(
                ModuleInfo(
                    name = "single-module",
                    sourceRoots = listOf(root.resolve("src/main/kotlin")),
                    testSourceRoots = listOf(root.resolve("src/test/kotlin")),
                    classpath = emptyList(),
                    testClasspath = emptyList(),
                    kotlinVersion = "2.1.0",
                    jvmTarget = "17"
                )
            ),
            projectDir = root
        )
    }

    fun multiModule(): ProjectModel {
        val root = resourcePath("multi-module")
        return ProjectModel(
            modules = listOf(
                ModuleInfo(
                    name = "core",
                    sourceRoots = listOf(root.resolve("core/src/main/kotlin")),
                    testSourceRoots = emptyList(),
                    classpath = emptyList(),
                    testClasspath = emptyList(),
                    kotlinVersion = "2.1.0",
                    jvmTarget = "17"
                ),
                ModuleInfo(
                    name = "app",
                    sourceRoots = listOf(root.resolve("app/src/main/kotlin")),
                    testSourceRoots = emptyList(),
                    classpath = emptyList(),
                    testClasspath = emptyList(),
                    kotlinVersion = "2.1.0",
                    jvmTarget = "17"
                )
            ),
            projectDir = root
        )
    }

    fun noBuildSystem(): ProjectModel {
        val root = resourcePath("no-build-system")
        return ProjectModel(
            modules = listOf(
                ModuleInfo(
                    name = "no-build-system",
                    sourceRoots = listOf(root),
                    testSourceRoots = emptyList(),
                    classpath = emptyList(),
                    testClasspath = emptyList(),
                    kotlinVersion = null,
                    jvmTarget = null
                )
            ),
            projectDir = root
        )
    }

    fun androidModule(): ProjectModel {
        val root = resourcePath("android-module")
        return ProjectModel(
            modules = listOf(
                ModuleInfo(
                    name = "android-module",
                    sourceRoots = listOf(root.resolve("src/main/kotlin")),
                    testSourceRoots = emptyList(),
                    classpath = emptyList(),
                    testClasspath = emptyList(),
                    kotlinVersion = "2.1.0",
                    jvmTarget = "17",
                    isAndroid = true
                )
            ),
            projectDir = root,
            variant = "debug"
        )
    }

    /**
     * Create a fake .aar file (ZIP containing classes.jar) in a temp directory.
     * Returns the path to the .aar file.
     */
    fun createFakeAar(tempDir: Path, name: String = "fake-library-1.0.0.aar"): Path {
        val aarPath = tempDir.resolve(name)
        // First create a minimal classes.jar in memory
        val classesJarBytes = createMinimalJar()
        // Then wrap it in a ZIP as classes.jar entry
        ZipOutputStream(Files.newOutputStream(aarPath)).use { zos ->
            zos.putNextEntry(ZipEntry("classes.jar"))
            zos.write(classesJarBytes)
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("AndroidManifest.xml"))
            zos.write("<manifest/>".toByteArray())
            zos.closeEntry()
        }
        return aarPath
    }

    /**
     * Create a fake R.jar file in a temp directory.
     * Returns the path to the JAR.
     */
    fun createFakeRJar(tempDir: Path): Path {
        val jarPath = tempDir.resolve("R.jar")
        JarOutputStream(Files.newOutputStream(jarPath)).use { jos ->
            // Add a dummy class entry (empty, just for structure validation)
            jos.putNextEntry(JarEntry("com/example/android/R.class"))
            jos.closeEntry()
            jos.putNextEntry(JarEntry("com/example/android/R\$string.class"))
            jos.closeEntry()
        }
        return jarPath
    }

    fun kmpModule(): ProjectModel {
        val root = resourcePath("kmp-module")
        val commonMainSrc = root.resolve("src/commonMain/kotlin")
        val commonTestSrc = root.resolve("src/commonTest/kotlin")
        val jvmMainSrc = root.resolve("src/jvmMain/kotlin")
        val jsMainSrc = root.resolve("src/jsMain/kotlin")
        return ProjectModel(
            modules = listOf(
                ModuleInfo(
                    name = "kmp-module",
                    sourceRoots = listOf(commonMainSrc),
                    testSourceRoots = listOf(commonTestSrc),
                    classpath = emptyList(),
                    testClasspath = emptyList(),
                    kotlinVersion = "2.1.0",
                    jvmTarget = "17",
                    targets = listOf(
                        KmpTarget(
                            name = "jvm",
                            platform = KmpPlatform.JVM,
                            sourceRoots = listOf(jvmMainSrc),
                            testSourceRoots = emptyList(),
                            classpath = emptyList(),
                            testClasspath = emptyList()
                        ),
                        KmpTarget(
                            name = "js",
                            platform = KmpPlatform.JS,
                            sourceRoots = listOf(jsMainSrc),
                            testSourceRoots = emptyList(),
                            classpath = emptyList(),
                            testClasspath = emptyList()
                        )
                    )
                )
            ),
            projectDir = root,
            isMultiplatform = true
        )
    }

    private fun createMinimalJar(): ByteArray {
        val baos = java.io.ByteArrayOutputStream()
        JarOutputStream(baos).use { jos ->
            jos.putNextEntry(JarEntry("META-INF/MANIFEST.MF"))
            jos.write("Manifest-Version: 1.0\n".toByteArray())
            jos.closeEntry()
        }
        return baos.toByteArray()
    }
}

package dev.review.lsp.buildsystem.gradle

import dev.review.lsp.buildsystem.BuildSystemProvider
import dev.review.lsp.buildsystem.ModuleInfo
import dev.review.lsp.buildsystem.ProjectModel
import org.gradle.tooling.GradleConnector
import org.gradle.tooling.model.idea.IdeaProject
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path

class GradleProvider : BuildSystemProvider {
    override val id = "gradle"
    override val markerFiles = listOf("build.gradle.kts", "build.gradle", "settings.gradle.kts", "settings.gradle")
    override val priority = 10

    override suspend fun resolve(workspaceRoot: Path): ProjectModel {
        val connector = GradleConnector.newConnector()
            .forProjectDirectory(workspaceRoot.toFile())

        val connection = try {
            connector.connect()
        } catch (e: Exception) {
            throw IllegalStateException("Failed to connect to Gradle at $workspaceRoot: ${e.message}", e)
        }

        return try {
            // Use model() builder to redirect stdout/stderr away from the LSP
            // protocol channel (process stdout). Without this, Gradle progress
            // output (e.g. downloading distributions) corrupts LSP headers.
            val ideaProject = connection.model(IdeaProject::class.java)
                .setStandardOutput(OutputStream.nullOutputStream())
                .setStandardError(OutputStream.nullOutputStream())
                .get()
            var modules = ideaProject.modules.mapNotNull { ideaModule ->
                try {
                    resolveModule(ideaModule)
                } catch (e: Exception) {
                    System.err.println("Skipping module '${ideaModule.name}': ${e.message}")
                    null
                }
            }

            // IdeaProject/EclipseProject don't report dependencies for Android (AGP) modules.
            // Use a Gradle init script to resolve debugCompileClasspath directly.
            val anyEmptyClasspath = modules.any { it.classpath.isEmpty() }
            if (anyEmptyClasspath) {
                try {
                    val resolvedCp = resolveClasspathViaInitScript(connection, workspaceRoot)
                    modules = modules.map { module ->
                        if (module.classpath.isEmpty()) {
                            val cp = resolvedCp[module.name]
                            if (cp != null && cp.isNotEmpty()) {
                                module.copy(classpath = cp)
                            } else module
                        } else module
                    }
                } catch (e: Exception) {
                    System.err.println("Init script classpath resolution failed: ${e.message}")
                }
            }

            ProjectModel(modules = modules)
        } finally {
            connection.close()
        }
    }

    private fun resolveModule(ideaModule: org.gradle.tooling.model.idea.IdeaModule): ModuleInfo {
        val sourceRoots = mutableListOf<Path>()
        val testSourceRoots = mutableListOf<Path>()
        val classpath = mutableListOf<Path>()
        val testClasspath = mutableListOf<Path>()

        // Module root directory (first content root, or derive from Gradle project dir)
        var moduleDir = ideaModule.contentRoots.firstOrNull()?.rootDirectory?.toPath()
        if (moduleDir == null) {
            // Try GradleProject.projectDirectory as fallback
            moduleDir = try { ideaModule.gradleProject.projectDirectory.toPath() } catch (_: Exception) { null }
        }

        for (contentRoot in ideaModule.contentRoots) {
            for (sourceDir in contentRoot.sourceDirectories) {
                sourceRoots.add(sourceDir.directory.toPath())
            }
            for (testDir in contentRoot.testDirectories) {
                testSourceRoots.add(testDir.directory.toPath())
            }
        }

        for (dep in ideaModule.dependencies) {
            if (dep is org.gradle.tooling.model.idea.IdeaSingleEntryLibraryDependency) {
                val file = dep.file
                if (file != null && file.exists()) {
                    val path = file.toPath()
                    if (dep.scope?.scope == "TEST") {
                        testClasspath.add(path)
                    } else {
                        classpath.add(path)
                    }
                }
            }
        }

        // Detect Android module
        val isAndroid = detectAndroid(moduleDir, classpath)

        // Android modules often don't report source dirs through IdeaModule.
        // Fall back to conventional Android source directory layout.
        if (moduleDir != null && sourceRoots.isEmpty()) {
            addConventionalSourceRoots(moduleDir, sourceRoots, testSourceRoots)
        } else if (moduleDir != null && isAndroid) {
            // Even if some source roots were reported, ensure conventional dirs are included
            addConventionalSourceRoots(moduleDir, sourceRoots, testSourceRoots)
        }

        if (isAndroid && moduleDir != null) {
            // Add android.jar if not already in classpath
            if (classpath.none { it.fileName.toString() == "android.jar" }) {
                findAndroidJar()?.let { classpath.add(it) }
            }

            // Add generated source directories that exist on disk (from previous build)
            addGeneratedSources(moduleDir, sourceRoots)
        }

        return ModuleInfo(
            name = ideaModule.name,
            sourceRoots = sourceRoots,
            testSourceRoots = testSourceRoots,
            classpath = classpath,
            testClasspath = testClasspath,
            kotlinVersion = null,
            jvmTarget = null,
            isAndroid = isAndroid
        )
    }

    private fun resolveClasspathViaInitScript(
        connection: org.gradle.tooling.ProjectConnection,
        workspaceRoot: Path
    ): Map<String, List<Path>> {
        // Write a temporary Gradle init script that resolves debugCompileClasspath
        val initScript = Files.createTempFile("lsp-classpath-", ".gradle")
        try {
            Files.writeString(initScript, """
                allprojects {
                    task lspResolveClasspath {
                        doLast {
                            def configs = ["debugCompileClasspath", "releaseCompileClasspath", "compileClasspath"]
                            for (configName in configs) {
                                def cp = configurations.findByName(configName)
                                if (cp != null) {
                                    try {
                                        // Try lenient resolution first
                                        cp.resolvedConfiguration.lenientConfiguration.files.each { file ->
                                            println "LSPCP:" + project.name + ":" + file.absolutePath
                                        }
                                        break
                                    } catch (e) {
                                        // resolvedConfiguration may throw; try incoming.files as fallback
                                        try {
                                            cp.incoming.files.each { file ->
                                                println "LSPCP:" + project.name + ":" + file.absolutePath
                                            }
                                            break
                                        } catch (e2) {
                                            // Last resort: iterate artifacts individually
                                            try {
                                                cp.incoming.artifacts.artifacts.each { art ->
                                                    println "LSPCP:" + project.name + ":" + art.file.absolutePath
                                                }
                                                break
                                            } catch (e3) {
                                                println "LSPERR:" + project.name + ":" + configName + ":" + e3.message?.take(80)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            """.trimIndent())

            val outputStream = java.io.ByteArrayOutputStream()
            connection.newBuild()
                .forTasks("lspResolveClasspath")
                .withArguments("--init-script", initScript.toString(), "-q")
                .setStandardOutput(outputStream)
                .setStandardError(OutputStream.nullOutputStream())
                .run()

            val result = mutableMapOf<String, MutableList<Path>>()
            outputStream.toString().lines().forEach { line ->
                when {
                    line.startsWith("LSPCP:") -> {
                        val parts = line.removePrefix("LSPCP:").split(":", limit = 2)
                        if (parts.size == 2) {
                            val moduleName = parts[0]
                            val filePath = Path.of(parts[1])
                            if (Files.exists(filePath)) {
                                result.getOrPut(moduleName) { mutableListOf() }.add(filePath)
                            }
                        }
                    }
                    line.startsWith("LSPERR:") -> {} // dependency resolution error, expected for some Android modules
                    line.startsWith("LSPAVAIL:") -> {}
                }
            }
            return result
        } finally {
            Files.deleteIfExists(initScript)
        }
    }

    private fun detectAndroid(moduleDir: Path?, classpath: List<Path>): Boolean {
        // Check classpath for android.jar
        if (classpath.any { it.toString().contains("platforms/android-") && it.fileName.toString() == "android.jar" }) {
            return true
        }

        if (moduleDir == null) return false

        // Check for AndroidManifest.xml
        if (Files.exists(moduleDir.resolve("src/main/AndroidManifest.xml"))) {
            return true
        }

        // Check build.gradle for Android plugin
        for (buildFile in listOf("build.gradle.kts", "build.gradle")) {
            val path = moduleDir.resolve(buildFile)
            if (Files.exists(path)) {
                val content = try { Files.readString(path) } catch (_: Exception) { continue }
                if (content.contains("com.android.application") ||
                    content.contains("com.android.library") ||
                    content.contains("android {")) {
                    return true
                }
            }
        }

        return false
    }

    private fun findAndroidJar(): Path? {
        val sdkRoot = System.getenv("ANDROID_HOME")
            ?: System.getenv("ANDROID_SDK_ROOT")
            ?: return null

        val platformsDir = Path.of(sdkRoot, "platforms")
        if (!Files.isDirectory(platformsDir)) return null

        // Find the highest API level platform
        return try {
            Files.list(platformsDir)
                .filter { Files.isDirectory(it) && it.fileName.toString().startsWith("android-") }
                .sorted(Comparator.comparingInt { dir ->
                    dir.fileName.toString().removePrefix("android-").toIntOrNull() ?: 0
                })
                .map { it.resolve("android.jar") }
                .filter { Files.exists(it) }
                .reduce { _, b -> b } // take last (highest)
                .orElse(null)
        } catch (_: Exception) { null }
    }

    private fun addConventionalSourceRoots(
        moduleDir: Path,
        sourceRoots: MutableList<Path>,
        testSourceRoots: MutableList<Path>
    ) {
        val existingPaths = (sourceRoots + testSourceRoots).map { it.normalize() }.toSet()

        // Conventional main source directories
        val mainDirs = listOf(
            "src/main/kotlin",
            "src/main/java",
            "src/debug/kotlin",
            "src/debug/java"
        )
        for (dir in mainDirs) {
            val path = moduleDir.resolve(dir)
            if (Files.isDirectory(path) && path.normalize() !in existingPaths) {
                sourceRoots.add(path)
            }
        }

        // Conventional test source directories
        val testDirs = listOf(
            "src/test/kotlin",
            "src/test/java",
            "src/androidTest/kotlin",
            "src/androidTest/java"
        )
        for (dir in testDirs) {
            val path = moduleDir.resolve(dir)
            if (Files.isDirectory(path) && path.normalize() !in existingPaths) {
                testSourceRoots.add(path)
            }
        }
    }

    private fun addGeneratedSources(moduleDir: Path, sourceRoots: MutableList<Path>) {
        // Common generated source directories for Android debug variant
        val generatedDirs = listOf(
            "build/generated/source/r/debug",
            "build/generated/source/buildConfig/debug",
            "build/generated/source/dataBinding/debug",
            "build/generated/data_binding_base_class_source_out/debug",
            "build/generated/source/kapt/debug",
            "build/generated/source/ksp/debug",
            "build/generated/ap_generated_sources/debug/out"
        )

        for (dir in generatedDirs) {
            val path = moduleDir.resolve(dir)
            if (Files.isDirectory(path)) {
                sourceRoots.add(path)
            }
        }
    }

    override suspend fun resolveModule(workspaceRoot: Path, moduleName: String): ModuleInfo {
        return resolve(workspaceRoot).modules.find { it.name == moduleName }
            ?: throw IllegalArgumentException("Module '$moduleName' not found")
    }
}

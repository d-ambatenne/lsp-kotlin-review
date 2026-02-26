package dev.review.lsp.buildsystem.gradle

import dev.review.lsp.buildsystem.BuildSystemProvider
import dev.review.lsp.buildsystem.KmpPlatform
import dev.review.lsp.buildsystem.KmpTarget
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

    override suspend fun resolve(workspaceRoot: Path, variant: String): ProjectModel {
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
                    resolveModule(ideaModule, variant)
                } catch (e: Exception) {
                    System.err.println("Skipping module '${ideaModule.name}': ${e.message}")
                    null
                }
            }

            // IdeaProject/EclipseProject don't report dependencies for Android (AGP) modules.
            // Use a Gradle init script to resolve classpath directly.
            // Run for any project with Android modules OR modules with empty classpath,
            // and merge results (not just replace) to fill gaps in partial IdeaProject data.
            val hasAndroid = modules.any { it.isAndroid }
            val anyEmptyClasspath = modules.any { it.classpath.isEmpty() }
            for (m in modules) {
                System.err.println("[gradle] module '${m.name}': android=${m.isAndroid}, classpath=${m.classpath.size} entries, sources=${m.sourceRoots.size}")
            }
            System.err.println("[gradle] hasAndroid=$hasAndroid, anyEmptyClasspath=$anyEmptyClasspath")
            if (hasAndroid || anyEmptyClasspath) {
                try {
                    System.err.println("[gradle] Running init script for classpath resolution (variant=$variant)...")
                    val resolvedClasspaths = resolveClasspathViaInitScript(connection, workspaceRoot, variant)
                    val resolvedCp = resolvedClasspaths.main
                    val resolvedTestCp = resolvedClasspaths.test
                    val resolvedKmp = resolvedClasspaths.kmp
                    System.err.println("[gradle] Init script resolved ${resolvedCp.size} modules: ${resolvedCp.map { "${it.key}=${it.value.size}" }}")
                    if (resolvedTestCp.isNotEmpty()) {
                        System.err.println("[gradle] Init script resolved test classpath for ${resolvedTestCp.size} modules: ${resolvedTestCp.map { "${it.key}=${it.value.size}" }}")
                    }
                    if (resolvedKmp.isNotEmpty()) {
                        System.err.println("[gradle] Init script resolved KMP classpaths for ${resolvedKmp.size} modules")
                    }
                    modules = modules.map { module ->
                        var updated = module
                        // Merge main classpath
                        val cp = resolvedCp[module.name]
                        if (cp != null && cp.isNotEmpty()) {
                            val existingPaths = updated.classpath.map { it.normalize() }.toSet()
                            val newEntries = cp.filter { it.normalize() !in existingPaths }
                            System.err.println("[gradle] module '${module.name}': +${newEntries.size} new classpath entries from init script")
                            if (newEntries.isNotEmpty()) {
                                updated = updated.copy(classpath = updated.classpath + newEntries)
                            }
                        }
                        // Merge test classpath
                        val tcp = resolvedTestCp[module.name]
                        if (tcp != null && tcp.isNotEmpty()) {
                            val existingPaths = (updated.classpath + updated.testClasspath).map { it.normalize() }.toSet()
                            val newEntries = tcp.filter { it.normalize() !in existingPaths }
                            System.err.println("[gradle] module '${module.name}': +${newEntries.size} new test classpath entries from init script")
                            if (newEntries.isNotEmpty()) {
                                updated = updated.copy(testClasspath = updated.testClasspath + newEntries)
                            }
                        }
                        // Merge KMP per-target classpaths
                        if (updated.targets.isNotEmpty()) {
                            val moduleKmp = resolvedKmp[module.name]
                            if (moduleKmp != null && moduleKmp.isNotEmpty()) {
                                val updatedTargets = updated.targets.map { target ->
                                    val existingPaths = target.classpath.map { it.normalize() }.toSet()
                                    val newEntries = mutableListOf<Path>()
                                    for ((configName, paths) in moduleKmp) {
                                        val platform = configNameToKmpPlatform(configName) ?: continue
                                        if (platform != target.platform) continue
                                        for (p in paths) {
                                            if (p.normalize() !in existingPaths && p !in newEntries) {
                                                newEntries.add(p)
                                            }
                                        }
                                    }
                                    if (newEntries.isNotEmpty()) {
                                        System.err.println("[gradle] module '${module.name}': KMP target '${target.name}' +${newEntries.size} classpath entries")
                                        target.copy(classpath = target.classpath + newEntries)
                                    } else {
                                        target
                                    }
                                }
                                updated = updated.copy(targets = updatedTargets)
                            }
                        }
                        updated
                    }
                } catch (e: Exception) {
                    System.err.println("[gradle] Init script classpath resolution failed: ${e.message}")
                    e.printStackTrace(System.err)
                }
            } else {
                System.err.println("[gradle] Skipping init script (no Android modules and no empty classpath)")
            }

            val isKmp = modules.any { it.targets.isNotEmpty() }
            if (isKmp) {
                for (m in modules.filter { it.targets.isNotEmpty() }) {
                    System.err.println("[gradle] KMP module '${m.name}': targets=${m.targets.map { "${it.name}(${it.platform})" }}")
                }
            }

            ProjectModel(modules = modules, variant = variant, isMultiplatform = isKmp)
        } finally {
            connection.close()
        }
    }

    private fun resolveModule(ideaModule: org.gradle.tooling.model.idea.IdeaModule, variant: String): ModuleInfo {
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

        // Detect KMP module
        val isKmp = detectKmp(moduleDir)
        val kmpTargets = if (isKmp && moduleDir != null) resolveKmpTargets(moduleDir) else emptyList()

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
            addGeneratedSources(moduleDir, sourceRoots, variant)

            // Modern AGP compiles R class directly to a JAR (no R.java source).
            // Add R.jar to classpath if it exists.
            addGeneratedClasspathJars(moduleDir, classpath, variant)
        }

        return ModuleInfo(
            name = ideaModule.name,
            sourceRoots = sourceRoots,
            testSourceRoots = testSourceRoots,
            classpath = classpath,
            testClasspath = testClasspath,
            kotlinVersion = null,
            jvmTarget = null,
            isAndroid = isAndroid,
            targets = kmpTargets
        )
    }

    private data class ResolvedClasspaths(
        val main: Map<String, List<Path>>,
        val test: Map<String, List<Path>>,
        val kmp: Map<String, Map<String, List<Path>>> = emptyMap()  // module -> configName -> paths
    )

    private fun resolveClasspathViaInitScript(
        connection: org.gradle.tooling.ProjectConnection,
        workspaceRoot: Path,
        variant: String = "debug"
    ): ResolvedClasspaths {
        // Write a temporary Gradle init script that resolves the variant's compile classpath
        val initScript = Files.createTempFile("lsp-classpath-", ".gradle")
        val variantCp = "${variant}CompileClasspath"
        val variantAndroidTestCp = "${variant}AndroidTestCompileClasspath"
        val variantUnitTestCp = "${variant}UnitTestCompileClasspath"
        try {
            Files.writeString(initScript, """
                allprojects {
                    task lspResolveClasspath {
                        doLast {
                            // List all resolvable compile-related configurations
                            // Use project.configurations explicitly — Gradle 8.14+ doesn't resolve
                            // bare 'configurations' inside doLast to the project scope
                            def compileConfigs = project.configurations.names.findAll {
                                it.toLowerCase().contains("compileclasspath")
                            }
                            println "LSPDBG:" + project.name + ":available=" + compileConfigs.join(",")

                            // --- Main classpath ---
                            def configs = ["$variantCp", "compileClasspath"]
                            // Also try any available compile classpath config as last resort
                            compileConfigs.each { if (!configs.contains(it)) configs.add(it) }

                            for (configName in configs) {
                                def cp = project.configurations.findByName(configName)
                                if (cp == null) continue
                                if (!cp.canBeResolved) {
                                    println "LSPDBG:" + project.name + ":" + configName + "=not-resolvable"
                                    continue
                                }
                                try {
                                    // Use artifactView with lenient=true — this is the proper
                                    // Gradle API that skips unresolvable artifacts gracefully
                                    cp.incoming.artifactView { lenient = true }.files.each { file ->
                                        println "LSPCP:" + project.name + ":" + file.absolutePath
                                    }
                                    break
                                } catch (e) {
                                    println "LSPERR:" + project.name + ":" + configName + ":" + e.message?.take(200)
                                }
                            }

                            // --- Test classpaths (androidTest + unitTest) ---
                            def testConfigs = ["$variantAndroidTestCp", "$variantUnitTestCp"]
                            for (configName in testConfigs) {
                                def cp = project.configurations.findByName(configName)
                                if (cp == null) continue
                                if (!cp.canBeResolved) continue
                                try {
                                    cp.incoming.artifactView { lenient = true }.files.each { file ->
                                        println "LSPTCP:" + project.name + ":" + file.absolutePath
                                    }
                                } catch (e) {
                                    println "LSPERR:" + project.name + ":test:" + configName + ":" + e.message?.take(200)
                                }
                            }

                            // --- KMP per-target classpaths ---
                            def kmpConfigs = project.configurations.names.findAll {
                                it.matches(/^(jvm|android|ios|js|wasmJs|native|linux|macos|mingw).*[Cc]ompile[Cc]lasspath$/)
                            }
                            for (configName in kmpConfigs) {
                                def cp = project.configurations.findByName(configName)
                                if (cp == null || !cp.canBeResolved) continue
                                try {
                                    cp.incoming.artifactView { lenient = true }.files.each { file ->
                                        println "LSPKMP:" + project.name + ":" + configName + ":" + file.absolutePath
                                    }
                                } catch (e) {
                                    println "LSPERR:" + project.name + ":kmp:" + configName + ":" + e.message?.take(200)
                                }
                            }
                        }
                    }
                }
            """.trimIndent())

            val outputStream = java.io.ByteArrayOutputStream()
            connection.newBuild()
                .forTasks("lspResolveClasspath")
                .withArguments("--init-script", initScript.toString(), "-q", "--no-configuration-cache")
                .setStandardOutput(outputStream)
                .setStandardError(OutputStream.nullOutputStream())
                .run()

            val mainResult = mutableMapOf<String, MutableList<Path>>()
            val testResult = mutableMapOf<String, MutableList<Path>>()
            val kmpResult = mutableMapOf<String, MutableMap<String, MutableList<Path>>>()
            outputStream.toString().lines().forEach { line ->
                when {
                    line.startsWith("LSPCP:") -> {
                        val parts = line.removePrefix("LSPCP:").split(":", limit = 2)
                        if (parts.size == 2) {
                            val moduleName = parts[0]
                            val filePath = Path.of(parts[1])
                            if (Files.exists(filePath)) {
                                mainResult.getOrPut(moduleName) { mutableListOf() }.add(filePath)
                            }
                        }
                    }
                    line.startsWith("LSPTCP:") -> {
                        val parts = line.removePrefix("LSPTCP:").split(":", limit = 2)
                        if (parts.size == 2) {
                            val moduleName = parts[0]
                            val filePath = Path.of(parts[1])
                            if (Files.exists(filePath)) {
                                testResult.getOrPut(moduleName) { mutableListOf() }.add(filePath)
                            }
                        }
                    }
                    line.startsWith("LSPKMP:") -> {
                        val parts = line.removePrefix("LSPKMP:").split(":", limit = 3)
                        if (parts.size == 3) {
                            val moduleName = parts[0]
                            val configName = parts[1]
                            val filePath = Path.of(parts[2])
                            if (Files.exists(filePath)) {
                                kmpResult
                                    .getOrPut(moduleName) { mutableMapOf() }
                                    .getOrPut(configName) { mutableListOf() }
                                    .add(filePath)
                            }
                        }
                    }
                    line.startsWith("LSPERR:") -> System.err.println("[gradle] $line")
                    line.startsWith("LSPDBG:") -> System.err.println("[gradle] $line")
                }
            }
            return ResolvedClasspaths(mainResult, testResult, kmpResult)
        } finally {
            Files.deleteIfExists(initScript)
        }
    }

    private fun configNameToKmpPlatform(configName: String): KmpPlatform? {
        val lower = configName.lowercase()
        return when {
            lower.startsWith("jvm") -> KmpPlatform.JVM
            lower.startsWith("android") || lower.startsWith("debug") || lower.startsWith("release") -> KmpPlatform.ANDROID
            lower.startsWith("ios") || lower.startsWith("native") || lower.startsWith("linux") || lower.startsWith("macos") || lower.startsWith("mingw") -> KmpPlatform.NATIVE
            lower.startsWith("js") || lower.startsWith("wasmjs") -> KmpPlatform.JS
            else -> null
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

    private fun detectKmp(moduleDir: Path?): Boolean {
        if (moduleDir == null) return false

        for (buildFile in listOf("build.gradle.kts", "build.gradle")) {
            val path = moduleDir.resolve(buildFile)
            if (Files.exists(path)) {
                val content = try { Files.readString(path) } catch (_: Exception) { continue }
                if (content.contains("kotlin(\"multiplatform\")") ||
                    content.contains("org.jetbrains.kotlin.multiplatform") ||
                    content.contains("id(\"org.jetbrains.kotlin.multiplatform\")")) {
                    return true
                }
            }
        }

        return false
    }

    private fun resolveKmpTargets(moduleDir: Path): List<KmpTarget> {
        val srcDir = moduleDir.resolve("src")
        if (!Files.isDirectory(srcDir)) return emptyList()

        // Map of source set directory name prefix to KmpPlatform
        val targetMapping = mapOf(
            "jvm" to KmpPlatform.JVM,
            "android" to KmpPlatform.ANDROID,
            "iosArm64" to KmpPlatform.NATIVE,
            "iosX64" to KmpPlatform.NATIVE,
            "iosSimulatorArm64" to KmpPlatform.NATIVE,
            "ios" to KmpPlatform.NATIVE,
            "macosArm64" to KmpPlatform.NATIVE,
            "macos" to KmpPlatform.NATIVE,
            "linuxX64" to KmpPlatform.NATIVE,
            "mingwX64" to KmpPlatform.NATIVE,
            "native" to KmpPlatform.NATIVE,
            "js" to KmpPlatform.JS,
            "wasmJs" to KmpPlatform.JS
        )

        // Intermediate source sets that fold into leaf targets
        val nativeIntermediates = setOf("native", "ios", "macos")

        val targets = mutableListOf<KmpTarget>()

        for ((targetName, platform) in targetMapping) {
            val mainDir = srcDir.resolve("${targetName}Main/kotlin")
            val testDir = srcDir.resolve("${targetName}Test/kotlin")

            if (!Files.isDirectory(mainDir) && !Files.isDirectory(testDir)) continue

            val sourceRoots = mutableListOf<Path>()
            val testSourceRoots = mutableListOf<Path>()

            if (Files.isDirectory(mainDir)) sourceRoots.add(mainDir)
            if (Files.isDirectory(testDir)) testSourceRoots.add(testDir)

            // Also check java subdirectory variant
            val mainJavaDir = srcDir.resolve("${targetName}Main/java")
            val testJavaDir = srcDir.resolve("${targetName}Test/java")
            if (Files.isDirectory(mainJavaDir)) sourceRoots.add(mainJavaDir)
            if (Files.isDirectory(testJavaDir)) testSourceRoots.add(testJavaDir)

            // For leaf native targets, fold in intermediate source sets
            if (platform == KmpPlatform.NATIVE && targetName !in nativeIntermediates) {
                // Fold nativeMain/nativeTest
                val nativeMainDir = srcDir.resolve("nativeMain/kotlin")
                val nativeTestDir = srcDir.resolve("nativeTest/kotlin")
                if (Files.isDirectory(nativeMainDir)) sourceRoots.add(nativeMainDir)
                if (Files.isDirectory(nativeTestDir)) testSourceRoots.add(nativeTestDir)

                // Fold iOS intermediates for iOS-specific targets
                if (targetName.startsWith("ios")) {
                    val iosMainDir = srcDir.resolve("iosMain/kotlin")
                    val iosTestDir = srcDir.resolve("iosTest/kotlin")
                    if (Files.isDirectory(iosMainDir)) sourceRoots.add(iosMainDir)
                    if (Files.isDirectory(iosTestDir)) testSourceRoots.add(iosTestDir)
                }

                // Fold macOS intermediates for macOS-specific targets
                if (targetName.startsWith("macos") && targetName != "macos") {
                    val macosMainDir = srcDir.resolve("macosMain/kotlin")
                    val macosTestDir = srcDir.resolve("macosTest/kotlin")
                    if (Files.isDirectory(macosMainDir)) sourceRoots.add(macosMainDir)
                    if (Files.isDirectory(macosTestDir)) testSourceRoots.add(macosTestDir)
                }
            }

            // Skip intermediate source sets that are already folded into leaf targets
            if (targetName in nativeIntermediates) {
                // Only emit intermediate as a standalone target if no leaf targets exist for it
                val hasLeafTargets = when (targetName) {
                    "native" -> targetMapping.keys.any { it !in nativeIntermediates && targetMapping[it] == KmpPlatform.NATIVE && Files.isDirectory(srcDir.resolve("${it}Main/kotlin")) }
                    "ios" -> listOf("iosArm64", "iosX64", "iosSimulatorArm64").any { Files.isDirectory(srcDir.resolve("${it}Main/kotlin")) }
                    "macos" -> listOf("macosArm64").any { Files.isDirectory(srcDir.resolve("${it}Main/kotlin")) }
                    else -> false
                }
                if (hasLeafTargets) continue
            }

            targets.add(
                KmpTarget(
                    name = targetName,
                    platform = platform,
                    sourceRoots = sourceRoots,
                    testSourceRoots = testSourceRoots,
                    classpath = emptyList(),
                    testClasspath = emptyList()
                )
            )
        }

        return targets
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

    private fun addGeneratedSources(moduleDir: Path, sourceRoots: MutableList<Path>, variant: String) {
        // Common generated source directories for the selected Android build variant.
        // Some tools use build/generated/source/X/variant, others use build/generated/X/variant/kotlin.
        val generatedDirs = listOf(
            "build/generated/source/r/$variant",
            "build/generated/source/buildConfig/$variant",
            "build/generated/source/dataBinding/$variant",
            "build/generated/data_binding_base_class_source_out/$variant",
            "build/generated/source/kapt/$variant",
            "build/generated/source/ksp/$variant",
            "build/generated/ksp/$variant/kotlin",          // KSP alternate layout
            "build/generated/ksp/$variant/java",            // KSP Java output
            "build/generated/ap_generated_sources/$variant/out"
        )

        val existingPaths = sourceRoots.map { it.normalize() }.toSet()
        for (dir in generatedDirs) {
            val path = moduleDir.resolve(dir)
            if (Files.isDirectory(path) && path.normalize() !in existingPaths) {
                sourceRoots.add(path)
            }
        }
    }

    private fun addGeneratedClasspathJars(moduleDir: Path, classpath: MutableList<Path>, variant: String) {
        // Modern AGP (7+/8+) compiles R class directly to R.jar instead of generating R.java
        val jarPaths = listOf(
            "build/intermediates/compile_and_runtime_not_namespaced_r_class_jar/$variant/processDebugResources/R.jar",
            "build/intermediates/compile_and_runtime_not_namespaced_r_class_jar/$variant/process${variant.replaceFirstChar { it.uppercase() }}Resources/R.jar"
        )
        val existingPaths = classpath.map { it.normalize() }.toSet()
        for (jarPath in jarPaths) {
            val path = moduleDir.resolve(jarPath)
            if (Files.exists(path) && path.normalize() !in existingPaths) {
                classpath.add(path)
            }
        }
    }

    override suspend fun resolveModule(workspaceRoot: Path, moduleName: String): ModuleInfo {
        return resolve(workspaceRoot).modules.find { it.name == moduleName }
            ?: throw IllegalArgumentException("Module '$moduleName' not found")
    }
}

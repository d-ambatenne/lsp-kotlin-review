package dev.review.lsp.buildsystem

import dev.review.lsp.buildsystem.gradle.GradleProvider
import dev.review.lsp.buildsystem.manual.ManualProvider
import java.nio.file.Files
import java.nio.file.Path

class BuildSystemResolver(
    private val providers: List<BuildSystemProvider> = listOf(GradleProvider())
) {
    private val manualProvider = ManualProvider()

    private companion object {
        /** Directories to skip during recursive build root discovery. */
        val SKIP_DIRS = setOf("build", ".gradle", ".git", "node_modules", ".idea", "buildSrc", ".kotlin")

        /** Marker files that indicate an independent Gradle build root. */
        val SETTINGS_MARKERS = listOf("settings.gradle.kts", "settings.gradle")

        /** Max directory depth for recursive build root discovery. */
        const val MAX_DISCOVERY_DEPTH = 5
    }

    /**
     * Detect the build system by checking for marker files at the workspace root
     * and in immediate child directories. Returns the matched provider and the
     * directory where it was found.
     */
    fun detect(workspaceRoot: Path): Pair<BuildSystemProvider, Path> {
        // Check workspace root first
        val rootMatch = findBestProvider(workspaceRoot)
        if (rootMatch != null) return rootMatch to workspaceRoot

        // Check immediate child directories (monorepo support)
        try {
            Files.newDirectoryStream(workspaceRoot) { Files.isDirectory(it) }.use { dirs ->
                var bestProvider: BuildSystemProvider? = null
                var bestDir: Path? = null
                for (dir in dirs) {
                    val match = findBestProvider(dir)
                    if (match != null && (bestProvider == null || match.priority > bestProvider.priority)) {
                        bestProvider = match
                        bestDir = dir
                    }
                }
                if (bestProvider != null && bestDir != null) {
                    return bestProvider to bestDir
                }
            }
        } catch (_: Exception) {
            // Directory listing failed, fall through to manual
        }

        return manualProvider to workspaceRoot
    }

    /**
     * Discover all independent build roots within a workspace by recursively
     * searching for settings.gradle(.kts) files. Each discovered settings file
     * indicates an independent Gradle build root.
     *
     * Skips build/, .gradle/, .git/, node_modules/, .idea/, buildSrc/, .kotlin/
     * directories. Depth-limited to [MAX_DISCOVERY_DEPTH] levels.
     *
     * If no build roots are found, falls back to [detect] behavior.
     */
    fun discoverBuildRoots(workspaceRoot: Path): List<Pair<BuildSystemProvider, Path>> {
        val roots = mutableListOf<Pair<BuildSystemProvider, Path>>()
        discoverBuildRootsRecursive(workspaceRoot, 0, roots)

        if (roots.isNotEmpty()) {
            return roots
        }

        // Fall back to existing detect() behavior
        val (provider, dir) = detect(workspaceRoot)
        return listOf(provider to dir)
    }

    private fun discoverBuildRootsRecursive(
        dir: Path,
        depth: Int,
        results: MutableList<Pair<BuildSystemProvider, Path>>
    ) {
        if (depth > MAX_DISCOVERY_DEPTH) return

        // Check if this directory is a build root (has settings.gradle(.kts))
        val hasSettings = SETTINGS_MARKERS.any { Files.exists(dir.resolve(it)) }
        if (hasSettings) {
            val provider = findBestProvider(dir)
            if (provider != null) {
                results.add(provider to dir)
            }
            // Don't recurse into subdirectories of a build root —
            // subprojects within a Gradle build are part of that root,
            // not independent build roots.
            return
        }

        // Recurse into child directories
        try {
            Files.newDirectoryStream(dir) { Files.isDirectory(it) }.use { children ->
                for (child in children) {
                    val name = child.fileName.toString()
                    if (name in SKIP_DIRS || name.startsWith(".")) continue
                    discoverBuildRootsRecursive(child, depth + 1, results)
                }
            }
        } catch (_: Exception) {
            // Directory listing failed, skip
        }
    }

    private fun findBestProvider(dir: Path): BuildSystemProvider? {
        return providers
            .filter { provider ->
                provider.markerFiles.any { marker -> Files.exists(dir.resolve(marker)) }
            }
            .maxByOrNull { it.priority }
    }

    suspend fun resolve(workspaceRoot: Path, variant: String = "debug"): Pair<BuildSystemProvider, ProjectModel> {
        val (provider, projectDir) = detect(workspaceRoot)

        // Try disk cache first (skips Gradle entirely if build files unchanged)
        if (provider !== manualProvider) {
            val cache = ProjectModelCache(projectDir)
            val cached = cache.load()
            if (cached != null) {
                System.err.println("[cache] Using cached project model (${cached.modules.size} modules)")
                return provider to cached.copy(projectDir = projectDir, variant = variant)
            }
        }

        return try {
            val model = provider.resolve(projectDir, variant)
            val result = model.copy(projectDir = projectDir, variant = variant)
            // Save to disk cache for next startup
            if (provider !== manualProvider) {
                ProjectModelCache(projectDir).save(result)
            }
            provider to result
        } catch (e: Exception) {
            if (provider === manualProvider) throw e
            // Gradle (or other provider) failed -- fall back to ManualProvider
            System.err.println("Build system '${provider.id}' failed: ${e.message}, falling back to manual")
            val model = manualProvider.resolve(projectDir, variant)
            manualProvider to model.copy(projectDir = projectDir, variant = variant)
        }
    }
}

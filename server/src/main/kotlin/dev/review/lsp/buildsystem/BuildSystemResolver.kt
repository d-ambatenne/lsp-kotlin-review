package dev.review.lsp.buildsystem

import dev.review.lsp.buildsystem.gradle.GradleProvider
import dev.review.lsp.buildsystem.manual.ManualProvider
import java.nio.file.Files
import java.nio.file.Path

class BuildSystemResolver(
    private val providers: List<BuildSystemProvider> = listOf(GradleProvider())
) {
    private val manualProvider = ManualProvider()

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

    private fun findBestProvider(dir: Path): BuildSystemProvider? {
        return providers
            .filter { provider ->
                provider.markerFiles.any { marker -> Files.exists(dir.resolve(marker)) }
            }
            .maxByOrNull { it.priority }
    }

    suspend fun resolve(workspaceRoot: Path, variant: String = "debug"): Pair<BuildSystemProvider, ProjectModel> {
        val (provider, projectDir) = detect(workspaceRoot)
        return try {
            val model = provider.resolve(projectDir, variant)
            provider to model.copy(projectDir = projectDir, variant = variant)
        } catch (e: Exception) {
            if (provider === manualProvider) throw e
            // Gradle (or other provider) failed -- fall back to ManualProvider
            System.err.println("Build system '${provider.id}' failed: ${e.message}, falling back to manual")
            val model = manualProvider.resolve(projectDir, variant)
            manualProvider to model.copy(projectDir = projectDir, variant = variant)
        }
    }
}

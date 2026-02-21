package dev.review.lsp.buildsystem

import dev.review.lsp.buildsystem.gradle.GradleProvider
import dev.review.lsp.buildsystem.manual.ManualProvider
import java.nio.file.Files
import java.nio.file.Path

class BuildSystemResolver(
    private val providers: List<BuildSystemProvider> = listOf(GradleProvider())
) {
    private val manualProvider = ManualProvider()

    fun detect(workspaceRoot: Path): BuildSystemProvider {
        val matched = providers.filter { provider ->
            provider.markerFiles.any { marker ->
                Files.exists(workspaceRoot.resolve(marker))
            }
        }

        return matched
            .maxByOrNull { it.priority }
            ?: manualProvider
    }

    suspend fun resolve(workspaceRoot: Path): Pair<BuildSystemProvider, ProjectModel> {
        val provider = detect(workspaceRoot)
        return try {
            val model = provider.resolve(workspaceRoot)
            provider to model
        } catch (e: Exception) {
            if (provider === manualProvider) throw e
            // Gradle (or other provider) failed -- fall back to ManualProvider
            System.err.println("Build system '${provider.id}' failed: ${e.message}, falling back to manual")
            val model = manualProvider.resolve(workspaceRoot)
            manualProvider to model
        }
    }
}

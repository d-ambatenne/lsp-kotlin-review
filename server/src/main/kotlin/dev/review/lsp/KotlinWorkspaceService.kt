package dev.review.lsp

import org.eclipse.lsp4j.DidChangeConfigurationParams
import org.eclipse.lsp4j.DidChangeWatchedFilesParams
import org.eclipse.lsp4j.services.WorkspaceService

class KotlinWorkspaceService : WorkspaceService {

    /** Callback invoked when a build file (build.gradle.kts, etc.) changes. */
    var onBuildFileChanged: (() -> Unit)? = null

    /** Callback invoked when generated source files (build/generated/) change. */
    var onGeneratedSourcesChanged: (() -> Unit)? = null

    /** Callback invoked when VS Code configuration changes. */
    var onConfigurationChanged: (() -> Unit)? = null

    override fun didChangeConfiguration(params: DidChangeConfigurationParams) {
        onConfigurationChanged?.invoke()
    }

    override fun didChangeWatchedFiles(params: DidChangeWatchedFilesParams) {
        var buildFileChanged = false
        var generatedChanged = false

        for (change in params.changes) {
            val uri = change.uri
            if (BUILD_FILE_PATTERNS.any { uri.endsWith(it) }) {
                buildFileChanged = true
            }
            if (uri.contains("build/generated/")) {
                generatedChanged = true
            }
        }

        if (buildFileChanged) {
            onBuildFileChanged?.invoke()
        } else if (generatedChanged) {
            // Only rebuild for generated changes if no build file also changed
            // (build file change already triggers full rebuild)
            onGeneratedSourcesChanged?.invoke()
        }
    }

    companion object {
        private val BUILD_FILE_PATTERNS = listOf(
            "build.gradle.kts",
            "build.gradle",
            "settings.gradle.kts",
            "settings.gradle"
        )
    }
}

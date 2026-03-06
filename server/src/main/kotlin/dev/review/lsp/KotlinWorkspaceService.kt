package dev.review.lsp

import org.eclipse.lsp4j.DidChangeConfigurationParams
import org.eclipse.lsp4j.DidChangeWatchedFilesParams
import org.eclipse.lsp4j.services.WorkspaceService

class KotlinWorkspaceService : WorkspaceService {

    /** Callback invoked when a build file (build.gradle.kts, etc.) changes. URI of the first matching change is passed. */
    var onBuildFileChanged: ((uri: String) -> Unit)? = null

    /** Callback invoked when generated source files (build/generated/) change. URI of the first matching change is passed. */
    var onGeneratedSourcesChanged: ((uri: String) -> Unit)? = null

    /** Callback invoked when VS Code configuration changes. */
    var onConfigurationChanged: (() -> Unit)? = null

    override fun didChangeConfiguration(params: DidChangeConfigurationParams) {
        onConfigurationChanged?.invoke()
    }

    override fun didChangeWatchedFiles(params: DidChangeWatchedFilesParams) {
        var buildFileUri: String? = null
        var generatedUri: String? = null

        for (change in params.changes) {
            val uri = change.uri
            if (buildFileUri == null && BUILD_FILE_PATTERNS.any { uri.endsWith(it) }) {
                buildFileUri = uri
            }
            if (generatedUri == null && uri.contains("build/generated/")) {
                generatedUri = uri
            }
        }

        if (buildFileUri != null) {
            onBuildFileChanged?.invoke(buildFileUri)
        } else if (generatedUri != null) {
            // Only rebuild for generated changes if no build file also changed
            // (build file change already triggers full rebuild)
            onGeneratedSourcesChanged?.invoke(generatedUri)
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

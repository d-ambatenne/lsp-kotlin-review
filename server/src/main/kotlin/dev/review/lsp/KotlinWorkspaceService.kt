package dev.review.lsp

import org.eclipse.lsp4j.DidChangeConfigurationParams
import org.eclipse.lsp4j.DidChangeWatchedFilesParams
import org.eclipse.lsp4j.services.WorkspaceService

class KotlinWorkspaceService : WorkspaceService {

    /** Callback invoked when a build file (build.gradle.kts, etc.) changes. */
    var onBuildFileChanged: (() -> Unit)? = null

    override fun didChangeConfiguration(params: DidChangeConfigurationParams) {
        // Reserved for future settings reload
    }

    override fun didChangeWatchedFiles(params: DidChangeWatchedFilesParams) {
        val hasBuildFileChange = params.changes.any { change ->
            val uri = change.uri
            BUILD_FILE_PATTERNS.any { uri.endsWith(it) }
        }
        if (hasBuildFileChange) {
            onBuildFileChanged?.invoke()
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

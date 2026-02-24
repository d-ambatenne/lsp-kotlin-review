package dev.review.lsp.buildsystem

import java.nio.file.Path

interface BuildSystemProvider {
    val id: String
    val markerFiles: List<String>
    val priority: Int

    suspend fun resolve(workspaceRoot: Path, variant: String = "debug"): ProjectModel
    suspend fun resolveModule(workspaceRoot: Path, moduleName: String): ModuleInfo
}

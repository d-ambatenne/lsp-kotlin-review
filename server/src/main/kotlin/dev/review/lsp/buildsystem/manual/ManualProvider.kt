package dev.review.lsp.buildsystem.manual

import dev.review.lsp.buildsystem.BuildSystemProvider
import dev.review.lsp.buildsystem.ModuleInfo
import dev.review.lsp.buildsystem.ProjectModel
import java.nio.file.Files
import java.nio.file.Path

class ManualProvider : BuildSystemProvider {
    override val id = "manual"
    override val markerFiles = emptyList<String>()
    override val priority = 0

    override suspend fun resolve(workspaceRoot: Path, variant: String): ProjectModel {
        val sourceRoots = SOURCE_DIRS
            .map { workspaceRoot.resolve(it) }
            .filter { Files.isDirectory(it) }

        val testSourceRoots = TEST_DIRS
            .map { workspaceRoot.resolve(it) }
            .filter { Files.isDirectory(it) }

        val module = ModuleInfo(
            name = workspaceRoot.fileName?.toString() ?: "root",
            sourceRoots = sourceRoots,
            testSourceRoots = testSourceRoots,
            classpath = emptyList(),
            testClasspath = emptyList(),
            kotlinVersion = null,
            jvmTarget = null
        )

        return ProjectModel(modules = listOf(module))
    }

    override suspend fun resolveModule(workspaceRoot: Path, moduleName: String): ModuleInfo {
        return resolve(workspaceRoot).modules.first()
    }

    companion object {
        private val SOURCE_DIRS = listOf(
            "src/main/kotlin",
            "src/main/java",
            "src/kotlin",
            "src"
        )

        private val TEST_DIRS = listOf(
            "src/test/kotlin",
            "src/test/java",
            "src/testKotlin"
        )
    }
}

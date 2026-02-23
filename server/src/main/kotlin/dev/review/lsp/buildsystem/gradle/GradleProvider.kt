package dev.review.lsp.buildsystem.gradle

import dev.review.lsp.buildsystem.BuildSystemProvider
import dev.review.lsp.buildsystem.ModuleInfo
import dev.review.lsp.buildsystem.ProjectModel
import org.gradle.tooling.GradleConnector
import org.gradle.tooling.model.idea.IdeaProject
import java.io.OutputStream
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
            val modules = ideaProject.modules.mapNotNull { ideaModule ->
                try {
                    resolveModule(ideaModule)
                } catch (e: Exception) {
                    System.err.println("Skipping module '${ideaModule.name}': ${e.message}")
                    null
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

        return ModuleInfo(
            name = ideaModule.name,
            sourceRoots = sourceRoots,
            testSourceRoots = testSourceRoots,
            classpath = classpath,
            testClasspath = testClasspath,
            kotlinVersion = null,
            jvmTarget = null
        )
    }

    override suspend fun resolveModule(workspaceRoot: Path, moduleName: String): ModuleInfo {
        return resolve(workspaceRoot).modules.find { it.name == moduleName }
            ?: throw IllegalArgumentException("Module '$moduleName' not found")
    }
}

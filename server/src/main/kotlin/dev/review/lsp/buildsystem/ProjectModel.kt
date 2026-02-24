package dev.review.lsp.buildsystem

import java.nio.file.Path

data class ProjectModel(
    val modules: List<ModuleInfo>,
    val projectDir: Path? = null,
    val variant: String = "debug"
)

data class ModuleInfo(
    val name: String,
    val sourceRoots: List<Path>,
    val testSourceRoots: List<Path>,
    val classpath: List<Path>,
    val testClasspath: List<Path>,
    val kotlinVersion: String?,
    val jvmTarget: String?,
    val isAndroid: Boolean = false
)

package dev.review.lsp.buildsystem

import java.nio.file.Path

data class ProjectModel(
    val modules: List<ModuleInfo>,
    val projectDir: Path? = null,
    val variant: String = "debug",
    val isMultiplatform: Boolean = false
)

data class ModuleInfo(
    val name: String,
    val sourceRoots: List<Path>,
    val testSourceRoots: List<Path>,
    val classpath: List<Path>,
    val testClasspath: List<Path>,
    val kotlinVersion: String?,
    val jvmTarget: String?,
    val isAndroid: Boolean = false,
    val targets: List<KmpTarget> = emptyList()
)

enum class KmpPlatform {
    JVM, ANDROID, NATIVE, JS
}

data class KmpTarget(
    val name: String,
    val platform: KmpPlatform,
    val sourceRoots: List<Path>,
    val testSourceRoots: List<Path>,
    val classpath: List<Path>,
    val testClasspath: List<Path>
)

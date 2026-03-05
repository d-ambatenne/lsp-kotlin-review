package dev.review.lsp.buildsystem

import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

class ProjectModelCache(private val projectDir: Path) {
    private val cacheDir = projectDir.resolve(".kotlin-review")
    private val cacheFile = cacheDir.resolve("project-model.json")

    fun load(): ProjectModel? {
        if (!Files.exists(cacheFile)) return null

        return try {
            val json = Files.readString(cacheFile)
            val root = Json.parseToJsonElement(json).jsonObject

            val savedHash = root["buildFilesHash"]?.jsonPrimitive?.content ?: return null
            val currentHash = computeBuildFileHash()
            if (savedHash != currentHash) {
                System.err.println("[cache] Build files changed, invalidating cache")
                return null
            }

            deserializeModel(root)
        } catch (e: Exception) {
            System.err.println("[cache] Failed to load cache: ${e.message}")
            null
        }
    }

    fun save(model: ProjectModel) {
        try {
            Files.createDirectories(cacheDir)
            val json = serializeModel(model, computeBuildFileHash())
            Files.writeString(cacheFile, json)
        } catch (e: Exception) {
            System.err.println("[cache] Failed to save cache: ${e.message}")
        }
    }

    private fun computeBuildFileHash(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buildFileNames = listOf(
            "build.gradle.kts", "build.gradle",
            "settings.gradle.kts", "settings.gradle",
            "gradle.properties"
        )

        // Hash root build files
        for (name in buildFileNames) {
            val f = projectDir.resolve(name)
            if (Files.exists(f)) {
                digest.update(name.toByteArray())
                digest.update(Files.readAllBytes(f))
            }
        }

        // Hash immediate submodule build files (covers multi-module projects)
        try {
            Files.newDirectoryStream(projectDir) { Files.isDirectory(it) }.use { dirs ->
                for (dir in dirs.sorted()) {
                    val dirName = dir.fileName.toString()
                    if (dirName.startsWith(".")) continue
                    for (name in listOf("build.gradle.kts", "build.gradle")) {
                        val f = dir.resolve(name)
                        if (Files.exists(f)) {
                            digest.update("$dirName/$name".toByteArray())
                            digest.update(Files.readAllBytes(f))
                        }
                    }
                }
            }
        } catch (_: Exception) { /* ignore directory listing failures */ }

        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun serializeModel(model: ProjectModel, hash: String): String {
        val json = buildJsonObject {
            put("buildFilesHash", hash)
            put("variant", model.variant)
            put("isMultiplatform", model.isMultiplatform)
            putJsonArray("modules") {
                for (m in model.modules) {
                    addJsonObject {
                        put("name", m.name)
                        putJsonArray("sourceRoots") { m.sourceRoots.forEach { add(it.toString()) } }
                        putJsonArray("testSourceRoots") { m.testSourceRoots.forEach { add(it.toString()) } }
                        putJsonArray("classpath") { m.classpath.forEach { add(it.toString()) } }
                        putJsonArray("testClasspath") { m.testClasspath.forEach { add(it.toString()) } }
                        put("kotlinVersion", m.kotlinVersion)
                        put("jvmTarget", m.jvmTarget)
                        put("isAndroid", m.isAndroid)
                        putJsonArray("targets") {
                            for (t in m.targets) {
                                addJsonObject {
                                    put("name", t.name)
                                    put("platform", t.platform.name)
                                    putJsonArray("sourceRoots") { t.sourceRoots.forEach { add(it.toString()) } }
                                    putJsonArray("testSourceRoots") { t.testSourceRoots.forEach { add(it.toString()) } }
                                    putJsonArray("classpath") { t.classpath.forEach { add(it.toString()) } }
                                    putJsonArray("testClasspath") { t.testClasspath.forEach { add(it.toString()) } }
                                }
                            }
                        }
                    }
                }
            }
        }
        return Json { prettyPrint = true }.encodeToString(JsonElement.serializer(), json)
    }

    private fun deserializeModel(root: JsonObject): ProjectModel {
        val modules = root["modules"]!!.jsonArray.map { elem ->
            val obj = elem.jsonObject
            ModuleInfo(
                name = obj["name"]!!.jsonPrimitive.content,
                sourceRoots = obj["sourceRoots"]!!.jsonArray.map { Path.of(it.jsonPrimitive.content) },
                testSourceRoots = obj["testSourceRoots"]!!.jsonArray.map { Path.of(it.jsonPrimitive.content) },
                classpath = obj["classpath"]!!.jsonArray.map { Path.of(it.jsonPrimitive.content) },
                testClasspath = obj["testClasspath"]!!.jsonArray.map { Path.of(it.jsonPrimitive.content) },
                kotlinVersion = obj["kotlinVersion"]?.jsonPrimitive?.contentOrNull,
                jvmTarget = obj["jvmTarget"]?.jsonPrimitive?.contentOrNull,
                isAndroid = obj["isAndroid"]?.jsonPrimitive?.boolean ?: false,
                targets = obj["targets"]!!.jsonArray.map { tElem ->
                    val tObj = tElem.jsonObject
                    KmpTarget(
                        name = tObj["name"]!!.jsonPrimitive.content,
                        platform = KmpPlatform.valueOf(tObj["platform"]!!.jsonPrimitive.content),
                        sourceRoots = tObj["sourceRoots"]!!.jsonArray.map { Path.of(it.jsonPrimitive.content) },
                        testSourceRoots = tObj["testSourceRoots"]!!.jsonArray.map { Path.of(it.jsonPrimitive.content) },
                        classpath = tObj["classpath"]!!.jsonArray.map { Path.of(it.jsonPrimitive.content) },
                        testClasspath = tObj["testClasspath"]!!.jsonArray.map { Path.of(it.jsonPrimitive.content) },
                    )
                }
            )
        }
        return ProjectModel(
            modules = modules,
            variant = root["variant"]?.jsonPrimitive?.content ?: "debug",
            isMultiplatform = root["isMultiplatform"]?.jsonPrimitive?.boolean ?: false,
        )
    }
}

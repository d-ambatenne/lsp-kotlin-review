package dev.review.lsp.compiler.analysisapi

import org.jetbrains.kotlin.library.metadata.parsePackageFragment
import org.jetbrains.kotlin.metadata.ProtoBuf
import org.jetbrains.kotlin.metadata.deserialization.Flags
import java.nio.file.Files
import java.nio.file.Path

/**
 * Generates Kotlin stub files from klib metadata.
 *
 * Klib files contain serialized ProtoBuf metadata for declarations.
 * This generator extracts class, function, and property signatures
 * and writes minimal Kotlin source stubs that the Analysis API can parse.
 */
class KlibStubGenerator {

    fun generateStubs(klibPath: Path): Path? {
        return try {
            val metadata = loadKlib(klibPath) ?: return null
            val outputDir = Files.createTempDirectory("lsp-klib-stubs")
            var stubCount = 0

            for ((pkgFqName, fragments) in metadata.packages) {
                for (bytes in fragments) {
                    try {
                        val fragment = parsePackageFragment(bytes)
                        if (generateFragmentStub(outputDir, pkgFqName, fragment)) {
                            stubCount++
                        }
                    } catch (_: Exception) { }
                }
            }

            if (stubCount > 0) outputDir else null
        } catch (e: Exception) {
            System.err.println("[klib-stub] Failed to generate stubs from ${klibPath.fileName}: ${e.message}")
            null
        }
    }

    private data class KlibMetadata(
        val moduleHeader: ByteArray,
        val packages: Map<String, List<ByteArray>>
    )

    private fun loadKlib(klibPath: Path): KlibMetadata? {
        return try {
            java.util.zip.ZipFile(klibPath.toFile()).use { zip ->
                val entries = zip.entries().toList()
                val linkdataEntries = entries.filter {
                    it.name.contains("/linkdata/") || it.name.startsWith("linkdata/")
                }
                if (linkdataEntries.isEmpty()) return null

                val prefix = linkdataEntries.first().name.substringBefore("linkdata/")
                val moduleEntry = zip.getEntry("${prefix}linkdata/module") ?: return null
                val moduleHeader = zip.getInputStream(moduleEntry).readBytes()

                val packages = mutableMapOf<String, MutableList<ByteArray>>()
                for (entry in linkdataEntries) {
                    if (entry.isDirectory) continue
                    val relativePath = entry.name.removePrefix(prefix).removePrefix("linkdata/")
                    if (relativePath == "module" || !relativePath.endsWith(".knm")) continue

                    val parts = relativePath.split("/")
                    val pkgFqName = if (parts.size >= 2) {
                        parts.dropLast(1).joinToString("/")
                            .replace("root_package/", "")
                            .replace("root_package", "")
                            .replace("/", ".")
                            .trimEnd('.')
                    } else ""

                    packages.getOrPut(pkgFqName) { mutableListOf() }
                        .add(zip.getInputStream(entry).readBytes())
                }

                KlibMetadata(moduleHeader, packages)
            }
        } catch (e: Exception) {
            System.err.println("[klib-stub] Failed to read klib ${klibPath.fileName}: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    // ---- Context for name resolution ----

    private class Ctx(
        val strings: ProtoBuf.StringTable,
        val qNames: ProtoBuf.QualifiedNameTable
    )

    // ---- Stub generation ----

    private fun generateFragmentStub(outputDir: Path, pkgFqName: String, fragment: ProtoBuf.PackageFragment): Boolean {
        val ctx = Ctx(fragment.strings, fragment.qualifiedNames)
        val sb = StringBuilder()
        if (pkgFqName.isNotEmpty()) {
            sb.appendLine("package $pkgFqName")
            sb.appendLine()
        }

        for (cls in fragment.class_List) {
            try { generateClass(sb, cls, ctx, "") } catch (_: Exception) { }
        }

        if (fragment.hasPackage()) {
            val pkg = fragment.`package`
            for (fn in pkg.functionList) {
                try { generateFunction(sb, fn, ctx, "") } catch (_: Exception) { }
            }
            for (prop in pkg.propertyList) {
                try { generateProperty(sb, prop, ctx, "") } catch (_: Exception) { }
            }
        }

        val content = sb.toString().trim()
        if (content.isNotEmpty() && content != "package $pkgFqName") {
            val pkgDir = outputDir.resolve(pkgFqName.replace('.', '/'))
            Files.createDirectories(pkgDir)
            Files.writeString(pkgDir.resolve("stubs_${System.nanoTime()}.kt"), content)
            return true
        }
        return false
    }

    private fun generateClass(sb: StringBuilder, cls: ProtoBuf.Class, ctx: Ctx, indent: String) {
        val className = resolveQName(cls.fqName, ctx) ?: return
        val shortName = className.substringAfterLast('.')

        val vis = visibility(Flags.VISIBILITY.get(cls.flags))
        if (vis == "private " || vis == "internal ") return

        val kind = Flags.CLASS_KIND.get(cls.flags)
        val mod = modality(Flags.MODALITY.get(cls.flags))
        val kindStr = when (kind) {
            ProtoBuf.Class.Kind.INTERFACE -> "interface"
            ProtoBuf.Class.Kind.ENUM_CLASS -> "enum class"
            ProtoBuf.Class.Kind.ANNOTATION_CLASS -> "annotation class"
            ProtoBuf.Class.Kind.OBJECT -> "object"
            ProtoBuf.Class.Kind.COMPANION_OBJECT -> "companion object"
            ProtoBuf.Class.Kind.ENUM_ENTRY -> return
            else -> "${mod}class"
        }

        val tps = typeParams(cls.typeParameterList, ctx)
        sb.append("${indent}${vis}${kindStr} $shortName$tps")

        val supers = cls.supertypeList.mapNotNull { renderType(it, ctx) }
            .filter { it != "Any" && it != "kotlin.Any" }
        if (supers.isNotEmpty()) sb.append(" : ${supers.joinToString(", ")}")

        sb.appendLine(" {")
        for (fn in cls.functionList) {
            try { generateFunction(sb, fn, ctx, "$indent    ") } catch (_: Exception) { }
        }
        for (prop in cls.propertyList) {
            try { generateProperty(sb, prop, ctx, "$indent    ") } catch (_: Exception) { }
        }
        sb.appendLine("$indent}")
        sb.appendLine()
    }

    private fun generateFunction(sb: StringBuilder, fn: ProtoBuf.Function, ctx: Ctx, indent: String) {
        val name = str(fn.name, ctx) ?: return
        val vis = visibility(Flags.VISIBILITY.get(fn.flags))
        if (vis == "private " || vis == "internal ") return

        val mod = modality(Flags.MODALITY.get(fn.flags))
        val susp = if (Flags.IS_SUSPEND.get(fn.flags)) "suspend " else ""
        val tps = typeParams(fn.typeParameterList, ctx)

        val recv = if (fn.hasReceiverType()) "${renderType(fn.receiverType, ctx) ?: "Any"}." else ""
        val params = fn.valueParameterList.joinToString(", ") { p ->
            val pName = str(p.name, ctx) ?: "p"
            val pType = if (p.hasType()) renderType(p.type, ctx) ?: "Any?" else "Any?"
            "$pName: $pType"
        }
        val ret = if (fn.hasReturnType()) renderType(fn.returnType, ctx) ?: "Unit" else "Unit"

        sb.appendLine("${indent}${vis}${susp}${mod}fun $tps${recv}${name}(${params}): $ret = TODO()")
    }

    private fun generateProperty(sb: StringBuilder, prop: ProtoBuf.Property, ctx: Ctx, indent: String) {
        val name = str(prop.name, ctx) ?: return
        val vis = visibility(Flags.VISIBILITY.get(prop.flags))
        if (vis == "private " || vis == "internal ") return

        val kw = if (Flags.IS_VAR.get(prop.flags)) "var" else "val"
        val recv = if (prop.hasReceiverType()) "${renderType(prop.receiverType, ctx) ?: "Any"}." else ""
        val type = if (prop.hasReturnType()) renderType(prop.returnType, ctx) ?: "Any?" else "Any?"

        sb.appendLine("${indent}${vis}$kw ${recv}$name: $type get() = TODO()")
    }

    // ---- Name resolution ----

    private fun str(index: Int, ctx: Ctx): String? {
        return if (index in 0 until ctx.strings.stringCount) ctx.strings.getString(index) else null
    }

    private fun resolveQName(index: Int, ctx: Ctx): String? {
        if (index < 0 || index >= ctx.qNames.qualifiedNameCount) return null
        val parts = mutableListOf<String>()
        var qn = ctx.qNames.getQualifiedName(index)
        while (true) {
            val name = str(qn.shortName, ctx) ?: return null
            parts.add(0, name)
            if (!qn.hasParentQualifiedName()) break
            val parentIdx = qn.parentQualifiedName
            if (parentIdx < 0 || parentIdx >= ctx.qNames.qualifiedNameCount) break
            qn = ctx.qNames.getQualifiedName(parentIdx)
        }
        return parts.joinToString(".")
    }

    // ---- Type rendering (uses QualifiedNameTable for className) ----

    private fun renderType(type: ProtoBuf.Type, ctx: Ctx): String? {
        val base = when {
            type.hasClassName() -> resolveQName(type.className, ctx) ?: return null
            type.hasTypeParameter() -> {
                // Type parameter — try to resolve from type parameter index
                // Just use a generic name since we don't have the full context
                "Any"
            }
            type.hasTypeAliasName() -> resolveQName(type.typeAliasName, ctx) ?: return null
            else -> return null
        }
        val args = if (type.argumentCount > 0) {
            "<${type.argumentList.joinToString(", ") { arg ->
                if (arg.hasType()) renderType(arg.type, ctx) ?: "*" else "*"
            }}>"
        } else ""
        val nullable = if (type.nullable) "?" else ""
        return "$base$args$nullable"
    }

    private fun typeParams(list: List<ProtoBuf.TypeParameter>, ctx: Ctx): String {
        if (list.isEmpty()) return ""
        val rendered = list.mapNotNull { str(it.name, ctx) }
        return if (rendered.isNotEmpty()) "<${rendered.joinToString(", ")}> " else ""
    }

    private fun visibility(v: ProtoBuf.Visibility?): String = when (v) {
        ProtoBuf.Visibility.PRIVATE, ProtoBuf.Visibility.PRIVATE_TO_THIS -> "private "
        ProtoBuf.Visibility.INTERNAL -> "internal "
        ProtoBuf.Visibility.PROTECTED -> "protected "
        else -> ""
    }

    private fun modality(m: ProtoBuf.Modality?): String = when (m) {
        ProtoBuf.Modality.ABSTRACT -> "abstract "
        ProtoBuf.Modality.OPEN -> "open "
        ProtoBuf.Modality.SEALED -> "sealed "
        else -> ""
    }
}

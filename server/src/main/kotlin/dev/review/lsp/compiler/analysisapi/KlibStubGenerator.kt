package dev.review.lsp.compiler.analysisapi

import org.jetbrains.kotlin.library.MetadataLibrary
import org.jetbrains.kotlin.library.metadata.parseModuleHeader
import org.jetbrains.kotlin.library.metadata.parsePackageFragment
import org.jetbrains.kotlin.metadata.ProtoBuf
import org.jetbrains.kotlin.metadata.deserialization.Flags
import org.jetbrains.kotlin.metadata.deserialization.NameResolver
import org.jetbrains.kotlin.metadata.deserialization.getExtensionOrNull
import org.jetbrains.kotlin.serialization.StringTableImpl
import java.nio.file.Files
import java.nio.file.Path

/**
 * Generates Kotlin stub files from klib metadata.
 *
 * Klib files contain serialized ProtoBuf metadata for declarations.
 * This generator extracts class, function, and property signatures
 * and writes minimal Kotlin source stubs that the Analysis API can parse.
 *
 * This enables resolution of platform-specific symbols (like
 * ComposeUIViewController for iOS) that only exist in .klib format.
 */
class KlibStubGenerator {

    /**
     * Generate Kotlin stubs from a klib file into a temporary directory.
     * Returns the directory path to be added as a source root, or null on failure.
     */
    fun generateStubs(klibPath: Path): Path? {
        return try {
            val library = loadKlib(klibPath) ?: return null
            val outputDir = Files.createTempDirectory("lsp-klib-stubs")

            val header = parseModuleHeader(library.moduleHeaderData)
            val packageNames = header.packageFragmentNameList

            for (pkgFqName in packageNames) {
                try {
                    val parts = library.packageMetadataParts(pkgFqName)
                    for (partName in parts) {
                        val bytes = library.packageMetadata(pkgFqName, partName)
                        val fragment = parsePackageFragment(bytes)
                        generateFragmentStub(outputDir, pkgFqName, fragment)
                    }
                } catch (e: Exception) {
                    System.err.println("[klib-stub] Failed to process package $pkgFqName in ${klibPath.fileName}: ${e.message}")
                }
            }

            outputDir
        } catch (e: Exception) {
            System.err.println("[klib-stub] Failed to generate stubs from ${klibPath.fileName}: ${e.message}")
            null
        }
    }

    private fun loadKlib(klibPath: Path): MetadataLibrary? {
        return try {
            // Use reflection to load klib — the API may vary between versions
            val loaderClass = Class.forName("org.jetbrains.kotlin.library.loader.KlibLoader")
            val constructor = loaderClass.constructors.firstOrNull { c ->
                c.parameterCount >= 1 && c.parameterTypes[0] == Path::class.java
            } ?: loaderClass.constructors.firstOrNull { c ->
                c.parameterCount >= 1
            }
            if (constructor == null) {
                System.err.println("[klib-stub] KlibLoader constructor not found")
                return null
            }

            val loader = if (constructor.parameterTypes[0] == Path::class.java) {
                constructor.newInstance(klibPath)
            } else {
                constructor.newInstance(klibPath.toFile())
            }

            val loadMethod = loaderClass.methods.firstOrNull { it.name == "load" }
                ?: return null
            val result = loadMethod.invoke(loader)

            // KlibLoaderResult has a load() method that returns List<KotlinLibrary>
            val resultLoadMethod = result.javaClass.methods.firstOrNull { it.name == "load" }
            if (resultLoadMethod != null) {
                val libraries = resultLoadMethod.invoke(result) as? List<*>
                libraries?.firstOrNull() as? MetadataLibrary
            } else {
                // Maybe it returns the library directly
                (result as? List<*>)?.firstOrNull() as? MetadataLibrary
                    ?: result as? MetadataLibrary
            }
        } catch (e: Exception) {
            System.err.println("[klib-stub] Failed to load klib ${klibPath.fileName}: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    private fun generateFragmentStub(
        outputDir: Path,
        pkgFqName: String,
        fragment: ProtoBuf.PackageFragment
    ) {
        val sb = StringBuilder()
        if (pkgFqName.isNotEmpty()) {
            sb.appendLine("package $pkgFqName")
            sb.appendLine()
        }

        val nameResolver = fragment.strings
        val qualifiedNameTable = fragment.qualifiedNames

        // Generate class stubs
        for (cls in fragment.class_List) {
            try {
                generateClassStub(sb, cls, nameResolver, qualifiedNameTable, "")
            } catch (_: Exception) { /* skip problematic declarations */ }
        }

        // Generate top-level function stubs
        if (fragment.hasPackage()) {
            val pkg = fragment.`package`
            for (fn in pkg.functionList) {
                try {
                    generateFunctionStub(sb, fn, nameResolver, "")
                } catch (_: Exception) { /* skip */ }
            }
            for (prop in pkg.propertyList) {
                try {
                    generatePropertyStub(sb, prop, nameResolver, "")
                } catch (_: Exception) { /* skip */ }
            }
        }

        val content = sb.toString().trim()
        if (content.isNotEmpty() && content != "package $pkgFqName") {
            // Write to file
            val pkgDir = outputDir.resolve(pkgFqName.replace('.', '/'))
            Files.createDirectories(pkgDir)
            val fileName = "stubs_${pkgFqName.replace('.', '_')}.kt"
            Files.writeString(pkgDir.resolve(fileName), content)
        }
    }

    private fun generateClassStub(
        sb: StringBuilder,
        cls: ProtoBuf.Class,
        nameResolver: ProtoBuf.StringTable,
        qualifiedNameTable: ProtoBuf.QualifiedNameTable,
        indent: String
    ) {
        val className = resolveClassName(cls.fqName, qualifiedNameTable, nameResolver)
            ?: return
        val shortName = className.substringAfterLast('.')

        val visibility = getVisibility(Flags.VISIBILITY.get(cls.flags))
        if (visibility == "private" || visibility == "internal") return

        val modality = getModality(Flags.MODALITY.get(cls.flags))
        val classKind = Flags.CLASS_KIND.get(cls.flags)

        val kindStr = when (classKind) {
            ProtoBuf.Class.Kind.INTERFACE -> "interface"
            ProtoBuf.Class.Kind.ENUM_CLASS -> "enum class"
            ProtoBuf.Class.Kind.ANNOTATION_CLASS -> "annotation class"
            ProtoBuf.Class.Kind.OBJECT -> "object"
            ProtoBuf.Class.Kind.COMPANION_OBJECT -> "companion object"
            ProtoBuf.Class.Kind.ENUM_ENTRY -> return // skip enum entries
            else -> "${modality}class"
        }

        val typeParams = renderTypeParameters(cls.typeParameterList, nameResolver)

        sb.append("${indent}${visibility}${kindStr} $shortName$typeParams")

        // Supertypes
        val supertypes = cls.supertypeList
            .mapNotNull { renderType(it, nameResolver, qualifiedNameTable) }
            .filter { it != "Any" && it != "kotlin.Any" }
        if (supertypes.isNotEmpty()) {
            sb.append(" : ${supertypes.joinToString(", ")}")
        }

        sb.appendLine(" {")

        // Constructor parameters (primary constructor)
        for (constructor in cls.constructorList) {
            if (!Flags.IS_SECONDARY.get(constructor.flags)) {
                // Primary constructor — skip rendering, params are complex
            }
        }

        // Member functions
        for (fn in cls.functionList) {
            try {
                generateFunctionStub(sb, fn, nameResolver, "$indent    ")
            } catch (_: Exception) { /* skip */ }
        }

        // Member properties
        for (prop in cls.propertyList) {
            try {
                generatePropertyStub(sb, prop, nameResolver, "$indent    ")
            } catch (_: Exception) { /* skip */ }
        }

        // Nested classes
        for (nested in cls.nestedClassNameList) {
            // Just the name reference, no full declaration available here
        }

        sb.appendLine("$indent}")
        sb.appendLine()
    }

    private fun generateFunctionStub(
        sb: StringBuilder,
        fn: ProtoBuf.Function,
        nameResolver: ProtoBuf.StringTable,
        indent: String
    ) {
        val name = resolveString(fn.name, nameResolver) ?: return
        val visibility = getVisibility(Flags.VISIBILITY.get(fn.flags))
        if (visibility == "private" || visibility == "internal") return

        val modality = getModality(Flags.MODALITY.get(fn.flags))
        val isSuspend = Flags.IS_SUSPEND.get(fn.flags)
        val suspendStr = if (isSuspend) "suspend " else ""

        val typeParams = renderTypeParameters(fn.typeParameterList, nameResolver)

        // Extension receiver
        val receiverStr = if (fn.hasReceiverType()) {
            val recType = renderTypeSimple(fn.receiverType, nameResolver)
            "$recType."
        } else ""

        // Parameters
        val params = fn.valueParameterList.mapNotNull { param ->
            val paramName = resolveString(param.name, nameResolver) ?: "p"
            val paramType = if (param.hasType()) renderTypeSimple(param.type, nameResolver) else "Any?"
            "$paramName: $paramType"
        }

        // Return type
        val returnType = if (fn.hasReturnType()) renderTypeSimple(fn.returnType, nameResolver) else "Unit"

        sb.appendLine("${indent}${visibility}${suspendStr}${modality}fun $typeParams${receiverStr}$name(${params.joinToString(", ")}): $returnType = TODO()")
    }

    private fun generatePropertyStub(
        sb: StringBuilder,
        prop: ProtoBuf.Property,
        nameResolver: ProtoBuf.StringTable,
        indent: String
    ) {
        val name = resolveString(prop.name, nameResolver) ?: return
        val visibility = getVisibility(Flags.VISIBILITY.get(prop.flags))
        if (visibility == "private" || visibility == "internal") return

        val isVar = Flags.IS_VAR.get(prop.flags)
        val keyword = if (isVar) "var" else "val"

        // Extension receiver
        val receiverStr = if (prop.hasReceiverType()) {
            val recType = renderTypeSimple(prop.receiverType, nameResolver)
            "$recType."
        } else ""

        val type = if (prop.hasReturnType()) renderTypeSimple(prop.returnType, nameResolver) else "Any?"

        sb.appendLine("${indent}${visibility}$keyword ${receiverStr}$name: $type get() = TODO()")
    }

    // ---- Helpers ----

    private fun resolveString(index: Int, strings: ProtoBuf.StringTable): String? {
        return if (index >= 0 && index < strings.stringCount) strings.getString(index)
        else null
    }

    private fun resolveClassName(
        fqNameIndex: Int,
        qualifiedNameTable: ProtoBuf.QualifiedNameTable,
        strings: ProtoBuf.StringTable
    ): String? {
        if (fqNameIndex < 0 || fqNameIndex >= qualifiedNameTable.qualifiedNameCount) return null
        val qn = qualifiedNameTable.getQualifiedName(fqNameIndex)
        val parts = mutableListOf<String>()
        var current = qn
        while (true) {
            val name = resolveString(current.shortName, strings) ?: return null
            parts.add(0, name)
            if (!current.hasParentQualifiedName()) break
            val parentIdx = current.parentQualifiedName
            if (parentIdx < 0 || parentIdx >= qualifiedNameTable.qualifiedNameCount) break
            current = qualifiedNameTable.getQualifiedName(parentIdx)
        }
        return parts.joinToString(".")
    }

    private fun renderTypeSimple(type: ProtoBuf.Type, strings: ProtoBuf.StringTable): String {
        val name = if (type.hasClassName()) {
            resolveString(type.className, strings) ?: "Any"
        } else if (type.hasTypeParameter()) {
            "T${type.typeParameter}"
        } else {
            "Any"
        }
        val nullable = if (type.nullable) "?" else ""
        val typeArgs = if (type.argumentCount > 0) {
            "<${type.argumentList.joinToString(", ") { arg ->
                if (arg.hasType()) renderTypeSimple(arg.type, strings)
                else "*"
            }}>"
        } else ""
        return "$name$typeArgs$nullable"
    }

    private fun renderType(
        type: ProtoBuf.Type,
        strings: ProtoBuf.StringTable,
        qualifiedNameTable: ProtoBuf.QualifiedNameTable
    ): String? {
        return if (type.hasClassName()) {
            val name = resolveClassName(type.className, qualifiedNameTable, strings) ?: return null
            val nullable = if (type.nullable) "?" else ""
            "$name$nullable"
        } else {
            renderTypeSimple(type, strings)
        }
    }

    private fun renderTypeParameters(
        typeParams: List<ProtoBuf.TypeParameter>,
        strings: ProtoBuf.StringTable
    ): String {
        if (typeParams.isEmpty()) return ""
        val rendered = typeParams.mapNotNull { tp ->
            resolveString(tp.name, strings)
        }
        return if (rendered.isNotEmpty()) "<${rendered.joinToString(", ")}>" else ""
    }

    private fun getVisibility(visibility: ProtoBuf.Visibility?): String = when (visibility) {
        ProtoBuf.Visibility.PRIVATE, ProtoBuf.Visibility.PRIVATE_TO_THIS -> "private "
        ProtoBuf.Visibility.INTERNAL -> "internal "
        ProtoBuf.Visibility.PROTECTED -> "protected "
        else -> ""  // public is default, omit
    }

    private fun getModality(modality: ProtoBuf.Modality?): String = when (modality) {
        ProtoBuf.Modality.ABSTRACT -> "abstract "
        ProtoBuf.Modality.OPEN -> "open "
        ProtoBuf.Modality.SEALED -> "sealed "
        else -> ""
    }
}

package dev.review.lsp.compiler.analysisapi

import dev.review.lsp.buildsystem.KmpPlatform
import dev.review.lsp.buildsystem.ProjectModel
import dev.review.lsp.compiler.*
import org.jetbrains.kotlin.analysis.api.KaExperimentalApi
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.components.KaDiagnosticCheckerFilter
import org.jetbrains.kotlin.analysis.api.diagnostics.KaDiagnosticWithPsi
import org.jetbrains.kotlin.analysis.api.diagnostics.KaSeverity
import org.jetbrains.kotlin.analysis.api.standalone.buildStandaloneAnalysisAPISession
import org.jetbrains.kotlin.analysis.api.symbols.*
import org.jetbrains.kotlin.analysis.api.types.*
import org.jetbrains.kotlin.analysis.project.structure.builder.buildKtLibraryModule
import org.jetbrains.kotlin.analysis.project.structure.builder.buildKtSdkModule
import org.jetbrains.kotlin.analysis.project.structure.builder.buildKtSourceModule
import org.jetbrains.kotlin.platform.jvm.JvmPlatforms
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.types.Variance
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.LinkedHashMap

@OptIn(KaExperimentalApi::class)
class AnalysisApiCompilerFacade(
    private val projectModel: ProjectModel
) : CompilerFacade {

    // Detect if this is an Android project missing generated sources (for diagnostic hints)
    private val androidMissingGenerated: Boolean = run {
        val hasAndroid = projectModel.modules.any { it.isAndroid }
        if (!hasAndroid) return@run false
        // Check if any Android module lacks build/generated/
        projectModel.modules.filter { it.isAndroid }.any { module ->
            val moduleDir = module.sourceRoots.firstOrNull()?.let { root ->
                // Walk up from src/main/java or src/main/kotlin to module root
                var dir = root
                repeat(3) { dir = dir.parent ?: return@any true }
                dir
            }
            moduleDir == null || !java.nio.file.Files.isDirectory(moduleDir.resolve("build/generated"))
        }
    }

    private val analysisThread: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "kotlin-analysis").apply { isDaemon = true }
    }

    private val fileContents = ConcurrentHashMap<Path, String>()

    // LRU cache for resolved symbols per file, invalidated on updateFileContent
    private val symbolCache = object : LinkedHashMap<Path, List<ResolvedSymbol>>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Path, List<ResolvedSymbol>>): Boolean =
            size > SYMBOL_CACHE_MAX_SIZE
    }

    companion object {
        private const val SYMBOL_CACHE_MAX_SIZE = 128
    }

    @Volatile
    private var sessions: Map<KmpPlatform, org.jetbrains.kotlin.analysis.api.standalone.StandaloneAnalysisAPISession> = emptyMap()

    private val session: org.jetbrains.kotlin.analysis.api.standalone.StandaloneAnalysisAPISession
        get() = sessions.values.firstOrNull() ?: throw IllegalStateException("No analysis session available")

    init {
        sessions = buildSessions()
    }

    /** Extract classes.jar from an AAR file to a temp directory. */
    private fun extractClassesFromAar(aar: Path): Path? {
        return try {
            val tempDir = java.nio.file.Files.createTempDirectory("lsp-aar-")
            val classesJar = tempDir.resolve("classes.jar")
            java.util.zip.ZipFile(aar.toFile()).use { zip ->
                val entry = zip.getEntry("classes.jar") ?: return null
                zip.getInputStream(entry).use { input ->
                    java.nio.file.Files.copy(input, classesJar, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                }
            }
            classesJar
        } catch (e: Exception) {
            System.err.println("[session] Failed to extract classes.jar from ${aar.fileName}: ${e.message}")
            null
        }
    }

    private fun buildSingleSession(
        targetPlatform: org.jetbrains.kotlin.platform.TargetPlatform,
        sourceRoots: List<Path>,
        classpathJars: List<Path>,
        includeJdk: Boolean
    ): org.jetbrains.kotlin.analysis.api.standalone.StandaloneAnalysisAPISession {
        // Separate klib files from regular JARs — generate stubs for klibs
        val klibFiles = classpathJars.filter { it.toString().endsWith(".klib") }
        val nonKlibEntries = classpathJars.filter { !it.toString().endsWith(".klib") }

        val klibStubRoots = if (klibFiles.isNotEmpty()) {
            val stubGen = KlibStubGenerator()
            System.err.println("[session] Generating stubs for ${klibFiles.size} klib files...")
            val roots = klibFiles.mapNotNull { klib ->
                try {
                    stubGen.generateStubs(klib)
                } catch (e: Exception) {
                    System.err.println("[session] klib stub generation failed for ${klib.fileName}: ${e.message}")
                    null
                }
            }
            // Log first few stub roots with sample content for debugging
            if (roots.isNotEmpty()) {
                for (root in roots.take(3)) {
                    try {
                        val files = java.nio.file.Files.walk(root).filter { it.toString().endsWith(".kt") }.toList()
                        System.err.println("[session] klib stub root $root: ${files.size} .kt files")
                        files.firstOrNull()?.let { f ->
                            val content = java.nio.file.Files.readString(f)
                            System.err.println("[session] Sample stub (${f.fileName}): ${content.take(200)}")
                        }
                    } catch (_: Exception) { }
                }
            }
            roots
        } else emptyList()

        // Extract classes.jar from AAR files (Android Archive bundles)
        val allClasspath = nonKlibEntries.flatMap { entry ->
            if (entry.toString().endsWith(".aar")) {
                val extracted = extractClassesFromAar(entry)
                if (extracted != null) listOf(extracted) else emptyList()
            } else {
                listOf(entry)
            }
        }
        val aarCount = nonKlibEntries.count { it.toString().endsWith(".aar") }
        System.err.println("[session] Classpath: ${nonKlibEntries.size} entries ($aarCount AARs extracted), ${allClasspath.size} JARs total${if (klibStubRoots.isNotEmpty()) ", ${klibStubRoots.size} klib stub roots" else ""}")

        return buildStandaloneAnalysisAPISession {
            buildKtModuleProvider {
                platform = targetPlatform

                val libraryModules = allClasspath.map { jar ->
                    val absoluteJar = jar.toAbsolutePath()
                    addModule(buildKtLibraryModule {
                        this.platform = targetPlatform
                        libraryName = absoluteJar.fileName.toString()
                        addBinaryRoot(absoluteJar)
                    })
                }

                val sdkModule = if (includeJdk) {
                    addModule(buildKtSdkModule {
                        this.platform = targetPlatform
                        libraryName = "jdk"
                        addBinaryRootsFromJdkHome(
                            Path.of(System.getProperty("java.home")),
                            isJre = true
                        )
                    })
                } else null

                addModule(buildKtSourceModule {
                    moduleName = projectModel.modules.firstOrNull()?.name ?: "sources"
                    this.platform = targetPlatform
                    for (root in sourceRoots) {
                        addSourceRoot(root)
                    }
                    // Add klib-generated stub directories as additional source roots
                    for (stubRoot in klibStubRoots) {
                        addSourceRoot(stubRoot)
                    }
                    if (sdkModule != null) {
                        addRegularDependency(sdkModule)
                    }
                    for (lib in libraryModules) {
                        addRegularDependency(lib)
                    }
                })
            }
        }
    }

    private fun buildSessions(): Map<KmpPlatform, org.jetbrains.kotlin.analysis.api.standalone.StandaloneAnalysisAPISession> {
        val kmpTargets = projectModel.modules.flatMap { it.targets }
        if (kmpTargets.isEmpty()) {
            // Non-KMP: single session with all source roots (existing behavior)
            val allSourceRoots = projectModel.modules.flatMap { it.sourceRoots + it.testSourceRoots }.distinct()
            val rawClasspath = projectModel.modules.flatMap { it.classpath + it.testClasspath }.distinct()
            return mapOf(KmpPlatform.JVM to buildSingleSession(JvmPlatforms.defaultJvmPlatform, allSourceRoots, rawClasspath, true))
        }

        // KMP: one session per platform
        val byPlatform = kmpTargets.groupBy { it.platform }

        return byPlatform.map { (platform, targets) ->
            val platformType = when (platform) {
                KmpPlatform.JVM -> JvmPlatforms.defaultJvmPlatform
                KmpPlatform.ANDROID -> JvmPlatforms.defaultJvmPlatform
                KmpPlatform.JS -> org.jetbrains.kotlin.platform.js.JsPlatforms.defaultJsPlatform
                KmpPlatform.NATIVE -> org.jetbrains.kotlin.platform.konan.NativePlatforms.unspecifiedNativePlatform
            }

            // Source roots: module-level roots + target-specific roots
            val moduleSourceRoots = projectModel.modules.flatMap { it.sourceRoots + it.testSourceRoots }.distinct()
            val targetSourceRoots = targets.flatMap { it.sourceRoots + it.testSourceRoots }
            val allRoots = (moduleSourceRoots + targetSourceRoots).distinct()

            // Classpath: module-level + target-specific
            val moduleClasspath = projectModel.modules.flatMap { it.classpath + it.testClasspath }
            val targetClasspath = targets.flatMap { it.classpath + it.testClasspath }
            val allClasspath = (moduleClasspath + targetClasspath).distinct()

            val includeJdk = platform == KmpPlatform.JVM || platform == KmpPlatform.ANDROID
            System.err.println("[session] Building $platform session: ${allRoots.size} source roots, ${allClasspath.size} classpath entries")
            platform to buildSingleSession(platformType, allRoots, allClasspath, includeJdk)
        }.toMap()
    }

    private fun kmpPlatformForFile(file: Path): KmpPlatform {
        if (sessions.size <= 1) return sessions.keys.firstOrNull() ?: KmpPlatform.JVM
        val pathStr = file.toString()
        return when {
            pathStr.contains("/androidMain/") || pathStr.contains("/androidTest/") -> KmpPlatform.ANDROID
            pathStr.contains("/jvmMain/") || pathStr.contains("/jvmTest/") -> KmpPlatform.JVM
            pathStr.contains("/iosMain/") || pathStr.contains("/nativeMain/") || pathStr.contains("/iosTest/") || pathStr.contains("/nativeTest/") -> KmpPlatform.NATIVE
            pathStr.contains("/jsMain/") || pathStr.contains("/jsTest/") || pathStr.contains("/wasmJsMain/") || pathStr.contains("/wasmJsTest/") -> KmpPlatform.JS
            else -> sessions.keys.firstOrNull { it == KmpPlatform.JVM }
                ?: sessions.keys.firstOrNull { it == KmpPlatform.ANDROID }
                ?: sessions.keys.firstOrNull()
                ?: KmpPlatform.JVM
        }
    }

    override fun platformForFile(file: Path): String? {
        if (sessions.size <= 1 && projectModel.modules.none { it.targets.isNotEmpty() }) return null
        return kmpPlatformForFile(file).name
    }

    override fun getAvailableTargets(): List<String> {
        return sessions.keys.map { it.name }
    }

    private fun sessionForFile(file: Path): org.jetbrains.kotlin.analysis.api.standalone.StandaloneAnalysisAPISession {
        return sessions[kmpPlatformForFile(file)] ?: session
    }

    private fun allKtFiles(): List<KtFile> =
        sessions.values.flatMap { s ->
            s.modulesWithFiles.values.flatten().filterIsInstance<KtFile>()
        }.distinctBy { it.virtualFile?.path }

    private fun findKtFile(file: Path): KtFile? {
        val targetSession = sessionForFile(file)
        val ktFile = targetSession.modulesWithFiles.values.flatten()
            .filterIsInstance<KtFile>()
            .find { ktFile ->
                val ktPath = ktFile.virtualFile?.path?.let { Path.of(it) }
                ktPath != null && (ktPath == file || ktPath.normalize() == file.normalize())
            }
        if (ktFile != null) return ktFile
        // Fall back: search all sessions
        return allKtFiles().find { ktFile ->
            val ktPath = ktFile.virtualFile?.path?.let { Path.of(it) }
            ktPath != null && (ktPath == file || ktPath.normalize() == file.normalize())
        }
    }

    private fun findElementAt(ktFile: KtFile, line: Int, column: Int): KtElement? {
        val document = ktFile.viewProvider.document ?: return null
        if (line >= document.lineCount) return null
        val offset = document.getLineStartOffset(line) + column
        var element = ktFile.findElementAt(offset) ?: return null
        // Walk up to the nearest meaningful KtElement
        while (element !is KtElement && element.parent != null) {
            element = element.parent
        }
        return element as? KtElement
    }

    private fun psiToSourceLocation(psi: com.intellij.psi.PsiElement): SourceLocation? {
        val file = psi.containingFile?.virtualFile?.path?.let { Path.of(it) } ?: return null
        val document = psi.containingFile?.viewProvider?.document ?: return null
        val offset = psi.textOffset
        val line = document.getLineNumber(offset)
        val col = offset - document.getLineStartOffset(line)
        return SourceLocation(file, line, col)
    }

    private fun mapKaSymbolKind(symbol: KaSymbol): SymbolKind = when (symbol) {
        is KaClassSymbol -> when {
            symbol.classKind == KaClassKind.INTERFACE -> SymbolKind.INTERFACE
            symbol.classKind == KaClassKind.ENUM_CLASS -> SymbolKind.ENUM
            symbol.classKind == KaClassKind.OBJECT -> SymbolKind.OBJECT
            symbol.classKind == KaClassKind.COMPANION_OBJECT -> SymbolKind.OBJECT
            else -> SymbolKind.CLASS
        }
        is KaEnumEntrySymbol -> SymbolKind.ENUM_ENTRY
        is KaConstructorSymbol -> SymbolKind.CONSTRUCTOR
        is KaNamedFunctionSymbol -> SymbolKind.FUNCTION
        is KaPropertySymbol -> SymbolKind.PROPERTY
        is KaTypeAliasSymbol -> SymbolKind.TYPE_ALIAS
        is KaTypeParameterSymbol -> SymbolKind.TYPE_PARAMETER
        is KaLocalVariableSymbol -> SymbolKind.LOCAL_VARIABLE
        is KaValueParameterSymbol -> SymbolKind.PARAMETER
        else -> SymbolKind.PROPERTY
    }

    /** Extract a clean name from a KaSymbol (works for both source and library symbols). */
    private fun symbolName(symbol: KaSymbol): String? = try {
        when (symbol) {
            is KaNamedFunctionSymbol -> symbol.name.asString()
            is KaPropertySymbol -> symbol.name.asString()
            is KaValueParameterSymbol -> symbol.name.asString()
            is KaLocalVariableSymbol -> symbol.name.asString()
            is KaEnumEntrySymbol -> symbol.name.asString()
            is KaTypeParameterSymbol -> symbol.name.asString()
            is KaTypeAliasSymbol -> symbol.classId?.shortClassName?.asString()
            is KaClassSymbol -> symbol.classId?.shortClassName?.asString()
            is KaConstructorSymbol -> symbol.containingClassId?.shortClassName?.asString()
            else -> null
        }
    } catch (_: Exception) { null }

    private fun renderSyntheticSignature(symbol: KaSymbol): String? = try {
        when (symbol) {
            is KaNamedFunctionSymbol -> buildString {
                append("fun ")
                append(symbol.name.asString())
                append("(")
                append(symbol.valueParameters.joinToString(", ") { p ->
                    "${p.name.asString()}: ${renderType(p.returnType)}"
                })
                append("): ")
                append(renderType(symbol.returnType))
            }
            is KaClassSymbol -> buildString {
                append(when (symbol.classKind) {
                    KaClassKind.INTERFACE -> "interface "
                    KaClassKind.ENUM_CLASS -> "enum class "
                    KaClassKind.OBJECT -> "object "
                    KaClassKind.COMPANION_OBJECT -> "companion object "
                    KaClassKind.ANNOTATION_CLASS -> "annotation class "
                    else -> "class "
                })
                append(symbol.classId?.asFqNameString() ?: symbolName(symbol) ?: "?")
            }
            is KaPropertySymbol -> buildString {
                append(if (symbol.isVal) "val " else "var ")
                append(symbol.name.asString())
                append(": ")
                append(renderType(symbol.returnType))
            }
            is KaConstructorSymbol -> buildString {
                val className = symbol.containingClassId?.shortClassName?.asString()
                if (className != null) {
                    append(className)
                } else {
                    append("constructor")
                }
                append("(")
                append(symbol.valueParameters.joinToString(", ") { p ->
                    "${p.name.asString()}: ${renderType(p.returnType)}"
                })
                append(")")
            }
            is KaLocalVariableSymbol -> buildString {
                append(if (symbol.isVal) "val " else "var ")
                append(symbol.name.asString())
                append(": ")
                append(renderType(symbol.returnType))
            }
            is KaValueParameterSymbol -> buildString {
                append(symbol.name.asString())
                append(": ")
                append(renderType(symbol.returnType))
            }
            else -> symbolName(symbol)
        }
    } catch (_: Exception) { null }

    /** Declaration keywords that identify the start of the actual signature. */
    private val declarationKeywordPattern = Regex(
        """^\s*(val|var|fun|class|interface|object|enum|typealias|constructor|abstract|open|override|private|protected|internal|public|lateinit|const|suspend|inline|data|sealed|annotation|inner|companion|expect|actual|external|tailrec|operator|infix|crossinline|noinline|reified|vararg)\b"""
    )

    /**
     * Extract the declaration signature line from PSI text, skipping leading
     * annotation blocks (including multi-line annotations with parentheses).
     * e.g. "@Inject\n@Named(\n  \"prefs\"\n)\nlateinit var x: T" → "lateinit var x: T"
     */
    private fun extractSignatureLine(psiText: String): String? {
        val lines = psiText.lines()
        // Find the first line that looks like a declaration keyword, not annotation content
        val declIndex = lines.indexOfFirst { declarationKeywordPattern.containsMatchIn(it) }
        if (declIndex >= 0) return lines[declIndex].trim().take(120)
        // Fallback: first non-empty, non-annotation line
        return (lines.firstOrNull { it.trim().let { t -> t.isNotEmpty() && !t.startsWith("@") } }
            ?: lines.firstOrNull())
            ?.trim()?.take(120)
    }

    /** Render a KaType to a clean Kotlin-syntax string with short names. */
    private fun renderType(type: KaType): String {
        val base = when (type) {
            is KaFunctionType -> {
                val prefix = if (type.isSuspend) "suspend " else ""
                val receiver = type.receiverType?.let { "${renderType(it)}." } ?: ""
                val params = type.parameterTypes.joinToString(", ") { renderType(it) }
                val ret = renderType(type.returnType)
                "$prefix${receiver}($params) -> $ret"
            }
            is KaClassType -> {
                val name = type.classId.shortClassName.asString()
                val args = type.typeArguments
                if (args.isEmpty()) name
                else "$name<${args.joinToString(", ") { renderTypeProjection(it) }}>"
            }
            is KaTypeParameterType -> type.name.asString()
            else -> type.toString()
        }
        return if (type.nullability == KaTypeNullability.NULLABLE) "$base?" else base
    }

    private fun renderTypeProjection(projection: KaTypeProjection): String = when (projection) {
        is KaStarTypeProjection -> "*"
        is KaTypeArgumentWithVariance -> {
            val v = when (projection.variance) {
                Variance.IN_VARIANCE -> "in "
                Variance.OUT_VARIANCE -> "out "
                else -> ""
            }
            "$v${renderType(projection.type)}"
        }
        else -> "*"
    }

    private fun kaTypeToTypeInfo(type: KaType): TypeInfo {
        val rendered = renderType(type)
        return TypeInfo(
            fqName = (type as? KaClassType)?.classId?.asFqNameString() ?: rendered,
            shortName = rendered,
            nullable = type.nullability == KaTypeNullability.NULLABLE,
            typeArguments = emptyList()
        )
    }

    // ==== CompilerFacade implementation ====

    override fun getDiagnostics(file: Path): List<DiagnosticInfo> {
        val ktFile = findKtFile(file) ?: return emptyList()
        val checkerFilter = KaDiagnosticCheckerFilter.EXTENDED_AND_COMMON_CHECKERS
        return try {
            runOnAnalysisThread {
                analyze(ktFile) {
                    val raw = ktFile.collectDiagnostics(checkerFilter)
                    raw.mapNotNull { diagnostic -> mapDiagnostic(diagnostic, file) }
                }
            }
        } catch (e: Throwable) {
            System.err.println("Diagnostics analysis failed for $file: ${e.message}")
            emptyList()
        }
    }

    override fun resolveAtPosition(file: Path, line: Int, column: Int): ResolvedSymbol? {
        val ktFile = findKtFile(file) ?: return null
        return try {
            runOnAnalysisThread {
                val element = findElementAt(ktFile, line, column)
                    ?: return@runOnAnalysisThread null
                // Handle annotation entries first — resolve to the annotation CLASS, not its constructor.
                // Check element itself and walk up a few levels (annotation PSI tree is 4-5 deep).
                val annotationEntry = run {
                    var cur: com.intellij.psi.PsiElement? = element
                    repeat(6) {
                        if (cur is KtAnnotationEntry) return@run cur as KtAnnotationEntry
                        cur = cur?.parent
                    }
                    null
                }
                if (annotationEntry != null) {
                    val result = try {
                        analyze(annotationEntry) {
                            val type = annotationEntry.typeReference?.type as? KaClassType
                                ?: return@analyze null
                            val classSymbol = type.symbol as? KaClassSymbol
                                ?: return@analyze null
                            val sig = renderSyntheticSignature(classSymbol)
                            val name = classSymbol.classId?.shortClassName?.asString() ?: "unknown"
                            val psi = try { classSymbol.psi } catch (_: Exception) { null }
                            val loc = psi?.let { psiToSourceLocation(it) }
                            ResolvedSymbol(
                                name = name,
                                kind = mapKaSymbolKind(classSymbol),
                                location = loc ?: SourceLocation(file, line, column),
                                containingClass = null,
                                signature = sig,
                                fqName = classSymbol.classId?.asFqNameString()
                            )
                        }
                    } catch (_: Exception) { null }
                    if (result != null) return@runOnAnalysisThread result
                }

                val ref = element as? KtReferenceExpression
                    ?: (element.parent as? KtReferenceExpression)
                if (ref != null) {
                    analyze(ref) {
                        val allRefs = ref.references
                        val ktRef = allRefs.filterIsInstance<org.jetbrains.kotlin.idea.references.KtReference>().firstOrNull()
                            ?: return@analyze null
                        val symbols = ktRef.resolveToSymbols()
                        val symbol = symbols.firstOrNull() ?: return@analyze null
                        val psi = symbol.psi
                        // psi.text crashes on compiled class elements (ClsElementImpl)
                        // with NPE in ClsFileImpl.getMirror(). Use try-catch to detect this.
                        val psiText = try { psi?.text } catch (_: Exception) { null }
                        if (psi != null && psiText != null) {
                            val loc = psiToSourceLocation(psi) ?: return@analyze null
                            ResolvedSymbol(
                                name = (psi as? KtNamedDeclaration)?.name
                                    ?: extractSignatureLine(psiText)?.take(40)
                                    ?: "unknown",
                                kind = mapKaSymbolKind(symbol),
                                location = loc,
                                containingClass = (psi.parent as? KtClassOrObject)?.name,
                                signature = extractSignatureLine(psiText),
                                fqName = null
                            )
                        } else {
                            // Library type — no readable PSI source available
                            val sig = renderSyntheticSignature(symbol)
                            val name = symbolName(symbol) ?: "unknown"
                            ResolvedSymbol(
                                name = name,
                                kind = mapKaSymbolKind(symbol),
                                location = SourceLocation(file, line, column),
                                containingClass = null,
                                signature = sig,
                                fqName = null
                            )
                        }
                    }
                } else {
                    // Not a reference — check if it's a declaration (val, fun, class, etc.)
                    val decl = element as? KtNamedDeclaration
                        ?: (element.parent as? KtNamedDeclaration)
                    if (decl != null) {
                        val loc = psiToSourceLocation(decl) ?: return@runOnAnalysisThread null
                        val kind = when (decl) {
                            is KtClass -> when {
                                decl.isInterface() -> SymbolKind.INTERFACE
                                decl.isEnum() -> SymbolKind.ENUM
                                else -> SymbolKind.CLASS
                            }
                            is KtObjectDeclaration -> SymbolKind.OBJECT
                            is KtNamedFunction -> SymbolKind.FUNCTION
                            is KtProperty -> SymbolKind.PROPERTY
                            is KtParameter -> SymbolKind.PARAMETER
                            is KtTypeAlias -> SymbolKind.TYPE_ALIAS
                            else -> SymbolKind.PROPERTY
                        }
                        // For callable declarations without explicit type annotation,
                        // use the Analysis API to produce a signature with the inferred type
                        val signature = if (decl is KtCallableDeclaration && decl.typeReference == null) {
                            try {
                                analyze(decl) {
                                    renderSyntheticSignature(decl.symbol)
                                }
                            } catch (_: Exception) { null }
                        } else null
                        ResolvedSymbol(
                            name = decl.name ?: "unknown",
                            kind = kind,
                            location = loc,
                            containingClass = (decl.parent as? KtClassOrObject)?.name,
                            signature = signature ?: decl.text?.let { extractSignatureLine(it) },
                            fqName = (decl as? KtNamedDeclaration)?.fqName?.asString()
                        )
                    } else null
                }
            }
        } catch (e: Exception) {
            System.err.println("resolveAtPosition failed for $file:$line:$column: ${e.message}")
            null
        }
    }

    override fun getType(file: Path, line: Int, column: Int): TypeInfo? {
        val ktFile = findKtFile(file) ?: return null
        return try {
            runOnAnalysisThread {
                val element = findElementAt(ktFile, line, column) ?: return@runOnAnalysisThread null

                // For declarations (val, var, fun, parameter), get the declared/inferred type
                val decl = element as? KtCallableDeclaration
                    ?: (element.parent as? KtCallableDeclaration)
                if (decl != null) {
                    return@runOnAnalysisThread analyze(decl) {
                        val symbol = decl.symbol as? KaCallableSymbol ?: return@analyze null
                        kaTypeToTypeInfo(symbol.returnType)
                    }
                }

                // For expressions, get the expression type
                val expr = element as? KtExpression ?: (element.parent as? KtExpression)
                    ?: return@runOnAnalysisThread null
                analyze(expr) {
                    val type = expr.expressionType ?: return@analyze null
                    kaTypeToTypeInfo(type)
                }
            }
        } catch (e: Exception) {
            System.err.println("getType failed for $file:$line:$column: ${e.message}")
            null
        }
    }

    override fun getDocumentation(symbol: ResolvedSymbol): String? {
        // KDoc extraction from resolved symbol location
        val ktFile = findKtFile(symbol.location.path) ?: return null
        val element = findElementAt(ktFile, symbol.location.line, symbol.location.column)
        val declaration = element as? KtDeclaration
            ?: element?.parent as? KtDeclaration
            ?: return null
        return declaration.docComment?.text
    }

    override fun getFileSymbols(file: Path): List<ResolvedSymbol> {
        synchronized(symbolCache) {
            symbolCache[file]?.let { return it }
        }
        val ktFile = findKtFile(file) ?: return emptyList()
        return try {
            val symbols = runOnAnalysisThread {
                analyze(ktFile) {
                    val result = mutableListOf<ResolvedSymbol>()
                    ktFile.declarations.forEach { decl ->
                        collectDeclarationSymbols(decl, file, result)
                    }
                    result
                }
            }
            synchronized(symbolCache) {
                symbolCache[file] = symbols
            }
            symbols
        } catch (e: Exception) {
            System.err.println("getFileSymbols failed for $file: ${e.message}")
            emptyList()
        }
    }

    private fun collectDeclarationSymbols(decl: KtDeclaration, file: Path, result: MutableList<ResolvedSymbol>) {
        val loc = psiToSourceLocation(decl) ?: return
        val name = (decl as? KtNamedDeclaration)?.name ?: return
        val kind = when (decl) {
            is KtClass -> when {
                decl.isInterface() -> SymbolKind.INTERFACE
                decl.isEnum() -> SymbolKind.ENUM
                else -> SymbolKind.CLASS
            }
            is KtObjectDeclaration -> SymbolKind.OBJECT
            is KtNamedFunction -> SymbolKind.FUNCTION
            is KtProperty -> SymbolKind.PROPERTY
            is KtTypeAlias -> SymbolKind.TYPE_ALIAS
            else -> SymbolKind.PROPERTY
        }

        result.add(ResolvedSymbol(
            name = name,
            kind = kind,
            location = loc,
            containingClass = (decl.parent as? KtClassOrObject)?.name,
            signature = decl.text?.lines()?.firstOrNull()?.take(120),
            fqName = (decl as? KtNamedDeclaration)?.fqName?.asString()
        ))

        // Recurse into class/object bodies
        if (decl is KtClassOrObject) {
            decl.declarations.forEach { child ->
                collectDeclarationSymbols(child, file, result)
            }
        }
    }

    override fun findReferences(symbol: ResolvedSymbol): List<SourceLocation> {
        // Simple text-based search + resolve confirmation across all project files
        val results = mutableListOf<SourceLocation>()
        for (ktFile in allKtFiles()) {
            val file = ktFile.virtualFile?.path?.let { Path.of(it) } ?: continue
            val text = ktFile.text
            var searchFrom = 0
            while (true) {
                val idx = text.indexOf(symbol.name, searchFrom)
                if (idx == -1) break
                searchFrom = idx + 1

                val document = ktFile.viewProvider.document ?: continue
                val line = document.getLineNumber(idx)
                val col = idx - document.getLineStartOffset(line)

                val resolved = resolveAtPosition(file, line, col)
                if (resolved != null && resolved.location == symbol.location) {
                    results.add(SourceLocation(file, line, col))
                }
            }
        }
        return results
    }

    override fun findImplementations(symbol: ResolvedSymbol): List<SourceLocation> {
        if (symbol.kind != SymbolKind.INTERFACE && symbol.kind != SymbolKind.CLASS) {
            return emptyList()
        }
        return try {
            runOnAnalysisThread {
                val results = mutableListOf<SourceLocation>()
                for (ktFile in allKtFiles()) {
                    collectImplementations(ktFile, symbol, results)
                }
                results
            }
        } catch (e: Exception) {
            System.err.println("findImplementations failed for ${symbol.name}: ${e.message}")
            emptyList()
        }
    }

    private fun collectImplementations(
        ktFile: KtFile,
        target: ResolvedSymbol,
        results: MutableList<SourceLocation>
    ) {
        for (decl in ktFile.declarations) {
            collectImplsRecursive(decl, target, results)
        }
    }

    private fun collectImplsRecursive(
        decl: KtDeclaration,
        target: ResolvedSymbol,
        results: MutableList<SourceLocation>
    ) {
        if (decl is KtClassOrObject) {
            // Cheap text filter: check supertype list for target name
            val superNames = decl.superTypeListEntries.mapNotNull {
                it.typeReference?.text?.substringAfterLast('.')
            }
            if (superNames.any { it == target.name }) {
                // Confirm via Analysis API
                val confirmed = try {
                    analyze(decl) {
                        val classSymbol = decl.symbol as? KaClassSymbol ?: return@analyze false
                        classSymbol.superTypes.any { superType ->
                            val superClass = (superType as? KaClassType)?.symbol as? KaClassSymbol ?: return@any false
                            val superFqName = superClass.classId?.asFqNameString()
                            if (target.fqName != null && superFqName != null) {
                                superFqName == target.fqName
                            } else {
                                val superPsi = superClass.psi
                                if (superPsi != null) {
                                    psiToSourceLocation(superPsi) == target.location
                                } else false
                            }
                        }
                    }
                } catch (_: Exception) { false }

                if (confirmed) {
                    psiToSourceLocation(decl)?.let { results.add(it) }
                }
            }
            // Recurse into nested classes
            decl.declarations.forEach { child ->
                collectImplsRecursive(child, target, results)
            }
        }
    }

    override fun getTypeDefinitionLocation(file: Path, line: Int, column: Int): SourceLocation? {
        val ktFile = findKtFile(file) ?: return null
        return try {
            runOnAnalysisThread {
                val element = findElementAt(ktFile, line, column) ?: return@runOnAnalysisThread null
                analyze(ktFile) {
                    val kaType: KaType? = run {
                        val decl = element as? KtCallableDeclaration
                            ?: (element.parent as? KtCallableDeclaration)
                        if (decl != null) {
                            return@run (decl.symbol as? KaCallableSymbol)?.returnType
                        }
                        val expr = element as? KtExpression
                            ?: (element.parent as? KtExpression)
                        expr?.expressionType
                    }
                    if (kaType == null) return@analyze null
                    val classSymbol = (kaType as? KaClassType)?.symbol as? KaClassSymbol ?: return@analyze null
                    val psi = classSymbol.psi ?: return@analyze null
                    // Guard against compiled class PSI (ClsElementImpl)
                    try { psi.text } catch (_: Exception) { return@analyze null }
                    psiToSourceLocation(psi)
                }
            }
        } catch (e: Exception) {
            System.err.println("getTypeDefinitionLocation failed for $file:$line:$column: ${e.message}")
            null
        }
    }

    override fun getCompletions(file: Path, line: Int, column: Int): List<CompletionCandidate> {
        val ktFile = findKtFile(file) ?: return emptyList()
        // Use current buffer for prefix/dot detection (PSI document is stale between saves)
        val bufferText = fileContents[file]
            ?: ktFile.viewProvider.document?.charsSequence?.toString()
            ?: return emptyList()
        return try {
            runOnAnalysisThread {
                analyze(ktFile) {
                    val prefix = extractPrefixFromBuffer(bufferText, line, column)
                    val seen = mutableSetOf<String>()
                    val candidates = mutableListOf<CompletionCandidate>()
                    val limit = 150

                    val receiverName = detectDotReceiver(bufferText, line, column)
                    if (receiverName != null) {
                        collectMemberCompletionsByName(ktFile, receiverName, prefix, seen, candidates, limit)
                    } else {
                        val element = findElementAt(ktFile, line, column) ?: ktFile
                        collectScopeCompletions(element, prefix, seen, candidates, limit)
                    }

                    candidates
                }
            }
        } catch (e: Exception) {
            System.err.println("[completion] FAILED for $file:$line:$column: ${e.message}")
            emptyList()
        }
    }

    // ---- Completion helpers ----

    /** Extract the identifier prefix from the current buffer text at cursor position. */
    private fun extractPrefixFromBuffer(text: String, line: Int, column: Int): String {
        var lineStart = 0
        for (i in 0 until line) {
            val nl = text.indexOf('\n', lineStart)
            if (nl == -1) return ""
            lineStart = nl + 1
        }
        val cursorOffset = (lineStart + column).coerceAtMost(text.length)
        var start = cursorOffset
        while (start > lineStart && (text[start - 1].isLetterOrDigit() || text[start - 1] == '_')) {
            start--
        }
        return text.substring(start, cursorOffset)
    }

    /**
     * Detect if the cursor is after a dot and return the receiver name.
     * Uses current buffer text, not stale PSI. Returns null if not dot completion.
     */
    private fun detectDotReceiver(text: String, line: Int, column: Int): String? {
        var lineStart = 0
        for (i in 0 until line) {
            val nl = text.indexOf('\n', lineStart)
            if (nl == -1) return null
            lineStart = nl + 1
        }
        val cursorOffset = (lineStart + column).coerceAtMost(text.length)

        // Walk backwards past identifier prefix
        var pos = cursorOffset
        while (pos > lineStart && (text[pos - 1].isLetterOrDigit() || text[pos - 1] == '_')) {
            pos--
        }

        // Check for dot (or safe-access ?.)
        if (pos > 0 && text[pos - 1] == '.') {
            val beforeDot = pos - 1
            // Skip ?. safe access
            val nameEnd = if (beforeDot > 0 && text[beforeDot - 1] == '?') beforeDot - 1 else beforeDot
            // Extract identifier before the dot
            var nameStart = nameEnd
            while (nameStart > 0 && (text[nameStart - 1].isLetterOrDigit() || text[nameStart - 1] == '_')) {
                nameStart--
            }
            val name = text.substring(nameStart, nameEnd)
            if (name.isNotEmpty()) return name
        }

        return null
    }

    private fun kaSymbolToCandidate(symbol: KaSymbol, name: String, priority: Int): CompletionCandidate {
        val kind = mapKaSymbolKind(symbol)
        val detail = try { renderSyntheticSignature(symbol) } catch (_: Exception) { null }
        val deprecated = false
        val insertText = when (symbol) {
            is KaNamedFunctionSymbol -> {
                if (symbol.valueParameters.isEmpty()) "$name()" else "$name("
            }
            is KaConstructorSymbol -> {
                if (symbol.valueParameters.isEmpty()) "$name()" else "$name("
            }
            else -> name
        }
        return CompletionCandidate(
            label = name,
            kind = kind,
            detail = detail,
            insertText = insertText,
            isDeprecated = deprecated,
            sortPriority = if (deprecated) 9 else priority
        )
    }

    /**
     * Identifier completion: use scopeContext to enumerate all visible symbols
     * (locals, file-level, imports, implicit imports like kotlin.*, java.lang.*, etc.)
     */
    @OptIn(KaExperimentalApi::class)
    private fun org.jetbrains.kotlin.analysis.api.KaSession.collectScopeCompletions(
        element: com.intellij.psi.PsiElement,
        prefix: String,
        seen: MutableSet<String>,
        candidates: MutableList<CompletionCandidate>,
        limit: Int
    ) {
        val ktElement = element as? KtElement ?: return
        val nameFilter: (org.jetbrains.kotlin.name.Name) -> Boolean = if (prefix.isEmpty()) {
            { true }
        } else {
            { name -> name.asString().startsWith(prefix, ignoreCase = true) }
        }

        val scopeCtx = try {
            ktElement.containingKtFile.scopeContext(ktElement)
        } catch (e: Exception) {
            System.err.println("[completion] scopeContext failed: ${e.message}")
            return
        }

        for (scopeWithKind in scopeCtx.scopes) {
            if (candidates.size >= limit) break

            val priority = scopePriority(scopeWithKind.kind)

            // Skip library/import scopes when prefix is empty (too many results, not useful)
            if (priority >= 2 && prefix.isEmpty()) continue

            val scope = scopeWithKind.scope

            for (symbol in scope.callables(nameFilter)) {
                if (candidates.size >= limit) break
                val name = symbolName(symbol) ?: continue
                if (name.startsWith("<")) continue
                if (!seen.add(name)) continue
                candidates.add(kaSymbolToCandidate(symbol, name, priority))
            }

            for (symbol in scope.classifiers(nameFilter)) {
                if (candidates.size >= limit) break
                val name = symbolName(symbol) ?: continue
                if (!seen.add(name)) continue
                candidates.add(kaSymbolToCandidate(symbol, name, priority))
            }
        }
    }

    /**
     * Dot/member completion: find a declaration by name in PSI, resolve its type,
     * and enumerate members. Works even when PSI is stale (between saves).
     */
    private fun org.jetbrains.kotlin.analysis.api.KaSession.collectMemberCompletionsByName(
        ktFile: KtFile,
        receiverName: String,
        prefix: String,
        seen: MutableSet<String>,
        candidates: MutableList<CompletionCandidate>,
        limit: Int
    ) {
        // Find the declaration by name in the PSI (searches local scope, file scope, etc.)
        val decl = findDeclarationByName(ktFile, receiverName) ?: return
        val type = try {
            (decl.symbol as? KaCallableSymbol)?.returnType ?: return
        } catch (_: Exception) { return }

        val classSymbol = (type as? KaClassType)?.symbol as? KaClassSymbol ?: return
        val memberScope = try {
            classSymbol.combinedMemberScope
        } catch (e: Exception) {
            System.err.println("[completion] combinedMemberScope failed: ${e.message}")
            return
        }

        val nameFilter: (org.jetbrains.kotlin.name.Name) -> Boolean = if (prefix.isEmpty()) {
            { true }
        } else {
            { name -> name.asString().startsWith(prefix, ignoreCase = true) }
        }

        for (symbol in memberScope.callables(nameFilter)) {
            if (candidates.size >= limit) break
            val name = symbolName(symbol) ?: continue
            if (name.startsWith("<")) continue
            if (!seen.add(name)) continue
            candidates.add(kaSymbolToCandidate(symbol, name, 0))
        }
    }

    /** Search a KtFile's PSI tree for a callable declaration matching the given name. */
    private fun findDeclarationByName(ktFile: KtFile, name: String): KtCallableDeclaration? {
        // Walk all declarations recursively looking for the name
        fun search(declarations: List<KtDeclaration>): KtCallableDeclaration? {
            for (decl in declarations) {
                if (decl is KtCallableDeclaration && (decl as? KtNamedDeclaration)?.name == name) {
                    return decl
                }
                if (decl is KtClassOrObject) {
                    search(decl.declarations)?.let { return it }
                }
                // Search inside function bodies for local declarations
                if (decl is KtDeclarationWithBody) {
                    val body = decl.bodyBlockExpression ?: continue
                    for (stmt in body.statements) {
                        if (stmt is KtCallableDeclaration && (stmt as? KtNamedDeclaration)?.name == name) {
                            return stmt
                        }
                    }
                }
            }
            return null
        }
        return search(ktFile.declarations)
    }

    @OptIn(KaExperimentalApi::class)
    private fun scopePriority(kind: org.jetbrains.kotlin.analysis.api.components.KaScopeKind): Int = when (kind) {
        is org.jetbrains.kotlin.analysis.api.components.KaScopeKind.LocalScope -> 0
        is org.jetbrains.kotlin.analysis.api.components.KaScopeKind.TypeScope -> 1
        is org.jetbrains.kotlin.analysis.api.components.KaScopeKind.PackageMemberScope -> 1
        is org.jetbrains.kotlin.analysis.api.components.KaScopeKind.StaticMemberScope -> 1
        is org.jetbrains.kotlin.analysis.api.components.KaScopeKind.ScriptMemberScope -> 1
        is org.jetbrains.kotlin.analysis.api.components.KaScopeKind.TypeParameterScope -> 1
        is org.jetbrains.kotlin.analysis.api.components.KaScopeKind.ExplicitSimpleImportingScope -> 2
        is org.jetbrains.kotlin.analysis.api.components.KaScopeKind.ExplicitStarImportingScope -> 2
        is org.jetbrains.kotlin.analysis.api.components.KaScopeKind.DefaultSimpleImportingScope -> 3
        is org.jetbrains.kotlin.analysis.api.components.KaScopeKind.DefaultStarImportingScope -> 3
    }

    override fun prepareRename(file: Path, line: Int, column: Int): RenameContext? {
        val ktFile = findKtFile(file) ?: return null
        return try {
            runOnAnalysisThread {
                val element = findElementAt(ktFile, line, column)
                    ?: return@runOnAnalysisThread null

                // Find the declaration: directly or via reference resolution
                val declaration: KtNamedDeclaration? = run {
                    val directDecl = element as? KtNamedDeclaration
                        ?: (element.parent as? KtNamedDeclaration)
                    if (directDecl != null) return@run directDecl

                    val ref = element as? KtReferenceExpression
                        ?: (element.parent as? KtReferenceExpression)
                    if (ref != null) {
                        analyze(ref) {
                            val ktRef = ref.references
                                .filterIsInstance<org.jetbrains.kotlin.idea.references.KtReference>()
                                .firstOrNull() ?: return@analyze null
                            val symbol = ktRef.resolveToSymbols().firstOrNull() ?: return@analyze null
                            symbol.psi as? KtNamedDeclaration
                        }
                    } else null
                }
                if (declaration == null) return@runOnAnalysisThread null

                val nameIdentifier = declaration.nameIdentifier ?: return@runOnAnalysisThread null
                val name = declaration.name ?: return@runOnAnalysisThread null
                if (declaration is KtPackageDirective) return@runOnAnalysisThread null

                val document = declaration.containingFile?.viewProvider?.document
                    ?: return@runOnAnalysisThread null
                val filePath = declaration.containingFile?.virtualFile?.path?.let { Path.of(it) }
                    ?: return@runOnAnalysisThread null

                val startOffset = nameIdentifier.textOffset
                val endOffset = startOffset + name.length
                val startLine = document.getLineNumber(startOffset)
                val startCol = startOffset - document.getLineStartOffset(startLine)
                val endLine = document.getLineNumber(endOffset)
                val endCol = endOffset - document.getLineStartOffset(endLine)

                val range = SourceRange(filePath, startLine, startCol, endLine, endCol)
                val loc = SourceLocation(filePath, startLine, startCol)

                val kind = when (declaration) {
                    is KtClass -> when {
                        declaration.isInterface() -> SymbolKind.INTERFACE
                        declaration.isEnum() -> SymbolKind.ENUM
                        else -> SymbolKind.CLASS
                    }
                    is KtObjectDeclaration -> SymbolKind.OBJECT
                    is KtNamedFunction -> SymbolKind.FUNCTION
                    is KtProperty -> SymbolKind.PROPERTY
                    is KtParameter -> SymbolKind.PARAMETER
                    is KtTypeAlias -> SymbolKind.TYPE_ALIAS
                    else -> SymbolKind.PROPERTY
                }

                RenameContext(
                    symbol = ResolvedSymbol(
                        name = name,
                        kind = kind,
                        location = loc,
                        containingClass = (declaration.parent as? KtClassOrObject)?.name,
                        signature = declaration.text?.lines()?.firstOrNull()?.take(120),
                        fqName = declaration.fqName?.asString()
                    ),
                    range = range
                )
            }
        } catch (e: Exception) {
            System.err.println("prepareRename failed for $file:$line:$column: ${e.message}")
            null
        }
    }

    override fun computeRename(context: RenameContext, newName: String): List<FileEdit> {
        return try {
            val references = findReferences(context.symbol)
            val edits = mutableListOf<FileEdit>()

            // Include the declaration itself
            edits.add(FileEdit(context.range.path, context.range, newName))

            // Include all references (skip if same as declaration)
            val declRange = context.range
            for (ref in references) {
                if (ref.path == declRange.path && ref.line == declRange.startLine && ref.column == declRange.startColumn) {
                    continue
                }
                val oldName = context.symbol.name
                val refRange = SourceRange(ref.path, ref.line, ref.column, ref.line, ref.column + oldName.length)
                edits.add(FileEdit(ref.path, refRange, newName))
            }
            edits
        } catch (e: Exception) {
            System.err.println("computeRename failed for ${context.symbol.name}: ${e.message}")
            emptyList()
        }
    }

    override fun updateFileContent(file: Path, content: String) {
        fileContents[file] = content
        synchronized(symbolCache) {
            symbolCache.remove(file)
        }
    }

    override fun findExpectActualCounterparts(file: Path, line: Int, column: Int): List<ResolvedSymbol> {
        val ktFile = findKtFile(file) ?: return emptyList()
        return try {
            runOnAnalysisThread {
                val element = findElementAt(ktFile, line, column) ?: return@runOnAnalysisThread emptyList()
                val decl = element as? KtNamedDeclaration
                    ?: (element.parent as? KtNamedDeclaration)
                    ?: return@runOnAnalysisThread emptyList()

                val fqName = decl.fqName?.asString() ?: return@runOnAnalysisThread emptyList()
                val isExpect = decl.hasModifier(org.jetbrains.kotlin.lexer.KtTokens.EXPECT_KEYWORD)
                val isActual = decl.hasModifier(org.jetbrains.kotlin.lexer.KtTokens.ACTUAL_KEYWORD)
                if (!isExpect && !isActual) return@runOnAnalysisThread emptyList()

                val currentPlatform = kmpPlatformForFile(file)

                if (isExpect) {
                    // Find actuals in all OTHER sessions
                    sessions.flatMap { (platform, targetSession) ->
                        if (platform == currentPlatform) emptyList()
                        else findDeclarationsInSession(targetSession, fqName, hasExpect = false)
                    }
                } else {
                    // Find expect in the common/primary session (prefer JVM as primary for common files)
                    val primaryPlatform = sessions.keys.firstOrNull { it == KmpPlatform.JVM }
                        ?: sessions.keys.firstOrNull { it == KmpPlatform.ANDROID }
                        ?: sessions.keys.firstOrNull()
                    val primarySession = primaryPlatform?.let { sessions[it] }
                    if (primarySession != null) {
                        findDeclarationsInSession(primarySession, fqName, hasExpect = true)
                    } else emptyList()
                }
            }
        } catch (e: Exception) {
            System.err.println("findExpectActualCounterparts failed for $file:$line:$column: ${e.message}")
            emptyList()
        }
    }

    private fun findDeclarationsInSession(
        targetSession: org.jetbrains.kotlin.analysis.api.standalone.StandaloneAnalysisAPISession,
        fqName: String,
        hasExpect: Boolean
    ): List<ResolvedSymbol> {
        val results = mutableListOf<ResolvedSymbol>()
        val ktFiles = targetSession.modulesWithFiles.values.flatten().filterIsInstance<KtFile>()
        for (ktFile in ktFiles) {
            for (decl in ktFile.declarations) {
                findMatchingDeclarations(decl, fqName, hasExpect, results)
            }
        }
        return results
    }

    private fun findMatchingDeclarations(
        decl: KtDeclaration,
        fqName: String,
        hasExpect: Boolean,
        results: MutableList<ResolvedSymbol>
    ) {
        if (decl is KtNamedDeclaration) {
            val declFqName = decl.fqName?.asString()
            if (declFqName == fqName) {
                val matchesModifier = if (hasExpect) {
                    decl.hasModifier(org.jetbrains.kotlin.lexer.KtTokens.EXPECT_KEYWORD)
                } else {
                    decl.hasModifier(org.jetbrains.kotlin.lexer.KtTokens.ACTUAL_KEYWORD)
                }
                if (matchesModifier) {
                    val loc = psiToSourceLocation(decl)
                    if (loc != null) {
                        val kind = when (decl) {
                            is KtClass -> when {
                                decl.isInterface() -> SymbolKind.INTERFACE
                                decl.isEnum() -> SymbolKind.ENUM
                                else -> SymbolKind.CLASS
                            }
                            is KtObjectDeclaration -> SymbolKind.OBJECT
                            is KtNamedFunction -> SymbolKind.FUNCTION
                            is KtProperty -> SymbolKind.PROPERTY
                            is KtTypeAlias -> SymbolKind.TYPE_ALIAS
                            else -> SymbolKind.PROPERTY
                        }
                        results.add(ResolvedSymbol(
                            name = decl.name ?: "unknown",
                            kind = kind,
                            location = loc,
                            containingClass = (decl.parent as? KtClassOrObject)?.name,
                            signature = decl.text?.let { extractSignatureLine(it) },
                            fqName = declFqName
                        ))
                    }
                }
            }
        }
        // Recurse into class/object bodies
        if (decl is KtClassOrObject) {
            decl.declarations.forEach { child ->
                findMatchingDeclarations(child, fqName, hasExpect, results)
            }
        }
    }

    override fun refreshAnalysis() {
        // The standalone Analysis API's PSI/FIR are immutable after session creation.
        // The only way to pick up file changes is to rebuild all sessions from disk.
        synchronized(symbolCache) {
            symbolCache.clear()
        }
        try {
            runOnAnalysisThread {
                // Release old sessions to free memory before building new ones
                sessions = emptyMap()
                System.gc()
                sessions = buildSessions()
            }
        } catch (e: Exception) {
            System.err.println("Session rebuild failed: ${e.message}")
        }
    }

    override fun dispose() {
        analysisThread.shutdownNow()
    }

    private fun <T> runOnAnalysisThread(block: () -> T): T {
        return analysisThread.submit(block).get()
    }

    private fun mapDiagnostic(diagnostic: KaDiagnosticWithPsi<*>, file: Path): DiagnosticInfo? {
        val psi = diagnostic.psi
        val textRange = psi.textRange ?: return null
        val document = psi.containingFile?.viewProvider?.document ?: return null

        val startLine = document.getLineNumber(textRange.startOffset)
        val startCol = textRange.startOffset - document.getLineStartOffset(startLine)
        val endLine = document.getLineNumber(textRange.endOffset)
        val endCol = textRange.endOffset - document.getLineStartOffset(endLine)

        val range = SourceRange(file, startLine, startCol, endLine, endCol)
        val quickFixes = generateQuickFixes(diagnostic, psi, file, document)

        // Enhance unresolved reference messages in Android projects missing generated sources
        val message = if (androidMissingGenerated && diagnostic.factoryName == "UNRESOLVED_REFERENCE") {
            "${diagnostic.defaultMessage} (may be a generated class — run: ./gradlew generateDebugResources generateDebugBuildConfig --continue)"
        } else {
            diagnostic.defaultMessage
        }

        return DiagnosticInfo(
            severity = mapSeverity(diagnostic.severity),
            message = message,
            range = range,
            code = diagnostic.factoryName,
            quickFixes = quickFixes
        )
    }

    private fun generateQuickFixes(
        diagnostic: KaDiagnosticWithPsi<*>,
        psi: com.intellij.psi.PsiElement,
        file: Path,
        document: com.intellij.openapi.editor.Document
    ): List<QuickFix> = try {
        when (diagnostic.factoryName) {
            "UNUSED_VARIABLE", "UNUSED_PARAMETER" -> {
                val decl = psi as? KtNamedDeclaration ?: (psi.parent as? KtNamedDeclaration)
                val name = decl?.name
                val nameId = decl?.nameIdentifier
                if (name != null && !name.startsWith("_") && nameId != null) {
                    val offset = nameId.textOffset
                    val endOffset = offset + name.length
                    val sLine = document.getLineNumber(offset)
                    val sCol = offset - document.getLineStartOffset(sLine)
                    val eLine = document.getLineNumber(endOffset)
                    val eCol = endOffset - document.getLineStartOffset(eLine)
                    listOf(QuickFix(
                        title = "Rename to '_$name'",
                        edits = listOf(FileEdit(file, SourceRange(file, sLine, sCol, eLine, eCol), "_$name"))
                    ))
                } else emptyList()
            }
            "REDUNDANT_NULLABLE" -> {
                val text = try { psi.text } catch (_: Exception) { null }
                if (text != null && text.endsWith("?")) {
                    val sLine = document.getLineNumber(psi.textRange.startOffset)
                    val sCol = psi.textRange.startOffset - document.getLineStartOffset(sLine)
                    val eLine = document.getLineNumber(psi.textRange.endOffset)
                    val eCol = psi.textRange.endOffset - document.getLineStartOffset(eLine)
                    listOf(QuickFix(
                        title = "Remove redundant '?'",
                        edits = listOf(FileEdit(file, SourceRange(file, sLine, sCol, eLine, eCol), text.dropLast(1)))
                    ))
                } else emptyList()
            }
            else -> emptyList()
        }
    } catch (_: Exception) { emptyList() }

    private fun mapSeverity(severity: KaSeverity): Severity = when (severity) {
        KaSeverity.ERROR -> Severity.ERROR
        KaSeverity.WARNING -> Severity.WARNING
        KaSeverity.INFO -> Severity.INFO
    }
}

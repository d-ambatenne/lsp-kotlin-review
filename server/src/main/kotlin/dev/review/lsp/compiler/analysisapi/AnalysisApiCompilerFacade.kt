package dev.review.lsp.compiler.analysisapi

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
    private var _session: org.jetbrains.kotlin.analysis.api.standalone.StandaloneAnalysisAPISession? = null

    private val session: org.jetbrains.kotlin.analysis.api.standalone.StandaloneAnalysisAPISession
        get() {
            var s = _session
            if (s == null) {
                s = buildSession()
                _session = s
            }
            return s
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

    private fun buildSession(): org.jetbrains.kotlin.analysis.api.standalone.StandaloneAnalysisAPISession {
        // Deduplicate classpath jars across all modules
        val rawClasspath = projectModel.modules.flatMap { it.classpath }.distinct()

        // Extract classes.jar from AAR files (Android Archive bundles)
        val allClasspath = rawClasspath.flatMap { entry ->
            if (entry.toString().endsWith(".aar")) {
                val extracted = extractClassesFromAar(entry)
                if (extracted != null) listOf(extracted) else emptyList()
            } else {
                listOf(entry)
            }
        }
        System.err.println("[session] Classpath: ${rawClasspath.size} entries (${rawClasspath.count { it.toString().endsWith(".aar") }} AARs extracted), ${allClasspath.size} JARs total")

        return buildStandaloneAnalysisAPISession {
            buildKtModuleProvider {
                platform = JvmPlatforms.defaultJvmPlatform

                val libraryModules = allClasspath.map { jar ->
                    addModule(buildKtLibraryModule {
                        this.platform = JvmPlatforms.defaultJvmPlatform
                        libraryName = jar.fileName.toString()
                        addBinaryRoot(jar)
                    })
                }

                val jdkModule = addModule(buildKtSdkModule {
                    this.platform = JvmPlatforms.defaultJvmPlatform
                    libraryName = "jdk"
                    addBinaryRootsFromJdkHome(
                        Path.of(System.getProperty("java.home")),
                        isJre = true
                    )
                })

                // Merge all source roots into a single module for cross-module resolution.
                // The standalone Analysis API doesn't support inter-module dependencies
                // without complex dependency graph wiring. A single merged module gives
                // full cross-module navigation/completion for code review purposes.
                val allSourceRoots = projectModel.modules.flatMap { it.sourceRoots }.distinct()
                addModule(buildKtSourceModule {
                    moduleName = projectModel.modules.firstOrNull()?.name ?: "sources"
                    this.platform = JvmPlatforms.defaultJvmPlatform
                    for (root in allSourceRoots) {
                        addSourceRoot(root)
                    }
                    addRegularDependency(jdkModule)
                    for (lib in libraryModules) {
                        addRegularDependency(lib)
                    }
                })
            }
        }
    }

    private fun allKtFiles(): List<KtFile> =
        session.modulesWithFiles.values
            .flatten()
            .filterIsInstance<KtFile>()

    private fun findKtFile(file: Path): KtFile? =
        allKtFiles().find { ktFile ->
            val ktPath = ktFile.virtualFile?.path?.let { Path.of(it) }
            ktPath != null && (ktPath == file || ktPath.normalize() == file.normalize())
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
        return try {
            runOnAnalysisThread {
                analyze(ktFile) {
                    val raw = ktFile.collectDiagnostics(KaDiagnosticCheckerFilter.EXTENDED_AND_COMMON_CHECKERS)
                    raw.mapNotNull { diagnostic -> mapDiagnostic(diagnostic, file) }
                }
            }
        } catch (e: Exception) {
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
                val ref = element as? KtReferenceExpression ?: (element.parent as? KtReferenceExpression)
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
                                    ?: psiText.lines().firstOrNull()?.trim()?.take(40)
                                    ?: "unknown",
                                kind = mapKaSymbolKind(symbol),
                                location = loc,
                                containingClass = (psi.parent as? KtClassOrObject)?.name,
                                signature = psiText.lines().firstOrNull()?.take(120),
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
                            signature = signature ?: decl.text?.lines()?.firstOrNull()?.take(120),
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

    override fun refreshAnalysis() {
        // The standalone Analysis API's PSI/FIR are immutable after session creation.
        // The only way to pick up file changes is to rebuild the entire session from disk.
        synchronized(symbolCache) {
            symbolCache.clear()
        }
        try {
            runOnAnalysisThread {
                // Release old session to free memory before building a new one
                _session = null
                System.gc()
                _session = buildSession()
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

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
import org.jetbrains.kotlin.analysis.api.types.KaType
import org.jetbrains.kotlin.analysis.project.structure.builder.buildKtLibraryModule
import org.jetbrains.kotlin.analysis.project.structure.builder.buildKtSdkModule
import org.jetbrains.kotlin.analysis.project.structure.builder.buildKtSourceModule
import org.jetbrains.kotlin.platform.jvm.JvmPlatforms
import org.jetbrains.kotlin.psi.*
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.LinkedHashMap

@OptIn(KaExperimentalApi::class)
class AnalysisApiCompilerFacade(
    private val projectModel: ProjectModel
) : CompilerFacade {

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

    private fun buildSession(): org.jetbrains.kotlin.analysis.api.standalone.StandaloneAnalysisAPISession {
        // Deduplicate classpath jars across all modules
        val allClasspath = projectModel.modules.flatMap { it.classpath }.distinct()

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

                // Configure per-module source roots for multi-module support.
                // Cross-module go-to-definition works through the shared session.
                for (moduleInfo in projectModel.modules) {
                    addModule(buildKtSourceModule {
                        moduleName = moduleInfo.name
                        this.platform = JvmPlatforms.defaultJvmPlatform
                        for (root in moduleInfo.sourceRoots) {
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
                    "${p.name.asString()}: ${p.returnType}"
                })
                append("): ")
                append(symbol.returnType)
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
                append(symbol.returnType)
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
                    "${p.name.asString()}: ${p.returnType}"
                })
                append(")")
            }
            else -> symbolName(symbol)
        }
    } catch (_: Exception) { null }

    private fun kaTypeToTypeInfo(type: KaType): TypeInfo {
        val rendered = type.toString()
        val shortName = rendered.substringAfterLast('.')
        return TypeInfo(
            fqName = rendered,
            shortName = shortName,
            nullable = rendered.endsWith("?"),
            typeArguments = emptyList()
        )
    }

    // ==== CompilerFacade implementation ====

    override fun getDiagnostics(file: Path): List<DiagnosticInfo> {
        val ktFile = findKtFile(file) ?: return emptyList()
        return try {
            runOnAnalysisThread {
                analyze(ktFile) {
                    val raw = ktFile.collectDiagnostics(KaDiagnosticCheckerFilter.ONLY_COMMON_CHECKERS)
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
                        ResolvedSymbol(
                            name = decl.name ?: "unknown",
                            kind = kind,
                            location = loc,
                            containingClass = (decl.parent as? KtClassOrObject)?.name,
                            signature = decl.text?.lines()?.firstOrNull()?.take(120),
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

    override fun findImplementations(symbol: ResolvedSymbol): List<SourceLocation> = emptyList()

    override fun getCompletions(file: Path, line: Int, column: Int): List<CompletionCandidate> = emptyList()

    override fun prepareRename(file: Path, line: Int, column: Int): RenameContext? = null

    override fun computeRename(context: RenameContext, newName: String): List<FileEdit> = emptyList()

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

        return DiagnosticInfo(
            severity = mapSeverity(diagnostic.severity),
            message = diagnostic.defaultMessage,
            range = SourceRange(file, startLine, startCol, endLine, endCol),
            code = diagnostic.factoryName,
            quickFixes = emptyList()
        )
    }

    private fun mapSeverity(severity: KaSeverity): Severity = when (severity) {
        KaSeverity.ERROR -> Severity.ERROR
        KaSeverity.WARNING -> Severity.WARNING
        KaSeverity.INFO -> Severity.INFO
    }
}

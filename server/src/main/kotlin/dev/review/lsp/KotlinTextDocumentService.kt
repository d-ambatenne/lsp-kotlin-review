package dev.review.lsp

import dev.review.lsp.analysis.DiagnosticsPublisher
import dev.review.lsp.analysis.WorkspaceManager
import dev.review.lsp.compiler.CompilerFacade
import dev.review.lsp.features.*
import dev.review.lsp.util.ProgressReporter
import dev.review.lsp.util.UriUtil
import kotlinx.coroutines.*
import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.eclipse.lsp4j.jsonrpc.messages.Either3
import org.eclipse.lsp4j.services.LanguageClient
import org.eclipse.lsp4j.services.TextDocumentService
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

class KotlinTextDocumentService : TextDocumentService {

    private var client: LanguageClient? = null
    @Volatile private var facade: CompilerFacade? = null
    @Volatile private var diagnosticsPublisher: DiagnosticsPublisher? = null
    @Volatile private var workspaceManager: WorkspaceManager? = null

    @Volatile private var notReadyMessageShown = false

    // Document version tracking for stale analysis cancellation
    private val documentVersions = ConcurrentHashMap<String, Int>()

    // Documents opened before the facade was ready — replay diagnostics when analysis starts
    private val pendingDocuments = ConcurrentHashMap<String, String>()

    // Debounce scheduler for didChange diagnostics
    private val debounceScheduler = Executors.newScheduledThreadPool(2) { r ->
        Thread(r, "diagnostics-debounce").apply { isDaemon = true }
    }
    private val pendingDiagnostics = ConcurrentHashMap<String, ScheduledFuture<*>>()

    // Two-tier diagnostics: Tier 1 (syntax, 300ms) and Tier 2 (semantic rebuild, 2000ms)
    private val pendingTier1 = ConcurrentHashMap<String, ScheduledFuture<*>>()
    private val pendingTier2 = ConcurrentHashMap<String, ScheduledFuture<*>>()

    // Last-saved file content for staleness comparison in Tier 1 merge
    private val lastSavedContent = ConcurrentHashMap<String, String>()

    // Coroutine scope for async multi-root operations
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    var debounceMs: Long = 250L

    // P0 providers (single-root cached path)
    @Volatile private var definitionProvider: DefinitionProvider? = null
    @Volatile private var referencesProvider: ReferencesProvider? = null
    @Volatile private var hoverProvider: HoverProvider? = null
    @Volatile private var documentSymbolProvider: DocumentSymbolProvider? = null

    // P1 providers (single-root cached path)
    @Volatile private var implementationProvider: ImplementationProvider? = null
    @Volatile private var typeDefinitionProvider: TypeDefinitionProvider? = null
    @Volatile private var renameProvider: RenameProvider? = null
    @Volatile private var codeActionProvider: CodeActionProvider? = null
    @Volatile private var completionProvider: CompletionProvider? = null

    fun connect(client: LanguageClient) {
        this.client = client
    }

    /** Single-root setup: cache providers for the one facade. */
    fun setAnalysis(facade: CompilerFacade, publisher: DiagnosticsPublisher, projectDir: java.nio.file.Path? = null) {
        this.facade = facade
        this.diagnosticsPublisher = publisher
        this.notReadyMessageShown = false
        // P0
        this.definitionProvider = DefinitionProvider(facade)
        this.referencesProvider = ReferencesProvider(facade)
        this.hoverProvider = HoverProvider(facade)
        this.documentSymbolProvider = DocumentSymbolProvider(facade)
        // P1
        this.implementationProvider = ImplementationProvider(facade)
        this.typeDefinitionProvider = TypeDefinitionProvider(facade)
        this.renameProvider = RenameProvider(facade)
        this.codeActionProvider = CodeActionProvider(facade, projectDir)
        this.completionProvider = CompletionProvider(facade)

        // Replay diagnostics for documents opened before the facade was ready
        val pending = pendingDocuments.toMap()
        pendingDocuments.clear()
        if (pending.isNotEmpty()) {
            client?.logMessage(MessageParams(MessageType.Info, "Publishing diagnostics for ${pending.size} pre-opened file(s)"))
            for ((uri, content) in pending) {
                val version = documentVersions[uri] ?: continue
                val path = UriUtil.toPath(uri)
                facade.updateFileContent(path, content)
                publisher.publishDiagnosticsAsync(path, uri, version) { documentVersions[uri] }
            }
        }
    }

    /** Multi-root setup: set the workspace manager for dynamic facade resolution. */
    fun setWorkspaceManager(wm: WorkspaceManager) {
        this.workspaceManager = wm
    }

    /**
     * Resolve the facade for a given URI. In single-root mode, returns the
     * cached facade. In multi-root mode, resolves via WorkspaceManager.
     */
    private fun facadeForUri(uri: String): CompilerFacade? {
        val wm = workspaceManager
        if (wm != null && !wm.isSingleRoot()) {
            return runBlocking { wm.facadeForFile(UriUtil.toPath(uri)) }
        }
        return facade
    }

    fun shutdown() {
        debounceScheduler.shutdownNow()
        scope.cancel()
    }

    // Document sync

    override fun didOpen(params: DidOpenTextDocumentParams) {
        val uri = params.textDocument.uri
        val version = params.textDocument.version
        documentVersions[uri] = version
        client?.logMessage(MessageParams(MessageType.Info, "Opened: $uri"))

        val wm = workspaceManager
        if (wm != null && !wm.isSingleRoot()) {
            // Multi-root: resolve facade lazily, then publish diagnostics
            scope.launch {
                val c = client ?: return@launch
                val filePath = UriUtil.toPath(uri)
                val rootDir = wm.buildRootForFile(filePath)
                val needsResolve = rootDir != null && !wm.isRootResolved(rootDir)

                val progress = if (needsResolve) {
                    ProgressReporter(c).also {
                        it.create()
                        it.begin("Kotlin Review", "Analyzing ${rootDir?.fileName ?: "project"}...")
                    }
                } else null

                val f = wm.facadeForFile(filePath)
                if (f == null) {
                    client?.logMessage(MessageParams(MessageType.Warning, "No build root found for: $uri"))
                    progress?.end("Resolution failed")
                    return@launch
                }
                progress?.end("Ready")

                val path = UriUtil.toPath(uri)
                f.updateFileContent(path, params.textDocument.text)
                val publisher = DiagnosticsPublisher(f, c)
                publisher.publishDiagnosticsAsync(path, uri, version) { documentVersions[uri] }
            }
            return
        }

        val f = facade
        if (f == null) {
            client?.logMessage(MessageParams(MessageType.Warning, "Facade not ready for: $uri"))
            pendingDocuments[uri] = params.textDocument.text
            if (!notReadyMessageShown) {
                notReadyMessageShown = true
                client?.showMessage(MessageParams(
                    MessageType.Info,
                    "Kotlin Review is still loading. Language features will be available shortly."
                ))
            }
            return
        }
        val path = UriUtil.toPath(uri)
        f.updateFileContent(path, params.textDocument.text)
        lastSavedContent[uri] = params.textDocument.text
        // Publish diagnostics asynchronously — serves cached results immediately,
        // computes fresh in background without blocking hover/completion
        diagnosticsPublisher?.publishDiagnosticsAsync(path, uri, version) { documentVersions[uri] }
    }

    override fun didChange(params: DidChangeTextDocumentParams) {
        val uri = params.textDocument.uri
        val version = params.textDocument.version
        val content = params.contentChanges.lastOrNull()?.text ?: return

        documentVersions[uri] = version

        // Keep pending content up to date if facade isn't ready yet
        if (pendingDocuments.containsKey(uri)) {
            pendingDocuments[uri] = content
        }

        val f = facadeForUri(uri) ?: return
        val path = UriUtil.toPath(uri)
        f.updateFileContent(path, content)

        val publisher = diagnosticsPublisher ?: return

        // Cancel any pending debounced diagnostics for this file
        pendingTier1.remove(uri)?.cancel(false)
        pendingTier2.remove(uri)?.cancel(false)

        // Tier 1: syntax-only diagnostics (300ms debounce, off analysis thread)
        val currentContent = content
        val savedContent = lastSavedContent[uri]
        pendingTier1[uri] = debounceScheduler.schedule({
            try {
                val epoch = publisher.bumpEpoch(path)
                val syntaxErrors = f.getSyntaxDiagnostics(path)
                publisher.publishMerged(path, uri, syntaxErrors, epoch, currentContent, savedContent)
            } catch (e: Exception) {
                System.err.println("[tier1] Syntax parse failed for $uri: ${e.message}")
            }
        }, 300, TimeUnit.MILLISECONDS)

        // Tier 2: full semantic diagnostics (2000ms debounce, triggers session rebuild)
        pendingTier2[uri] = debounceScheduler.schedule({
            try {
                val epoch = publisher.bumpEpoch(path)
                f.refreshAnalysis()
                publisher.invalidateCacheForFile(path)
                val freshDiagnostics = f.getDiagnostics(path)
                publisher.updateCacheAndPublish(path, uri, freshDiagnostics, epoch)
            } catch (e: Exception) {
                System.err.println("[tier2] Semantic analysis failed for $uri: ${e.message}")
            }
        }, 2000, TimeUnit.MILLISECONDS)
    }

    override fun didClose(params: DidCloseTextDocumentParams) {
        val uri = params.textDocument.uri
        documentVersions.remove(uri)
        pendingDocuments.remove(uri)
        lastSavedContent.remove(uri)
        pendingDiagnostics.remove(uri)?.cancel(false)
        pendingTier1.remove(uri)?.cancel(false)
        pendingTier2.remove(uri)?.cancel(false)
        diagnosticsPublisher?.clearDiagnostics(uri)
        // Multi-root: create an ad-hoc publisher to clear diagnostics
        if (workspaceManager != null && !workspaceManager!!.isSingleRoot()) {
            val f = facadeForUri(uri)
            if (f != null) {
                val c = client ?: return
                DiagnosticsPublisher(f, c).clearDiagnostics(uri)
            }
        }
    }

    override fun didSave(params: DidSaveTextDocumentParams) {
        val uri = params.textDocument.uri

        // Cancel pending tier diagnostics — didSave does its own authoritative rebuild
        pendingTier1.remove(uri)?.cancel(false)
        pendingTier2.remove(uri)?.cancel(false)

        // Update last-saved content baseline for Tier 1 staleness checks
        val path = UriUtil.toPath(uri)
        try {
            lastSavedContent[uri] = java.nio.file.Files.readString(path)
        } catch (_: Exception) { }

        val wm = workspaceManager
        if (wm != null && !wm.isSingleRoot()) {
            // Multi-root: refresh the correct facade
            val f = facadeForUri(uri) ?: return
            f.refreshAnalysis()
            val version = documentVersions[uri] ?: 0
            val c = client ?: return
            val publisher = DiagnosticsPublisher(f, c)
            publisher.invalidateCache()
            publisher.publishDiagnostics(path, uri, version) { documentVersions[uri] }
            return
        }

        val f = facade ?: return

        // Rebuild session to pick up saved changes (PSI is immutable — ADR-16).
        // refreshAnalysis() has a 5s cooldown to avoid auto-save cascades.
        diagnosticsPublisher?.invalidateCache()
        f.refreshAnalysis()

        val version = documentVersions[uri] ?: 0
        diagnosticsPublisher?.publishDiagnostics(path, uri, version) { documentVersions[uri] }
    }

    // P0 features

    override fun definition(params: DefinitionParams): CompletableFuture<Either<List<Location>, List<LocationLink>>> {
        val provider = definitionProvider ?: facadeForUri(params.textDocument.uri)?.let { DefinitionProvider(it) }
        return (provider?.definition(params)
            ?: CompletableFuture.completedFuture(Either.forLeft(emptyList())))
            .exceptionally { e ->
                System.err.println("[service] Error in definition: ${e.message}")
                Either.forLeft(emptyList())
            }
    }

    override fun references(params: ReferenceParams): CompletableFuture<List<Location>> {
        val provider = referencesProvider ?: facadeForUri(params.textDocument.uri)?.let { ReferencesProvider(it) }
        return (provider?.references(params)
            ?: CompletableFuture.completedFuture(emptyList()))
            .exceptionally { e ->
                System.err.println("[service] Error in references: ${e.message}")
                emptyList()
            }
    }

    override fun hover(params: HoverParams): CompletableFuture<Hover?> {
        val provider = hoverProvider ?: facadeForUri(params.textDocument.uri)?.let { HoverProvider(it) }
        val future: CompletableFuture<Hover?> = provider?.hover(params)
            ?: CompletableFuture.completedFuture(null)
        return future.exceptionally { e ->
            System.err.println("[service] Error in hover: ${e.message}")
            null
        }
    }

    override fun documentSymbol(params: DocumentSymbolParams): CompletableFuture<List<Either<SymbolInformation, DocumentSymbol>>> {
        val provider = documentSymbolProvider ?: facadeForUri(params.textDocument.uri)?.let { DocumentSymbolProvider(it) }
            ?: return CompletableFuture.completedFuture(emptyList())

        return provider.documentSymbol(params).thenApply { symbols ->
            symbols.map { Either.forRight<SymbolInformation, DocumentSymbol>(it) }
        }.exceptionally { e ->
            System.err.println("[service] Error in documentSymbol: ${e.message}")
            emptyList()
        }
    }

    // P1 features

    override fun implementation(params: ImplementationParams): CompletableFuture<Either<List<Location>, List<LocationLink>>> {
        val provider = implementationProvider ?: facadeForUri(params.textDocument.uri)?.let { ImplementationProvider(it) }
        return (provider?.implementation(params)
            ?: CompletableFuture.completedFuture(Either.forLeft(emptyList())))
            .exceptionally { e ->
                System.err.println("[service] Error in implementation: ${e.message}")
                Either.forLeft(emptyList())
            }
    }

    override fun typeDefinition(params: TypeDefinitionParams): CompletableFuture<Either<List<Location>, List<LocationLink>>> {
        val provider = typeDefinitionProvider ?: facadeForUri(params.textDocument.uri)?.let { TypeDefinitionProvider(it) }
        return (provider?.typeDefinition(params)
            ?: CompletableFuture.completedFuture(Either.forLeft(emptyList())))
            .exceptionally { e ->
                System.err.println("[service] Error in typeDefinition: ${e.message}")
                Either.forLeft(emptyList())
            }
    }

    override fun prepareRename(params: PrepareRenameParams): CompletableFuture<Either3<Range, PrepareRenameResult, PrepareRenameDefaultBehavior>> {
        val provider = renameProvider ?: facadeForUri(params.textDocument.uri)?.let { RenameProvider(it) }
            ?: return CompletableFuture.completedFuture(null)
        val future: CompletableFuture<Either3<Range, PrepareRenameResult, PrepareRenameDefaultBehavior>?> =
            provider.prepareRename(params)
        return future.exceptionally { e ->
            System.err.println("[service] Error in prepareRename: ${e.message}")
            null
        }.thenApply { it }
    }

    override fun rename(params: RenameParams): CompletableFuture<WorkspaceEdit?> {
        val provider = renameProvider ?: facadeForUri(params.textDocument.uri)?.let { RenameProvider(it) }
        val future: CompletableFuture<WorkspaceEdit?> = provider?.rename(params)
            ?: CompletableFuture.completedFuture(null)
        return future.exceptionally { e ->
            System.err.println("[service] Error in rename: ${e.message}")
            null
        }
    }

    override fun codeAction(params: CodeActionParams): CompletableFuture<List<Either<Command, CodeAction>>> {
        val provider = codeActionProvider ?: run {
            val uri = params.textDocument.uri
            val f = facadeForUri(uri) ?: return CompletableFuture.completedFuture(emptyList())
            val model = workspaceManager?.modelForFile(UriUtil.toPath(uri))
            CodeActionProvider(f, model?.projectDir)
        }
        return provider.codeAction(params)
            .exceptionally { e ->
                System.err.println("[service] Error in codeAction: ${e.message}")
                emptyList()
            }
    }

    override fun completion(params: CompletionParams): CompletableFuture<Either<List<CompletionItem>, CompletionList>> {
        val provider = completionProvider ?: facadeForUri(params.textDocument.uri)?.let { CompletionProvider(it) }
        return (provider?.completion(params)
            ?: CompletableFuture.completedFuture(Either.forLeft(emptyList())))
            .exceptionally { e ->
                System.err.println("[service] Error in completion: ${e.message}")
                Either.forLeft(emptyList())
            }
    }
}

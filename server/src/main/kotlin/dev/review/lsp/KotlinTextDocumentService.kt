package dev.review.lsp

import dev.review.lsp.analysis.DiagnosticsPublisher
import dev.review.lsp.compiler.CompilerFacade
import dev.review.lsp.features.*
import dev.review.lsp.util.UriUtil
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

    // Document version tracking for stale analysis cancellation
    private val documentVersions = ConcurrentHashMap<String, Int>()

    // Debounce scheduler for didChange diagnostics
    private val debounceScheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "diagnostics-debounce").apply { isDaemon = true }
    }
    private val pendingDiagnostics = ConcurrentHashMap<String, ScheduledFuture<*>>()

    var debounceMs: Long = 250L

    // P0 providers
    @Volatile private var definitionProvider: DefinitionProvider? = null
    @Volatile private var referencesProvider: ReferencesProvider? = null
    @Volatile private var hoverProvider: HoverProvider? = null
    @Volatile private var documentSymbolProvider: DocumentSymbolProvider? = null

    // P1 providers
    @Volatile private var implementationProvider: ImplementationProvider? = null
    @Volatile private var typeDefinitionProvider: TypeDefinitionProvider? = null
    @Volatile private var renameProvider: RenameProvider? = null
    @Volatile private var codeActionProvider: CodeActionProvider? = null
    @Volatile private var completionProvider: CompletionProvider? = null

    fun connect(client: LanguageClient) {
        this.client = client
    }

    fun setAnalysis(facade: CompilerFacade, publisher: DiagnosticsPublisher, projectDir: java.nio.file.Path? = null) {
        this.facade = facade
        this.diagnosticsPublisher = publisher
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
    }

    fun shutdown() {
        debounceScheduler.shutdownNow()
    }

    // Document sync

    override fun didOpen(params: DidOpenTextDocumentParams) {
        val uri = params.textDocument.uri
        val version = params.textDocument.version
        documentVersions[uri] = version
        client?.logMessage(MessageParams(MessageType.Info, "Opened: $uri"))

        val f = facade
        if (f == null) {
            client?.logMessage(MessageParams(MessageType.Warning, "Facade not ready for: $uri"))
            return
        }
        val path = UriUtil.toPath(uri)
        f.updateFileContent(path, params.textDocument.text)
        // Publish diagnostics asynchronously — serves cached results immediately,
        // computes fresh in background without blocking hover/completion
        diagnosticsPublisher?.publishDiagnosticsAsync(path, uri, version) { documentVersions[uri] }
    }

    override fun didChange(params: DidChangeTextDocumentParams) {
        val uri = params.textDocument.uri
        val version = params.textDocument.version
        val content = params.contentChanges.lastOrNull()?.text ?: return

        documentVersions[uri] = version

        val f = facade ?: return
        val path = UriUtil.toPath(uri)
        f.updateFileContent(path, content)

        // No diagnostics on didChange — the Analysis API session is immutable (ADR-16),
        // so collectDiagnostics returns the same results until the next save/rebuild.
        // This avoids blocking the analysis thread on every keystroke.
    }

    override fun didClose(params: DidCloseTextDocumentParams) {
        val uri = params.textDocument.uri
        documentVersions.remove(uri)
        pendingDiagnostics.remove(uri)?.cancel(false)
        diagnosticsPublisher?.clearDiagnostics(uri)
    }

    override fun didSave(params: DidSaveTextDocumentParams) {
        val f = facade ?: return
        val uri = params.textDocument.uri
        val path = UriUtil.toPath(uri)

        // Rebuild session to pick up saved changes (PSI is immutable — ADR-16).
        // refreshAnalysis() has a 5s cooldown to avoid auto-save cascades.
        diagnosticsPublisher?.invalidateCache()
        f.refreshAnalysis()

        val version = documentVersions[uri] ?: 0
        diagnosticsPublisher?.publishDiagnostics(path, uri, version) { documentVersions[uri] }
    }

    // P0 features

    override fun definition(params: DefinitionParams): CompletableFuture<Either<List<Location>, List<LocationLink>>> {
        return definitionProvider?.definition(params)
            ?: CompletableFuture.completedFuture(Either.forLeft(emptyList()))
    }

    override fun references(params: ReferenceParams): CompletableFuture<List<Location>> {
        return referencesProvider?.references(params)
            ?: CompletableFuture.completedFuture(emptyList())
    }

    override fun hover(params: HoverParams): CompletableFuture<Hover?> {
        return hoverProvider?.hover(params)
            ?: CompletableFuture.completedFuture(null)
    }

    override fun documentSymbol(params: DocumentSymbolParams): CompletableFuture<List<Either<SymbolInformation, DocumentSymbol>>> {
        val provider = documentSymbolProvider
            ?: return CompletableFuture.completedFuture(emptyList())

        return provider.documentSymbol(params).thenApply { symbols ->
            symbols.map { Either.forRight<SymbolInformation, DocumentSymbol>(it) }
        }
    }

    // P1 features

    override fun implementation(params: ImplementationParams): CompletableFuture<Either<List<Location>, List<LocationLink>>> {
        return implementationProvider?.implementation(params)
            ?: CompletableFuture.completedFuture(Either.forLeft(emptyList()))
    }

    override fun typeDefinition(params: TypeDefinitionParams): CompletableFuture<Either<List<Location>, List<LocationLink>>> {
        return typeDefinitionProvider?.typeDefinition(params)
            ?: CompletableFuture.completedFuture(Either.forLeft(emptyList()))
    }

    override fun prepareRename(params: PrepareRenameParams): CompletableFuture<Either3<Range, PrepareRenameResult, PrepareRenameDefaultBehavior>> {
        val provider = renameProvider ?: return CompletableFuture.completedFuture(null)
        return provider.prepareRename(params).thenApply { it }
    }

    override fun rename(params: RenameParams): CompletableFuture<WorkspaceEdit?> {
        return renameProvider?.rename(params)
            ?: CompletableFuture.completedFuture(null)
    }

    override fun codeAction(params: CodeActionParams): CompletableFuture<List<Either<Command, CodeAction>>> {
        return codeActionProvider?.codeAction(params)
            ?: CompletableFuture.completedFuture(emptyList())
    }

    override fun completion(params: CompletionParams): CompletableFuture<Either<List<CompletionItem>, CompletionList>> {
        return completionProvider?.completion(params)
            ?: CompletableFuture.completedFuture(Either.forLeft(emptyList()))
    }
}

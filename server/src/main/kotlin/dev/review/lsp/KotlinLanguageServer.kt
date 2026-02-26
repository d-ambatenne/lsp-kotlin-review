package dev.review.lsp

import dev.review.lsp.analysis.AnalysisSession
import dev.review.lsp.analysis.DiagnosticsPublisher
import dev.review.lsp.buildsystem.BuildSystemResolver
import kotlinx.coroutines.*
import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.eclipse.lsp4j.services.*
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

class KotlinLanguageServer : LanguageServer, LanguageClientAware {

    private val textDocumentService = KotlinTextDocumentService()
    private val workspaceService = KotlinWorkspaceService()
    private var client: LanguageClient? = null
    private var workspaceRoot: String? = null
    private var rootPath: Path? = null
    private var analysisSession: AnalysisSession? = null
    private var buildVariant: String = "debug"
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val rebuildScheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "rebuild-debounce").apply { isDaemon = true }
    }
    @Volatile private var pendingRebuild: ScheduledFuture<*>? = null
    private companion object {
        const val REBUILD_DEBOUNCE_MS = 2000L // 2s — batches burst of generated file events
    }

    override fun initialize(params: InitializeParams): CompletableFuture<InitializeResult> {
        workspaceRoot = params.rootUri ?: params.rootPath
        // Read build variant from client initialization options
        try {
            val initOptions = params.initializationOptions
            if (initOptions is com.google.gson.JsonObject) {
                initOptions.get("buildVariant")?.asString?.let { buildVariant = it }
            }
        } catch (_: Exception) { /* use default */ }
        val capabilities = ServerCapabilities().apply {
            setTextDocumentSync(TextDocumentSyncKind.Full)
            definitionProvider = Either.forLeft(true)
            referencesProvider = Either.forLeft(true)
            hoverProvider = Either.forLeft(true)
            documentSymbolProvider = Either.forLeft(true)
            implementationProvider = Either.forLeft(true)
            typeDefinitionProvider = Either.forLeft(true)
            renameProvider = Either.forRight(RenameOptions(true))
            codeActionProvider = Either.forLeft(true)
            completionProvider = CompletionOptions(false, listOf("."))
        }
        val serverInfo = ServerInfo("kotlin-review-lsp", "0.1.0")
        return CompletableFuture.completedFuture(InitializeResult(capabilities, serverInfo))
    }

    override fun initialized(params: InitializedParams) {
        log(MessageType.Info, "Kotlin Review LSP server initialized")

        val root = workspaceRoot ?: return
        scope.launch {
            try {
                val rp = Paths.get(java.net.URI.create(root))
                rootPath = rp

                log(MessageType.Info, "Resolving build system (variant: $buildVariant)...")
                val resolver = BuildSystemResolver()
                val (provider, model) = resolver.resolve(rp, buildVariant)

                log(MessageType.Info, "Detected build system: ${provider.id}")

                val session = AnalysisSession(model)
                analysisSession = session

                val c = client ?: return@launch
                val publisher = DiagnosticsPublisher(session.facade, c)
                textDocumentService.setAnalysis(session.facade, publisher, model.projectDir)

                // Wire up workspace service callbacks — debounced to batch rapid changes
                workspaceService.onBuildFileChanged = { scheduleRebuild() }
                workspaceService.onGeneratedSourcesChanged = { scheduleRebuild() }
                workspaceService.onConfigurationChanged = { scheduleRebuild() }

                log(MessageType.Info, "Analysis session ready (${model.modules.size} module(s))")

                // Show hint for Android projects missing generated sources
                checkAndroidBuildHint(model, c)

                // Register file watchers for build files
                registerFileWatchers()
            } catch (e: Exception) {
                log(MessageType.Error, "Failed to initialize analysis: ${e.message}")
            }
        }
    }

    private fun registerFileWatchers() {
        val c = client ?: return
        val watchers = listOf(
            FileSystemWatcher(Either.forLeft("**/build.gradle.kts"), WatchKind.Create + WatchKind.Change + WatchKind.Delete),
            FileSystemWatcher(Either.forLeft("**/build.gradle"), WatchKind.Create + WatchKind.Change + WatchKind.Delete),
            FileSystemWatcher(Either.forLeft("**/settings.gradle.kts"), WatchKind.Create + WatchKind.Change + WatchKind.Delete),
            FileSystemWatcher(Either.forLeft("**/settings.gradle"), WatchKind.Create + WatchKind.Change + WatchKind.Delete),
            // Watch generated source files for auto-rebuild when Gradle generates them
            FileSystemWatcher(Either.forLeft("**/build/generated/**/*.kt"), WatchKind.Create + WatchKind.Change + WatchKind.Delete),
            FileSystemWatcher(Either.forLeft("**/build/generated/**/*.java"), WatchKind.Create + WatchKind.Change + WatchKind.Delete)
        )
        val registration = Registration(
            "build-file-watcher",
            "workspace/didChangeWatchedFiles",
            DidChangeWatchedFilesRegistrationOptions(watchers)
        )
        c.registerCapability(RegistrationParams(listOf(registration)))
    }

    private fun checkAndroidBuildHint(model: dev.review.lsp.buildsystem.ProjectModel, c: LanguageClient) {
        val androidModules = model.modules.filter { it.isAndroid }
        if (androidModules.isEmpty()) return

        val rp = rootPath ?: return
        val anyMissingGenerated = androidModules.any { module ->
            // Check if this module's build/generated/ directory exists and has content
            val moduleDir = module.sourceRoots.firstOrNull()?.parent?.parent?.parent // src/main/kotlin -> module root
                ?: rp
            val generatedDir = moduleDir.resolve("build/generated")
            !java.nio.file.Files.isDirectory(generatedDir) ||
                (java.nio.file.Files.list(generatedDir).use { it.count() } == 0L)
        }

        if (anyMissingGenerated) {
            val cap = model.variant.replaceFirstChar { it.uppercase() }
            c.showMessage(MessageParams(
                MessageType.Info,
                "Android project detected. Run ./gradlew generate${cap}Resources generate${cap}BuildConfig --continue for R class and BuildConfig support."
            ))
        }
    }

    private fun scheduleRebuild() {
        pendingRebuild?.cancel(false)
        pendingRebuild = rebuildScheduler.schedule({
            rebuildSession()
        }, REBUILD_DEBOUNCE_MS, TimeUnit.MILLISECONDS)
    }

    private fun rebuildSession() {
        val rp = rootPath ?: return
        scope.launch {
            try {
                log(MessageType.Info, "Rebuilding analysis session (variant: $buildVariant)...")
                val resolver = BuildSystemResolver()
                val (provider, model) = resolver.resolve(rp, buildVariant)

                log(MessageType.Info, "Rebuilt with build system: ${provider.id}")

                val session = analysisSession
                if (session != null) {
                    val newFacade = session.rebuild(model)
                    val c = client ?: return@launch
                    val publisher = DiagnosticsPublisher(newFacade, c)
                    textDocumentService.setAnalysis(newFacade, publisher, model.projectDir)
                    log(MessageType.Info, "Analysis session rebuilt (${model.modules.size} module(s))")
                }
            } catch (e: Exception) {
                log(MessageType.Error, "Failed to rebuild analysis session: ${e.message}")
            }
        }
    }

    override fun shutdown(): CompletableFuture<Any> {
        pendingRebuild?.cancel(false)
        rebuildScheduler.shutdownNow()
        textDocumentService.shutdown()
        analysisSession?.dispose()
        scope.cancel()
        return CompletableFuture.completedFuture(null)
    }

    override fun exit() {
        System.exit(0)
    }

    override fun getTextDocumentService(): TextDocumentService = textDocumentService

    override fun getWorkspaceService(): WorkspaceService = workspaceService

    override fun connect(client: LanguageClient) {
        this.client = client
        textDocumentService.connect(client)
    }

    private fun log(type: MessageType, message: String) {
        client?.logMessage(MessageParams(type, message))
    }
}

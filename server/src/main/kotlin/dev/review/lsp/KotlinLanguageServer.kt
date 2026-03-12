package dev.review.lsp

import dev.review.lsp.analysis.AnalysisSession
import dev.review.lsp.analysis.DiagnosticsPublisher
import dev.review.lsp.analysis.WorkspaceManager
import dev.review.lsp.buildsystem.BuildSystemResolver
import dev.review.lsp.util.ProgressReporter
import dev.review.lsp.util.UriUtil
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
    private var workspaceManager: WorkspaceManager? = null
    private var buildVariant: String = "debug"
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val rebuildScheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "rebuild-debounce").apply { isDaemon = true }
    }
    @Volatile private var pendingRebuild: ScheduledFuture<*>? = null
    @Volatile private var heapWarningShown = false
    private var heapMonitor: ScheduledFuture<*>? = null
    private companion object {
        const val REBUILD_DEBOUNCE_MS = 2000L // 2s — batches burst of generated file events
        const val HEAP_CHECK_INTERVAL_S = 30L
        const val HEAP_WARNING_THRESHOLD = 0.85 // 85%
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
            val c = client
            val progress = c?.let { ProgressReporter(it) }
            try {
                progress?.create("kotlin-review-init")
                progress?.begin("Kotlin Review", "Discovering build roots...", 0)

                val rp = Paths.get(java.net.URI.create(root))
                rootPath = rp

                log(MessageType.Info, "Discovering build roots (variant: $buildVariant)...")
                val wm = WorkspaceManager(rp, buildVariant)
                wm.discover()
                workspaceManager = wm

                if (wm.isSingleRoot()) {
                    // Single root: resolve eagerly, wire up like before
                    val singleRoot = wm.singleRoot()
                    log(MessageType.Info, "Single build root: ${singleRoot.rootDir}")

                    progress?.report("Resolving Gradle project model...", 20)
                    val facade = wm.resolveRoot(singleRoot.rootDir)
                    if (facade == null) {
                        log(MessageType.Error, "Failed to resolve build root")
                        progress?.end("Failed to resolve project")
                        return@launch
                    }
                    val model = singleRoot.model ?: run {
                        progress?.end("Failed to resolve project")
                        return@launch
                    }

                    progress?.report("Building analysis sessions...", 70)
                    val lc = c ?: return@launch
                    val publisher = DiagnosticsPublisher(facade, lc)
                    textDocumentService.setAnalysis(facade, publisher, model.projectDir)

                    progress?.end("Ready (${model.modules.size} modules)")
                    log(MessageType.Info, "Analysis session ready (${model.modules.size} module(s))")
                    checkAndroidBuildHint(model, lc)
                } else {
                    // Multi root: set workspace manager on text doc service, resolve lazily
                    log(MessageType.Info, "Multi-root workspace: ${wm.allRoots().size} build roots")
                    for (rootDir in wm.allRoots()) {
                        log(MessageType.Info, "  Build root: $rootDir")
                    }
                    textDocumentService.setWorkspaceManager(wm)
                    progress?.end("Ready (${wm.allRoots().size} build roots)")
                }

                // Wire up workspace service callbacks — debounced to batch rapid changes
                workspaceService.onBuildFileChanged = { uri -> scheduleRebuild(uri) }
                workspaceService.onGeneratedSourcesChanged = { uri -> scheduleRebuild(uri) }
                workspaceService.onConfigurationChanged = { scheduleRebuild(null) }

                // Register file watchers for build files
                registerFileWatchers()

                // Start heap usage monitor
                startHeapMonitor()
            } catch (e: Exception) {
                log(MessageType.Error, "Failed to initialize analysis: ${e.message}")
                progress?.end("Initialization failed")
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

    private fun startHeapMonitor() {
        heapMonitor = rebuildScheduler.scheduleAtFixedRate({
            try {
                checkHeapUsage()
            } catch (_: Exception) { /* don't crash the scheduler */ }
        }, HEAP_CHECK_INTERVAL_S, HEAP_CHECK_INTERVAL_S, TimeUnit.SECONDS)
    }

    private fun checkHeapUsage() {
        if (heapWarningShown) return
        val rt = Runtime.getRuntime()
        val maxHeap = rt.maxMemory()
        val usedHeap = rt.totalMemory() - rt.freeMemory()
        val usage = usedHeap.toDouble() / maxHeap.toDouble()
        if (usage >= HEAP_WARNING_THRESHOLD) {
            heapWarningShown = true
            val usedMb = usedHeap / (1024 * 1024)
            val maxMb = maxHeap / (1024 * 1024)
            val c = client ?: return
            c.showMessage(MessageParams(
                MessageType.Warning,
                "Kotlin Review: heap usage is high (${usedMb}MB / ${maxMb}MB). " +
                    "Consider increasing memory via Settings > Kotlin Review > Server: JVM Args, " +
                    "e.g. add \"-Xmx6g\". Restart VS Code after changing."
            ))
        }
    }

    private fun scheduleRebuild(changedUri: String?) {
        pendingRebuild?.cancel(false)
        pendingRebuild = rebuildScheduler.schedule({
            rebuildForUri(changedUri)
        }, REBUILD_DEBOUNCE_MS, TimeUnit.MILLISECONDS)
    }

    private fun rebuildForUri(changedUri: String?) {
        val wm = workspaceManager ?: return
        scope.launch {
            val c = client
            val progress = c?.let { ProgressReporter(it) }
            try {
                progress?.create()
                progress?.begin("Kotlin Review", "Rebuilding analysis...")

                // Determine which build root to rebuild
                val targetRoot = if (changedUri != null) {
                    try {
                        val filePath = UriUtil.toPath(changedUri)
                        wm.buildRootForFile(filePath)
                    } catch (_: Exception) { null }
                } else null

                heapWarningShown = false // reset so warning can fire again after rebuild

                if (wm.isSingleRoot()) {
                    // Single root: rebuild the one root
                    val rootDir = wm.singleRoot().rootDir
                    log(MessageType.Info, "Rebuilding analysis session (variant: $buildVariant)...")
                    val newFacade = wm.rebuildRoot(rootDir) ?: run {
                        progress?.end("Rebuild failed")
                        return@launch
                    }
                    val model = wm.singleRoot().model ?: run {
                        progress?.end("Rebuild failed")
                        return@launch
                    }
                    val lc = c ?: return@launch
                    val publisher = DiagnosticsPublisher(newFacade, lc)
                    textDocumentService.setAnalysis(newFacade, publisher, model.projectDir)
                    log(MessageType.Info, "Analysis session rebuilt (${model.modules.size} module(s))")
                } else if (targetRoot != null) {
                    // Multi root: rebuild only the affected root
                    log(MessageType.Info, "Rebuilding build root: ${targetRoot.fileName}...")
                    wm.rebuildRoot(targetRoot)
                    log(MessageType.Info, "Build root rebuilt: ${targetRoot.fileName}")
                } else {
                    // No URI or couldn't determine root — rebuild all resolved roots
                    log(MessageType.Info, "Rebuilding all resolved build roots...")
                    for (rootDir in wm.allRoots()) {
                        wm.rebuildRoot(rootDir)
                    }
                    log(MessageType.Info, "All build roots rebuilt")
                }
                progress?.end("Rebuild complete")
            } catch (e: Exception) {
                log(MessageType.Error, "Failed to rebuild analysis session: ${e.message}")
                progress?.end("Rebuild failed")
            }
        }
    }

    override fun shutdown(): CompletableFuture<Any> {
        heapMonitor?.cancel(false)
        pendingRebuild?.cancel(false)
        rebuildScheduler.shutdownNow()
        textDocumentService.shutdown()
        workspaceManager?.dispose()
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

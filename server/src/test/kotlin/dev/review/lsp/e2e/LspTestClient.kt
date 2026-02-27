package dev.review.lsp.e2e

import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.jsonrpc.Launcher
import org.eclipse.lsp4j.launch.LSPLauncher
import org.eclipse.lsp4j.services.LanguageServer
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * Test client that spawns the server JAR as a subprocess and communicates
 * via stdio JSON-RPC. Used for E2E LSP protocol tests.
 */
class LspTestClient(
    private val serverJarPath: String,
    private val workspaceRoot: Path,
    private val javaPath: String = System.getProperty("java.home") + "/bin/java"
) : AutoCloseable {

    private lateinit var process: Process
    lateinit var server: LanguageServer
        private set
    private lateinit var launcher: Launcher<LanguageServer>

    val diagnostics = mutableMapOf<String, List<Diagnostic>>()
    val messages = mutableListOf<MessageParams>()

    fun start(): InitializeResult {
        process = ProcessBuilder(
            javaPath, "-Xmx1g", "-jar", serverJarPath
        ).redirectErrorStream(false).start()

        val client = object : org.eclipse.lsp4j.services.LanguageClient {
            override fun telemetryEvent(obj: Any?) {}
            override fun publishDiagnostics(params: PublishDiagnosticsParams) {
                diagnostics[params.uri] = params.diagnostics
            }
            override fun showMessage(params: MessageParams) {
                messages.add(params)
            }
            override fun showMessageRequest(params: ShowMessageRequestParams): CompletableFuture<MessageActionItem> =
                CompletableFuture.completedFuture(null)
            override fun logMessage(params: MessageParams) {
                messages.add(params)
            }
            override fun registerCapability(params: RegistrationParams): CompletableFuture<Void> =
                CompletableFuture.completedFuture(null)
        }

        launcher = LSPLauncher.createClientLauncher(client, process.inputStream, process.outputStream)
        launcher.startListening()
        server = launcher.remoteProxy

        val initResult = server.initialize(InitializeParams().apply {
            rootUri = workspaceRoot.toUri().toString()
            capabilities = ClientCapabilities()
        }).get(30, TimeUnit.SECONDS)

        server.initialized(InitializedParams())
        // Give server time to initialize analysis session (Gradle + Analysis API startup)
        Thread.sleep(10000)

        return initResult
    }

    fun openFile(filePath: Path): String {
        val uri = filePath.toUri().toString()
        val content = java.nio.file.Files.readString(filePath)
        server.textDocumentService.didOpen(DidOpenTextDocumentParams(
            TextDocumentItem(uri, "kotlin", 1, content)
        ))
        // Wait for diagnostics to arrive
        Thread.sleep(5000)
        return uri
    }

    fun hover(uri: String, line: Int, col: Int): Hover? {
        return server.textDocumentService.hover(HoverParams(
            TextDocumentIdentifier(uri),
            Position(line, col)
        )).get(10, TimeUnit.SECONDS)
    }

    fun completion(uri: String, line: Int, col: Int): List<CompletionItem> {
        val result = server.textDocumentService.completion(CompletionParams(
            TextDocumentIdentifier(uri),
            Position(line, col)
        )).get(10, TimeUnit.SECONDS)
        return result?.left ?: result?.right?.items ?: emptyList()
    }

    fun definition(uri: String, line: Int, col: Int): List<Location> {
        val result = server.textDocumentService.definition(DefinitionParams(
            TextDocumentIdentifier(uri),
            Position(line, col)
        )).get(10, TimeUnit.SECONDS)
        return result?.left ?: emptyList()
    }

    override fun close() {
        try {
            server.shutdown().get(5, TimeUnit.SECONDS)
            server.exit()
        } catch (_: Exception) {}
        process.destroyForcibly()
        process.waitFor(5, TimeUnit.SECONDS)
    }

    companion object {
        fun serverJar(): String {
            return System.getProperty("server.jar")
                ?: throw IllegalStateException(
                    "System property 'server.jar' not set. Run with: ./gradlew e2eTest"
                )
        }
    }
}

package dev.review.lsp

import org.eclipse.lsp4j.launch.LSPLauncher
import java.io.OutputStream
import java.io.PrintStream

fun main() {
    // Capture stdout for LSP, then redirect System.out to stderr so that
    // any library (e.g. Gradle Tooling API) writing to stdout doesn't
    // corrupt the LSP protocol stream.
    val lspOut = System.out
    System.setOut(PrintStream(OutputStream.nullOutputStream()))

    val server = KotlinLanguageServer()
    val launcher = LSPLauncher.createServerLauncher(server, System.`in`, lspOut)
    server.connect(launcher.remoteProxy)
    launcher.startListening().get()
}

package dev.review.lsp

import org.eclipse.lsp4j.launch.LSPLauncher

fun main() {
    val server = KotlinLanguageServer()
    val launcher = LSPLauncher.createServerLauncher(server, System.`in`, System.out)
    server.connect(launcher.remoteProxy)
    launcher.startListening().get()
}

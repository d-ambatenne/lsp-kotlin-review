import * as vscode from "vscode";
import * as path from "path";
import {
  LanguageClient,
  LanguageClientOptions,
  ServerOptions,
} from "vscode-languageclient/node";
import { findJava } from "./javaDetector";
import { getServerJvmArgs, getTraceServer } from "./config";

let client: LanguageClient | undefined;

export async function activate(context: vscode.ExtensionContext): Promise<void> {
  const outputChannel = vscode.window.createOutputChannel("Kotlin Review");

  let javaInfo;
  try {
    javaInfo = findJava();
  } catch (e: unknown) {
    const msg = e instanceof Error ? e.message : String(e);
    vscode.window.showErrorMessage(msg);
    outputChannel.appendLine(`[ERROR] ${msg}`);
    return;
  }

  outputChannel.appendLine(`Using Java ${javaInfo.version} at ${javaInfo.javaPath}`);

  const jarPath = path.join(context.extensionPath, "server", "server-all.jar");

  const defaultJvmArgs = [
    "-Xmx2g",
    "-XX:+UseG1GC",
  ];
  const userJvmArgs = getServerJvmArgs();

  const serverOptions: ServerOptions = {
    command: javaInfo.javaPath,
    args: [...defaultJvmArgs, ...userJvmArgs, "-jar", jarPath],
  };

  const traceServer = getTraceServer();

  const clientOptions: LanguageClientOptions = {
    documentSelector: [
      { scheme: "file", language: "kotlin" },
    ],
    outputChannel,
    traceOutputChannel: outputChannel,
    initializationOptions: {},
  };

  client = new LanguageClient(
    "kotlinReview",
    "Kotlin Review",
    serverOptions,
    clientOptions
  );

  if (traceServer !== "off") {
    client.setTrace(
      traceServer === "verbose"
        ? vscode.lsp.Trace.Verbose
        : vscode.lsp.Trace.Messages
    );
  }

  // Register command for generating Android sources from code action
  context.subscriptions.push(
    vscode.commands.registerCommand("kotlinReview.generateSources", (projectDir?: string) => {
      const terminal = vscode.window.createTerminal("Kotlin Review: Generate Sources");
      if (projectDir) {
        terminal.sendText(`cd "${projectDir}" && ./gradlew generateDebugResources generateDebugBuildConfig --continue; echo 'Done. Reload window to pick up generated sources.'`);
      } else {
        terminal.sendText("./gradlew generateDebugResources generateDebugBuildConfig --continue; echo 'Done. Reload window to pick up generated sources.'");
      }
      terminal.show();
    })
  );

  await client.start();
  outputChannel.appendLine("Kotlin Review LSP client started");
}

export async function deactivate(): Promise<void> {
  if (client) {
    await client.stop();
    client = undefined;
  }
}

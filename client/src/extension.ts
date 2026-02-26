import * as vscode from "vscode";
import * as path from "path";
import * as cp from "child_process";
import {
  LanguageClient,
  LanguageClientOptions,
  ServerOptions,
} from "vscode-languageclient/node";
import { findJava } from "./javaDetector";
import { getServerJvmArgs, getTraceServer, getBuildVariant, getAutoGenerate } from "./config";

let client: LanguageClient | undefined;
let androidStatusBar: vscode.StatusBarItem | undefined;
let platformStatusBar: vscode.StatusBarItem | undefined;
let generateTimer: ReturnType<typeof setTimeout> | undefined;
let generating = false;

function detectPlatformFromPath(filePath: string): string | null {
  if (filePath.includes("/androidMain/") || filePath.includes("/androidTest/")) return "androidMain";
  if (filePath.includes("/jvmMain/") || filePath.includes("/jvmTest/")) return "jvmMain";
  if (filePath.includes("/iosMain/") || filePath.includes("/iosTest/")) return "iosMain";
  if (filePath.includes("/nativeMain/") || filePath.includes("/nativeTest/")) return "nativeMain";
  if (filePath.includes("/jsMain/") || filePath.includes("/jsTest/")) return "jsMain";
  if (filePath.includes("/wasmJsMain/") || filePath.includes("/wasmJsTest/")) return "wasmJsMain";
  if (filePath.includes("/commonMain/") || filePath.includes("/commonTest/")) return "commonMain";
  return null;
}

function updatePlatformIndicator(editor: vscode.TextEditor | undefined): void {
  if (!platformStatusBar) return;
  if (!editor || editor.document.languageId !== "kotlin") {
    platformStatusBar.hide();
    return;
  }
  const filePath = editor.document.uri.fsPath;
  const platform = detectPlatformFromPath(filePath);
  if (platform) {
    platformStatusBar.text = `$(symbol-class) Kotlin: ${platform}`;
    platformStatusBar.tooltip = platform === "commonMain"
      ? "Click to switch primary target for common files"
      : `KMP source set: ${platform}`;
    platformStatusBar.show();
  } else {
    platformStatusBar.hide();
  }
}

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
    initializationOptions: {
      buildVariant: getBuildVariant(),
    },
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

  // --- Android status bar ---
  androidStatusBar = vscode.window.createStatusBarItem(vscode.StatusBarAlignment.Left, 0);
  androidStatusBar.command = "kotlinReview.selectBuildVariant";
  androidStatusBar.tooltip = "Click to change Android build variant";
  updateStatusBar();
  androidStatusBar.show();
  context.subscriptions.push(androidStatusBar);

  // --- KMP platform indicator ---
  platformStatusBar = vscode.window.createStatusBarItem(vscode.StatusBarAlignment.Right, 100);
  platformStatusBar.command = "kotlinReview.selectPrimaryTarget";
  context.subscriptions.push(platformStatusBar);

  vscode.window.onDidChangeActiveTextEditor(updatePlatformIndicator, null, context.subscriptions);
  updatePlatformIndicator(vscode.window.activeTextEditor);

  // --- Commands ---

  // Select primary KMP target for common files
  context.subscriptions.push(
    vscode.commands.registerCommand("kotlinReview.selectPrimaryTarget", async () => {
      const targets = ["JVM", "Android", "iOS/Native", "JS"];
      const selected = await vscode.window.showQuickPick(targets, {
        placeHolder: "Select primary target for common files",
      });
      if (selected) {
        vscode.window.showInformationMessage(`Primary target set to: ${selected}`);
      }
    })
  );

  // Generate sources (from code action or manual trigger)
  context.subscriptions.push(
    vscode.commands.registerCommand("kotlinReview.generateSources", (projectDir?: string) => {
      runCodeGeneration(projectDir || getWorkspaceRoot(), outputChannel);
    })
  );

  // Select build variant
  context.subscriptions.push(
    vscode.commands.registerCommand("kotlinReview.selectBuildVariant", async () => {
      const variant = await vscode.window.showInputBox({
        prompt: "Enter Android build variant name",
        value: getBuildVariant(),
        placeHolder: "debug, release, stagingDebug, etc.",
      });
      if (variant && variant !== getBuildVariant()) {
        await vscode.workspace.getConfiguration("kotlinReview").update(
          "android.buildVariant", variant, vscode.ConfigurationTarget.Workspace
        );
        updateStatusBar();
        outputChannel.appendLine(`Build variant changed to: ${variant}`);
      }
    })
  );

  // --- Auto-generate on save ---
  context.subscriptions.push(
    vscode.workspace.onDidSaveTextDocument((doc) => {
      if (!getAutoGenerate()) return;
      if (doc.languageId !== "kotlin") return;

      // Debounce: reset timer on each save, fire after 3 seconds of inactivity
      if (generateTimer) clearTimeout(generateTimer);
      generateTimer = setTimeout(() => {
        runCodeGeneration(getWorkspaceRoot(), outputChannel);
      }, 3000);
    })
  );

  await client.start();
  outputChannel.appendLine("Kotlin Review LSP client started");
}

function updateStatusBar(): void {
  if (!androidStatusBar) return;
  const variant = getBuildVariant();
  if (generating) {
    androidStatusBar.text = "$(sync~spin) Generating...";
  } else {
    androidStatusBar.text = `$(gear) Android: ${variant}`;
  }
}

function getWorkspaceRoot(): string {
  const folders = vscode.workspace.workspaceFolders;
  return folders?.[0]?.uri.fsPath || "";
}

function runCodeGeneration(projectDir: string, outputChannel: vscode.OutputChannel): void {
  if (generating) return; // already running

  const variant = getBuildVariant();
  const cap = variant.charAt(0).toUpperCase() + variant.slice(1);
  const tasks = [`generate${cap}Resources`, `generate${cap}BuildConfig`, "--continue", "-q"];

  generating = true;
  updateStatusBar();

  vscode.window.withProgress({
    location: vscode.ProgressLocation.Window,
    title: `Generating Android sources (${variant})...`,
  }, () => {
    return new Promise<void>((resolve) => {
      const cwd = projectDir || getWorkspaceRoot();
      if (!cwd) {
        generating = false;
        updateStatusBar();
        resolve();
        return;
      }

      outputChannel.appendLine(`[Android] Running: ./gradlew ${tasks.join(" ")} in ${cwd}`);

      const proc = cp.spawn("./gradlew", tasks, {
        cwd,
        shell: true,
        stdio: ["ignore", "pipe", "pipe"],
      });

      let stderr = "";
      proc.stdout?.on("data", (data: Buffer) => {
        const text = data.toString().trim();
        if (text) outputChannel.appendLine(`[Android] ${text}`);
      });
      proc.stderr?.on("data", (data: Buffer) => {
        stderr += data.toString();
      });

      proc.on("close", (code) => {
        generating = false;
        updateStatusBar();
        if (code === 0) {
          outputChannel.appendLine("[Android] Code generation completed successfully");
        } else {
          outputChannel.appendLine(`[Android] Code generation finished with exit code ${code}`);
          if (stderr.trim()) {
            outputChannel.appendLine(`[Android] ${stderr.trim().split("\n").slice(0, 5).join("\n")}`);
          }
        }
        resolve();
      });

      proc.on("error", (err) => {
        generating = false;
        updateStatusBar();
        outputChannel.appendLine(`[Android] Failed to run Gradle: ${err.message}`);
        resolve();
      });
    });
  });
}

export async function deactivate(): Promise<void> {
  if (generateTimer) clearTimeout(generateTimer);
  platformStatusBar?.dispose();
  platformStatusBar = undefined;
  if (client) {
    await client.stop();
    client = undefined;
  }
}

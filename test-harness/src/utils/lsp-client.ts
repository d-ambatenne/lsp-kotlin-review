import { ChildProcess, spawn } from 'node:child_process';
import * as fs from 'node:fs';
import type {
  InitializeParams,
  Hover,
  Location,
  CompletionItem,
  DocumentSymbol,
  SymbolInformation,
  Diagnostic,
  InitializeResult,
  PublishDiagnosticsParams,
} from 'vscode-languageserver-protocol';

// vscode-jsonrpc/node exports stream-based createMessageConnection
// that accepts NodeJS.ReadableStream/WritableStream directly
// eslint-disable-next-line @typescript-eslint/no-var-requires
const { createMessageConnection } = require('vscode-jsonrpc/node') as {
  createMessageConnection(input: NodeJS.ReadableStream, output: NodeJS.WritableStream): JsonRpcConnection;
};

interface JsonRpcConnection {
  listen(): void;
  sendRequest(method: string, ...params: any[]): Promise<any>;
  sendNotification(method: string, ...params: any[]): void;
  onNotification(method: string, handler: (...params: any[]) => void): void;
  onRequest(method: string, handler: (...params: any[]) => any): void;
  dispose(): void;
}

export interface LspClientOptions {
  serverJarPath: string;
  workspaceRoot: string;
  javaPath?: string;
  jvmArgs?: string[];
  initTimeoutMs?: number;
  requestTimeoutMs?: number;
}

export class LspClient {
  private process: ChildProcess | null = null;
  private connection: JsonRpcConnection | null = null;
  private _diagnostics: Map<string, Diagnostic[]> = new Map();
  private _messages: string[] = [];
  private _stderr: string[] = [];
  private _serverCrashed = false;

  constructor(private options: LspClientOptions) {}

  get diagnostics(): Map<string, Diagnostic[]> {
    return this._diagnostics;
  }

  get messages(): string[] {
    return this._messages;
  }

  get serverCrashed(): boolean {
    return this._serverCrashed;
  }

  async start(): Promise<InitializeResult> {
    const javaPath = this.options.javaPath ?? `${process.env.JAVA_HOME ?? ''}/bin/java`;
    const jvmArgs = this.options.jvmArgs ?? ['-Xmx2g', '-XX:+UseG1GC'];

    this.process = spawn(javaPath, [
      ...jvmArgs,
      '-jar',
      this.options.serverJarPath,
    ], {
      stdio: ['pipe', 'pipe', 'pipe'],
    });

    this.process.stderr?.on('data', (data: Buffer) => {
      const text = data.toString();
      this._stderr.push(text);
      if (text.includes('Exception') || text.includes('OutOfMemoryError')) {
        this._serverCrashed = true;
      }
    });

    this.process.on('exit', (code) => {
      if (code !== 0 && code !== null) {
        this._serverCrashed = true;
      }
    });

    this.connection = createMessageConnection(
      this.process.stdout!,
      this.process.stdin!,
    );

    // Listen for diagnostics (use string method name to avoid type conflicts)
    this.connection.onNotification('textDocument/publishDiagnostics', (params: PublishDiagnosticsParams) => {
      this._diagnostics.set(params.uri, params.diagnostics);
    });

    this.connection.onNotification('window/logMessage', (params: any) => {
      this._messages.push(`[LOG] ${params.message}`);
    });

    this.connection.onNotification('window/showMessage', (params: any) => {
      this._messages.push(`[MSG] ${params.message}`);
    });

    // Handle dynamic registration requests from server
    this.connection.onRequest('client/registerCapability', () => {
      return;
    });

    this.connection.listen();

    const initParams: InitializeParams = {
      processId: process.pid ?? null,
      rootUri: `file://${this.options.workspaceRoot}`,
      capabilities: {
        textDocument: {
          hover: { contentFormat: ['markdown', 'plaintext'] },
          completion: { completionItem: { snippetSupport: false } },
          publishDiagnostics: { relatedInformation: true },
          documentSymbol: { hierarchicalDocumentSymbolSupport: true },
        },
      },
      workspaceFolders: [{
        uri: `file://${this.options.workspaceRoot}`,
        name: 'workspace',
      }],
    };

    const timeoutMs = this.options.initTimeoutMs ?? 30000;
    const result = await withTimeout(
      this.connection.sendRequest('initialize', initParams),
      timeoutMs,
      'Initialize request timed out',
    ) as InitializeResult;

    this.connection.sendNotification('initialized', {});

    return result;
  }

  /**
   * Wait for the server to finish initializing (analysis session ready).
   * The server logs "Analysis session ready" when the session is built.
   */
  async waitForReady(maxWaitMs: number = 60000): Promise<void> {
    const start = Date.now();
    while (Date.now() - start < maxWaitMs) {
      const hasReady = this._messages.some(m =>
        m.includes('Analysis session ready') || m.includes('session ready')
      );
      if (hasReady) return;
      await sleep(1000);
    }
    console.log(`  (waited ${((Date.now() - start) / 1000).toFixed(0)}s for session, proceeding)`);
  }

  async openFile(filePath: string): Promise<{ uri: string; diagnostics: Diagnostic[] }> {
    if (!this.connection) throw new Error('Not connected');

    const uri = `file://${filePath}`;
    const content = fs.readFileSync(filePath, 'utf-8');

    this.connection.sendNotification('textDocument/didOpen', {
      textDocument: { uri, languageId: 'kotlin', version: 1, text: content },
    });

    await this.waitForDiagnostics(uri, 10000);

    return { uri, diagnostics: this._diagnostics.get(uri) ?? [] };
  }

  async hover(uri: string, line: number, character: number): Promise<Hover | null> {
    if (!this.connection) throw new Error('Not connected');

    try {
      const result = await withTimeout(
        this.connection.sendRequest('textDocument/hover', {
          textDocument: { uri },
          position: { line, character },
        }),
        this.options.requestTimeoutMs ?? 10000,
        'Hover request timed out',
      ) as Hover | null;
      return result;
    } catch {
      return null;
    }
  }

  async definition(uri: string, line: number, character: number): Promise<Location[]> {
    if (!this.connection) throw new Error('Not connected');

    try {
      const result = await withTimeout(
        this.connection.sendRequest('textDocument/definition', {
          textDocument: { uri },
          position: { line, character },
        }),
        this.options.requestTimeoutMs ?? 10000,
        'Definition request timed out',
      ) as any;
      if (!result) return [];
      if (Array.isArray(result)) {
        return result.map((r: any) => 'uri' in r ? r as Location : { uri: r.targetUri, range: r.targetRange });
      }
      if (typeof result === 'object' && 'uri' in result) return [result as Location];
      return [];
    } catch {
      return [];
    }
  }

  async completion(uri: string, line: number, character: number): Promise<CompletionItem[]> {
    if (!this.connection) throw new Error('Not connected');

    try {
      const result = await withTimeout(
        this.connection.sendRequest('textDocument/completion', {
          textDocument: { uri },
          position: { line, character },
        }),
        this.options.requestTimeoutMs ?? 10000,
        'Completion request timed out',
      ) as any;
      if (!result) return [];
      if (Array.isArray(result)) return result;
      return result.items ?? [];
    } catch {
      return [];
    }
  }

  async documentSymbols(uri: string): Promise<DocumentSymbol[]> {
    if (!this.connection) throw new Error('Not connected');

    try {
      const result = await withTimeout(
        this.connection.sendRequest('textDocument/documentSymbol', {
          textDocument: { uri },
        }),
        this.options.requestTimeoutMs ?? 10000,
        'DocumentSymbol request timed out',
      ) as any;
      if (!result || !Array.isArray(result) || result.length === 0) return [];
      // DocumentSymbol has 'range' + 'selectionRange', SymbolInformation has 'location'
      if ('range' in result[0]) {
        return result as DocumentSymbol[];
      }
      if ('location' in result[0]) {
        // Convert SymbolInformation to DocumentSymbol-like
        return (result as SymbolInformation[]).map(si => ({
          name: si.name,
          kind: si.kind,
          range: si.location.range,
          selectionRange: si.location.range,
        } as DocumentSymbol));
      }
      return [];
    } catch {
      return [];
    }
  }

  async references(uri: string, line: number, character: number): Promise<Location[]> {
    if (!this.connection) throw new Error('Not connected');

    try {
      const result = await withTimeout(
        this.connection.sendRequest('textDocument/references', {
          textDocument: { uri },
          position: { line, character },
          context: { includeDeclaration: true },
        }),
        this.options.requestTimeoutMs ?? 10000,
        'References request timed out',
      ) as Location[] | null;
      return result ?? [];
    } catch {
      return [];
    }
  }

  getDiagnostics(uri: string): Diagnostic[] {
    return this._diagnostics.get(uri) ?? [];
  }

  getStderr(): string {
    return this._stderr.join('');
  }

  async shutdown(): Promise<void> {
    if (!this.connection) return;

    try {
      await withTimeout(
        this.connection.sendRequest('shutdown'),
        5000,
        'Shutdown timed out',
      );
      this.connection.sendNotification('exit');
    } catch {
      // Best effort
    }

    this.connection.dispose();
    this.connection = null;

    if (this.process) {
      this.process.kill('SIGTERM');
      await new Promise<void>((resolve) => {
        const timer = setTimeout(() => {
          this.process?.kill('SIGKILL');
          resolve();
        }, 5000);
        this.process?.on('exit', () => {
          clearTimeout(timer);
          resolve();
        });
      });
      this.process = null;
    }
  }

  private async waitForDiagnostics(uri: string, maxWaitMs: number): Promise<void> {
    const start = Date.now();
    while (Date.now() - start < maxWaitMs) {
      if (this._diagnostics.has(uri)) return;
      await sleep(200);
    }
  }
}

function sleep(ms: number): Promise<void> {
  return new Promise(resolve => setTimeout(resolve, ms));
}

function withTimeout<T>(promise: Promise<T>, ms: number, message: string): Promise<T> {
  return new Promise<T>((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error(message)), ms);
    promise.then(
      (value) => { clearTimeout(timer); resolve(value); },
      (error) => { clearTimeout(timer); reject(error); },
    );
  });
}

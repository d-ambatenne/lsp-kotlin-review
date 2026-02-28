import * as path from 'node:path';
import * as fs from 'node:fs';
import { LspClient, LspClientOptions } from './utils/lsp-client';
import { ProjectConfig, ProjectReport, Feature } from './types';
import { ProjectManager } from './project-manager';
import { HoverValidator } from './validators/hover';
import { DefinitionValidator } from './validators/definition';
import { CompletionValidator } from './validators/completion';
import { DiagnosticsValidator } from './validators/diagnostics';
import { SymbolsValidator } from './validators/symbols';
import { ReferencesValidator } from './validators/references';
import { HoverSample, CompletionSample } from './types';

export interface RunOptions {
  serverJarPath: string;
  javaPath?: string;
  jvmArgs?: string[];
  maxFilesPerProject?: number;
  requestTimeoutMs?: number;
  initTimeoutMs?: number;
}

export class LspRunner {
  private projectManager = new ProjectManager();

  async runProject(project: ProjectConfig, options: RunOptions): Promise<{ report: ProjectReport; hoverSamples: HoverSample[]; completionSamples: CompletionSample[] }> {
    console.log(`\n--- Testing project: ${project.name} (${project.type}) ---`);

    // Clone
    const workspacePath = await this.projectManager.clone(project);
    console.log(`  Workspace: ${workspacePath}`);

    // Find .kt files
    const ktFiles = await this.projectManager.findKotlinFiles(workspacePath);
    const maxFiles = options.maxFilesPerProject ?? 50;
    const filesToTest = ktFiles.slice(0, maxFiles);
    console.log(`  Found ${ktFiles.length} .kt files, testing ${filesToTest.length}`);

    if (filesToTest.length === 0) {
      console.log(`  WARNING: No .kt files found`);
      return {
        report: {
          name: project.name,
          type: project.type,
          files_tested: 0,
          startup_time_s: 0,
          server_crashed: false,
        },
        hoverSamples: [],
        completionSamples: [],
      };
    }

    // Start LSP server
    const clientOptions: LspClientOptions = {
      serverJarPath: options.serverJarPath,
      workspaceRoot: workspacePath,
      javaPath: options.javaPath,
      jvmArgs: options.jvmArgs,
      initTimeoutMs: options.initTimeoutMs ?? 30000,
      requestTimeoutMs: options.requestTimeoutMs ?? 10000,
    };

    const client = new LspClient(clientOptions);
    const startTime = Date.now();

    try {
      console.log(`  Starting LSP server...`);
      await client.start();
      await client.waitForReady(120000);
      const startupTime = (Date.now() - startTime) / 1000;
      console.log(`  Server ready in ${startupTime.toFixed(1)}s`);
      if (client.messages.length > 0) {
        for (const msg of client.messages) {
          console.log(`  [server] ${msg}`);
        }
      }

      const report: ProjectReport = {
        name: project.name,
        type: project.type,
        files_tested: filesToTest.length,
        startup_time_s: startupTime,
        server_crashed: false,
      };

      let allHoverSamples: HoverSample[] = [];
      let allCompletionSamples: CompletionSample[] = [];

      // Open all files first (triggers didOpen, which the server needs to know about files)
      console.log(`  Opening ${filesToTest.length} files...`);
      for (const file of filesToTest) {
        try {
          await client.openFile(file);
        } catch (error) {
          console.log(`  Warning: failed to open ${file}: ${error}`);
        }
      }

      // Run validators based on configured features
      for (const feature of project.features) {
        if (client.serverCrashed) {
          console.log(`  Server crashed, stopping validation`);
          report.server_crashed = true;
          break;
        }

        console.log(`  Running ${feature} validator...`);
        try {
          const result = await this.runValidator(feature, client, filesToTest);
          (report as any)[feature] = result.report;
          if (result.hoverSamples) {
            allHoverSamples.push(...result.hoverSamples);
          }
          if (result.completionSamples) {
            allCompletionSamples.push(...result.completionSamples);
          }
        } catch (error) {
          console.log(`  ERROR in ${feature}: ${error}`);
        }
      }

      report.server_crashed = client.serverCrashed;

      return { report, hoverSamples: allHoverSamples, completionSamples: allCompletionSamples };
    } finally {
      console.log(`  Shutting down server...`);
      await client.shutdown();
    }
  }

  private async runValidator(feature: Feature, client: LspClient, files: string[]): Promise<{ report: any; hoverSamples?: HoverSample[]; completionSamples?: CompletionSample[] }> {
    switch (feature) {
      case 'hover': {
        const validator = new HoverValidator();
        const result = await validator.validate(client, files);
        return { report: result.report, hoverSamples: result.samples };
      }
      case 'definition': {
        const validator = new DefinitionValidator();
        return { report: await validator.validate(client, files) };
      }
      case 'completion': {
        const validator = new CompletionValidator();
        const result = await validator.validate(client, files);
        return { report: result.report, completionSamples: result.samples };
      }
      case 'diagnostics': {
        const validator = new DiagnosticsValidator();
        return { report: await validator.validate(client, files) };
      }
      case 'symbols': {
        const validator = new SymbolsValidator();
        return { report: await validator.validate(client, files) };
      }
      case 'references': {
        const validator = new ReferencesValidator();
        return { report: await validator.validate(client, files) };
      }
      default:
        throw new Error(`Unknown feature: ${feature}`);
    }
  }
}

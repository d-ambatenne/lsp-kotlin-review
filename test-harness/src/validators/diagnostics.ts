import { LspClient } from '../utils/lsp-client';
import { DiagnosticsReport } from '../types';
import { DiagnosticSeverity } from 'vscode-languageserver-protocol';

export class DiagnosticsValidator {
  /**
   * Open each file and collect diagnostics.
   * Checks for server crashes and counts by severity.
   */
  async validate(client: LspClient, files: string[]): Promise<DiagnosticsReport> {
    let filesOpened = 0;
    let filesWithDiagnostics = 0;
    let totalDiagnostics = 0;
    let errorCount = 0;
    let warningCount = 0;
    let infoCount = 0;
    let hintCount = 0;
    let serverCrashes = 0;

    for (const file of files) {
      filesOpened++;
      try {
        const { diagnostics } = await client.openFile(file);

        if (diagnostics.length > 0) {
          filesWithDiagnostics++;
          totalDiagnostics += diagnostics.length;

          for (const diag of diagnostics) {
            switch (diag.severity) {
              case DiagnosticSeverity.Error:
                errorCount++;
                break;
              case DiagnosticSeverity.Warning:
                warningCount++;
                break;
              case DiagnosticSeverity.Information:
                infoCount++;
                break;
              case DiagnosticSeverity.Hint:
                hintCount++;
                break;
            }
          }
        }

        // Check for server crashes after each file
        if (client.serverCrashed) {
          serverCrashes++;
          console.log(`    Server crashed processing: ${file}`);
          break;
        }
      } catch (error) {
        console.log(`    Error opening file: ${file}: ${error}`);
      }
    }

    console.log(`    Diagnostics: ${filesOpened} files opened, ${filesWithDiagnostics} with diagnostics, ${totalDiagnostics} total (${errorCount}E/${warningCount}W/${infoCount}I/${hintCount}H), ${serverCrashes} crashes`);

    return {
      files_opened: filesOpened,
      files_with_diagnostics: filesWithDiagnostics,
      total_diagnostics: totalDiagnostics,
      errors: errorCount,
      warnings: warningCount,
      info: infoCount,
      hints: hintCount,
      server_crashes: serverCrashes,
    };
  }
}

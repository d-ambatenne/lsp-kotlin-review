import { LspClient } from '../utils/lsp-client';
import { SymbolsReport } from '../types';

export class SymbolsValidator {
  /**
   * Send textDocument/documentSymbol for each file.
   * Verify at least 1 symbol for non-empty files.
   */
  async validate(client: LspClient, files: string[]): Promise<SymbolsReport> {
    let filesTested = 0;
    let totalSymbols = 0;
    let filesWithSymbols = 0;
    let filesEmpty = 0;
    let errors = 0;

    for (const file of files) {
      filesTested++;
      const uri = `file://${file}`;

      try {
        const symbols = await client.documentSymbols(uri);
        const count = countSymbols(symbols);
        totalSymbols += count;

        if (count > 0) {
          filesWithSymbols++;
        } else {
          filesEmpty++;
        }
      } catch {
        errors++;
      }
    }

    const successRate = filesTested > 0 ? filesWithSymbols / filesTested : 0;
    console.log(`    Symbols: ${filesWithSymbols}/${filesTested} files with symbols, ${totalSymbols} total, ${errors} errors`);

    return {
      files_tested: filesTested,
      total_symbols: totalSymbols,
      files_with_symbols: filesWithSymbols,
      files_empty: filesEmpty,
      errors,
      success_rate: successRate,
    };
  }
}

function countSymbols(symbols: any[]): number {
  let count = 0;
  for (const sym of symbols) {
    count++;
    if (sym.children) {
      count += countSymbols(sym.children);
    }
  }
  return count;
}

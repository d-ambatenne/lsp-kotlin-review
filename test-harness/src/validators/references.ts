import * as fs from 'node:fs';
import { LspClient } from '../utils/lsp-client';
import { extractIdentifiers } from '../utils/kotlin-parser';
import { FeatureReport, SymbolLocation } from '../types';

const MAX_SYMBOLS_PER_FILE = 20;

export class ReferencesValidator {
  /**
   * For each identifier, send textDocument/references request.
   * Records how many identifiers have at least one reference.
   */
  async validate(client: LspClient, files: string[]): Promise<FeatureReport> {
    let total = 0;
    let resolved = 0;
    let unresolved = 0;
    let errors = 0;

    for (const file of files) {
      const source = fs.readFileSync(file, 'utf-8');
      const uri = `file://${file}`;

      // Get structural symbols
      let positions: SymbolLocation[] = [];
      try {
        const docSymbols = await client.documentSymbols(uri);
        positions = flattenSymbols(docSymbols);
      } catch {
        // fallback
      }

      // Add regex identifiers
      const regexIdents = extractIdentifiers(source);
      const allPositions = [...positions, ...regexIdents].slice(0, MAX_SYMBOLS_PER_FILE);

      for (const pos of allPositions) {
        total++;
        try {
          const locations = await client.references(uri, pos.line, pos.character);
          if (locations.length > 0) {
            resolved++;
          } else {
            unresolved++;
          }
        } catch {
          errors++;
        }
      }
    }

    const successRate = total > 0 ? resolved / total : 0;
    console.log(`    References: ${resolved}/${total} found (${(successRate * 100).toFixed(1)}%), ${errors} errors`);

    return { total, resolved, unresolved, errors, success_rate: successRate };
  }
}

function flattenSymbols(symbols: any[], result: SymbolLocation[] = []): SymbolLocation[] {
  for (const sym of symbols) {
    result.push({
      name: sym.name,
      line: sym.selectionRange?.start?.line ?? sym.range?.start?.line ?? 0,
      character: sym.selectionRange?.start?.character ?? sym.range?.start?.character ?? 0,
      kind: String(sym.kind),
    });
    if (sym.children) {
      flattenSymbols(sym.children, result);
    }
  }
  return result;
}

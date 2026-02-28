import * as fs from 'node:fs';
import { LspClient } from '../utils/lsp-client';
import { extractIdentifiers, extractContext } from '../utils/kotlin-parser';
import { FeatureReport, HoverSample, SymbolLocation } from '../types';

const MAX_SYMBOLS_PER_FILE = 50;

export class HoverValidator {
  /**
   * For each .kt file, extract identifiers and send hover requests.
   * Uses documentSymbol first for structural symbols, then regex for usage sites.
   */
  async validate(client: LspClient, files: string[]): Promise<{ report: FeatureReport; samples: HoverSample[] }> {
    let total = 0;
    let resolved = 0;
    let unresolved = 0;
    let errors = 0;
    const samples: HoverSample[] = [];

    for (const file of files) {
      const source = fs.readFileSync(file, 'utf-8');
      const uri = `file://${file}`;

      // First try documentSymbol for structural symbols
      let symbolLocations: SymbolLocation[] = [];
      try {
        const docSymbols = await client.documentSymbols(uri);
        symbolLocations = flattenDocumentSymbols(docSymbols);
      } catch {
        // Fall back to regex extraction
      }

      // Add regex-extracted identifiers (usage sites)
      const regexIdents = extractIdentifiers(source);

      // Merge: document symbols first, then regex identifiers, capped
      const allPositions = [...symbolLocations, ...regexIdents].slice(0, MAX_SYMBOLS_PER_FILE);

      for (const pos of allPositions) {
        total++;
        try {
          const hover = await client.hover(uri, pos.line, pos.character);
          if (hover && hover.contents) {
            resolved++;
            // Collect sample for AI evaluation
            if (samples.length < 200) {
              const content = typeof hover.contents === 'string'
                ? hover.contents
                : 'value' in hover.contents
                  ? hover.contents.value
                  : JSON.stringify(hover.contents);
              samples.push({
                file,
                symbol: pos.name,
                line: pos.line,
                character: pos.character,
                hoverContent: content,
                sourceContext: extractContext(source, pos.line),
              });
            }
          } else {
            unresolved++;
          }
        } catch {
          errors++;
        }
      }
    }

    const successRate = total > 0 ? resolved / total : 0;
    console.log(`    Hover: ${resolved}/${total} resolved (${(successRate * 100).toFixed(1)}%), ${errors} errors`);

    return {
      report: { total, resolved, unresolved, errors, success_rate: successRate },
      samples,
    };
  }
}

function flattenDocumentSymbols(symbols: any[], result: SymbolLocation[] = []): SymbolLocation[] {
  for (const sym of symbols) {
    result.push({
      name: sym.name,
      line: sym.selectionRange?.start?.line ?? sym.range?.start?.line ?? 0,
      character: sym.selectionRange?.start?.character ?? sym.range?.start?.character ?? 0,
      kind: String(sym.kind),
    });
    if (sym.children) {
      flattenDocumentSymbols(sym.children, result);
    }
  }
  return result;
}

import * as fs from 'node:fs';
import { LspClient } from '../utils/lsp-client';
import { findDotCompletionPositions, findPartialCompletionPositions, findKeywordCompletionPositions, extractContext } from '../utils/kotlin-parser';
import { CompletionReport, CompletionSample } from '../types';

const MAX_POSITIONS_PER_FILE = 20;
const MAX_KEYWORD_POSITIONS_PER_FILE = 5;

export class CompletionValidator {
  /**
   * Test completion at dot-access, partial identifier, and keyword positions.
   * Collects samples for AI evaluation.
   */
  async validate(client: LspClient, files: string[]): Promise<{ report: CompletionReport; samples: CompletionSample[] }> {
    let positionsTested = 0;
    let nonEmpty = 0;
    let empty = 0;
    let errors = 0;
    const samples: CompletionSample[] = [];

    for (const file of files) {
      const source = fs.readFileSync(file, 'utf-8');
      const uri = `file://${file}`;

      // Find completion-worthy positions
      const dotPositions = findDotCompletionPositions(source);
      const partialPositions = findPartialCompletionPositions(source);
      const keywordPositions = findKeywordCompletionPositions(source);

      // Sample positions: dots, partials, keywords
      const positions: Array<{ line: number; character: number; name: string; kind: string }> = [
        ...dotPositions.slice(0, Math.ceil(MAX_POSITIONS_PER_FILE * 0.5)),
        ...partialPositions.slice(0, Math.floor(MAX_POSITIONS_PER_FILE * 0.3)),
        ...keywordPositions.slice(0, MAX_KEYWORD_POSITIONS_PER_FILE),
      ];

      for (const pos of positions) {
        positionsTested++;
        try {
          const items = await client.completion(uri, pos.line, pos.character);
          if (items.length > 0) {
            nonEmpty++;
          } else {
            empty++;
          }

          // Collect sample for AI evaluation
          if (samples.length < 200) {
            const triggerKind = pos.kind === 'dot_completion' ? 'dot' as const
              : pos.kind === 'keyword_completion' ? 'keyword' as const
              : 'partial' as const;

            samples.push({
              file,
              line: pos.line,
              character: pos.character,
              triggerKind,
              prefix: pos.name,
              sourceContext: extractContext(source, pos.line),
              items: items.slice(0, 15).map(item => item.label ?? String(item)),
              itemCount: items.length,
            });
          }
        } catch {
          errors++;
        }
      }
    }

    const successRate = positionsTested > 0 ? nonEmpty / positionsTested : 0;
    console.log(`    Completion: ${nonEmpty}/${positionsTested} non-empty (${(successRate * 100).toFixed(1)}%), ${errors} errors`);

    return {
      report: {
        positions_tested: positionsTested,
        non_empty: nonEmpty,
        empty,
        errors,
        success_rate: successRate,
      },
      samples,
    };
  }
}

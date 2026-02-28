import * as fs from 'node:fs';
import { LspClient } from '../utils/lsp-client';
import { findDotCompletionPositions, findPartialCompletionPositions } from '../utils/kotlin-parser';
import { CompletionReport } from '../types';

const MAX_POSITIONS_PER_FILE = 20;

export class CompletionValidator {
  /**
   * Test completion at dot-access positions and partial identifier positions.
   */
  async validate(client: LspClient, files: string[]): Promise<CompletionReport> {
    let positionsTested = 0;
    let nonEmpty = 0;
    let empty = 0;
    let errors = 0;

    for (const file of files) {
      const source = fs.readFileSync(file, 'utf-8');
      const uri = `file://${file}`;

      // Find completion-worthy positions
      const dotPositions = findDotCompletionPositions(source);
      const partialPositions = findPartialCompletionPositions(source);

      // Sample positions (prefer dots, then partials)
      const positions = [
        ...dotPositions.slice(0, Math.ceil(MAX_POSITIONS_PER_FILE * 0.6)),
        ...partialPositions.slice(0, Math.floor(MAX_POSITIONS_PER_FILE * 0.4)),
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
        } catch {
          errors++;
        }
      }
    }

    const successRate = positionsTested > 0 ? nonEmpty / positionsTested : 0;
    console.log(`    Completion: ${nonEmpty}/${positionsTested} non-empty (${(successRate * 100).toFixed(1)}%), ${errors} errors`);

    return {
      positions_tested: positionsTested,
      non_empty: nonEmpty,
      empty,
      errors,
      success_rate: successRate,
    };
  }
}

import Anthropic from '@anthropic-ai/sdk';
import { HoverSample, CompletionSample, AIEvaluation, AIEvalDetail, HarnessReport, Regression } from './types';

const DEFAULT_MODEL = 'claude-sonnet-4-6';
const MAX_SAMPLES = 50;

interface EvalConfig {
  apiKey?: string;
  model?: string;
  maxSamples?: number;
}

export class AIEvaluator {
  private client: Anthropic;
  private model: string;
  private maxSamples: number;

  constructor(config: EvalConfig = {}) {
    this.client = new Anthropic({ apiKey: config.apiKey });
    this.model = config.model ?? DEFAULT_MODEL;
    this.maxSamples = config.maxSamples ?? MAX_SAMPLES;
  }

  /**
   * Evaluate hover quality by sending samples to Claude for scoring.
   */
  async evaluateHoverQuality(samples: HoverSample[]): Promise<AIEvaluation> {
    const toEvaluate = samples
      .filter(s => s.hoverContent)
      .slice(0, this.maxSamples);

    if (toEvaluate.length === 0) {
      return { hover_quality_avg: 0, completion_quality_avg: 0, diagnostic_accuracy: 0, samples_evaluated: 0, details: [] };
    }

    // Batch into groups of 10 for efficient API usage
    const batches = chunk(toEvaluate, 10);
    const allDetails: AIEvalDetail[] = [];

    for (const batch of batches) {
      const details = await this.evaluateHoverBatch(batch);
      allDetails.push(...details);
    }

    const avgScore = allDetails.length > 0
      ? allDetails.reduce((sum, d) => sum + d.score, 0) / allDetails.length
      : 0;

    return {
      hover_quality_avg: avgScore,
      completion_quality_avg: 0, // Filled by evaluateCompletionQuality
      diagnostic_accuracy: 0, // Filled by evaluateDiagnostics
      samples_evaluated: allDetails.length,
      details: allDetails,
    };
  }

  /**
   * Evaluate completion quality by sending samples to Claude for scoring.
   * Evaluates dot-completion relevance, partial-completion accuracy, and keyword completeness.
   */
  async evaluateCompletionQuality(samples: CompletionSample[]): Promise<{ avg: number; details: AIEvalDetail[] }> {
    const toEvaluate = samples
      .filter(s => s.itemCount > 0)
      .slice(0, this.maxSamples);

    if (toEvaluate.length === 0) {
      return { avg: 0, details: [] };
    }

    // Separate by trigger kind for targeted evaluation
    const dotSamples = toEvaluate.filter(s => s.triggerKind === 'dot');
    const partialSamples = toEvaluate.filter(s => s.triggerKind === 'partial');
    const keywordSamples = toEvaluate.filter(s => s.triggerKind === 'keyword');

    const allDetails: AIEvalDetail[] = [];

    // Evaluate dot-completions in batches
    for (const batch of chunk(dotSamples, 10)) {
      const details = await this.evaluateCompletionBatch(batch, 'dot');
      allDetails.push(...details);
    }

    // Evaluate partial-completions in batches
    for (const batch of chunk(partialSamples, 10)) {
      const details = await this.evaluateCompletionBatch(batch, 'partial');
      allDetails.push(...details);
    }

    // Evaluate keyword completions in batches
    for (const batch of chunk(keywordSamples, 10)) {
      const details = await this.evaluateKeywordBatch(batch);
      allDetails.push(...details);
    }

    const avg = allDetails.length > 0
      ? allDetails.reduce((sum, d) => sum + d.score, 0) / allDetails.length
      : 0;

    return { avg, details: allDetails };
  }

  /**
   * Evaluate diagnostics for false positive rate.
   */
  async evaluateDiagnostics(
    diagnosticSamples: Array<{ file: string; source: string; diagnostics: Array<{ message: string; line: number; severity: string }> }>
  ): Promise<number> {
    if (diagnosticSamples.length === 0) return 0;

    const samplesText = diagnosticSamples.slice(0, 10).map((s, i) => {
      const diagList = s.diagnostics.map(d => `  - Line ${d.line}: [${d.severity}] ${d.message}`).join('\n');
      return `### File ${i + 1}: ${s.file}\n\`\`\`kotlin\n${s.source.slice(0, 500)}\n\`\`\`\nDiagnostics:\n${diagList}`;
    }).join('\n\n');

    const response = await this.client.messages.create({
      model: this.model,
      max_tokens: 1000,
      messages: [{
        role: 'user',
        content: `You are evaluating the accuracy of a Kotlin LSP server's diagnostics. For each diagnostic below, classify it as a TRUE POSITIVE (real issue) or FALSE POSITIVE (incorrect warning).

${samplesText}

Respond with a JSON object:
{
  "total": <number>,
  "true_positives": <number>,
  "false_positives": <number>,
  "accuracy": <number between 0 and 1>
}

Only output JSON, no other text.`,
      }],
    });

    try {
      const text = response.content[0].type === 'text' ? response.content[0].text : '';
      const result = JSON.parse(text);
      return result.accuracy ?? 0;
    } catch {
      return 0;
    }
  }

  /**
   * Analyze regressions between baseline and current results.
   */
  async analyzeRegressions(regressions: Regression[], report: HarnessReport): Promise<string> {
    if (regressions.length === 0) return 'No regressions detected.';

    const regressionText = regressions.map(r =>
      `- ${r.project}/${r.feature}: ${(r.baseline_rate * 100).toFixed(1)}% -> ${(r.current_rate * 100).toFixed(1)}% (${(r.delta * 100).toFixed(1)}%)`
    ).join('\n');

    const response = await this.client.messages.create({
      model: this.model,
      max_tokens: 500,
      messages: [{
        role: 'user',
        content: `A Kotlin LSP server has the following regressions compared to its baseline:

${regressionText}

Report version: ${report.version}

Analyze these regressions. Which are concerning? What might have caused them? Provide a brief analysis (2-3 sentences per regression).`,
      }],
    });

    return response.content[0].type === 'text' ? response.content[0].text : '';
  }

  private async evaluateHoverBatch(batch: HoverSample[]): Promise<AIEvalDetail[]> {
    const samplesText = batch.map((s, i) => {
      return `### Sample ${i + 1}
Symbol: \`${s.symbol}\` at line ${s.line}
Source context:
\`\`\`kotlin
${s.sourceContext}
\`\`\`
Hover result:
\`\`\`
${s.hoverContent}
\`\`\``;
    }).join('\n\n');

    const response = await this.client.messages.create({
      model: this.model,
      max_tokens: 2000,
      messages: [{
        role: 'user',
        content: `You are evaluating the quality of hover tooltips from a Kotlin LSP server. For each sample, rate the hover result on a 1-5 scale:

1 = Wrong or misleading information
2 = Technically correct but unhelpful (e.g., just shows "Any" for everything)
3 = Acceptable — shows type/signature but missing context
4 = Good — shows accurate type signature with useful detail
5 = Excellent — shows full signature, documentation, and relevant context

${samplesText}

Respond with a JSON array:
[
  { "sample": 1, "score": 4, "explanation": "..." },
  ...
]

Only output JSON, no other text.`,
      }],
    });

    try {
      const text = response.content[0].type === 'text' ? response.content[0].text : '';
      // Extract JSON from response (handle markdown code blocks)
      const jsonMatch = text.match(/\[[\s\S]*\]/);
      if (!jsonMatch) return [];

      const results = JSON.parse(jsonMatch[0]) as Array<{ sample: number; score: number; explanation: string }>;
      return results.map((r, i) => ({
        feature: 'hover',
        symbol: batch[r.sample - 1]?.symbol ?? batch[i]?.symbol ?? 'unknown',
        score: r.score,
        explanation: r.explanation,
      }));
    } catch {
      return [];
    }
  }

  private async evaluateCompletionBatch(batch: CompletionSample[], kind: 'dot' | 'partial'): Promise<AIEvalDetail[]> {
    const kindDesc = kind === 'dot'
      ? 'dot/member-access completion (e.g., `str.` should suggest String members)'
      : 'partial identifier completion (e.g., typing `pri` should suggest `println`, `private`)';

    const samplesText = batch.map((s, i) => {
      return `### Sample ${i + 1}
Trigger: \`${s.prefix}\` (${s.triggerKind}) at line ${s.line}
Source context:
\`\`\`kotlin
${s.sourceContext}
\`\`\`
Completion items (${s.itemCount} total, showing top 15):
${s.items.map(item => `  - ${item}`).join('\n')}`;
    }).join('\n\n');

    const response = await this.client.messages.create({
      model: this.model,
      max_tokens: 2000,
      messages: [{
        role: 'user',
        content: `You are evaluating ${kindDesc} results from a Kotlin LSP server. For each sample, rate the completion list on a 1-5 scale:

1 = Wrong — suggestions are for the wrong type/context entirely
2 = Poor — mostly irrelevant items, missing obvious suggestions
3 = Acceptable — some relevant items but missing important ones or cluttered with noise
4 = Good — relevant items present, reasonable ranking, minor gaps
5 = Excellent — highly relevant items, good ranking, includes expected members/functions

Consider:
- Are the suggestions appropriate for the receiver type (dot) or the partial text?
- Are commonly-used members/functions included?
- Are irrelevant or wrong-type suggestions polluting the list?
- For dot completion: do items match the expected type's API?
- For partial completion: does the list include the most likely intended symbol?

${samplesText}

Respond with a JSON array:
[
  { "sample": 1, "score": 4, "explanation": "..." },
  ...
]

Only output JSON, no other text.`,
      }],
    });

    try {
      const text = response.content[0].type === 'text' ? response.content[0].text : '';
      const jsonMatch = text.match(/\[[\s\S]*\]/);
      if (!jsonMatch) return [];

      const results = JSON.parse(jsonMatch[0]) as Array<{ sample: number; score: number; explanation: string }>;
      return results.map((r, i) => ({
        feature: `completion_${kind}`,
        symbol: batch[r.sample - 1]?.prefix ?? batch[i]?.prefix ?? 'unknown',
        score: r.score,
        explanation: r.explanation,
      }));
    } catch {
      return [];
    }
  }

  private async evaluateKeywordBatch(batch: CompletionSample[]): Promise<AIEvalDetail[]> {
    const samplesText = batch.map((s, i) => {
      return `### Sample ${i + 1}
Position: ${s.prefix} at line ${s.line}
Source context:
\`\`\`kotlin
${s.sourceContext}
\`\`\`
Completion items (${s.itemCount} total, showing top 15):
${s.items.map(item => `  - ${item}`).join('\n')}`;
    }).join('\n\n');

    const response = await this.client.messages.create({
      model: this.model,
      max_tokens: 2000,
      messages: [{
        role: 'user',
        content: `You are evaluating keyword completion results from a Kotlin LSP server. These are completions triggered at positions where Kotlin keywords/statements are expected (e.g., start of a line in a function body, after '=', after '{').

For each sample, rate the keyword completion list on a 1-5 scale:

1 = Wrong — no keywords offered, or completely wrong suggestions
2 = Poor — very few keywords, missing essential ones (val, var, if, when, for, return)
3 = Acceptable — basic keywords present but missing some contextually important ones
4 = Good — comprehensive keyword list appropriate for the context
5 = Excellent — context-aware keywords with smart ordering (e.g., 'return' suggested at end of function, 'when' after '=')

Key Kotlin keywords that should generally appear in statement positions:
val, var, if, when, for, while, return, throw, try, fun, class, object, data class

After '=': if, when, try, null, true, false, object
After '{': val, var, if, for, while, return

${samplesText}

Respond with a JSON array:
[
  { "sample": 1, "score": 4, "explanation": "..." },
  ...
]

Only output JSON, no other text.`,
      }],
    });

    try {
      const text = response.content[0].type === 'text' ? response.content[0].text : '';
      const jsonMatch = text.match(/\[[\s\S]*\]/);
      if (!jsonMatch) return [];

      const results = JSON.parse(jsonMatch[0]) as Array<{ sample: number; score: number; explanation: string }>;
      return results.map((r, i) => ({
        feature: 'completion_keyword',
        symbol: batch[r.sample - 1]?.prefix ?? batch[i]?.prefix ?? 'unknown',
        score: r.score,
        explanation: r.explanation,
      }));
    } catch {
      return [];
    }
  }
}

function chunk<T>(array: T[], size: number): T[][] {
  const chunks: T[][] = [];
  for (let i = 0; i < array.length; i += size) {
    chunks.push(array.slice(i, i + size));
  }
  return chunks;
}

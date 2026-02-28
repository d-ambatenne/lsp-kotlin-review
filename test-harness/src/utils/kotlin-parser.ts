import { SymbolLocation } from '../types';

const KOTLIN_KEYWORDS = new Set([
  'abstract', 'actual', 'annotation', 'as', 'break', 'by', 'catch', 'class',
  'companion', 'const', 'constructor', 'continue', 'crossinline', 'data',
  'delegate', 'do', 'dynamic', 'else', 'enum', 'expect', 'external',
  'false', 'field', 'file', 'final', 'finally', 'for', 'fun', 'get',
  'if', 'import', 'in', 'infix', 'init', 'inline', 'inner', 'interface',
  'internal', 'is', 'it', 'lateinit', 'lazy', 'noinline', 'null', 'object',
  'open', 'operator', 'out', 'override', 'package', 'param', 'private',
  'property', 'protected', 'public', 'receiver', 'reified', 'return',
  'sealed', 'set', 'setparam', 'super', 'suspend', 'tailrec', 'this',
  'throw', 'true', 'try', 'typealias', 'typeof', 'val', 'value', 'var',
  'vararg', 'when', 'where', 'while',
]);

/**
 * Extract identifier positions from Kotlin source code using regex.
 * Returns positions suitable for hover/definition requests.
 */
export function extractIdentifiers(source: string): SymbolLocation[] {
  const identifiers: SymbolLocation[] = [];
  const lines = source.split('\n');
  const identRegex = /\b([A-Za-z_]\w*)\b/g;

  for (let lineIdx = 0; lineIdx < lines.length; lineIdx++) {
    const line = lines[lineIdx];
    // Skip comments and blank lines
    const trimmed = line.trim();
    if (trimmed.startsWith('//') || trimmed.startsWith('*') || trimmed.startsWith('/*') || trimmed === '') {
      continue;
    }
    // Skip import/package lines
    if (trimmed.startsWith('import ') || trimmed.startsWith('package ')) {
      continue;
    }

    let match: RegExpExecArray | null;
    while ((match = identRegex.exec(line)) !== null) {
      const name = match[1];
      // Skip keywords and single-char identifiers
      if (KOTLIN_KEYWORDS.has(name) || name.length <= 1) continue;
      // Skip string literals (rough heuristic)
      const beforeMatch = line.substring(0, match.index);
      const quoteCount = (beforeMatch.match(/"/g) || []).length;
      if (quoteCount % 2 !== 0) continue;

      identifiers.push({
        name,
        line: lineIdx,
        character: match.index,
        kind: 'identifier',
      });
    }
  }

  return identifiers;
}

/**
 * Extract a context window around a position for AI evaluation.
 */
export function extractContext(source: string, line: number, contextLines: number = 3): string {
  const lines = source.split('\n');
  const start = Math.max(0, line - contextLines);
  const end = Math.min(lines.length, line + contextLines + 1);
  return lines.slice(start, end).join('\n');
}

/**
 * Find positions suitable for dot-completion testing (after '.' in function bodies).
 */
export function findDotCompletionPositions(source: string): SymbolLocation[] {
  const positions: SymbolLocation[] = [];
  const lines = source.split('\n');

  for (let lineIdx = 0; lineIdx < lines.length; lineIdx++) {
    const line = lines[lineIdx];
    const trimmed = line.trim();
    if (trimmed.startsWith('//') || trimmed.startsWith('*') || trimmed === '') continue;

    // Find dots that are likely member access (not in strings/comments)
    const dotRegex = /(\w+)\./g;
    let match: RegExpExecArray | null;
    while ((match = dotRegex.exec(line)) !== null) {
      const beforeMatch = line.substring(0, match.index);
      const quoteCount = (beforeMatch.match(/"/g) || []).length;
      if (quoteCount % 2 !== 0) continue;

      positions.push({
        name: match[1] + '.',
        line: lineIdx,
        character: match.index + match[0].length, // Position after the dot
        kind: 'dot_completion',
      });
    }
  }

  return positions;
}

/**
 * Find positions where partial identifiers can be completed.
 * Looks for short identifiers (2-4 chars) inside function bodies.
 */
export function findPartialCompletionPositions(source: string): SymbolLocation[] {
  const positions: SymbolLocation[] = [];
  const lines = source.split('\n');
  let braceDepth = 0;

  for (let lineIdx = 0; lineIdx < lines.length; lineIdx++) {
    const line = lines[lineIdx];
    const trimmed = line.trim();
    if (trimmed.startsWith('//') || trimmed.startsWith('*') || trimmed === '') continue;

    for (const ch of line) {
      if (ch === '{') braceDepth++;
      if (ch === '}') braceDepth--;
    }

    // Only inside function bodies (brace depth > 0)
    if (braceDepth <= 0) continue;

    const identRegex = /\b([A-Za-z_]\w{1,3})\b/g;
    let match: RegExpExecArray | null;
    while ((match = identRegex.exec(line)) !== null) {
      const name = match[1];
      if (KOTLIN_KEYWORDS.has(name)) continue;
      const beforeMatch = line.substring(0, match.index);
      const quoteCount = (beforeMatch.match(/"/g) || []).length;
      if (quoteCount % 2 !== 0) continue;

      positions.push({
        name,
        line: lineIdx,
        character: match.index + name.length, // Position at end of partial
        kind: 'partial_completion',
      });
    }
  }

  return positions;
}

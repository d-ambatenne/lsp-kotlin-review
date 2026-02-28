export type Feature = 'hover' | 'definition' | 'completion' | 'diagnostics' | 'references' | 'symbols';
export type ProjectType = 'android' | 'kmp' | 'jvm';

export interface ProjectConfig {
  name: string;
  repo: string;
  branch: string;
  type: ProjectType;
  subdir?: string;
  localPath?: string;  // Use local directory instead of cloning
  features: Feature[];
}

export interface Thresholds {
  hover_success_rate: number;
  definition_success_rate: number;
  diagnostics_no_crash: boolean;
  completion_non_empty: number;
  startup_time_max_s: number;
}

export interface HarnessConfig {
  projects: ProjectConfig[];
  thresholds: Thresholds;
}

export interface FeatureReport {
  total: number;
  resolved: number;
  unresolved: number;
  errors: number;
  success_rate: number;
}

export interface CompletionReport {
  positions_tested: number;
  non_empty: number;
  empty: number;
  errors: number;
  success_rate: number;
}

export interface DiagnosticsReport {
  files_opened: number;
  files_with_diagnostics: number;
  total_diagnostics: number;
  errors: number;
  warnings: number;
  info: number;
  hints: number;
  server_crashes: number;
}

export interface SymbolsReport {
  files_tested: number;
  total_symbols: number;
  files_with_symbols: number;
  files_empty: number;
  errors: number;
  success_rate: number;
}

export interface AIEvaluation {
  hover_quality_avg: number;
  completion_quality_avg: number;
  diagnostic_accuracy: number;
  samples_evaluated: number;
  details: AIEvalDetail[];
}

export interface AIEvalDetail {
  feature: string;
  symbol: string;
  score: number;
  explanation: string;
}

export interface ProjectReport {
  name: string;
  type: ProjectType;
  files_tested: number;
  startup_time_s: number;
  server_crashed: boolean;
  hover?: FeatureReport;
  definition?: FeatureReport;
  completion?: CompletionReport;
  diagnostics?: DiagnosticsReport;
  symbols?: SymbolsReport;
  references?: FeatureReport;
  ai_evaluation?: AIEvaluation;
}

export interface HarnessReport {
  version: string;
  timestamp: string;
  projects: Record<string, ProjectReport>;
  summary: {
    projects_tested: number;
    projects_passed: number;
    projects_failed: number;
    overall_hover_rate: number;
    overall_definition_rate: number;
    overall_completion_rate: number;
    regressions_from_baseline: Regression[];
  };
}

export interface Regression {
  project: string;
  feature: string;
  baseline_rate: number;
  current_rate: number;
  delta: number;
}

export interface SymbolLocation {
  name: string;
  line: number;
  character: number;
  kind: string;
}

export interface HoverSample {
  file: string;
  symbol: string;
  line: number;
  character: number;
  hoverContent: string | null;
  sourceContext: string;
}

export interface CompletionSample {
  file: string;
  line: number;
  character: number;
  triggerKind: 'dot' | 'partial' | 'keyword';
  /** The text before the cursor (e.g., "str.", "pri", "if") */
  prefix: string;
  sourceContext: string;
  /** Top 10 completion item labels returned by the server */
  items: string[];
  itemCount: number;
}

export interface ValidatorResult<T> {
  report: T;
  samples?: HoverSample[];
}

import * as fs from 'node:fs';
import * as path from 'node:path';
import {
  HarnessReport,
  ProjectReport,
  Regression,
  Thresholds,
  FeatureReport,
} from './types';

const REPORTS_DIR = path.join(process.cwd(), 'reports');
const BASELINES_DIR = path.join(process.cwd(), 'baselines');

export class ReportGenerator {
  /**
   * Build the full harness report from individual project reports.
   */
  buildReport(projectReports: ProjectReport[], version: string): HarnessReport {
    const projects: Record<string, ProjectReport> = {};
    for (const pr of projectReports) {
      projects[pr.name] = pr;
    }

    // Calculate summary
    const hoverRates = projectReports
      .filter(p => p.hover)
      .map(p => p.hover!.success_rate);
    const defRates = projectReports
      .filter(p => p.definition)
      .map(p => p.definition!.success_rate);
    const compRates = projectReports
      .filter(p => p.completion)
      .map(p => p.completion!.success_rate);

    return {
      version,
      timestamp: new Date().toISOString(),
      projects,
      summary: {
        projects_tested: projectReports.length,
        projects_passed: projectReports.filter(p => !p.server_crashed).length,
        projects_failed: projectReports.filter(p => p.server_crashed).length,
        overall_hover_rate: avg(hoverRates),
        overall_definition_rate: avg(defRates),
        overall_completion_rate: avg(compRates),
        regressions_from_baseline: [],
      },
    };
  }

  /**
   * Compare report against saved baseline, return regressions.
   */
  compareBaseline(report: HarnessReport): Regression[] {
    const regressions: Regression[] = [];

    for (const [name, current] of Object.entries(report.projects)) {
      const baselinePath = path.join(BASELINES_DIR, `${name}.json`);
      if (!fs.existsSync(baselinePath)) continue;

      const baseline = JSON.parse(fs.readFileSync(baselinePath, 'utf-8')) as ProjectReport;

      // Compare each feature's success rate
      const features: Array<{ key: keyof ProjectReport; label: string }> = [
        { key: 'hover', label: 'hover' },
        { key: 'definition', label: 'definition' },
        { key: 'completion', label: 'completion' },
        { key: 'references', label: 'references' },
      ];

      for (const { key, label } of features) {
        const baseVal = baseline[key] as FeatureReport | undefined;
        const currVal = current[key] as FeatureReport | undefined;
        if (!baseVal || !currVal) continue;

        const baseRate = 'success_rate' in baseVal ? baseVal.success_rate : 0;
        const currRate = 'success_rate' in currVal ? currVal.success_rate : 0;
        const delta = currRate - baseRate;

        // Flag regressions (> 5% drop)
        if (delta < -0.05) {
          regressions.push({
            project: name,
            feature: label,
            baseline_rate: baseRate,
            current_rate: currRate,
            delta,
          });
        }
      }
    }

    return regressions;
  }

  /**
   * Save report as JSON.
   */
  saveJson(report: HarnessReport): string {
    fs.mkdirSync(REPORTS_DIR, { recursive: true });
    const filename = `report-${new Date().toISOString().replace(/[:.]/g, '-')}.json`;
    const filepath = path.join(REPORTS_DIR, filename);
    fs.writeFileSync(filepath, JSON.stringify(report, null, 2));
    return filepath;
  }

  /**
   * Save current results as baseline per project.
   */
  saveBaseline(report: HarnessReport): void {
    fs.mkdirSync(BASELINES_DIR, { recursive: true });
    for (const [name, projectReport] of Object.entries(report.projects)) {
      const filepath = path.join(BASELINES_DIR, `${name}.json`);
      fs.writeFileSync(filepath, JSON.stringify(projectReport, null, 2));
      console.log(`  Saved baseline: ${filepath}`);
    }
  }

  /**
   * Generate an HTML report with inline charts.
   */
  saveHtml(report: HarnessReport): string {
    fs.mkdirSync(REPORTS_DIR, { recursive: true });
    const filename = `report-${new Date().toISOString().replace(/[:.]/g, '-')}.html`;
    const filepath = path.join(REPORTS_DIR, filename);
    fs.writeFileSync(filepath, generateHtml(report));
    return filepath;
  }

  /**
   * Check if report passes configured thresholds.
   */
  checkThresholds(report: HarnessReport, thresholds: Thresholds): { passed: boolean; failures: string[] } {
    const failures: string[] = [];

    for (const [name, project] of Object.entries(report.projects)) {
      if (project.hover && project.hover.success_rate < thresholds.hover_success_rate) {
        failures.push(`${name}: hover rate ${(project.hover.success_rate * 100).toFixed(1)}% < ${(thresholds.hover_success_rate * 100)}%`);
      }
      if (project.definition && project.definition.success_rate < thresholds.definition_success_rate) {
        failures.push(`${name}: definition rate ${(project.definition.success_rate * 100).toFixed(1)}% < ${(thresholds.definition_success_rate * 100)}%`);
      }
      if (thresholds.diagnostics_no_crash && project.server_crashed) {
        failures.push(`${name}: server crashed`);
      }
      if (project.completion && project.completion.success_rate < thresholds.completion_non_empty) {
        failures.push(`${name}: completion rate ${(project.completion.success_rate * 100).toFixed(1)}% < ${(thresholds.completion_non_empty * 100)}%`);
      }
      if (project.startup_time_s > thresholds.startup_time_max_s) {
        failures.push(`${name}: startup ${project.startup_time_s.toFixed(1)}s > ${thresholds.startup_time_max_s}s`);
      }
    }

    return { passed: failures.length === 0, failures };
  }
}

function avg(values: number[]): number {
  if (values.length === 0) return 0;
  return values.reduce((a, b) => a + b, 0) / values.length;
}

function generateHtml(report: HarnessReport): string {
  const projectRows = Object.entries(report.projects).map(([name, p]) => {
    const hoverBar = p.hover ? renderBar(p.hover.success_rate) : '-';
    const defBar = p.definition ? renderBar(p.definition.success_rate) : '-';
    const compBar = p.completion ? renderBar(p.completion.success_rate) : '-';
    const diagInfo = p.diagnostics
      ? `${p.diagnostics.total_diagnostics} (${p.diagnostics.errors}E/${p.diagnostics.warnings}W)`
      : '-';
    const symbolInfo = p.symbols ? `${p.symbols.total_symbols}` : '-';

    return `
      <tr>
        <td><strong>${name}</strong></td>
        <td>${p.type}</td>
        <td>${p.files_tested}</td>
        <td>${p.startup_time_s.toFixed(1)}s</td>
        <td>${hoverBar}</td>
        <td>${defBar}</td>
        <td>${compBar}</td>
        <td>${diagInfo}</td>
        <td>${symbolInfo}</td>
        <td>${p.server_crashed ? '<span class="fail">CRASHED</span>' : '<span class="pass">OK</span>'}</td>
      </tr>`;
  }).join('\n');

  const regressionRows = report.summary.regressions_from_baseline.length > 0
    ? report.summary.regressions_from_baseline.map(r => `
      <tr>
        <td>${r.project}</td>
        <td>${r.feature}</td>
        <td>${(r.baseline_rate * 100).toFixed(1)}%</td>
        <td>${(r.current_rate * 100).toFixed(1)}%</td>
        <td class="fail">${(r.delta * 100).toFixed(1)}%</td>
      </tr>`).join('\n')
    : '<tr><td colspan="5">No regressions detected</td></tr>';

  return `<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<title>LSP Test Harness Report - ${report.version}</title>
<style>
  body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif; margin: 2rem; background: #f8f9fa; }
  h1 { color: #7F52FF; }
  h2 { color: #333; margin-top: 2rem; }
  table { border-collapse: collapse; width: 100%; margin: 1rem 0; background: white; box-shadow: 0 1px 3px rgba(0,0,0,0.1); }
  th, td { padding: 0.75rem 1rem; text-align: left; border-bottom: 1px solid #eee; }
  th { background: #f5f5f5; font-weight: 600; }
  .bar { display: inline-block; height: 18px; border-radius: 3px; min-width: 3px; }
  .bar-bg { display: inline-block; width: 100px; height: 18px; background: #eee; border-radius: 3px; position: relative; }
  .pass { color: #28a745; font-weight: 600; }
  .fail { color: #dc3545; font-weight: 600; }
  .warn { color: #ffc107; font-weight: 600; }
  .summary { display: flex; gap: 2rem; margin: 1rem 0; }
  .summary-card { background: white; padding: 1.5rem; border-radius: 8px; box-shadow: 0 1px 3px rgba(0,0,0,0.1); flex: 1; }
  .summary-card h3 { margin: 0 0 0.5rem; color: #666; font-size: 0.9rem; }
  .summary-card .value { font-size: 2rem; font-weight: 700; color: #333; }
  .meta { color: #888; font-size: 0.9rem; }
</style>
</head>
<body>
<h1>LSP Test Harness Report</h1>
<p class="meta">Version: ${report.version} | Generated: ${report.timestamp}</p>

<div class="summary">
  <div class="summary-card">
    <h3>Projects Tested</h3>
    <div class="value">${report.summary.projects_tested}</div>
  </div>
  <div class="summary-card">
    <h3>Passed / Failed</h3>
    <div class="value"><span class="pass">${report.summary.projects_passed}</span> / <span class="fail">${report.summary.projects_failed}</span></div>
  </div>
  <div class="summary-card">
    <h3>Hover Rate</h3>
    <div class="value">${(report.summary.overall_hover_rate * 100).toFixed(1)}%</div>
  </div>
  <div class="summary-card">
    <h3>Definition Rate</h3>
    <div class="value">${(report.summary.overall_definition_rate * 100).toFixed(1)}%</div>
  </div>
  <div class="summary-card">
    <h3>Completion Rate</h3>
    <div class="value">${(report.summary.overall_completion_rate * 100).toFixed(1)}%</div>
  </div>
</div>

<h2>Project Results</h2>
<table>
  <thead>
    <tr>
      <th>Project</th>
      <th>Type</th>
      <th>Files</th>
      <th>Startup</th>
      <th>Hover</th>
      <th>Definition</th>
      <th>Completion</th>
      <th>Diagnostics</th>
      <th>Symbols</th>
      <th>Status</th>
    </tr>
  </thead>
  <tbody>
    ${projectRows}
  </tbody>
</table>

<h2>Regressions from Baseline</h2>
<table>
  <thead>
    <tr>
      <th>Project</th>
      <th>Feature</th>
      <th>Baseline</th>
      <th>Current</th>
      <th>Delta</th>
    </tr>
  </thead>
  <tbody>
    ${regressionRows}
  </tbody>
</table>
</body>
</html>`;
}

function renderBar(rate: number): string {
  const pct = Math.round(rate * 100);
  const color = pct >= 70 ? '#28a745' : pct >= 50 ? '#ffc107' : '#dc3545';
  return `<div class="bar-bg"><div class="bar" style="width:${pct}px;background:${color}"></div></div> ${pct}%`;
}

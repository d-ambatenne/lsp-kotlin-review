#!/usr/bin/env node

import { Command } from 'commander';
import * as fs from 'node:fs';
import * as path from 'node:path';
import { parse as parseYaml } from 'yaml';
import { LspRunner, RunOptions } from './lsp-runner';
import { ReportGenerator } from './report-generator';
import { AIEvaluator } from './ai-evaluator';
import { HarnessConfig, ProjectConfig, HoverSample, CompletionSample, ProjectReport } from './types';
import { ProjectManager } from './project-manager';

const DEFAULT_CONFIG_PATH = path.join(process.cwd(), 'projects.yaml');
const DEFAULT_SERVER_JAR = path.resolve(process.cwd(), '..', 'server', 'build', 'libs', 'server-all.jar');

function loadConfig(configPath: string): HarnessConfig {
  const content = fs.readFileSync(configPath, 'utf-8');
  return parseYaml(content) as HarnessConfig;
}

function resolveServerJar(jarPath?: string): string {
  const resolved = jarPath ?? DEFAULT_SERVER_JAR;
  if (!fs.existsSync(resolved)) {
    console.error(`Server JAR not found: ${resolved}`);
    console.error('Build it with: cd server && ./gradlew shadowJar');
    process.exit(1);
  }
  return resolved;
}

const program = new Command();

program
  .name('test-harness')
  .description('E2E test harness for multi-project Kotlin LSP validation')
  .version('1.0.0');

// --- run command ---
program
  .command('run')
  .description('Run LSP validation against configured projects')
  .option('-c, --config <path>', 'Path to projects.yaml', DEFAULT_CONFIG_PATH)
  .option('-p, --project <name>', 'Run a specific project only')
  .option('-f, --feature <name>', 'Run a specific feature only')
  .option('-j, --jar <path>', 'Path to server-all.jar', DEFAULT_SERVER_JAR)
  .option('--java <path>', 'Path to java binary')
  .option('--jvm-args <args>', 'JVM arguments (comma-separated)', '-Xmx2g,-XX:+UseG1GC')
  .option('--max-files <n>', 'Max .kt files per project', '50')
  .option('--timeout <ms>', 'Request timeout in ms', '10000')
  .option('--compare-baseline', 'Compare results against saved baseline')
  .option('--ai-eval', 'Run AI-powered evaluation on results')
  .option('--format <type>', 'Report format: json, html, both', 'json')
  .option('--version-tag <tag>', 'Version tag for the report', '1.5.0')
  .option('--no-cleanup', 'Keep cloned projects after testing')
  .action(async (opts) => {
    const config = loadConfig(opts.config);
    const serverJar = resolveServerJar(opts.jar);
    const jvmArgs = opts.jvmArgs.split(',');

    console.log('=== LSP Test Harness ===');
    console.log(`Server JAR: ${serverJar}`);
    console.log(`Config: ${opts.config}`);
    console.log();

    // Filter projects if specified
    let projects = config.projects;
    if (opts.project) {
      projects = projects.filter(p => p.name === opts.project);
      if (projects.length === 0) {
        console.error(`Project not found: ${opts.project}`);
        console.error(`Available: ${config.projects.map(p => p.name).join(', ')}`);
        process.exit(1);
      }
    }

    // Filter features if specified
    if (opts.feature) {
      projects = projects.map(p => ({
        ...p,
        features: p.features.filter(f => f === opts.feature),
      }));
    }

    const runOptions: RunOptions = {
      serverJarPath: serverJar,
      javaPath: opts.java,
      jvmArgs,
      maxFilesPerProject: parseInt(opts.maxFiles, 10),
      requestTimeoutMs: parseInt(opts.timeout, 10),
    };

    const runner = new LspRunner();
    const reportGen = new ReportGenerator();
    const projectReports: ProjectReport[] = [];
    let allHoverSamples: HoverSample[] = [];
    let allCompletionSamples: CompletionSample[] = [];

    for (const project of projects) {
      try {
        const { report, hoverSamples, completionSamples } = await runner.runProject(project, runOptions);
        projectReports.push(report);
        allHoverSamples.push(...hoverSamples);
        allCompletionSamples.push(...completionSamples);
      } catch (error) {
        console.error(`\nFATAL ERROR testing ${project.name}: ${error}`);
        projectReports.push({
          name: project.name,
          type: project.type,
          files_tested: 0,
          startup_time_s: 0,
          server_crashed: true,
        });
      }
    }

    // Build report
    const report = reportGen.buildReport(projectReports, opts.versionTag);

    // Baseline comparison
    if (opts.compareBaseline) {
      const regressions = reportGen.compareBaseline(report);
      report.summary.regressions_from_baseline = regressions;
      if (regressions.length > 0) {
        console.log('\n=== REGRESSIONS DETECTED ===');
        for (const r of regressions) {
          console.log(`  ${r.project}/${r.feature}: ${(r.baseline_rate * 100).toFixed(1)}% -> ${(r.current_rate * 100).toFixed(1)}% (${(r.delta * 100).toFixed(1)}%)`);
        }
      } else {
        console.log('\nNo regressions from baseline.');
      }
    }

    // AI evaluation
    if (opts.aiEval) {
      console.log('\n=== AI Evaluation ===');
      const evaluator = new AIEvaluator();

      // Hover quality
      const aiResult = await evaluator.evaluateHoverQuality(allHoverSamples);
      console.log(`  Hover quality: ${aiResult.hover_quality_avg.toFixed(1)}/5 (${aiResult.samples_evaluated} samples)`);

      // Completion quality
      if (allCompletionSamples.length > 0) {
        const compResult = await evaluator.evaluateCompletionQuality(allCompletionSamples);
        aiResult.completion_quality_avg = compResult.avg;
        aiResult.details.push(...compResult.details);
        aiResult.samples_evaluated += compResult.details.length;

        const dotDetails = compResult.details.filter(d => d.feature === 'completion_dot');
        const partialDetails = compResult.details.filter(d => d.feature === 'completion_partial');
        const kwDetails = compResult.details.filter(d => d.feature === 'completion_keyword');
        const avgOf = (arr: { score: number }[]) => arr.length > 0 ? arr.reduce((s, d) => s + d.score, 0) / arr.length : 0;

        console.log(`  Completion quality: ${compResult.avg.toFixed(1)}/5 (${compResult.details.length} samples)`);
        if (dotDetails.length > 0) console.log(`    Dot completion: ${avgOf(dotDetails).toFixed(1)}/5 (${dotDetails.length} samples)`);
        if (partialDetails.length > 0) console.log(`    Partial completion: ${avgOf(partialDetails).toFixed(1)}/5 (${partialDetails.length} samples)`);
        if (kwDetails.length > 0) console.log(`    Keyword completion: ${avgOf(kwDetails).toFixed(1)}/5 (${kwDetails.length} samples)`);
      }

      // Attach AI results to each project report
      for (const pr of projectReports) {
        pr.ai_evaluation = aiResult;
      }

      // Regression analysis
      if (opts.compareBaseline && report.summary.regressions_from_baseline.length > 0) {
        console.log('\n  AI Regression Analysis:');
        const analysis = await evaluator.analyzeRegressions(report.summary.regressions_from_baseline, report);
        console.log(`  ${analysis}`);
      }
    }

    // Save reports
    const format = opts.format as string;
    if (format === 'json' || format === 'both') {
      const jsonPath = reportGen.saveJson(report);
      console.log(`\nJSON report: ${jsonPath}`);
    }
    if (format === 'html' || format === 'both') {
      const htmlPath = reportGen.saveHtml(report);
      console.log(`HTML report: ${htmlPath}`);
    }

    // Threshold check
    const { passed, failures } = reportGen.checkThresholds(report, config.thresholds);
    if (!passed) {
      console.log('\n=== THRESHOLD FAILURES ===');
      for (const f of failures) {
        console.log(`  FAIL: ${f}`);
      }
      process.exitCode = 1;
    } else {
      console.log('\nAll thresholds passed.');
    }

    // Cleanup
    if (opts.cleanup !== false) {
      const pm = new ProjectManager();
      for (const project of projects) {
        pm.cleanup(project);
      }
    }

    // Summary
    console.log(`\n=== Summary ===`);
    console.log(`Projects: ${report.summary.projects_tested} tested, ${report.summary.projects_passed} passed, ${report.summary.projects_failed} failed`);
    console.log(`Hover: ${(report.summary.overall_hover_rate * 100).toFixed(1)}%`);
    console.log(`Definition: ${(report.summary.overall_definition_rate * 100).toFixed(1)}%`);
    console.log(`Completion: ${(report.summary.overall_completion_rate * 100).toFixed(1)}%`);
  });

// --- save-baseline command ---
program
  .command('save-baseline')
  .description('Save current report results as baseline for future comparison')
  .option('-r, --report <path>', 'Path to report JSON file (uses latest if not specified)')
  .action((opts) => {
    const reportGen = new ReportGenerator();
    let report: any;

    if (opts.report) {
      report = JSON.parse(fs.readFileSync(opts.report, 'utf-8'));
    } else {
      // Find latest report
      const reportsDir = path.join(process.cwd(), 'reports');
      if (!fs.existsSync(reportsDir)) {
        console.error('No reports directory found. Run `test-harness run` first.');
        process.exit(1);
      }
      const files = fs.readdirSync(reportsDir)
        .filter(f => f.endsWith('.json'))
        .sort()
        .reverse();
      if (files.length === 0) {
        console.error('No report files found. Run `test-harness run` first.');
        process.exit(1);
      }
      report = JSON.parse(fs.readFileSync(path.join(reportsDir, files[0]), 'utf-8'));
    }

    reportGen.saveBaseline(report);
    console.log('Baseline saved.');
  });

// --- report command ---
program
  .command('report')
  .description('Generate report from saved results')
  .option('-r, --report <path>', 'Path to report JSON file (uses latest if not specified)')
  .option('--format <type>', 'Report format: json, html', 'html')
  .action((opts) => {
    const reportGen = new ReportGenerator();

    let reportPath: string;
    if (opts.report) {
      reportPath = opts.report;
    } else {
      const reportsDir = path.join(process.cwd(), 'reports');
      const files = fs.readdirSync(reportsDir)
        .filter(f => f.endsWith('.json'))
        .sort()
        .reverse();
      if (files.length === 0) {
        console.error('No report files found.');
        process.exit(1);
      }
      reportPath = path.join(reportsDir, files[0]);
    }

    const report = JSON.parse(fs.readFileSync(reportPath, 'utf-8'));

    if (opts.format === 'html') {
      const htmlPath = reportGen.saveHtml(report);
      console.log(`HTML report: ${htmlPath}`);
    } else {
      console.log(JSON.stringify(report, null, 2));
    }
  });

// --- cleanup command ---
program
  .command('cleanup')
  .description('Remove cloned projects')
  .action(() => {
    const pm = new ProjectManager();
    pm.cleanupAll();
    console.log('Cleaned up all cloned projects.');
  });

program.parse();

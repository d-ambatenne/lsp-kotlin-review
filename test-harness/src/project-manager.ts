import { execSync, ExecSyncOptions } from 'node:child_process';
import * as fs from 'node:fs';
import * as path from 'node:path';
import { glob } from 'glob';
import { ProjectConfig, ProjectType } from './types';

const CLONE_DIR = path.join(process.cwd(), '.projects');

export class ProjectManager {
  /**
   * Clone a project from GitHub (shallow clone for speed).
   * Returns the path to the project root (including subdir if specified).
   */
  async clone(project: ProjectConfig): Promise<string> {
    // Support local paths (no cloning needed)
    if (project.localPath) {
      const localRoot = project.subdir
        ? path.join(project.localPath, project.subdir)
        : project.localPath;
      if (!fs.existsSync(localRoot)) {
        throw new Error(`Local path not found: ${localRoot}`);
      }
      console.log(`  Using local path: ${localRoot}`);
      return localRoot;
    }

    const projectDir = path.join(CLONE_DIR, project.name);

    if (fs.existsSync(projectDir)) {
      console.log(`  Project ${project.name} already cloned, reusing...`);
    } else {
      fs.mkdirSync(CLONE_DIR, { recursive: true });
      console.log(`  Cloning ${project.repo} (branch: ${project.branch})...`);
      const opts: ExecSyncOptions = { stdio: 'pipe', timeout: 120000 };
      execSync(
        `git clone --depth 1 --branch ${project.branch} ${project.repo} ${projectDir}`,
        opts,
      );
    }

    const workspaceRoot = project.subdir
      ? path.join(projectDir, project.subdir)
      : projectDir;

    if (!fs.existsSync(workspaceRoot)) {
      throw new Error(`Subdirectory not found: ${workspaceRoot}`);
    }

    return workspaceRoot;
  }

  /**
   * Detect project type by scanning for build files and configurations.
   */
  detectType(projectPath: string): ProjectType {
    // Check for Android
    if (
      fs.existsSync(path.join(projectPath, 'src/main/AndroidManifest.xml')) ||
      this.buildGradleContains(projectPath, 'android')
    ) {
      return 'android';
    }

    // Check for KMP
    if (this.buildGradleContains(projectPath, 'multiplatform')) {
      return 'kmp';
    }

    return 'jvm';
  }

  /**
   * Enumerate all .kt source files in the project.
   */
  async findKotlinFiles(projectPath: string): Promise<string[]> {
    const patterns = [
      // Standard single-module
      'src/main/kotlin/**/*.kt',
      'src/main/java/**/*.kt',
      'src/commonMain/kotlin/**/*.kt',
      'src/jvmMain/kotlin/**/*.kt',
      'src/androidMain/kotlin/**/*.kt',
      'src/iosMain/kotlin/**/*.kt',
      // Top-level app module
      'app/src/main/kotlin/**/*.kt',
      'app/src/main/java/**/*.kt',
      // Multi-module: any submodule's source sets
      '*/src/main/kotlin/**/*.kt',
      '*/src/main/java/**/*.kt',
      '*/src/commonMain/kotlin/**/*.kt',
      '*/src/jvmMain/kotlin/**/*.kt',
      '*/src/androidMain/kotlin/**/*.kt',
      '*/src/iosMain/kotlin/**/*.kt',
      // Two-level deep submodules (e.g., features/auth/src/...)
      '*/*/src/main/kotlin/**/*.kt',
      '*/*/src/commonMain/kotlin/**/*.kt',
    ];

    const files: string[] = [];
    for (const pattern of patterns) {
      const matches = await glob(pattern, {
        cwd: projectPath,
        absolute: true,
        nodir: true,
      });
      files.push(...matches);
    }

    // Deduplicate
    return [...new Set(files)];
  }

  /**
   * Clean up a cloned project.
   */
  cleanup(project: ProjectConfig): void {
    const projectDir = path.join(CLONE_DIR, project.name);
    if (fs.existsSync(projectDir)) {
      fs.rmSync(projectDir, { recursive: true, force: true });
    }
  }

  /**
   * Clean up all cloned projects.
   */
  cleanupAll(): void {
    if (fs.existsSync(CLONE_DIR)) {
      fs.rmSync(CLONE_DIR, { recursive: true, force: true });
    }
  }

  private buildGradleContains(projectPath: string, keyword: string): boolean {
    const candidates = [
      path.join(projectPath, 'build.gradle.kts'),
      path.join(projectPath, 'build.gradle'),
    ];
    for (const candidate of candidates) {
      if (fs.existsSync(candidate)) {
        const content = fs.readFileSync(candidate, 'utf-8');
        if (content.includes(keyword)) return true;
      }
    }
    return false;
  }
}

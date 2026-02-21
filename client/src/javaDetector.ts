import { execFileSync } from "child_process";
import * as path from "path";
import * as fs from "fs";
import { getJavaHome } from "./config";

export interface JavaInfo {
  javaPath: string;
  version: number;
}

export function findJava(): JavaInfo {
  const candidates = buildCandidates();

  for (const javaPath of candidates) {
    const version = getJavaVersion(javaPath);
    if (version !== undefined && version >= 11) {
      return { javaPath, version };
    }
  }

  throw new Error(
    "Java 11+ is required but not found. " +
      "Set kotlinReview.java.home, JAVA_HOME, or ensure java is on PATH."
  );
}

function buildCandidates(): string[] {
  const candidates: string[] = [];

  // 1. Extension setting
  const settingHome = getJavaHome();
  if (settingHome) {
    candidates.push(javaBin(settingHome));
  }

  // 2. JAVA_HOME
  if (process.env.JAVA_HOME) {
    candidates.push(javaBin(process.env.JAVA_HOME));
  }

  // 3. JDK_HOME
  if (process.env.JDK_HOME) {
    candidates.push(javaBin(process.env.JDK_HOME));
  }

  // 4. PATH
  candidates.push("java");

  return candidates;
}

function javaBin(home: string): string {
  return path.join(home, "bin", "java");
}

function getJavaVersion(javaPath: string): number | undefined {
  try {
    // Check the binary exists (unless it's just "java" for PATH lookup)
    if (javaPath !== "java" && !fs.existsSync(javaPath)) {
      return undefined;
    }

    const output = execFileSync(javaPath, ["-version"], {
      encoding: "utf-8",
      stdio: ["pipe", "pipe", "pipe"],
      timeout: 5000,
    });

    // java -version outputs to stderr, but execFileSync with encoding captures both
    // The format is: java version "17.0.1" or openjdk version "11.0.12"
    return parseJavaVersion(output);
  } catch {
    return undefined;
  }
}

function parseJavaVersion(output: string): number | undefined {
  // Match patterns like "17.0.1", "11.0.12", "1.8.0_312"
  const match = output.match(/version\s+"(\d+)(?:\.(\d+))?/);
  if (!match) return undefined;

  const major = parseInt(match[1], 10);
  // Java 8 and earlier use 1.x versioning
  if (major === 1) {
    return match[2] ? parseInt(match[2], 10) : undefined;
  }
  return major;
}

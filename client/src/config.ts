import * as vscode from "vscode";

const SECTION = "kotlinReview";

export function getJavaHome(): string {
  return vscode.workspace.getConfiguration(SECTION).get<string>("java.home", "");
}

export function getServerJvmArgs(): string[] {
  const raw = vscode.workspace.getConfiguration(SECTION).get<string>("server.jvmArgs", "");
  return raw ? raw.split(/\s+/).filter(Boolean) : [];
}

export function getTraceServer(): string {
  return vscode.workspace.getConfiguration(SECTION).get<string>("trace.server", "off");
}

export function getBuildVariant(): string {
  return vscode.workspace.getConfiguration(SECTION).get<string>("android.buildVariant", "debug");
}

export function getAutoGenerate(): boolean {
  return vscode.workspace.getConfiguration(SECTION).get<boolean>("android.autoGenerate", false);
}

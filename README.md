# lsp-kotlin-review

A lightweight Kotlin Language Server for VS Code, focused on **code review and small changes**.

Unlike full-featured Kotlin IDEs, this extension provides just the language intelligence you need when reviewing pull requests or making quick edits — fast startup, low memory, no bloat.

## Features

### P0 — Core
- **Diagnostics** — syntax and semantic errors/warnings as you type
- **Go to Definition** — jump to where a symbol is declared
- **Find References** — find all usages of a symbol across the project
- **Hover** — type information and KDoc on mouse-over
- **Document Symbols** — outline view of classes, functions, and properties

### P1 — Enhanced
- **Go to Implementation** — find concrete implementations of interfaces/abstract classes
- **Go to Type Definition** — jump to the type of a variable or expression
- **Rename Symbol** — project-wide safe rename
- **Code Actions / Quick Fixes** — auto-fix common diagnostics
- **Basic Completion** — context-aware code completion for project symbols and dependencies

## Architecture

```
VS Code Extension (TypeScript)
        │ stdio (LSP JSON-RPC)
        ▼
Language Server (Kotlin/JVM)
  ├── LSP4J protocol layer
  ├── Feature Providers
  ├── CompilerFacade (our stable abstraction)
  │     └── AnalysisApiCompilerFacade (K2/FIR backend)
  └── Build System Resolver
        ├── Gradle (v1)
        └── Manual fallback
```

- **Compiler backend**: Kotlin Analysis API with K2/FIR in standalone mode — no IntelliJ dependency
- **Build system**: Pluggable SPI. Ships with Gradle Tooling API provider; Maven, Bazel, and others planned
- **Performance**: Analysis-phase only (no codegen), single-writer/multi-reader concurrency, incremental invalidation

See [docs/design.md](docs/design.md) for the full architecture, [docs/decisions.md](docs/decisions.md) for ADRs, and [docs/scope.md](docs/scope.md) for the feature roadmap.

## Requirements

- **Java 11+** on your system (via `JAVA_HOME` or `PATH`)
- **VS Code 1.75+**
- A Kotlin project (Gradle recommended for full classpath resolution)

## Project Structure

```
lsp-kotlin-review/
├── client/          # VS Code extension (TypeScript, vscode-languageclient)
├── server/          # Language server (Kotlin, LSP4J, Analysis API)
├── docs/            # Architecture, decisions, scope
└── scripts/         # Build and packaging
```

## Building

```bash
# Build server fat JAR + client bundle
./scripts/build.sh

# Package as .vsix
./scripts/package.sh
```

## Status

**Design phase** — architecture and scope finalized, implementation not yet started.

## License

TBD

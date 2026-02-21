# lsp-kotlin-review

A lightweight Kotlin Language Server for VS Code, focused on **code review and small changes**.

Unlike full-featured Kotlin IDEs, this extension provides just the language intelligence you need when reviewing pull requests or making quick edits -- fast startup, low memory, no bloat.

## Installation

### From VSIX (recommended)

1. Download the `.vsix` file from the [latest release](https://github.com/<org>/lsp-kotlin-review/releases)
2. In VS Code, open the Command Palette (`Ctrl+Shift+P` / `Cmd+Shift+P`)
3. Run **Extensions: Install from VSIX...** and select the downloaded file

### From source

```bash
git clone https://github.com/<org>/lsp-kotlin-review.git
cd lsp-kotlin-review
./scripts/package.sh
```

Then install the generated `.vsix` from `client/`.

## Requirements

- **Java 17+** on your system (via `JAVA_HOME` or `PATH`)
- **VS Code 1.75+**
- A Kotlin project (Gradle recommended for full classpath resolution)

## Features

### Core

- **Diagnostics** -- syntax and semantic errors/warnings as you type
- **Go to Definition** -- jump to where a symbol is declared
- **Find References** -- find all usages of a symbol across the project
- **Hover** -- type information and KDoc on mouse-over
- **Document Symbols** -- outline view of classes, functions, and properties

### Enhanced

- **Go to Implementation** -- find concrete implementations of interfaces/abstract classes
- **Go to Type Definition** -- jump to the type of a variable or expression
- **Rename Symbol** -- project-wide safe rename
- **Code Actions / Quick Fixes** -- auto-fix common diagnostics
- **Basic Completion** -- context-aware code completion for project symbols and dependencies

## Architecture

```
VS Code Extension (TypeScript)
        | stdio (LSP JSON-RPC)
        v
Language Server (Kotlin/JVM)
  +-- LSP4J protocol layer
  +-- Feature Providers
  +-- CompilerFacade (our stable abstraction)
  |     +-- AnalysisApiCompilerFacade (K2/FIR backend)
  +-- Build System Resolver
        +-- Gradle (v1)
        +-- Manual fallback
```

- **Compiler backend**: Kotlin Analysis API with K2/FIR in standalone mode -- no IntelliJ dependency
- **Build system**: Pluggable SPI. Ships with Gradle Tooling API provider; Maven, Bazel, and others planned
- **Performance**: Analysis-phase only (no codegen), single-writer/multi-reader concurrency, incremental invalidation

See [docs/design.md](docs/design.md) for the full architecture, [docs/decisions.md](docs/decisions.md) for ADRs, and [docs/scope.md](docs/scope.md) for the feature roadmap.

## Configuration

| Setting | Description | Default |
|---------|-------------|---------|
| `kotlinReview.java.home` | Path to Java 17+ runtime | `JAVA_HOME` or `PATH` |
| `kotlinReview.server.jvmArgs` | Additional JVM arguments for the language server | (empty) |
| `kotlinReview.trace.server` | Trace LSP communication (`off`, `messages`, `verbose`) | `off` |

## Project Structure

```
lsp-kotlin-review/
+-- client/          # VS Code extension (TypeScript, vscode-languageclient)
+-- server/          # Language server (Kotlin, LSP4J, Analysis API)
+-- docs/            # Architecture, decisions, scope
+-- scripts/         # Build and packaging
+-- .github/         # CI workflows
```

## Building

```bash
# Build server fat JAR + client bundle
./scripts/build.sh

# Run server tests
cd server && ./gradlew test

# Package as .vsix
./scripts/package.sh
```

## Troubleshooting

### Java not found

The extension requires Java 17 or later. If the server fails to start:

1. Verify Java is installed: `java -version`
2. Set `JAVA_HOME` to point to a JDK 17+ installation
3. Or configure `kotlinReview.java.home` in VS Code settings

### Server crashes on startup

Check the Output panel in VS Code (select "Kotlin Review" from the dropdown). Common causes:

- **Insufficient memory**: add `-Xmx2g` to `kotlinReview.server.jvmArgs`
- **Incompatible Java version**: ensure Java 17+

### No diagnostics / features not working

- The server needs time to initialize the Kotlin Analysis API on first open. Check the Output panel for "Analysis session ready".
- For projects without Gradle, the server uses a manual fallback with limited classpath resolution. Some features may be reduced.

### Gradle project not detected

Ensure your project has one of: `build.gradle.kts`, `build.gradle`, `settings.gradle.kts`, or `settings.gradle` in the workspace root.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for development setup and guidelines.

## License

Apache-2.0

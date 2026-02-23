# Changelog

All notable changes to the Kotlin Review LSP extension will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [0.23.0] - 2026-02-23

### Added

- **Syntax Highlighting**: TextMate grammar for Kotlin with full coverage — keywords, comments (line/block/KDoc), strings with `${}` interpolation, triple-quoted strings, numbers, annotations, declarations, operators, and Kotlin-specific syntax (`?.`, `!!`, `?:`, `::`, `..`, `->`)
- **Language Configuration**: bracket matching, comment toggling (Cmd+/), auto-closing pairs, surrounding pairs, folding regions, indentation rules
- **Hover for library types**: synthetic signature rendering from KaSymbol metadata when PSI source is unavailable (e.g. `LSPLauncher`, `PrintStream`, `String`)
- **Hover for declarations**: hovering over `val`, `fun`, `class` etc. now shows the declaration signature and inferred type (was returning null)
- **Session rebuild on save**: `refreshAnalysis()` rebuilds the Analysis API session from disk when files are saved, so diagnostics reflect saved changes
- `symbolName()` helper for clean name extraction from any KaSymbol type using `classId`/`containingClassId`/`name`

### Fixed

- Hover crash on compiled class PSI (`ClsElementImpl`) — `psi.text` NPE in `ClsFileImpl.getMirror()` now caught gracefully
- `getType()` returning `kotlin/Unit` for declarations — now uses `decl.symbol.returnType` for `KtCallableDeclaration`
- Name extraction showing Java object `toString()` (e.g. `KaFirNamedClassSymbol@7fed9c1f`) — replaced with proper `classId.shortClassName`
- OOM on session rebuild — increased heap from 512m to 2g, old session nulled + GC'd before rebuild
- Removed `-XX:+TieredCompilation -XX:TieredStopAtLevel=1` (hurt long-running server perf)

## [0.1.0] - 2026-02-22

### Added

- Language server with Kotlin Analysis API (K2/FIR) backend
- **Diagnostics**: syntax and semantic errors/warnings on file open and change
- **Go to Definition**: resolve symbol declarations
- **Find References**: find all usages of a symbol across the project
- **Hover**: type information and KDoc on mouse-over
- **Document Symbols**: outline view of classes, functions, and properties
- **Go to Implementation**: find concrete implementations of interfaces/abstract classes
- **Go to Type Definition**: jump to the type of a variable or expression
- **Rename Symbol**: project-wide safe rename with prepare support
- **Code Actions / Quick Fixes**: auto-fix common diagnostics
- **Basic Completion**: context-aware code completion for project symbols
- Build system detection with Gradle Tooling API provider
- Manual fallback provider for projects without a build system
- VS Code extension with stdio LSP transport
- GitHub Actions CI with Java 17/21 matrix on Linux and macOS

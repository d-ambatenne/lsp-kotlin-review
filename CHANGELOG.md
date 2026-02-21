# Changelog

All notable changes to the Kotlin Review LSP extension will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [0.1.0] - Unreleased

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

# Contributing to Kotlin Review LSP

Thank you for your interest in contributing. This guide covers how to set up your development environment and run the project locally.

## Prerequisites

- **Java 17+** (JDK, not just JRE) -- [Temurin](https://adoptium.net/) recommended
- **Node.js 18+** and npm
- **VS Code 1.75+**

## Project Structure

```
lsp-kotlin-review/
├── client/          # VS Code extension (TypeScript)
├── server/          # Language server (Kotlin/JVM)
├── docs/            # Architecture docs, ADRs, scope
├── scripts/         # Build and packaging scripts
└── .github/         # CI workflows
```

## Development Setup

### 1. Clone the repository

```bash
git clone https://github.com/<org>/lsp-kotlin-review.git
cd lsp-kotlin-review
```

### 2. Build the server

```bash
cd server
./gradlew shadowJar
```

This produces `server/build/libs/server-all.jar`.

### 3. Build the client

```bash
cd client
npm install
npm run build
```

### 4. Run tests

```bash
cd server
./gradlew test
```

### 5. Full build + package

```bash
./scripts/build.sh     # Build server + client
./scripts/package.sh   # Build + package VSIX
```

## Running in VS Code

1. Open the repo root in VS Code
2. Press **F5** to launch the Extension Development Host
3. Open a Kotlin project in the new window

## Code Conventions

- Server code is Kotlin, targeting JVM 17
- Client code is TypeScript with esbuild bundling
- Tests use JUnit 5 / kotlin.test
- Feature providers accept a `CompilerFacade` and return LSP types
- Use `StubCompilerFacade` for unit tests (no Analysis API dependency)

## Test Fixtures

Test fixtures live under `server/src/test/resources/`:

| Directory         | Description                              |
|-------------------|------------------------------------------|
| `test-project/`   | Minimal project with intentional errors |
| `single-module/`  | Single-module Gradle project            |
| `multi-module/`   | Multi-module Gradle project             |
| `no-build-system/`| Plain Kotlin files, no build tool       |

## Submitting Changes

1. Create a feature branch from `main`
2. Make your changes with tests
3. Run `./gradlew test` in the server directory and verify all tests pass
4. Open a pull request against `main`

## Architecture

See [docs/design.md](docs/design.md) for the full architecture and [docs/decisions.md](docs/decisions.md) for architectural decision records.

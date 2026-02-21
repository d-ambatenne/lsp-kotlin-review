# Architecture Design - lsp-kotlin-review

## Overview

```
┌──────────────────────────────────────────────────────────┐
│  VS Code                                                 │
│  ┌────────────────────────────────────────────────────┐  │
│  │  Extension (TypeScript)                            │  │
│  │  - LanguageClient (vscode-languageclient)          │  │
│  │  - Activation: onLanguage:kotlin                   │  │
│  │  - Java runtime detection                          │  │
│  └──────────────┬─────────────────────────────────────┘  │
│                 │ stdio (LSP JSON-RPC)                    │
│  ┌──────────────▼─────────────────────────────────────┐  │
│  │  Language Server (Kotlin/JVM)                      │  │
│  │  ┌──────────────────────────────────────────────┐  │  │
│  │  │  LSP4J (protocol layer)                      │  │  │
│  │  ├──────────────────────────────────────────────┤  │  │
│  │  │  Feature Providers (P0 + P1)                 │  │  │
│  │  ├──────────────────────────────────────────────┤  │  │
│  │  │  CompilerFacade ← AnalysisApiCompilerFacade   │  │  │
│  │  ├──────────────────────────────────────────────┤  │  │
│  │  │  Build System Resolver          ┌─────────┐ │  │  │
│  │  │  ┌─────────┬────────┬─────────┐ │ Manual  │ │  │  │
│  │  │  │ Gradle  │ Maven  │ Bazel   │ │(fallbk) │ │  │  │
│  │  │  │  (v1)   │  (v2)  │  (v2)   │ │         │ │  │  │
│  │  │  └─────────┴────────┴─────────┘ └─────────┘ │  │  │
│  │  └──────────────────────────────────────────────┘  │  │
│  └────────────────────────────────────────────────────┘  │
└──────────────────────────────────────────────────────────┘
```

## Repository Structure

```
lsp-kotlin-review/
├── client/                          # VS Code extension (TypeScript)
│   ├── src/
│   │   ├── extension.ts             # Entry point, LanguageClient setup
│   │   ├── javaDetector.ts          # Find/validate Java runtime
│   │   └── config.ts                # Extension settings
│   ├── package.json                 # Extension manifest + contributes
│   ├── tsconfig.json
│   └── esbuild.mjs                  # Bundle config
├── server/                          # Language server (Kotlin)
│   ├── src/main/kotlin/
│   │   └── dev/review/lsp/
│   │       ├── Server.kt            # Entry point, stdio setup
│   │       ├── KotlinLanguageServer.kt  # LSP4J server impl
│   │       ├── KotlinTextDocumentService.kt  # Document operations
│   │       ├── KotlinWorkspaceService.kt     # Workspace operations
│   │       ├── buildsystem/
│   │       │   ├── BuildSystemProvider.kt    # SPI interface
│   │       │   ├── BuildSystemResolver.kt    # Detection + dispatch
│   │       │   ├── ProjectModel.kt           # Unified model
│   │       │   ├── gradle/
│   │       │   │   └── GradleProvider.kt     # Gradle Tooling API (v1)
│   │       │   └── manual/
│   │       │       └── ManualProvider.kt     # Fallback: source-only
│   │       ├── compiler/
│   │       │   ├── CompilerFacade.kt         # Our stable interface
│   │       │   ├── Types.kt                  # ResolvedSymbol, TypeInfo, etc.
│   │       │   └── analysisapi/
│   │       │       └── AnalysisApiCompilerFacade.kt  # K2/FIR implementation
│   │       ├── analysis/
│   │       │   ├── AnalysisSession.kt    # Session lifecycle, owns CompilerFacade
│   │       │   ├── FileIndex.kt          # Symbol/file index
│   │       │   └── DiagnosticsPublisher.kt
│   │       ├── features/
│   │       │   ├── DefinitionProvider.kt
│   │       │   ├── ReferencesProvider.kt
│   │       │   ├── HoverProvider.kt
│   │       │   ├── DocumentSymbolProvider.kt
│   │       │   ├── ImplementationProvider.kt
│   │       │   ├── TypeDefinitionProvider.kt
│   │       │   ├── RenameProvider.kt
│   │       │   ├── CodeActionProvider.kt
│   │       │   └── CompletionProvider.kt
│   │       └── util/
│   │           ├── PositionConverter.kt  # LSP <-> compiler positions
│   │           └── UriUtil.kt
│   ├── build.gradle.kts
│   └── settings.gradle.kts
├── scripts/
│   ├── build.sh                     # Full build (server + client)
│   └── package.sh                   # Create VSIX
├── .vscodeignore                    # Exclude dev files from VSIX
├── package.json                     # Root: workspace + vsce config
└── README.md
```

## Component Design

### 1. VS Code Client (`client/`)

**Responsibilities:**
- Detect Java runtime (JAVA_HOME, PATH, settings override)
- Spawn server JAR as child process via stdio
- Register LanguageClient with document selectors for `kotlin`
- Surface settings: java path, server JVM args, log level

**Key dependencies:**
- `vscode-languageclient` ^9.x
- `vscode` engine ^1.75.0

**package.json contributes:**
```json
{
  "languages": [{ "id": "kotlin", "extensions": [".kt", ".kts"] }],
  "configuration": {
    "kotlinReview.java.home": "Path to Java runtime",
    "kotlinReview.server.jvmArgs": "Additional JVM arguments",
    "kotlinReview.trace.server": "off | messages | verbose"
  }
}
```

### 2. Language Server (`server/`)

**Responsibilities:**
- Implement LSP protocol via LSP4J
- Manage Kotlin compiler analysis sessions
- Provide all P0 + P1 features

**Key dependencies:**
- `org.eclipse.lsp4j` - LSP protocol implementation for JVM
- `org.jetbrains.kotlin:analysis-api` - Kotlin Analysis API (core interfaces)
- `org.jetbrains.kotlin:analysis-api-standalone` - Standalone session setup (no IntelliJ)
- `org.jetbrains.kotlin:analysis-api-fir` - K2/FIR backend implementation
- `org.jetbrains.kotlin:kotlin-compiler-embeddable` - Transitive dep (PSI infrastructure)

**Server lifecycle:**
1. `initialize` → configure capabilities, set workspace root
2. `initialized` → detect build system, resolve ProjectModel (async), start indexing
3. `textDocument/didOpen` → analyze file, publish diagnostics
4. `textDocument/didChange` → re-analyze, update diagnostics
5. `workspace/didChangeWatchedFiles` → if build file changed, re-resolve ProjectModel
6. `shutdown` → release compiler resources
7. `exit` → terminate JVM

### 3. Build System Integration (`server/.../buildsystem/`)

Pluggable architecture for resolving project classpath and source roots from any JVM build system.

**Unified project model — what every provider must produce:**
```kotlin
data class ProjectModel(
    val modules: List<ModuleInfo>
)

data class ModuleInfo(
    val name: String,
    val sourceRoots: List<Path>,        // src/main/kotlin, src/main/java, ...
    val testSourceRoots: List<Path>,    // src/test/kotlin, ...
    val classpath: List<Path>,          // resolved dependency JARs
    val testClasspath: List<Path>,
    val kotlinVersion: String?,         // if detectable
    val jvmTarget: String?              // e.g. "17"
)
```

**Provider SPI:**
```kotlin
interface BuildSystemProvider {
    /** Unique ID: "gradle", "maven", "bazel", etc. */
    val id: String

    /** Files that indicate this build system is in use */
    val markerFiles: List<String>

    /** Priority when multiple build systems detected (higher wins) */
    val priority: Int

    /** Resolve full project model from workspace root */
    suspend fun resolve(workspaceRoot: Path): ProjectModel

    /** Re-resolve a single module (for incremental updates) */
    suspend fun resolveModule(workspaceRoot: Path, moduleName: String): ModuleInfo
}
```

**BuildSystemResolver — detection and dispatch:**
```kotlin
class BuildSystemResolver(
    private val providers: List<BuildSystemProvider>  // loaded via ServiceLoader
) {
    fun detect(workspaceRoot: Path): BuildSystemProvider?
    suspend fun resolve(workspaceRoot: Path): ProjectModel
}
```

Detection logic:
1. Scan workspace root for marker files
2. If multiple match, pick by priority (e.g. Gradle > Maven if both present)
3. If none match, fall back to `ManualProvider` (source-only, no classpath)
4. Notify user via LSP `window/showMessage` which build system was detected

**Providers — implementation plan:**

| Provider | Marker Files | Strategy | Status |
|---|---|---|---|
| `GradleProvider` | `build.gradle.kts`, `build.gradle`, `settings.gradle.kts` | Gradle Tooling API — query `IdeaProject` model for source sets + classpath | **v1 (ship)** |
| `MavenProvider` | `pom.xml` | Shell out to `mvn dependency:build-classpath` + parse POM for source dirs | **future** |
| `BazelProvider` | `WORKSPACE`, `WORKSPACE.bazel`, `MODULE.bazel` | Shell out to `bazel query` + `bazel cquery` for deps | **future** |
| `BuckProvider` | `.buckconfig`, `BUCK` | Shell out to `buck2 audit classpath` | **future** |
| `MillProvider` | `build.sc` | Shell out to `mill show _.sources` + `mill show _.compileClasspath` | **future** |
| `AmperProvider` | `module.yaml` (Amper format) | Parse Amper YAML config directly (simple declarative format) | **future** |
| `ManualProvider` | *(fallback)* | Scan for `src/` dirs, no classpath. Warn user about missing deps | **always** |

**Caching & refresh:**
- Cache `ProjectModel` after first resolution
- Re-resolve on: `build.gradle.kts` changed (file watcher), explicit user command, or LSP `workspace/didChangeWatchedFiles`
- Resolution runs on Index thread (async, non-blocking)

**Repository structure addition:**
```
server/src/main/kotlin/dev/review/lsp/
├── buildsystem/
│   ├── BuildSystemProvider.kt       # SPI interface
│   ├── BuildSystemResolver.kt       # Detection + dispatch
│   ├── ProjectModel.kt              # Unified model
│   ├── gradle/
│   │   └── GradleProvider.kt        # Gradle Tooling API impl
│   └── manual/
│       └── ManualProvider.kt        # Fallback: source-only
```

Future providers go in their own subpackages (`maven/`, `bazel/`, etc.) and register via `ServiceLoader`.

### 4. CompilerFacade Abstraction (`server/.../compiler/`)

Feature providers never touch Kotlin compiler or Analysis API types directly. They program against our `CompilerFacade` interface using our own stable types.

**Interface:**
```kotlin
interface CompilerFacade {
    /** Resolve the symbol at a given position */
    fun resolveAtPosition(file: VirtualFile, position: Position): ResolvedSymbol?

    /** Get the type of the expression at position */
    fun getType(file: VirtualFile, position: Position): TypeInfo?

    /** Get all diagnostics for a file */
    fun getDiagnostics(file: VirtualFile): List<DiagnosticInfo>

    /** Get completion candidates at position */
    fun getCompletions(file: VirtualFile, position: Position): List<CompletionCandidate>

    /** Find all references to a symbol across the project */
    fun findReferences(symbol: ResolvedSymbol): List<SourceLocation>

    /** Find implementations of an interface/abstract class */
    fun findImplementations(symbol: ResolvedSymbol): List<SourceLocation>

    /** Get KDoc/documentation for a symbol */
    fun getDocumentation(symbol: ResolvedSymbol): String?

    /** Get all symbols declared in a file */
    fun getFileSymbols(file: VirtualFile): List<ResolvedSymbol>

    /** Prepare rename: validate the symbol can be renamed */
    fun prepareRename(file: VirtualFile, position: Position): RenameContext?

    /** Compute rename edits across all affected files */
    fun computeRename(context: RenameContext, newName: String): List<FileEdit>
}
```

**Our stable types (never change even if Kotlin APIs change):**
```kotlin
data class ResolvedSymbol(
    val name: String,
    val kind: SymbolKind,           // CLASS, FUNCTION, PROPERTY, CONSTRUCTOR, etc.
    val location: SourceLocation,
    val containingClass: String?,
    val signature: String?,         // human-readable: "fun foo(x: Int): String"
    val fqName: String?             // fully qualified name
)

data class TypeInfo(
    val fqName: String,
    val shortName: String,
    val nullable: Boolean,
    val typeArguments: List<TypeInfo>
)

data class DiagnosticInfo(
    val severity: Severity,         // ERROR, WARNING, INFO
    val message: String,
    val range: SourceRange,
    val code: String?,              // e.g. "UNRESOLVED_REFERENCE"
    val quickFixes: List<QuickFix>  // attached fix suggestions
)

data class CompletionCandidate(
    val label: String,
    val kind: SymbolKind,
    val detail: String?,            // type info
    val insertText: String,
    val isDeprecated: Boolean
)
```

**AnalysisApiCompilerFacade — the K2/FIR implementation:**
```kotlin
class AnalysisApiCompilerFacade(
    private val projectModel: ProjectModel
) : CompilerFacade {

    // Standalone Analysis API session — no IntelliJ dependency
    private val analysisSession: StandaloneAnalysisAPISession =
        buildStandaloneAnalysisAPISession {
            buildKtModuleProvider {
                // Configure from ProjectModel
                addModule(buildKtSourceModule {
                    moduleName = projectModel.modules.first().name
                    addSourceRoot(sourceRoots)
                    addRegularDependency(libraryModule)  // classpath JARs
                    platform = JvmPlatforms.defaultJvmPlatform
                })
            }
        }

    override fun resolveAtPosition(file, position): ResolvedSymbol? {
        // Use Analysis API's analyze {} block
        analyze(ktElement) {
            val symbol: KaSymbol = ktElement.resolveToSymbol() ?: return null
            return symbol.toResolvedSymbol()  // map to our type
        }
    }

    override fun getDiagnostics(file): List<DiagnosticInfo> {
        analyze(ktFile) {
            return ktFile.collectDiagnostics(KaDiagnosticCheckerFilter.ONLY_COMMON_CHECKERS)
                .map { it.toDiagnosticInfo() }  // map to our type
        }
    }

    // ... etc — each method enters analyze {} block, uses Ka* APIs,
    //           maps results to our types before returning
}
```

**Key design rules:**
- All `Ka*` types (`KaSymbol`, `KaType`, `KaDiagnostic`) stay **inside** the facade
- All `analyze {}` blocks stay **inside** the facade
- Feature providers only see `ResolvedSymbol`, `TypeInfo`, `DiagnosticInfo`, etc.
- If an Analysis API standalone method doesn't work, the fix is localized to one facade method

### 5. Analysis Session Management (`server/.../analysis/`)

**AnalysisSession:**
- Receives `ProjectModel` from `BuildSystemResolver`
- Creates `StandaloneAnalysisAPISession` configured with source roots + classpath from ProjectModel
- Owns the `AnalysisApiCompilerFacade` instance
- Handles session lifecycle: init, update (file changed), refresh (build file changed), dispose
- On `ProjectModel` refresh (build file changed): rebuild the standalone session with new classpath

**FileIndex:**
- Maps: symbol name -> locations, file -> symbols, file -> imports
- Built during initial workspace scan using `CompilerFacade.getFileSymbols()`
- Updated incrementally on file changes
- Supports: classes, functions, properties, type aliases

**DiagnosticsPublisher:**
- Calls `CompilerFacade.getDiagnostics()` on file open/change
- Maps `DiagnosticInfo` to LSP `Diagnostic` (trivial, both are our types)
- Publishes via LSP `textDocument/publishDiagnostics`

### 6. Feature Providers

Each provider gets `CompilerFacade` + `FileIndex` injected.

| Provider | LSP Method | CompilerFacade method used |
|---|---|---|
| DefinitionProvider | `textDocument/definition` | `resolveAtPosition()` → return `location` |
| ReferencesProvider | `textDocument/references` | `resolveAtPosition()` → `findReferences()` |
| HoverProvider | `textDocument/hover` | `resolveAtPosition()` + `getType()` + `getDocumentation()` |
| DocumentSymbolProvider | `textDocument/documentSymbol` | `getFileSymbols()` |
| ImplementationProvider | `textDocument/implementation` | `resolveAtPosition()` → `findImplementations()` |
| TypeDefinitionProvider | `textDocument/typeDefinition` | `getType()` → resolve type's declaration location |
| RenameProvider | `textDocument/rename` | `prepareRename()` → `computeRename()` |
| CodeActionProvider | `textDocument/codeAction` | `getDiagnostics()` → extract `quickFixes` |
| CompletionProvider | `textDocument/completion` | `getCompletions()` |

## Marketplace Constraints Addressed

| Constraint | Solution |
|---|---|
| VSIX size | No bundled JVM. Server JAR ~30-50 MB. Total VSIX < 60 MB |
| Activation | `onLanguage:kotlin` only, no startup penalty |
| JVM dependency | Require system Java 11+, detect via JAVA_HOME/PATH |
| Licensing | Kotlin compiler = Apache 2.0, LSP4J = EPL 2.0. Both permissive |
| Process spawning | stdio via vscode-languageclient (standard pattern) |
| Platform support | Pure JVM server = runs anywhere Java runs. No native binaries |

## Performance Design

### Startup Strategy
- **Lazy compiler init**: Don't create `StandaloneAnalysisAPISession` until first file is opened
- **Async indexing**: Return `initialized` immediately, index workspace in background on `Index thread`
- **Progress reporting**: Use LSP `window/workDoneProgress` during workspace indexing
- **JVM flags for fast startup**:
  ```
  -Xmx512m -XX:+UseG1GC -XX:+TieredCompilation -XX:TieredStopAtLevel=1
  ```
  `TieredStopAtLevel=1` skips C2 optimization — faster to interactive by ~1-2s at the cost of slightly lower peak throughput (acceptable for a review tool)

### Incremental Analysis
Stop the compiler at the **ANALYSIS phase** (skip codegen) — ~30-40% faster than full compilation.

**Tiered invalidation on file change:**
1. Re-parse only the changed file (PSI tree)
2. Re-resolve only that file's `BindingContext`
3. If **signatures changed** → re-resolve dependents (tracked via file dependency graph in `FileIndex`)
4. If **only body changed** → no propagation needed

Most review edits are body-only, so the common case re-analyzes a single file.

### Debouncing & Cancellation
- **Debounce `didChange`**: Wait 200-300ms of inactivity before triggering analysis
- **Cancel stale analysis**: Use LSP `$/cancelRequest` (native in LSP4J) to abort in-flight analysis when a newer change arrives
- **Document versioning**: Tag each analysis with document version, discard results for stale versions

### Concurrency Model (Single-Writer / Multiple-Reader)

```
┌─────────────────────────────────────┐
│  Request thread (LSP4J dispatcher)  │  ← fast: hover, symbols, definition
│  Reads from last-good snapshot      │    (no locks on read path)
├─────────────────────────────────────┤
│  Analysis thread (single, serial)   │  ← heavy: re-analyze, diagnostics
│  Calls CompilerFacade methods       │    (swaps in snapshot when done)
├─────────────────────────────────────┤
│  Index thread (background)          │  ← startup: workspace scan
│  Populates FileIndex                │    (runs once, then merges updates)
└─────────────────────────────────────┘
```

- Read-heavy requests (hover, definition, references) served from cached `CompilerFacade` results — never blocked by ongoing analysis
- Analysis thread re-runs `CompilerFacade.getDiagnostics()` and updates caches atomically
- No locks on the hot read path

### Caching
| Cache | Strategy |
|---|---|
| PSI trees per file | Managed by Analysis API session internally |
| Diagnostics per file | Cache `List<DiagnosticInfo>` per file; replace atomically on re-analysis |
| Resolved symbols by position | LRU cache per file, invalidate on file change |
| File dependency graph | Maintained incrementally in `FileIndex` |

### Review-Specific Optimizations
Since this is a review-focused tool, not a full IDE:

| Optimization | Rationale |
|---|---|
| Skip codegen phases | Never compile to bytecode — stop after ANALYSIS |
| Index `src/main` first | Prioritize main sources, index `src/test` in background after |
| Lazy classpath resolution | Only resolve external deps when a file actually imports them |
| Limit completion depth | Only project + direct deps, skip full Java stdlib enumeration |

### Target Latencies

| Scenario | Target |
|---|---|
| Cold start → first diagnostics | < 5s (small project), < 15s (large) |
| Keystroke → updated diagnostics | < 500ms |
| Hover / Go to Definition | < 100ms |
| Find References | < 300ms |
| Completion popup | < 200ms |

## Build & Package Pipeline

1. `./gradlew :server:shadowJar` → fat JAR with all deps
2. `cd client && npm run build` → esbuild bundle
3. `cp server/build/libs/server-all.jar client/server/` → embed JAR
4. `vsce package` → produce `.vsix`

## Resolved Questions
- ~~Gradle/Maven project detection~~: **Resolved (ADR-12)** — Pluggable BuildSystemProvider SPI. Gradle Tooling API in v1, others via future providers.
- ~~Multi-module projects~~: **Resolved** — ProjectModel supports multiple ModuleInfo entries. BuildSystemProvider.resolve() returns all modules. AnalysisSession configures per-module source roots and classpath.
- ~~Kotlin compiler version~~: **Resolved (ADR-15)** — Target Kotlin 2.0+. Pin Analysis API artifacts to a specific Kotlin version (e.g. 2.1.x). Upgrade deliberately after testing. The CompilerFacade abstraction (ADR-14) isolates version-specific breakage.

## Open Questions
- Analysis API standalone session threading: does `analyze {}` block support concurrent calls from multiple threads, or must we serialize all calls through a single thread?
- Analysis API incremental file updates: does the standalone session support notifying about file content changes without rebuilding the entire session?

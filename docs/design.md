# Architecture Design - lsp-kotlin-review

## Overview

```
┌──────────────────────────────────────────────────────────┐
│  VS Code                                                 │
│  ┌────────────────────────────────────────────────────┐  │
│  │  Extension (TypeScript)                            │  │
│  │  - LanguageClient (vscode-languageclient ^9.0.1)   │  │
│  │  - Activation: onLanguage:kotlin                   │  │
│  │  - Java runtime detection (17+)                    │  │
│  └──────────────┬─────────────────────────────────────┘  │
│                 │ stdio (LSP JSON-RPC)                    │
│  ┌──────────────▼─────────────────────────────────────┐  │
│  │  Language Server (Kotlin/JVM 17)                   │  │
│  │  ┌──────────────────────────────────────────────┐  │  │
│  │  │  LSP4J 0.24.0 (protocol layer)              │  │  │
│  │  ├──────────────────────────────────────────────┤  │  │
│  │  │  Feature Providers (P0 + P1)                 │  │  │
│  │  ├──────────────────────────────────────────────┤  │  │
│  │  │  CompilerFacade ← AnalysisApiCompilerFacade  │  │  │
│  │  │       Kotlin Analysis API 2.1.0 (K2/FIR)    │  │  │
│  │  ├──────────────────────────────────────────────┤  │  │
│  │  │  Build System Resolver          ┌─────────┐ │  │  │
│  │  │  ┌─────────┬────────┬─────────┐ │ Manual  │ │  │  │
│  │  │  │ Gradle  │ Maven  │ Bazel   │ │(fallbk) │ │  │  │
│  │  │  │  (impl) │(future)│(future) │ │         │ │  │  │
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
│   ├── syntaxes/
│   │   └── kotlin.tmLanguage.json   # TextMate grammar for syntax highlighting
│   ├── language-configuration.json  # Bracket matching, comment toggling, folding
│   ├── package.json                 # Extension manifest + contributes
│   ├── tsconfig.json
│   ├── esbuild.mjs                  # Bundle config
│   ├── icon.png                     # Marketplace icon (128x128)
│   └── .vscodeignore                # Exclude dev files from VSIX
├── server/                          # Language server (Kotlin)
│   ├── src/main/kotlin/
│   │   └── dev/review/lsp/
│   │       ├── Server.kt            # Entry point, stdio setup
│   │       ├── KotlinLanguageServer.kt  # LSP4J server impl, lifecycle, file watchers
│   │       ├── KotlinTextDocumentService.kt  # Document operations, debounce, providers
│   │       ├── KotlinWorkspaceService.kt     # Workspace operations, build file detection
│   │       ├── buildsystem/
│   │       │   ├── BuildSystemProvider.kt    # SPI interface
│   │       │   ├── BuildSystemResolver.kt    # Detection + dispatch + error fallback
│   │       │   ├── ProjectModel.kt           # Unified model
│   │       │   ├── gradle/
│   │       │   │   └── GradleProvider.kt     # Gradle Tooling API 8.12
│   │       │   └── manual/
│   │       │       └── ManualProvider.kt     # Fallback: source-only
│   │       ├── compiler/
│   │       │   ├── CompilerFacade.kt         # Our stable interface (12 methods)
│   │       │   ├── StubCompilerFacade.kt     # No-op impl for testing
│   │       │   ├── Types.kt                  # ResolvedSymbol, TypeInfo, etc.
│   │       │   └── analysisapi/
│   │       │       └── AnalysisApiCompilerFacade.kt  # K2/FIR implementation
│   │       ├── analysis/
│   │       │   ├── AnalysisSession.kt    # Session lifecycle, rebuild on build changes
│   │       │   ├── FileIndex.kt          # Symbol/file index
│   │       │   └── DiagnosticsPublisher.kt  # Version-aware diagnostic publishing
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
│   ├── src/test/kotlin/              # Tests (unit, integration, e2e)
│   ├── src/test/resources/           # Test fixtures (single-module, multi-module, no-build-system, android-module)
│   ├── build.gradle.kts
│   └── settings.gradle.kts
├── scripts/
│   ├── build.sh                     # Full build (server + client + copy JAR)
│   └── package.sh                   # Build + create VSIX
├── .github/
│   └── workflows/
│       ├── ci.yml                   # Java 17/21 × macOS/Linux matrix
│       └── release.yml              # Tag push → build → publish to Marketplace
├── .gitignore
├── .vscodeignore                    # Exclude dev files from VSIX
├── package.json                     # Root: workspace scripts
├── CONTRIBUTING.md
├── CHANGELOG.md
└── README.md
```

## Component Design

### 1. VS Code Client (`client/`)

**Responsibilities:**
- Detect Java runtime (settings → JAVA_HOME → JDK_HOME → `/usr/libexec/java_home` on macOS → PATH)
- Validate Java version >= 17
- Spawn server JAR as child process via stdio with default JVM flags
- Register LanguageClient with document selectors for `kotlin`
- Provide syntax highlighting via TextMate grammar
- Provide language configuration (bracket matching, comment toggling, folding)
- Surface settings: java path, server JVM args, trace level

**Default JVM flags** (set in `extension.ts`):
```
-Xmx2g -XX:+UseG1GC
```

**Key dependencies:**
- `vscode-languageclient` ^9.0.1
- `vscode` engine ^1.75.0

**package.json contributes:**
```json
{
  "languages": [{
    "id": "kotlin",
    "aliases": ["Kotlin", "kotlin"],
    "extensions": [".kt", ".kts"],
    "configuration": "./language-configuration.json"
  }],
  "grammars": [{
    "language": "kotlin",
    "scopeName": "source.kotlin",
    "path": "./syntaxes/kotlin.tmLanguage.json"
  }],
  "configuration": {
    "kotlinReview.java.home": "Path to Java runtime (17+)",
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
- Register file watchers for build files and trigger session rebuild

**Key dependencies (actual, from build.gradle.kts):**
- `org.eclipse.lsp4j:org.eclipse.lsp4j:0.24.0` — LSP protocol
- `org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0` — async operations
- 10 Kotlin Analysis API `-for-ide` artifacts at 2.1.0 (all `isTransitive = false`):
  - `analysis-api-standalone-for-ide`, `analysis-api-for-ide`, `analysis-api-k2-for-ide`
  - `analysis-api-platform-interface-for-ide`, `analysis-api-impl-base-for-ide`
  - `low-level-api-fir-for-ide`, `symbol-light-classes-for-ide`
  - `kotlin-compiler-common-for-ide`, `kotlin-compiler-fir-for-ide`, `kotlin-compiler-ir-for-ide`
- 11 IntelliJ Platform artifacts at 243.21565.193 (all `isTransitive = false`):
  - `core`, `core-impl`, `extensions`, `util`, `util-base`, `util-rt`
  - `util-class-loader`, `util-text-matching`, `util-xml-dom`
  - `java-psi`, `java-psi-impl`
- `org.gradle:gradle-tooling-api:8.12` — Gradle project resolution
- `org.jetbrains.kotlinx:kotlinx-collections-immutable-jvm:0.3.8`

**Important: All `-for-ide` artifacts require `isTransitive = false`** because their POMs reference unpublished internal JetBrains module names that don't exist as Maven artifacts.

**Maven repositories required:**
- Maven Central
- `packages.jetbrains.team/maven/p/kt/kotlin-ide-plugin-dependencies` — Analysis API artifacts
- `www.jetbrains.com/intellij-repository/releases` — IntelliJ Platform
- `packages.jetbrains.team/maven/p/ij/intellij-dependencies` — IntelliJ dependencies
- `repo.gradle.org/gradle/libs-releases` — Gradle Tooling API

**Server lifecycle:**
1. `initialize` → configure capabilities (all P0+P1), set workspace root
2. `initialized` → detect build system (async), resolve ProjectModel, create AnalysisSession, wire providers, register file watchers for build files
3. `textDocument/didOpen` → update file content, publish diagnostics (version-tracked)
4. `textDocument/didChange` → update file content in map, debounced diagnostics (250ms, runs on stale session)
5. `textDocument/didSave` → rebuild analysis session from disk, re-publish diagnostics
6. `textDocument/didClose` → clear diagnostics, remove version tracking
6. `workspace/didChangeWatchedFiles` → if build file or generated source changed, schedule debounced rebuild (2s window — rapid changes coalesce into one rebuild)
7. `shutdown` → cancel pending rebuilds, dispose analysis session, shut down debounce schedulers, cancel coroutine scope
8. `exit` → terminate JVM

### 3. Build System Integration (`server/.../buildsystem/`)

Pluggable architecture for resolving project classpath and source roots from any JVM build system.

**Unified project model:**
```kotlin
data class ProjectModel(
    val modules: List<ModuleInfo>,
    val projectDir: Path? = null       // detected Gradle project directory
)

data class ModuleInfo(
    val name: String,
    val sourceRoots: List<Path>,
    val testSourceRoots: List<Path>,
    val classpath: List<Path>,
    val testClasspath: List<Path>,
    val kotlinVersion: String?,
    val jvmTarget: String?,
    val isAndroid: Boolean = false      // detected via AndroidManifest/build.gradle/classpath
)
```

**Provider SPI:**
```kotlin
interface BuildSystemProvider {
    val id: String
    val markerFiles: List<String>
    val priority: Int
    suspend fun resolve(workspaceRoot: Path): ProjectModel
    suspend fun resolveModule(workspaceRoot: Path, moduleName: String): ModuleInfo
}
```

**BuildSystemResolver — detection, dispatch, and error fallback:**
```kotlin
class BuildSystemResolver(
    private val providers: List<BuildSystemProvider> = listOf(GradleProvider())
) {
    fun detect(workspaceRoot: Path): BuildSystemProvider
    suspend fun resolve(workspaceRoot: Path): Pair<BuildSystemProvider, ProjectModel>
    // If a provider fails, falls back to ManualProvider with a log message
}
```

Detection logic:
1. Scan workspace root for marker files
2. If multiple match, pick by priority (Gradle = 10, Manual = 0)
3. If none match, fall back to `ManualProvider` (source-only, no classpath)
4. If detected provider fails (e.g. Gradle can't connect), fall back to ManualProvider
5. Log detected build system via LSP `window/logMessage`

**Providers — implementation status:**

| Provider | Marker Files | Strategy | Status |
|---|---|---|---|
| `GradleProvider` | `build.gradle.kts`, `build.gradle`, `settings.gradle.kts`, `settings.gradle` | Gradle Tooling API — query `IdeaProject` model for source sets + classpath. Android enrichment: detect Android modules, add `android.jar`, scan `build/generated/` for generated sources, resolve classpath via init script when `IdeaProject` returns empty (ADR-18). Init script also resolves test classpaths (`debugAndroidTestCompileClasspath`, `debugUnitTestCompileClasspath`) for test source set support (ADR-22). Conventional source root fallback for Android modules. Per-module error handling. | **Implemented** |
| `MavenProvider` | `pom.xml` | Shell out to `mvn dependency:build-classpath` + parse POM for source dirs | **future** |
| `BazelProvider` | `WORKSPACE`, `WORKSPACE.bazel`, `MODULE.bazel` | Shell out to `bazel query` + `bazel cquery` for deps | **future** |
| `ManualProvider` | *(fallback)* | Scan for `src/main/kotlin`, `src/main/java`, `src/test/kotlin`, `src/kotlin`, `src`. No classpath. | **Implemented** |

**Session rebuild on build file / generated source changes:**
- `KotlinLanguageServer` registers file watchers for `**/build.gradle.kts`, `**/build.gradle`, `**/settings.gradle.kts`, `**/settings.gradle`, `**/build/generated/**/*.kt`, `**/build/generated/**/*.java`
- `KotlinWorkspaceService.didChangeWatchedFiles` detects build file or generated source changes and invokes a callback
- Callback schedules a debounced rebuild (2-second window via `ScheduledExecutorService`) — rapid changes (e.g. KSP generating 50 files) coalesce into a single rebuild
- Rebuild re-resolves ProjectModel via `BuildSystemResolver`, calls `AnalysisSession.rebuild(newModel)`, and rewires all providers with the new facade

### 4. CompilerFacade Abstraction (`server/.../compiler/`)

Feature providers never touch Kotlin compiler or Analysis API types directly. They program against our `CompilerFacade` interface using our own stable types.

**Interface (actual implementation):**
```kotlin
interface CompilerFacade {
    fun resolveAtPosition(file: Path, line: Int, column: Int): ResolvedSymbol?
    fun getType(file: Path, line: Int, column: Int): TypeInfo?
    fun getDiagnostics(file: Path): List<DiagnosticInfo>
    fun getCompletions(file: Path, line: Int, column: Int): List<CompletionCandidate>
    fun findReferences(symbol: ResolvedSymbol): List<SourceLocation>
    fun findImplementations(symbol: ResolvedSymbol): List<SourceLocation>
    fun getDocumentation(symbol: ResolvedSymbol): String?
    fun getFileSymbols(file: Path): List<ResolvedSymbol>
    fun prepareRename(file: Path, line: Int, column: Int): RenameContext?
    fun computeRename(context: RenameContext, newName: String): List<FileEdit>
    fun updateFileContent(file: Path, content: String)
    fun refreshAnalysis()  // Rebuild session from disk (called on didSave)
    fun dispose()
}
```

Note: The interface uses `Path` + `line`/`column` (0-based) instead of `VirtualFile` + `Position` as originally designed. This avoids leaking LSP4J or IntelliJ types through the facade boundary.

**Our stable types (defined in `Types.kt`):**
```kotlin
data class ResolvedSymbol(
    val name: String,
    val kind: SymbolKind,           // CLASS, INTERFACE, OBJECT, ENUM, ENUM_ENTRY,
    val location: SourceLocation,   // FUNCTION, PROPERTY, CONSTRUCTOR, TYPE_ALIAS,
    val containingClass: String?,   // TYPE_PARAMETER, PACKAGE, FILE, LOCAL_VARIABLE,
    val signature: String?,         // PARAMETER
    val fqName: String?
)

data class TypeInfo(val fqName: String, val shortName: String, val nullable: Boolean, val typeArguments: List<TypeInfo>)
data class DiagnosticInfo(val severity: Severity, val message: String, val range: SourceRange, val code: String?, val quickFixes: List<QuickFix>)
data class CompletionCandidate(val label: String, val kind: SymbolKind, val detail: String?, val insertText: String, val isDeprecated: Boolean, val sortPriority: Int = 0)
data class QuickFix(val title: String, val edits: List<FileEdit>)
data class FileEdit(val path: Path, val range: SourceRange, val newText: String)
data class RenameContext(val symbol: ResolvedSymbol, val range: SourceRange)
data class SourceLocation(val path: Path, val line: Int, val column: Int)
data class SourceRange(val path: Path, val startLine: Int, val startColumn: Int, val endLine: Int, val endColumn: Int)
```

**AnalysisApiCompilerFacade — the K2/FIR implementation:**
```kotlin
@OptIn(KaExperimentalApi::class)
class AnalysisApiCompilerFacade(
    private val projectModel: ProjectModel
) : CompilerFacade {

    // All analysis calls serialized through single thread
    private val analysisThread: ExecutorService = Executors.newSingleThreadExecutor(...)

    // LRU cache for getFileSymbols results (128 entries, invalidated on updateFileContent)
    private val symbolCache = LinkedHashMap<Path, List<ResolvedSymbol>>(...)

    // Mutable session — rebuilt from disk on didSave via refreshAnalysis()
    @Volatile
    private var _session: StandaloneAnalysisAPISession? = null

    private fun buildSession(): StandaloneAnalysisAPISession {
        return buildStandaloneAnalysisAPISession {
            buildKtModuleProvider {
                // 1. Library modules (deduplicated classpath + testClasspath JARs from all modules)
                // 2. JDK module (from java.home)
                // 3. Single merged source module (all modules' sourceRoots + testSourceRoots — ADR-19, ADR-22)
            }
        }
    }

    override fun getDiagnostics(file): List<DiagnosticInfo> {
        // analyze(ktFile) { ktFile.collectDiagnostics(EXTENDED_AND_COMMON_CHECKERS) }
        // Maps KaDiagnosticWithPsi -> DiagnosticInfo with quick fixes + Android hints
    }

    override fun resolveAtPosition(file, line, column): ResolvedSymbol? {
        // 0. Annotation entries: detect KtAnnotationEntry by walking PSI up to 6 levels (ADR-23)
        //    Resolve typeReference.type → KaClassSymbol → "annotation class <FQN>"
        // 1. Try KtReferenceExpression: resolve via KtReference.resolveToSymbols()
        //    - For source PSI: name from KtNamedDeclaration, signature from extractSignatureLine()
        //      (skips leading annotation lines including multi-line annotations)
        //    - For compiled PSI (ClsElementImpl): synthetic signature via renderSyntheticSignature()
        //      using classId, name, returnType from KaSymbol metadata
        // 2. Try KtNamedDeclaration: return declaration info directly (for hovering over val/fun/class)
        //    Signature via extractSignatureLine() which uses declaration keyword matching
    }

    override fun getType(file, line, column): TypeInfo? {
        // For KtCallableDeclaration: decl.symbol.returnType (avoids Unit for declarations)
        // For KtExpression: expr.expressionType
    }

    override fun refreshAnalysis() {
        // Null old session, GC, then rebuild from disk
        // Called on didSave to pick up saved file changes
    }

    override fun getFileSymbols(file): List<ResolvedSymbol> {
        // Check LRU cache first
        // Walk KtFile.declarations recursively (classes, functions, properties)
        // Map PSI declarations -> ResolvedSymbol
        // Cache result
    }

    override fun findReferences(symbol): List<SourceLocation> {
        // Text-based search for symbol name across all project KtFiles
        // Resolve each occurrence, confirm it points to the same declaration
    }

    // getDocumentation: extract KDoc from declaration PSI
    // findImplementations, getCompletions, prepareRename, computeRename: stub (future)
}
```

**Key design rules (unchanged from original):**
- All `Ka*` types (`KaSymbol`, `KaType`, `KaDiagnostic`) stay **inside** the facade
- All `analyze {}` blocks stay **inside** the facade
- Feature providers only see `ResolvedSymbol`, `TypeInfo`, `DiagnosticInfo`, etc.
- If an Analysis API standalone method doesn't work, the fix is localized to one facade method
- All analysis calls serialized through single-threaded `ExecutorService` (answers the threading open question)

**StubCompilerFacade:** Returns empty/null for all methods. Used by `AnalysisSession` as fallback if `AnalysisApiCompilerFacade` fails to initialize, and used extensively in tests.

### 5. Analysis Session Management (`server/.../analysis/`)

**AnalysisSession:**
- Receives `ProjectModel` from `BuildSystemResolver`
- Creates `AnalysisApiCompilerFacade` (falls back to `StubCompilerFacade` on failure)
- Owns the `CompilerFacade` instance (exposed as `@Volatile var`)
- `rebuild(newModel)`: disposes old facade, creates new one with updated ProjectModel — used when build files change
- `dispose()`: releases compiler resources

**FileIndex:**
- Maps: symbol name → set of files, file → list of symbols
- Uses `ConcurrentHashMap` for thread safety
- `updateFile(path, facade)`: refresh symbols for a single file
- `removeFile(path)`: clear on file close
- `findFilesBySymbolName(name)`: for narrowing reference search candidates

**DiagnosticsPublisher:**
- Calls `CompilerFacade.getDiagnostics()` on file open/change
- **Version-aware**: accepts `requestVersion` and `currentVersionSupplier` — discards results if document version has advanced since request was initiated (stale analysis cancellation)
- **Error-resilient**: try/catch around facade call, logs warning via LSP `window/logMessage`
- Maps `DiagnosticInfo` → LSP `Diagnostic`, publishes via `textDocument/publishDiagnostics`

### 6. Feature Providers

Each provider receives `CompilerFacade` injected via `KotlinTextDocumentService.setAnalysis()`. All providers return `CompletableFuture` and run their work on `CompletableFuture.supplyAsync`.

| Provider | LSP Method | CompilerFacade method used | Status |
|---|---|---|---|
| DefinitionProvider | `textDocument/definition` | `resolveAtPosition()` → return `location` | Implemented |
| ReferencesProvider | `textDocument/references` | `resolveAtPosition()` → `findReferences()` | Implemented |
| HoverProvider | `textDocument/hover` | `resolveAtPosition()` + `getType()` + `getDocumentation()` → markdown | Implemented |
| DocumentSymbolProvider | `textDocument/documentSymbol` | `getFileSymbols()` → DocumentSymbol hierarchy | Implemented |
| ImplementationProvider | `textDocument/implementation` | `resolveAtPosition()` → `findImplementations()` | Implemented |
| TypeDefinitionProvider | `textDocument/typeDefinition` | `getTypeDefinitionLocation()` → resolve type's declaration | Implemented |
| RenameProvider | `textDocument/rename` | `prepareRename()` → `computeRename()` (reuses `findReferences()`) | Implemented |
| CodeActionProvider | `textDocument/codeAction` | `getDiagnostics()` → `quickFixes` + Android build hint command | Implemented |
| CompletionProvider | `textDocument/completion` | `getCompletions()` (scope-based: locals, file, imports, stdlib, library deps + dot/member completion) | Implemented |

## Marketplace Constraints Addressed

| Constraint | Solution | Actual |
|---|---|---|
| VSIX size | No bundled JVM. Server JAR ~85 MB (Analysis API + IntelliJ Platform). Total VSIX ~78 MB | **~78 MB VSIX** (85 MB shadow JAR) |
| Activation | `onLanguage:kotlin` only, no startup penalty | Implemented |
| JVM dependency | Require system Java 17+, detect via JAVA_HOME/PATH | Implemented (javaDetector.ts) |
| Licensing | Kotlin compiler = Apache 2.0, LSP4J = EPL 2.0. Both permissive | Verified |
| Process spawning | stdio via vscode-languageclient (standard pattern) | Implemented |
| Platform support | Pure JVM server = runs anywhere Java runs. No native binaries | Verified |

## Performance Design

### Startup Strategy
- **Lazy compiler init**: `StandaloneAnalysisAPISession` created on first facade method call (mutable `@Volatile var`, rebuilt on save)
- **Async initialization**: `initialized` handler runs build system resolution and session creation on `Dispatchers.Default` coroutine scope, returns immediately
- **JVM flags** (set by client):
  ```
  -Xmx2g -XX:+UseG1GC
  ```

### Debouncing & Cancellation (Implemented)
- **Debounce `didChange`**: 250ms configurable delay before triggering diagnostics. Uses `ScheduledExecutorService` with per-URI pending future tracking. New changes cancel pending diagnostics for that URI.
- **Document versioning**: `ConcurrentHashMap<String, Int>` tracks current version per URI. Versions set on `didOpen`/`didChange`, cleared on `didClose`.
- **Stale analysis cancellation**: `DiagnosticsPublisher.publishDiagnostics()` accepts the request version and a supplier for the current version. If document version has advanced, results are silently discarded.

### Concurrency Model

```
┌─────────────────────────────────────────┐
│  LSP4J Request thread                   │  ← feature provider calls (async)
│  Feature providers run on               │    hover, definition, symbols, etc.
│  CompletableFuture.supplyAsync          │
├─────────────────────────────────────────┤
│  Analysis thread (single, serial)       │  ← CompilerFacade methods
│  Executors.newSingleThreadExecutor      │    all analyze {} blocks serialized
│  Named "kotlin-analysis"                │
├─────────────────────────────────────────┤
│  Diagnostics debounce thread            │  ← didChange → delayed diagnostics
│  ScheduledExecutorService               │    250ms debounce, cancellable
│  Named "diagnostics-debounce"           │
├─────────────────────────────────────────┤
│  Rebuild debounce thread                │  ← file watcher → delayed rebuild
│  ScheduledExecutorService               │    2s debounce, cancellable
│  Named "rebuild-debounce"              │
├─────────────────────────────────────────┤
│  Coroutine scope (Dispatchers.Default)  │  ← initialization, session rebuild
│  Build system resolution, session setup │
└─────────────────────────────────────────┘
```

### Caching (Implemented)
| Cache | Strategy |
|---|---|
| PSI trees per file | Managed by Analysis API session internally |
| File symbols | LRU cache (128 entries) in `AnalysisApiCompilerFacade`, invalidated on `updateFileContent` |

### Multi-Module Support (Implemented)
- `GradleProvider` returns all modules from `IdeaProject` (with Android enrichment for AGP modules)
- `AnalysisApiCompilerFacade` merges all source roots into a single `KtSourceModule` (ADR-19) for cross-module resolution without inter-module dependency wiring
- Classpath JARs deduplicated across all modules (shared library modules)
- JDK module shared across all source modules
- Cross-module go-to-definition, find references, rename all work through the merged session

### Target Latencies

| Scenario | Target |
|---|---|
| Cold start → first diagnostics | < 5s (small project), < 15s (large) |
| Keystroke → updated diagnostics | < 500ms (after 250ms debounce) |
| Hover / Go to Definition | < 100ms |
| Find References | < 300ms |
| Completion popup | < 200ms |

## Error Handling (Implemented)

| Scenario | Behavior |
|---|---|
| Java not found | Extension shows clear error message, does not start server |
| Java < 17 | Extension shows version requirement error |
| Gradle connection fails | `BuildSystemResolver` falls back to `ManualProvider`, logs warning |
| Individual Gradle module fails | `GradleProvider` skips that module, logs warning, continues with others |
| Analysis API initialization fails | `AnalysisSession` falls back to `StubCompilerFacade`, logs error |
| Corrupt .kt file during analysis | `AnalysisApiCompilerFacade` catches exception, returns empty/null, logs warning |
| Diagnostics analysis fails | `DiagnosticsPublisher` catches exception, logs warning via LSP, publishes empty list |

## Build & Package Pipeline

1. `cd server && ./gradlew shadowJar` → fat JAR (`server-all.jar`, ~85 MB)
2. `cd client && npm install && npm run build` → esbuild bundle (`dist/extension.js`)
3. `cp server/build/libs/server-all.jar client/server/` → embed JAR in extension
4. `cd client && npx @vscode/vsce package` → produce `.vsix` (~78 MB)

Automated via `scripts/build.sh` (steps 1-3) and `scripts/package.sh` (all steps).

## CI/CD

**CI** (`.github/workflows/ci.yml`): Runs on push/PR. Matrix: Java 17/21 × macOS/Linux. Builds server, runs 48 tests, builds client, packages VSIX. Uploads VSIX as artifact.

**Release** (`.github/workflows/release.yml`): Triggered on tag push (`v*`). Builds, tests, verifies VSIX < 60 MB, publishes to VS Code Marketplace via `VSCE_PAT` secret, creates GitHub Release with VSIX attached.

## Resolved Questions
- ~~Gradle/Maven project detection~~: **Resolved (ADR-12)** — Pluggable BuildSystemProvider SPI. Gradle Tooling API in v1, others via future providers. Gradle failure falls back to Manual.
- ~~Multi-module projects~~: **Resolved (ADR-19)** — All modules' source roots merged into a single `KtSourceModule`. Classpath deduplicated. Cross-module navigation, references, and rename work. Android modules get conventional source roots + init-script classpath resolution (ADR-18).
- ~~Kotlin compiler version~~: **Resolved (ADR-15)** — Using Kotlin Analysis API 2.1.0. All `-for-ide` artifacts pinned with `isTransitive = false`. CompilerFacade abstraction (ADR-14) isolates version-specific breakage.
- ~~Analysis API threading~~: **Resolved** — All `analyze {}` calls serialized through a single-threaded `ExecutorService`. The standalone session does not support concurrent `analyze {}` calls.
- ~~Analysis API incremental updates~~: **Resolved (ADR-16)** — The standalone session's PSI/FIR trees are immutable. `document.setText()`, `replaceAllChildrenToChildrenOf()`, and raw tree operations all fail. On `didSave`, the entire session is rebuilt via `refreshAnalysis()`. Diagnostics update on save only.

## Compatibility

- **Kotlin**: 2.0+ (Analysis API 2.1.0 / K2/FIR)
- **Gradle**: 6.0+ (Tooling API 8.12 connects to any project Gradle version)
- **JVM**: Java 17+ required
- **Android**: Supported. Auto-detects Android modules, adds `android.jar` from `ANDROID_HOME`, resolves classpath via init script (ADR-18), scans `build/generated/` for R/BuildConfig/KSP sources. Test source sets (`src/androidTest/`, `src/test/`) included in analysis session with test-specific classpath resolution (ADR-22). Generated code (KSP/KAPT) requires running `./gradlew generateDebugResources generateDebugBuildConfig` once. Notification + code action provided.
- **KMP**: Not currently supported (JVM-only source modules configured)

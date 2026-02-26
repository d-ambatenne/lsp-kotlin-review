# Changelog

All notable changes to the Kotlin Review LSP extension will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [0.64.0] - 2026-02-26

### Added

- **Test source set support**: `src/test/` and `src/androidTest/` files are now included in the analysis session — hover, diagnostics, navigation, and completion all work on test files (ADR-22)
- **Test classpath resolution**: Gradle init script now also resolves `debugAndroidTestCompileClasspath` and `debugUnitTestCompileClasspath` for test-only libraries (compose-ui-test, hilt-android-testing, espresso, junit)
- **Annotation hover**: hovering on `@Inject`, `@HiltAndroidTest`, etc. shows `annotation class <FQN>` instead of the constructor signature (ADR-23)
- **Regression test suite**: 11 integration tests covering annotation signature extraction, annotation hover, and test source set resolution

### Fixed

- **Hover on annotated declarations**: hovering on a property like `@Inject lateinit var x: T` showed `@Inject` as the signature — now correctly shows `lateinit var x: T`
- **Stacked/multi-line annotations**: signature extraction now uses declaration keyword matching to reliably skip annotation blocks, including multi-line annotations with parameters (e.g. `@Named(\n  value = "x"\n)`)

## [0.45.0] - 2026-02-23

### Added

- **Android project support**: auto-detect Android modules via AndroidManifest.xml / build.gradle plugin declarations, add `android.jar` from `ANDROID_HOME`, scan `build/generated/` for R class, BuildConfig, KSP, and KAPT sources
- **Gradle init script classpath resolution**: resolve `debugCompileClasspath` for Android modules where `IdeaProject`/`EclipseProject` models return empty dependencies (ADR-18)
- **Merged source module**: all modules' source roots merged into a single Analysis API module for cross-module resolution without dependency graph wiring (ADR-19)
- **Conventional source root fallback**: for Android modules where Gradle doesn't report source dirs, scan for `src/main/java`, `src/main/kotlin`, `src/debug/*`, `src/test/*`, `src/androidTest/*`
- **Android build hint notification**: shown when Android modules lack `build/generated/` directory
- **Enhanced UNRESOLVED_REFERENCE diagnostics**: append "(may be a generated class)" hint for Android projects missing generated sources
- **Code action "Run Gradle code generation"**: lightbulb action on unresolved references that opens terminal with `./gradlew generateDebugResources generateDebugBuildConfig --continue` in the correct project directory
- **P1 features implemented**:
  - **Go to Implementation**: scan project for class/object declarations whose supertypes match target interface/class
  - **Go to Type Definition**: resolve cursor type to its declaration source location via `KaClassType.symbol`
  - **Rename Symbol**: find declaration + all references, generate `FileEdit` for each
  - **Quick Fixes**: `UNUSED_VARIABLE`/`UNUSED_PARAMETER` → rename to `_name`, `REDUNDANT_NULLABLE` → remove `?`
  - **Basic Completion**: local declarations + function parameters + top-level project declarations, filtered by prefix
- Switch diagnostics from `ONLY_COMMON_CHECKERS` to `EXTENDED_AND_COMMON_CHECKERS` (unused variable/parameter warnings)
- `getTypeDefinitionLocation()` method added to `CompilerFacade`
- `isAndroid` flag on `ModuleInfo`, `projectDir` on `ProjectModel`
- `kotlinReview.generateSources` VS Code command

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

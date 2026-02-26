# Architectural Decisions

## ADR-1: Language Server Runtime
**Decision**: JVM-based server using Kotlin compiler APIs (Apache 2.0)
**Rationale**: Kotlin compiler is the only reliable source for semantic analysis. No viable pure-JS alternative exists.
**Trade-off**: Requires Java on user's machine, but all serious Kotlin devs already have it.

## ADR-2: JVM Distribution Strategy
**Decision**: Require system Java (11+), do NOT bundle JVM
**Rationale**:
- Keeps VSIX under 10 MB (marketplace friendly)
- Avoids multi-platform binary builds (macOS Intel/ARM, Linux, Windows)
- Target audience (Kotlin devs) already has Java installed
- Download-on-first-use adds complexity and fragility
**Alternative rejected**: Bundling JVM (200+ MB, platform builds needed)
**Alternative rejected**: Download-on-first-use (network dependency, corporate firewall issues)

## ADR-3: Server Distribution
**Decision**: Ship server as fat JAR inside VSIX
**Rationale**: Single artifact, no network dependency, predictable. Server JAR ~30-50 MB is acceptable.
**Alternative rejected**: Download server at activation (fragile, firewall issues)

## ADR-4: LSP Communication
**Decision**: stdio transport between VS Code client and JVM server
**Rationale**: Simplest, most reliable, best supported by vscode-languageclient. No port conflicts.
**Alternative rejected**: Socket/TCP (port conflicts, firewall issues)

## ADR-5: Kotlin Analysis Backend ~~(SUPERSEDED by ADR-14 + ADR-15)~~
**Original decision**: Use kotlin-compiler-embeddable + kotlin-scripting for analysis
**Superseded because**: K1 APIs are in maintenance mode and will be removed. Project targets Kotlin 2.+. See ADR-14 and ADR-15.

## ADR-6: Extension Activation
**Decision**: Activate on `onLanguage:kotlin` only
**Rationale**: Marketplace best practice. No startup penalty for non-Kotlin projects.

## ADR-7: Build System
**Decision**: Gradle (Kotlin DSL) for server, npm/esbuild for VS Code client
**Rationale**: Standard tooling for each side. Gradle is natural for Kotlin. esbuild is fast and produces small bundles.

## ADR-8: Performance — Analysis-Phase-Only Compilation
**Decision**: Stop Kotlin compiler at ANALYSIS phase, skip codegen
**Rationale**: ~30-40% faster than full pipeline. We never produce bytecode — only need type resolution and diagnostics.

## ADR-9: Performance — Concurrency Model
**Decision**: Single-writer / multiple-reader with atomic snapshot swaps
**Rationale**: Read-heavy requests (hover, definition, references) must never block on analysis. Analysis thread produces a new BindingContext and swaps it in atomically. Zero locks on the read path.
**Alternative rejected**: Mutex-based locking (adds latency to every read request)
**Alternative rejected**: Full copy-on-write (too much memory for large BindingContexts)

## ADR-10: Performance — Incremental Invalidation
**Decision**: Tiered invalidation — body-only changes re-analyze one file, signature changes propagate to dependents
**Rationale**: Most review edits are body-only (fixing a bug, tweaking logic). Re-analyzing the whole project for a one-line change is unacceptable. File dependency graph in FileIndex makes propagation cheap.

## ADR-11: Performance — JVM Tuning
**Decision**: `-Xmx2g -XX:+UseG1GC`
**Rationale**: The K2 Analysis API session requires significant heap for FIR trees and PSI caches (~500-800 MB for a medium project). Session rebuild on save temporarily doubles memory before the old session is GC'd. 2 GB provides headroom. G1GC provides low-latency pauses.
**Revised from**: Originally `-Xmx512m -XX:+TieredCompilation -XX:TieredStopAtLevel=1`. The 512 MB cap caused OOM during session rebuilds. TieredStopAtLevel=1 was removed because it hurts long-running server performance by preventing full JIT optimization.

## ADR-12: Pluggable Build System Integration
**Decision**: Define a `BuildSystemProvider` SPI interface. All build systems produce a unified `ProjectModel` (source roots + classpath). Ship Gradle provider in v1; Maven, Bazel, Buck, Mill, Amper as future providers. Manual fallback always available.
**Rationale**:
- Kotlin is used with Gradle (most common), Maven, Bazel (Google/large corps), Buck (Meta), Mill (Scala/Kotlin), and Amper (JetBrains experimental)
- Designing the abstraction now avoids a painful refactor when adding the second build system
- Every build system ultimately produces the same output for our purposes: source roots + dependency JARs
- SPI via ServiceLoader allows adding providers without modifying core code
**Detection**: Scan workspace for marker files (`build.gradle.kts`, `pom.xml`, `WORKSPACE`, `.buckconfig`, `build.sc`, `module.yaml`). Priority-based selection when multiple match.
**Alternative rejected**: Hardcoded Gradle-only (works initially but requires rewrite to add others)
**Alternative rejected**: IntelliJ project model import (massive dependency, proprietary coupling)

## ADR-13: Gradle Tooling API for v1 Provider
**Decision**: Use Gradle Tooling API to query the `IdeaProject` model for source sets and resolved classpath
**Rationale**:
- Official programmatic API, standalone library (~20 MB)
- Handles multi-module, dependency resolution, source sets natively
- Does not require Gradle to be installed separately (uses wrapper)
- Query `IdeaProject` model which maps cleanly to our `ProjectModel`
**Trade-off**: First resolution requires Gradle daemon startup (5-10s). Cached thereafter.
**Mitigation**: Resolve async on Index thread, serve source-only analysis until resolution completes

## ADR-14: CompilerFacade Abstraction Layer
**Decision**: Define an internal `CompilerFacade` interface that all feature providers program against. No feature provider ever touches Kotlin compiler or Analysis API types directly.
**Rationale**:
- The Kotlin Analysis API is still `@KaExperimentalApi` — breakage is expected across versions
- Our abstraction isolates the blast radius: compiler API changes affect one implementation class, not ten feature providers
- Enables testing feature providers with a mock/fake CompilerFacade
- If the Analysis API standalone mode proves unworkable for a specific feature, we can fall back to lower-level APIs in that one method without polluting the rest of the codebase
**Our stable types**: `ResolvedSymbol`, `TypeInfo`, `DiagnosticInfo`, `CompletionCandidate`, `SymbolKind` — all defined by us, not by JetBrains

## ADR-15: Kotlin Analysis API (K2/FIR) as Compiler Backend
**Decision**: Use the Kotlin Analysis API in standalone mode with the FIR (K2) backend as the implementation behind CompilerFacade. Target Kotlin 2.0+.
**Rationale**:
- K1 is in maintenance mode — JetBrains is not adding features, only critical fixes. It will be removed.
- The Analysis API is JetBrains' intended stable tooling API, already used in production inside the IntelliJ Kotlin plugin
- FIR/K2 backend is faster than K1 for analysis
- Targeting Kotlin 2.+ from the start avoids a forced migration later
- Apache 2.0 licensed
**Risks & mitigations**:
- *Standalone mode is experimental*: Pin to a specific Kotlin version (e.g. 2.1.x), test thoroughly before upgrading. CompilerFacade abstraction limits blast radius.
- *Sparse documentation*: Use the IntelliJ Kotlin plugin source code as reference implementation (open source, Apache 2.0)
- *Possible missing standalone features*: If a specific API doesn't work standalone, fall back to lower-level FIR tree access within the facade implementation
**Key artifacts**:
- `org.jetbrains.kotlin:analysis-api` — core API interfaces
- `org.jetbrains.kotlin:analysis-api-standalone` — standalone session setup (no IntelliJ dependency)
- `org.jetbrains.kotlin:analysis-api-fir` — K2/FIR implementation
- `org.jetbrains.kotlin:kotlin-compiler-embeddable` — still needed as transitive dep for PSI/compiler infrastructure
**Alternative rejected**: K1 BindingContext APIs (dead end, no Kotlin 2.+ future)
**Alternative rejected**: Direct FIR tree access (too low-level, unstable internal representation)

## ADR-16: Session Rebuild for Live Diagnostics
**Decision**: Rebuild the entire standalone Analysis API session from disk on `didSave`. No incremental PSI/FIR updates.
**Rationale**: The standalone Analysis API's PSI and FIR trees are immutable after session creation. Attempts to modify PSI at runtime all failed:
- `document.setText()` is a no-op (documents are read-only in standalone mode)
- `CompositeElement.replaceAllChildrenToChildrenOf()` fails (PomManager not initialized)
- Raw tree operations (`rawRemove`/`rawAddChildren`) partially succeed then crash on writability checks, corrupting the PSI tree and causing cascading FIR/PSI mismatch errors
The only reliable path is `_session = buildSession()` which re-reads all files from disk and builds a fresh FIR tree. The old session is nulled + GC'd before building the new one to avoid OOM.
**Trade-off**: Diagnostics only update on save, not on every keystroke. Acceptable for a code review tool. Session rebuild takes ~2-5 seconds.

## ADR-17: TextMate Grammar for Syntax Highlighting
**Decision**: Use a TextMate grammar (`.tmLanguage.json`) for syntax highlighting instead of LSP semantic tokens.
**Rationale**: TextMate grammars are client-side, regex-based, and instant — no server roundtrip required. They work even before the server initializes. Semantic tokens (LSP) would require server-side Analysis API integration and are explicitly deferred. The grammar covers keywords, comments, strings (including `${}` interpolation), numbers, annotations, declarations, operators, and Kotlin-specific syntax.
**Alternative rejected**: LSP semantic tokens (requires server work, adds latency, deferred per scope.md)

## ADR-18: Android Classpath Resolution via Gradle Init Script
**Decision**: Use a temporary Gradle init script to resolve `debugCompileClasspath` for Android modules, since the standard `IdeaProject` and `EclipseProject` Gradle models don't report dependencies for AGP modules.
**Rationale**: The Android Gradle Plugin doesn't populate the standard Gradle IDE models (`IdeaModule.dependencies`, `EclipseProject.classpath`) for Android modules. Only pure Kotlin/JVM modules get their dependencies reported. The init script creates a `lspResolveClasspath` task that resolves `debugCompileClasspath` (with `releaseCompileClasspath` and `compileClasspath` fallbacks) using lenient resolution to tolerate partial failures.
**Trade-off**: Adds ~1-2 seconds to project initialization. Some Android modules with complex dependency graphs may still fail to resolve (lenient resolution silently skips unresolvable transitive deps). Users can run `./gradlew assembleDebug` for full resolution.
**Alternative rejected**: AGP-specific model API (requires adding `com.android.tools.build:builder-model` dependency, version coupling)
**Alternative rejected**: Scanning Gradle cache directly (fragile, cache layout varies across Gradle versions)

## ADR-19: Merged Source Module for Cross-Module Resolution
**Decision**: Merge all modules' source roots into a single `KtSourceModule` in the Analysis API session, instead of creating per-module source modules with inter-module dependencies.
**Rationale**: The standalone Analysis API doesn't support wiring inter-module dependencies (`addRegularDependency` between source modules) without complex dependency graph construction. A single merged module gives full cross-module navigation and completion. The trade-off (no module boundary enforcement) is acceptable for a code review tool.
**Alternative rejected**: Per-module source modules with dependency wiring (complex, requires tracking which module depends on which)

## ADR-20: Scope-Based Completion with Buffer-Aware Context Detection
**Decision**: Use the Analysis API's `scopeContext(element)` for identifier completion (replaces manual PSI walking), and text-based dot detection from the current buffer for member completion (replaces PSI `KtDotQualifiedExpression` lookup).
**Rationale**: The previous completion implementation only walked PSI trees manually, yielding only local and file-level project declarations — no library or stdlib symbols. `scopeContext` returns all visibility layers (local, file, explicit imports, implicit imports like `kotlin.*`, `kotlin.collections.*`, `java.lang.*`) in priority order. For dot/member completion, the PSI document is stale between saves (ADR-16), so dot detection reads from the current buffer (`fileContents`), extracts the receiver name, and resolves it by name search in the saved PSI.
**Trade-off**: Dot completion requires the receiver declaration to exist in the last-saved PSI. Auto-import (symbols not yet imported) is deferred — `scopeContext` only covers symbols already visible at the position.
**Alternative rejected**: Global classpath scan for auto-import (expensive, thousands of classes, not needed for code review)

## ADR-21: Android Build Variant Support
**Decision**: Replace hardcoded `/debug/` variant paths with a configurable `buildVariant` setting threaded through GradleProvider, init script, and code generation commands. Add file watchers for `build/generated/**` to auto-rebuild the analysis session when generated sources change. Add opt-in background Gradle code generation on file save with progress UI.
**Rationale**: Android projects have multiple build variants (debug, release, custom flavors). The hardcoded debug-only paths prevented the extension from working with other variants. Manual Gradle runs + window reload was poor UX. File watchers eliminate the reload step, and background generation makes the workflow seamless.
**Trade-off**: Background Gradle runs consume resources; opt-in via `android.autoGenerate` setting (default: false). Variant selection requires server restart (full session rebuild) to pick up different classpath and generated sources.

## ADR-22: Test Source Sets in Analysis Session
**Decision**: Include `testSourceRoots` and `testClasspath` alongside their main counterparts in the merged Analysis API session. Extend the init script to also resolve `debugAndroidTestCompileClasspath` and `debugUnitTestCompileClasspath`.
**Rationale**: Test files (`src/test/`, `src/androidTest/`) are valid code review targets but were invisible to the LSP — hover, diagnostics, navigation all returned nothing. Since the project already merges all modules into a single `KtSourceModule` (ADR-19), adding test sources is a natural extension with no architectural cost. Test classpath entries are deduplicated against main classpath in `buildSession()`.
**Trade-off**: Slightly larger analysis session (more source files and classpath entries). No module boundary enforcement between main and test (already the case for cross-module resolution per ADR-19).

## ADR-23: Annotation Hover Resolution
**Decision**: Add a dedicated annotation branch in `resolveAtPosition()` that detects `KtAnnotationEntry` by walking up the PSI tree (up to 6 levels), then resolves the annotation's `typeReference.type` to get the `KaClassSymbol` directly — bypassing the normal reference resolution which returns the constructor.
**Rationale**: Annotation usages (`@Inject`, `@HiltAndroidTest`) are constructor calls in Kotlin's type system. Normal reference resolution returns `KaConstructorSymbol`, rendering as `HiltAndroidTest()`. Users expect to see the annotation class info (`annotation class dagger.hilt.android.testing.HiltAndroidTest`). The dedicated branch intercepts annotation entries before the general reference path and resolves to the class symbol instead.
**Trade-off**: The PSI walk (up to 6 levels) adds minimal overhead. The annotation branch takes priority over the general reference path when hovering anywhere inside an annotation entry.

## ADR-24: Kotlin Multiplatform Support via Per-Target Sessions
**Decision**: Build separate `StandaloneAnalysisAPISession` instances for each KMP target platform (JVM, Android, JS, Native). Each session gets `commonMain` + target-specific source roots merged, with the correct Analysis API platform type (`JvmPlatforms`, `JsPlatforms`, `NativePlatforms`). File routing via path-based detection (`/jvmMain/` → JVM session, `/jsMain/` → JS session, etc.) determines which session handles each file. Non-KMP projects use a single JVM session (backward compatible).
**Rationale**: KMP projects have per-target source sets with different classpaths and platform APIs. A single JVM session can't analyze JS or Native code correctly. Per-target sessions give correct diagnostics, hover, and completion for each platform. The Gradle init script resolves per-target classpaths (`jvmCompileClasspath`, `iosArm64CompileClasspath`, etc.) using the existing lenient resolution pattern.
**Features included**:
- Expect/actual navigation: `findExpectActualCounterparts()` searches across sessions by FQN matching + expect/actual modifier detection. Wired into go-to-implementation, hover, and references.
- Per-file platform indicator: VS Code status bar shows `Kotlin: jvmMain` etc., with target switching for common files.
**Trade-off**: Multiple sessions increase memory usage (~500-800 MB per session). 4-target KMP project may need `-Xmx3g`. Session rebuild on save rebuilds all sessions.

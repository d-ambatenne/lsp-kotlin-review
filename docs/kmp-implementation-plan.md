# KMP Implementation Plan

Ordered phases for implementing Kotlin Multiplatform support as designed in `kmp-design.md`. Each phase is independently testable and non-KMP projects remain backward compatible throughout.

## Phase 1 — Data Model + KMP Detection
*Foundation. No behavioral change for existing non-KMP projects.*

1. Add `KmpTarget`, `KmpPlatform` enum, and `isMultiplatform` to `ProjectModel.kt`
2. Add `detectKmp(moduleDir)` in `GradleProvider.kt` — scan `build.gradle.kts`/`build.gradle` for `kotlin("multiplatform")` plugin
3. Add `resolveKmpTargets(moduleDir)` — scan disk for `src/{target}Main/kotlin` directories, map to `KmpPlatform`
4. Add `addConventionalKmpSourceRoots()` — populate `KmpTarget.sourceRoots` for each detected target
5. Wire into `resolveModule()` — after Android detection, detect KMP and populate `targets` list
6. Set `isMultiplatform = true` on `ProjectModel` if any module has targets

**Verify**: `compileKotlin test` + unit tests for detection logic and source root scanning. Non-KMP projects unchanged.

## Phase 2 — Init Script + Per-Target Classpath

1. Extend init script with KMP pass — resolve per-target compile classpaths (`jvmCompileClasspath`, `iosArm64CompileClasspath`, `jsCompileClasspath`, etc.)
2. New output prefix `LSPKMP:<module>:<configName>:<path>`
3. Parse `LSPKMP:` entries in `GradleProvider`, map config names to `KmpPlatform`
4. Populate `KmpTarget.classpath` and `KmpTarget.testClasspath`
5. Log resolved targets: `[gradle] KMP module 'shared': targets=[jvm(45 deps), js(32 deps), iosArm64(28 deps)]`

**Verify**: Test on a real KMP project (e.g. JetBrains KMP template). Check logs for correct target/classpath resolution.

## Phase 3 — Multi-Session Facade
*Core change. Biggest risk — must keep non-KMP backward compatible.*

1. Create KMP test fixture in `src/test/resources/kmp-module/` with `commonMain` + `jvmMain` + `jsMain` source sets
2. Add `TestFixtures.kmpModule()` returning `ProjectModel` with `KmpTarget` entries
3. Replace `_session` with `sessions: Map<KmpPlatform, StandaloneAnalysisAPISession>`
4. Extract current `buildSession()` into `buildSingleSession(platform, sourceRoots, classpath, includeJdk)`
5. Add `buildSessions()` — for non-KMP: single entry in map (backward compat). For KMP: one session per platform with common + target source roots merged
6. Add `sessionForFile(file: Path)` — path-based routing to correct session
7. Add `platformForFile(file: Path): KmpPlatform?` (public, for later use by platform indicator)
8. Update `allKtFiles()`, `findKtFile()`, and all `CompilerFacade` methods to use `sessionForFile()`
9. Update `refreshAnalysis()` to rebuild all sessions
10. Add `JsPlatforms`, `NativePlatforms` imports (verify they're available from existing deps)

**Verify**: Integration tests — hover/diagnostics/completion work on `commonMain`, `jvmMain`, `jsMain` files in KMP fixture. Non-KMP integration tests still pass.

## Phase 4 — Expect/Actual Navigation

1. Add `findExpectActualCounterparts(file, line, column): List<ResolvedSymbol>` to `CompilerFacade` (default: `emptyList()`)
2. Implement in `AnalysisApiCompilerFacade`:
   - Detect `expect`/`actual` modifier on resolved symbol
   - For `expect` → search all other sessions for `actual` with same FQN
   - For `actual` → search common session for `expect` with same FQN
   - Fallback: text-based grep for `actual fun <name>` / `expect fun <name>`
3. Extend `ImplementationProvider` — if symbol is `expect`, return `findExpectActualCounterparts()` results
4. Extend `HoverProvider` — append `expect (actual in: jvm, js, ios)` or `actual (expect in: common)` to hover
5. Extend `ReferencesProvider` — include expect/actual counterparts in reference results

**Verify**: Integration tests with `expect fun` in commonMain + `actual fun` in jvmMain/jsMain. Go-to-implementation on `expect` returns actuals.

## Phase 5 — Platform Indicator (Client)

1. Add `kotlinReview/filePlatform` custom notification from server → client in `KotlinTextDocumentService.didOpen()`
2. Add `kotlinReview/setPrimaryTarget` custom request from client → server
3. Add `primaryTarget: KmpPlatform` field to facade, used by `sessionForFile()` for `commonMain` files
4. Client: create `vscode.window.createStatusBarItem` showing `Kotlin: jvmMain` / `Kotlin: commonMain → JVM`
5. Client: update on `onDidChangeActiveTextEditor` + on `filePlatform` notification
6. Client: click action → quick-pick for target switching on common files
7. Client: send `setPrimaryTarget` request on selection → server re-publishes diagnostics

**Verify**: Manual testing — status bar shows correct platform, switching works, diagnostics update.

## Phase 6 — Validation + Polish

1. Test on real KMP project (JetBrains KMP Wizard template, or CashApp/sqldelight)
2. Validate native `.klib` support in Analysis API standalone — if unsupported, document as limitation
3. Memory profiling — measure heap with 2 and 4 sessions
4. Auto-detect KMP and log heap warning if `-Xmx` < 3g
5. Write ADR-24
6. Update `docs/scope.md`, `docs/design.md`, `CHANGELOG.md`
7. Version bump + VSIX

**Verify**: Full manual test cycle on real project. All existing tests pass. Memory within bounds.

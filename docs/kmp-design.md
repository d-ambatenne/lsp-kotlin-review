# Kotlin Multiplatform (KMP) Support — Design

## Context

The LSP server is currently hardcoded for JVM-only analysis (`JvmPlatforms.defaultJvmPlatform` everywhere). KMP projects use multiple source sets (`commonMain`, `jvmMain`, `iosMain`, `jsMain`) with per-target classpaths. Opening a KMP project today results in missing source roots and incorrect platform resolution.

**Goal**: Per-target Analysis API sessions for Android, JVM (desktop), iOS/Native, and JS — with file routing based on source set paths.

## Architecture: Per-Target Sessions

```
KMP Project
├── src/commonMain/kotlin/   ──┐
├── src/commonTest/kotlin/   ──┤
├── src/jvmMain/kotlin/      ──┼── JVM session (commonMain + jvmMain, JVM classpath)
├── src/androidMain/kotlin/  ──┼── Android session (commonMain + androidMain, android classpath)
├── src/iosMain/kotlin/      ──┼── Native session (commonMain + iosMain, native klibs)
├── src/jsMain/kotlin/       ──┼── JS session (commonMain + jsMain, JS classpath)
└── src/nativeMain/kotlin/   ──┘  (intermediate source sets fold into their leaf targets)
```

Each target gets its own `StandaloneAnalysisAPISession` with the correct platform type and merged source roots (common + target-specific). File routing determines which session handles a given file.

## Data Model Changes

### `ProjectModel.kt`

```kotlin
data class ProjectModel(
    val modules: List<ModuleInfo>,
    val projectDir: Path? = null,
    val variant: String = "debug",
    val isMultiplatform: Boolean = false   // NEW
)

data class ModuleInfo(
    val name: String,
    val sourceRoots: List<Path>,
    val testSourceRoots: List<Path>,
    val classpath: List<Path>,
    val testClasspath: List<Path>,
    val kotlinVersion: String?,
    val jvmTarget: String?,
    val isAndroid: Boolean = false,
    val targets: List<KmpTarget> = emptyList()  // NEW — empty for non-KMP modules
)

// NEW
data class KmpTarget(
    val name: String,              // "jvm", "android", "iosArm64", "js", etc.
    val platform: KmpPlatform,     // JVM, ANDROID, NATIVE, JS
    val sourceRoots: List<Path>,   // target-specific source roots (e.g. src/jvmMain/kotlin)
    val testSourceRoots: List<Path>,
    val classpath: List<Path>,     // target-specific dependencies
    val testClasspath: List<Path>
)

enum class KmpPlatform {
    JVM, ANDROID, NATIVE, JS
}
```

## GradleProvider Changes

### KMP Detection (`GradleProvider.kt`)

In `resolveModule()`, after existing Android detection:

```kotlin
val isKmp = detectKmp(moduleDir)
```

Detection logic — scan `build.gradle.kts` / `build.gradle` for:
- `kotlin("multiplatform")` or `org.jetbrains.kotlin.multiplatform` plugin
- `kotlin { }` block with target declarations

### Target Enumeration

New function `resolveKmpTargets(moduleDir, connection)`:

1. **Conventional source root scanning** — check which `src/{target}Main/kotlin` directories exist on disk:
   - `jvmMain` → JVM
   - `androidMain` → ANDROID
   - `iosMain`, `iosArm64Main`, `iosX64Main`, `macosMain`, `macosArm64Main`, `linuxX64Main`, `mingwX64Main`, `nativeMain` → NATIVE
   - `jsMain`, `wasmJsMain` → JS
   - `commonMain` → shared across all (not a target itself)

2. **Intermediate source sets** — `nativeMain` is shared across all native targets, `iosMain` is shared across all iOS targets. These fold into their leaf target sessions.

3. **Per-target classpath resolution** — extend the init script to resolve:
   - `jvmCompileClasspath` or `jvmMainCompileClasspath`
   - `debugCompileClasspath` (for Android, already handled)
   - `iosArm64CompileClasspath` or similar (native targets use `.klib` files)
   - `jsCompileClasspath` or `jsMainCompileClasspath`

   New init script output prefix: `LSPKMP:<module>:<target>:<path>`

### Conventional KMP Source Roots

New function `addConventionalKmpSourceRoots(moduleDir, targets)`:

```
Common:    src/commonMain/kotlin, src/commonTest/kotlin
JVM:       src/jvmMain/kotlin, src/jvmTest/kotlin
Android:   src/androidMain/kotlin, src/androidTest/kotlin (+ existing Android logic)
Native:    src/nativeMain/kotlin, src/iosMain/kotlin, src/iosArm64Main/kotlin, ...
JS:        src/jsMain/kotlin, src/jsTest/kotlin, src/wasmJsMain/kotlin
```

Only directories that exist on disk are included.

## AnalysisApiCompilerFacade Changes

### Multi-Session Management

Replace the single `_session` with a map of per-target sessions:

```kotlin
// Current (single session):
@Volatile private var _session: StandaloneAnalysisAPISession? = null

// New (per-target sessions):
@Volatile private var sessions: Map<KmpPlatform, StandaloneAnalysisAPISession> = emptyMap()
// For non-KMP projects, sessions has one entry: JVM (or ANDROID)
```

### `buildSession()` → `buildSessions()`

For non-KMP projects: unchanged behavior (single JVM/Android session).

For KMP projects, build one session per detected platform:

```kotlin
private fun buildSessions(): Map<KmpPlatform, StandaloneAnalysisAPISession> {
    val kmpTargets = projectModel.modules.flatMap { it.targets }
    if (kmpTargets.isEmpty()) {
        // Non-KMP: single session (existing code)
        return mapOf(KmpPlatform.JVM to buildSingleSession(JvmPlatforms.defaultJvmPlatform, ...))
    }

    // Group targets by platform
    val byPlatform = kmpTargets.groupBy { it.platform }
    val commonSourceRoots = findCommonSourceRoots(projectModel)

    return byPlatform.mapValues { (platform, targets) ->
        val platformType = when (platform) {
            KmpPlatform.JVM -> JvmPlatforms.defaultJvmPlatform
            KmpPlatform.ANDROID -> JvmPlatforms.defaultJvmPlatform  // Android is JVM-based
            KmpPlatform.JS -> JsPlatforms.defaultJsPlatform
            KmpPlatform.NATIVE -> NativePlatforms.unspecifiedNativePlatform
        }
        val sourceRoots = commonSourceRoots + targets.flatMap { it.sourceRoots + it.testSourceRoots }
        val classpath = targets.flatMap { it.classpath + it.testClasspath }.distinct()

        buildSingleSession(platformType, sourceRoots, classpath, includeJdk = platform in setOf(KmpPlatform.JVM, KmpPlatform.ANDROID))
    }
}
```

**Platform mapping:**
| KmpPlatform | Analysis API Platform | JDK module? | AAR extraction? |
|---|---|---|---|
| JVM | `JvmPlatforms.defaultJvmPlatform` | Yes | No |
| ANDROID | `JvmPlatforms.defaultJvmPlatform` | Yes | Yes |
| JS | `JsPlatforms.defaultJsPlatform` | No | No |
| NATIVE | `NativePlatforms.unspecifiedNativePlatform` | No | No |

### File Routing

New function to determine which session handles a file:

```kotlin
private fun sessionForFile(file: Path): StandaloneAnalysisAPISession? {
    val pathStr = file.toString()
    // Match target-specific source sets
    val platform = when {
        pathStr.contains("/androidMain/") || pathStr.contains("/androidTest/") -> KmpPlatform.ANDROID
        pathStr.contains("/jvmMain/") || pathStr.contains("/jvmTest/") -> KmpPlatform.JVM
        pathStr.contains("/iosMain/") || pathStr.contains("/nativeMain/") || pathStr.contains("/iosTest/") -> KmpPlatform.NATIVE
        pathStr.contains("/jsMain/") || pathStr.contains("/jsTest/") || pathStr.contains("/wasmJsMain/") -> KmpPlatform.JS
        pathStr.contains("/commonMain/") || pathStr.contains("/commonTest/") -> {
            // Common files: use the "primary" session (prefer JVM > Android > JS > Native)
            sessions.keys.firstOrNull { it == KmpPlatform.JVM }
                ?: sessions.keys.firstOrNull { it == KmpPlatform.ANDROID }
                ?: sessions.keys.firstOrNull()
                ?: KmpPlatform.JVM
        }
        else -> sessions.keys.firstOrNull() ?: KmpPlatform.JVM
    }
    return sessions[platform]
}
```

All `CompilerFacade` methods (`resolveAtPosition`, `getType`, `getDiagnostics`, etc.) use `sessionForFile(file)` instead of `_session`.

### `refreshAnalysis()` Changes

Rebuild all sessions (same pattern as today but iterating over platforms):

```kotlin
override fun refreshAnalysis() {
    synchronized(symbolCache) { symbolCache.clear() }
    runOnAnalysisThread {
        sessions.values.forEach { /* dispose if needed */ }
        sessions = emptyMap()
        System.gc()
        sessions = buildSessions()
    }
}
```

## Init Script Extension

The init script needs a KMP-aware pass. After the existing main/test classpath resolution, add:

```groovy
// --- KMP per-target classpaths ---
def kmpConfigs = configurations.names.findAll {
    it.matches(/^(jvm|android|ios|js|wasmJs|native|linux|macos|mingw).*[Cc]ompile[Cc]lasspath$/)
}
for (configName in kmpConfigs) {
    def cp = configurations.findByName(configName)
    if (cp == null || !cp.canBeResolved) continue
    try {
        cp.incoming.artifactView { lenient = true }.files.each { file ->
            println "LSPKMP:" + project.name + ":" + configName + ":" + file.absolutePath
        }
    } catch (e) {
        println "LSPERR:" + project.name + ":kmp:" + configName + ":" + e.message?.take(200)
    }
}
```

Parse `LSPKMP:` entries and map configuration names to `KmpPlatform`:
- `jvmCompileClasspath` → JVM
- `debugCompileClasspath` → ANDROID (already handled)
- `iosArm64CompileClasspath` → NATIVE
- `jsCompileClasspath` → JS

## Native Target Considerations

Native targets use `.klib` files instead of `.jar`. The Analysis API's `addBinaryRoot` may or may not support klibs in standalone mode. This needs validation:

- If klibs work: add them as binary roots to native library modules
- If klibs don't work: native targets get source-only analysis (common + native source, no stdlib completion). Document as known limitation.

## Memory Impact

Each session holds PSI trees for its source roots. For a typical KMP project:

| Scenario | Sessions | Estimated Memory |
|---|---|---|
| Non-KMP (today) | 1 | ~800 MB |
| KMP: JVM + Android | 2 | ~1.2 GB (shared common sources) |
| KMP: JVM + Android + iOS + JS | 4 | ~2.0 GB |

Recommendation: increase default heap to `-Xmx3g` for KMP projects (detect at startup, log warning if KMP + current heap < 3g).

## Files to Modify

| File | Changes |
|---|---|
| `server/.../buildsystem/ProjectModel.kt` | Add `KmpTarget`, `KmpPlatform`, `isMultiplatform`, `targets` field |
| `server/.../buildsystem/gradle/GradleProvider.kt` | KMP detection, target enumeration, conventional KMP source roots, init script KMP pass |
| `server/.../compiler/analysisapi/AnalysisApiCompilerFacade.kt` | Multi-session map, `buildSessions()`, `sessionForFile()`, route all methods through file routing, expect/actual resolution |
| `server/.../compiler/CompilerFacade.kt` | Add `findExpectActualCounterparts()` method |
| `server/.../features/ImplementationProvider.kt` | Extend to handle expect→actual navigation |
| `server/.../features/HoverProvider.kt` | Show expect/actual info in hover |
| `server/.../KotlinTextDocumentService.kt` | Send `filePlatform` notification on `didOpen` |
| `server/build.gradle.kts` | Add imports for `JsPlatforms`, `NativePlatforms` if not transitively available |
| `client/src/extension.ts` | Platform status bar item, target switching command, increase default heap for KMP |

## Expect/Actual Navigation

KMP's `expect`/`actual` declarations need cross-platform navigation: hovering or go-to-definition on an `expect fun` should list its `actual` implementations across targets.

### Detection

In the Analysis API, `expect` declarations have the `EXPECT` modifier in PSI (`KtDeclaration.hasExpectModifier()`), and `actual` declarations have `ACTUAL` (`KtDeclaration.hasActualModifier()`).

### Implementation

New `CompilerFacade` method:

```kotlin
fun findExpectActualCounterparts(file: Path, line: Int, column: Int): List<ResolvedSymbol>
```

**For `expect` → find `actual`s:**
1. Resolve the symbol at cursor → get its FQN
2. For each target session (excluding the current one), search for declarations with the same FQN that have `actual` modifier
3. Return all matches as `ResolvedSymbol` list

**For `actual` → find `expect`:**
1. Resolve the symbol at cursor → get its FQN
2. Search the common session for a declaration with the same FQN that has `expect` modifier
3. Return as single-item list

### LSP Integration

Wire into existing providers:
- **Go to Definition** on `expect` → show `actual` implementations (similar to go-to-implementation)
- **Go to Definition** on `actual` → jump to the `expect` declaration
- **Find References** on `expect` → include all `actual` counterparts
- **Hover** on `expect`/`actual` → show which targets have implementations

New feature provider: `ExpectActualProvider` (or extend `ImplementationProvider`):
- Register as `textDocument/implementation` handler for `expect` declarations
- LSP method: reuse `textDocument/implementation` — when cursor is on an `expect`, return all `actual` locations

### Fallback

If the Analysis API standalone mode doesn't support cross-session FQN lookup, fall back to text-based search:
1. Get the `expect` declaration's name and signature
2. Grep all target source roots for `actual` + same name
3. Resolve each match to confirm it's the correct counterpart

## Per-File Platform Indicator

### Status Bar Item

When a KMP project is detected, show the active platform in the VS Code status bar:

```
[Kotlin: jvmMain] or [Kotlin: commonMain → JVM] or [Kotlin: iosMain]
```

### Client-Side Implementation (`extension.ts`)

1. **Status bar item**: Create `vscode.window.createStatusBarItem` with platform info
2. **Update on active editor change**: Listen to `vscode.window.onDidChangeActiveTextEditor`
3. **Determine platform from file path**: Same path-matching logic as server-side `sessionForFile()`
4. **Click action**: Show quick-pick to switch the "primary" session for `commonMain` files (e.g., analyze common code as JVM vs JS vs Native)

### Server-Side Support

New LSP custom notification: `kotlinReview/filePlatform`

```typescript
// Server → Client notification when a file is opened
interface FilePlatformNotification {
    uri: string;
    platform: string;       // "jvm", "android", "ios", "js", "common"
    availableTargets: string[]; // All targets in the project
}
```

Sent from `KotlinTextDocumentService.didOpen()` after determining the file's platform via `sessionForFile()`.

### Common File Target Switching

For `commonMain` files, the user may want to analyze them under a different target (e.g., see iOS-specific diagnostics for `expect` declarations). The status bar click triggers:

1. Quick-pick: "Analyze common code as: JVM / Android / iOS / JS"
2. Client sends `kotlinReview/setPrimaryTarget` request
3. Server updates the preferred session for common files
4. Re-publishes diagnostics for open common files

## Non-Goals (Deferred)

- **Wasm standalone target**: `wasmWasi` is niche; only `wasmJs` included under JS.
- **Custom intermediate source sets**: projects with custom source set hierarchies beyond the conventional patterns.

## Verification

1. **Unit tests**: `KmpPlatform` enum, file routing logic, conventional source root scanning
2. **Integration tests**: Create KMP test fixture (`src/commonMain` + `src/jvmMain` + `src/jsMain`) in test resources, verify each session resolves symbols correctly
3. **Expect/actual tests**: Fixture with `expect fun` in commonMain + `actual fun` in jvmMain/jsMain, verify cross-target navigation
4. **Manual testing**: Test on a real KMP project (e.g., KMP template from JetBrains) — hover, diagnostics, completion on commonMain, jvmMain, and iosMain files
5. **Platform indicator test**: Verify status bar shows correct platform when switching between files in different source sets
6. **Memory profiling**: Measure heap usage with 2 vs 4 sessions active

## ADR Reference

This will be **ADR-24: Kotlin Multiplatform Support via Per-Target Sessions**.

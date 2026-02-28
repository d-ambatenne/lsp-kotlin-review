# Feature Scope

## Agreed: 2026-02-21 (Session 1)

### P0 - Must Have
1. Diagnostics (syntax + semantic errors + warnings)
2. Go to Definition
3. Find References
4. Hover (type info, docs)
5. Document Symbols

### P1 - Should Have
6. Go to Implementation
7. Go to Type Definition
8. Rename Symbol
9. Code Actions / Quick Fixes
10. Basic Completion

### Deferred (P2)
- Formatting, Signature Help, Organize Imports, Workspace Symbols, Call Hierarchy

### P3 - Implemented Later
11. Syntax Highlighting (TextMate grammar) — added in 0.19.0
12. Hover for library types (synthetic signatures) — added in 0.16.0
13. Diagnostics refresh on save (session rebuild) — added in 0.19.0
14. P1 features: Go to Implementation, Go to Type Definition, Rename, Quick Fixes, Completion — added in 0.27.0
15. Android project support (detection, android.jar, generated sources, classpath via init script) — added in 0.45.0
16. Type inference in hover (inferred types shown for declarations without explicit type annotations) — added in 0.46.0
17. Library/dependency completion + dot/member completion (scopeContext-based) — added in 0.49.0
18. Android build variant selection, generated source file watchers, background code generation with progress — added in 0.50.0
19. Hover annotation fix: skip leading annotations in signature extraction, handle multi-line annotations — added in 0.60.0
20. Test source set support: androidTest + unit test files in analysis session, test classpath resolution via init script — added in 0.62.0
21. Annotation hover: resolve to annotation class (not constructor), show `annotation class <FQN>` — added in 0.64.0
22. Debounced file watcher rebuilds (2s window) to batch rapid generated source changes — added in 0.65.0

### P3 - Implemented Later (continued)
23. Kotlin Multiplatform support: per-target Analysis API sessions (JVM, Android, iOS/Native, JS), KMP detection, per-target classpath resolution, expect/actual navigation, per-file platform indicator (ADR-24) — added in 0.70.0
24. Analysis API upgrade 2.1.0 → 2.3.0: library metadata resolution for modern Kotlin projects — added in 0.90.0
25. Klib-to-stub generator: native/JS library resolution from .klib metadata (ProtoBuf) — added in 0.91.0
26. Kotlin keyword completions: 50+ keywords with smart insert text — added in 0.98.0
27. Diagnostics performance: async + caching + skip didChange + klib/AAR rebuild caching — added in 1.0.0

### Explicitly Out of Scope
- Snippet completion, code generation, refactoring (extract method/variable)
- Inlay hints, semantic tokens, folding/selection ranges
- Debug adapter, test runner, notebook support

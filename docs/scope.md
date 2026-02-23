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

### Explicitly Out of Scope
- Snippet completion, code generation, refactoring (extract method/variable)
- Inlay hints, semantic tokens, folding/selection ranges
- Debug adapter, test runner, notebook support

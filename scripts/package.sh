#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"

# Run full build first
"$REPO_ROOT/scripts/build.sh"

echo "=== Packaging VSIX ==="
cd "$REPO_ROOT/client"
npx @vscode/vsce package --no-dependencies

echo "=== Package complete ==="
ls -lh "$REPO_ROOT/client"/*.vsix 2>/dev/null || echo "No VSIX found"

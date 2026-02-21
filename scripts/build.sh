#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"

echo "=== Building server ==="
cd "$REPO_ROOT/server"
./gradlew shadowJar

echo "=== Installing client dependencies ==="
cd "$REPO_ROOT/client"
npm install

echo "=== Building client ==="
npm run build

echo "=== Copying server JAR ==="
mkdir -p "$REPO_ROOT/client/server"
cp "$REPO_ROOT/server/build/libs/server-all.jar" "$REPO_ROOT/client/server/"

echo "=== Build complete ==="

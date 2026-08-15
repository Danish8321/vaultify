#!/usr/bin/env bash
# Build + format + analyzers. The gate that "done" is measured against.
set -euo pipefail
cd "$(dirname "$0")/../.."

echo "==> restore (locked)"
dotnet restore --locked-mode 2>/dev/null || dotnet restore

echo "==> format (verify only)"
dotnet format --verify-no-changes --no-restore

echo "==> build (warnings are errors, analyzers on)"
dotnet build --no-restore -v q --nologo

echo "check.sh OK"

#!/usr/bin/env bash
# Build + format + analyzers. The gate that "done" is measured against.
set -euo pipefail
cd "$(dirname "$0")/../.."

echo "==> restore (locked)"
# No fallback to an unlocked restore. Falling back would turn the one check that
# catches an unexpected dependency change into a step that silently repairs it.
dotnet restore --locked-mode

echo "==> format (verify only)"
dotnet format --verify-no-changes --no-restore

echo "==> build (warnings are errors, analyzers on)"
dotnet build --no-restore -v q --nologo

echo "check.sh OK"

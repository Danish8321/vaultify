#!/usr/bin/env bash
# Unit tests only. No network, no Azure, no database.
set -euo pipefail
cd "$(dirname "$0")/../.."

dotnet test tests/Cryptum.UnitTests/Cryptum.UnitTests.csproj \
  --nologo -v q --no-restore

echo "test-fast.sh OK"

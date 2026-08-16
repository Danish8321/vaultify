#!/usr/bin/env bash
# Unit + integration tests. Integration tests need a dev database and Key Vault.
set -euo pipefail
cd "$(dirname "$0")/../.."

"$(dirname "$0")/test-fast.sh"

dotnet test tests/Cryptum.IntegrationTests/Cryptum.IntegrationTests.csproj \
  --nologo -v q --no-restore

echo "test-full.sh OK"

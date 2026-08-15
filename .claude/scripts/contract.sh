#!/usr/bin/env bash
# The API contract is the source of truth between tiers. This regenerates the
# spec and the Android client from it, then fails if either drifted from what
# is committed. A hand-written client type that duplicates a contract type is a bug.
set -euo pipefail
cd "$(dirname "$0")/../.."

SPEC="artifacts/openapi.json"
mkdir -p artifacts

echo "==> generate OpenAPI document from the API"
dotnet build src/Cryptum.Api/Cryptum.Api.csproj --no-restore -v q --nologo
# Microsoft.Extensions.ApiDescription.Server writes the spec at build time once
# configured (plan task 2.5). Until endpoints exist there is nothing to compare.
if [ ! -f "$SPEC" ]; then
  echo "contract.sh: no OpenAPI spec yet — no contract to verify (pre-task-2.5)"
  exit 0
fi

echo "==> regenerate Android client"
# Client generation wired in plan task 2.11.
if [ -d android/core-api ]; then
  ./android/gradlew -p android :core-api:generateApiClient
fi

echo "==> fail on drift"
if ! git diff --exit-code -- "$SPEC" android/core-api; then
  echo "contract.sh: FAILED — generated contract differs from committed. Regenerate and commit in the same change." >&2
  exit 1
fi

echo "contract.sh OK"

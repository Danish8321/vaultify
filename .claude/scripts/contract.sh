#!/usr/bin/env bash
# The API contract is the source of truth between tiers. This regenerates the
# spec and the Android client from it, then fails if either drifted from what
# is committed. A hand-written client type that duplicates a contract type is a bug.
set -euo pipefail
cd "$(dirname "$0")/../.."

SPEC="artifacts/openapi.json"

echo "==> generate OpenAPI document from the API"
# Microsoft.Extensions.ApiDescription.Server writes the spec during build, so
# the build IS the generation step.
# --no-incremental matters: an up-to-date build skips document generation, so a
# stale committed spec would pass the drift check and the gate would lie.
rm -f "$SPEC"
dotnet build src/Cryptum.Api/Cryptum.Api.csproj --no-restore --no-incremental -v q --nologo

# A missing spec is a failure, not a pass. A gate that cannot fail is not a gate,
# and this one silently passed for the whole of phase 2 while proving nothing.
if [ ! -f "$SPEC" ]; then
  echo "contract.sh: FAILED — the build produced no OpenAPI document at $SPEC." >&2
  echo "  Check OpenApiGenerateDocuments in src/Cryptum.Api/Cryptum.Api.csproj." >&2
  exit 1
fi

# The spec describes the contract; it must never carry example key material.
if grep -qiE '"(example|default)"[[:space:]]*:' "$SPEC"; then
  echo "contract.sh: FAILED — the spec contains example values. A DEK or ciphertext" >&2
  echo "  example would publish key-shaped material in a committed artifact." >&2
  exit 1
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

# An untracked spec would make the diff above vacuously succeed.
if ! git ls-files --error-unmatch "$SPEC" >/dev/null 2>&1; then
  echo "contract.sh: FAILED — $SPEC is not tracked by git, so drift cannot be detected." >&2
  exit 1
fi

echo "contract.sh OK"

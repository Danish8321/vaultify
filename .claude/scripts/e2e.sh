#!/usr/bin/env bash
# End-to-end against a deployed dev environment. Includes the check that actually
# proves the architecture: the stored row must be ciphertext, not readable text.
set -euo pipefail
cd "$(dirname "$0")/../.."

: "${CRYPTUM_E2E_BASE_URL:?set CRYPTUM_E2E_BASE_URL to the dev environment}"

if [ ! -d e2e ]; then
  echo "e2e.sh: no e2e suite yet (pre-task-2.14)"
  exit 0
fi

dotnet test e2e --nologo -v q

echo "e2e.sh OK"

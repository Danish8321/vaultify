#!/usr/bin/env bash
# Unit tests only. No network, no Azure, no database.
set -euo pipefail
cd "$(dirname "$0")/../.."

dotnet test tests/Cryptum.UnitTests/Cryptum.UnitTests.csproj \
  --nologo -v q --no-restore

# Android JVM unit tests. core-crypto has no Android dependency by design, so
# its tests run here in seconds rather than needing a booted emulator. The
# on-device confirmation is a separate gate — see ticket 13.
# 'test' rather than a list of module tasks, so a module added later is covered
# without anyone remembering to add it here. Instrumented tests are NOT included
# — they need a device (ticket 13).
if [ -f android/gradlew ]; then
  (cd android && ./gradlew --console=plain -q test)
fi

echo "test-fast.sh OK"

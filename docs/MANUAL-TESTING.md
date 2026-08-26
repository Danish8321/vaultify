# Manual testing guide — development environment

Stepwise guide for exercising Cryptum by hand on a dev machine. Covers what
can actually be verified today given the project's current state (2026-08-21):
no Azure resource is provisioned (ticket 06), and the Android app has no
sign-in flow yet (ticket 14). Every step below says which limitation applies
before you hit it, so a failure you expect isn't mistaken for a bug.

Automated gates (`.claude/scripts/*.sh`) are the source of truth for
correctness. This guide is for what those gates cannot check: things you have
to look at or tap through.

## 0. Prerequisites

| Tool | Why |
|---|---|
| .NET 10 SDK | builds/runs `src/*` and `tests/*` |
| Android Studio (or standalone SDK + JDK 17 temurin) | builds/runs `android/*` |
| A running Android emulator or a physical device, USB debugging on | instrumented tests, manual app run |
| `gh` CLI, authenticated | optional, only for checking CI runs |

Confirm toolchain:

```bash
dotnet --version        # expect a 10.x SDK
java -version           # expect 17 (matches android/build.gradle.kts jvmToolchain)
adb devices              # at least one device/emulator listed, "device" not "unauthorized"
```

## 1. Backend — build and automated gates

Run from repo root.

```bash
.claude/scripts/check.sh       # restore --locked-mode, format check, build with warnings-as-errors
.claude/scripts/test-fast.sh   # unit tests only (~1s) — includes the Android JVM-only test module
.claude/scripts/test-full.sh   # unit + integration tests
```

`test-full.sh`'s integration tests do **not** need a real Azure SQL or Key
Vault: `CryptumApiFactory` (`tests/Cryptum.IntegrationTests/CryptumApiFactory.cs`)
boots the real API against an in-memory SQLite connection and an
`InMemoryKeyWrapper`, swapping only those two things — JWT validation stays
real, against a test signing key. If either script fails, read the failure
before rerunning; don't retry blind (three failed fixes on the same bug =
stop and invoke `diagnosing-bugs`).

Expect: all three green. If `check.sh` fails on format, run
`dotnet format --no-restore` and re-diff before assuming a real bug.

## 2. Backend — manual API smoke test

**Limitation:** `src/Cryptum.Api/Program.cs` always wires `UseSqlServer` and a
real `KeyClient` against `KeyVault:Uri` — there is no dev-mode switch. Running
`dotnet run` against `src/Cryptum.Api` requires a reachable SQL Server and a
real Azure Key Vault, neither of which is provisioned (ticket 06). Skip this
section until that's unblocked; the integration-test path in step 1 is the
closest thing to a manual API check available today.

If you *do* have a personal dev SQL Server + Key Vault to point at:

1. Create `src/Cryptum.Api/appsettings.Development.json` (gitignored) with:
   ```json
   {
     "ConnectionStrings": { "Cryptum": "<your SQL connection string>" },
     "KeyVault": { "Uri": "https://<your-vault>.vault.azure.net/" }
   }
   ```
2. Auth to the vault via `az login` — `DefaultAzureCredential` picks up the
   Azure CLI session (Managed Identity only in production; no client secret
   exists to leak, per ADR-0002).
3. `dotnet run --project src/Cryptum.Api`
4. `curl http://localhost:5284/health` → expect `{"status":"ok"}`.
5. Anything beyond `/health` needs a valid Azure AD B2C token — not
   obtainable without the B2C tenant, so stop here.

## 3. API contract drift check

Whenever `src/Cryptum.Api`'s endpoints or DTOs change:

```bash
.claude/scripts/contract.sh
```

Regenerates `artifacts/openapi.json` and the `android/core-api` generated
client, then fails on any diff against what's committed. If it fails, commit
the regenerated files in the *same* change — a hand-written type that
duplicates a contract type is a bug, not a fix.

## 4. Android — build and automated tests

From repo root:

```bash
cd android
./gradlew build                              # full build, all modules
./gradlew test                                # JVM-only unit tests (core-crypto, feature-lock, feature-vault, core-api)
./gradlew connectedAndroidTest                 # instrumented tests — needs a booted emulator/device
```

Or via `test-fast.sh` from repo root, which runs the Android JVM tests as
part of the same gate the CI `verify` job uses.

Expect: green. `connectedAndroidTest` needs `adb devices` to show a target
first — start an emulator or plug in a device before running it.

**If `connectedAndroidTest` fails intermittently:** this is tracked as
ticket 20 — before touching test code, capture logcat:
```bash
adb logcat -c
./gradlew connectedAndroidTest
adb logcat -d | grep -iE "FATAL|AndroidRuntime|crash|died" > crash.log
```
Attach `crash.log` to the ticket; a single clean rerun does not close it.

## 5. Android — manual app run

**Limitation:** `Repositories.forSignedInUser()` and
`Repositories.filesForSignedInUser()`
(`android/app/src/main/kotlin/com/cryptum/app/Repositories.kt`) both
deliberately throw — there is no sign-in (ticket 14) and no deployed
backend (ticket 06) to call. The app will show onboarding, then the lock
screen, and once unlocked, **crash on purpose** entering the Vault
screen. That crash is the honest state of the wiring, not a regression —
don't file it as a new bug.

Steps:

1. Build and install:
   ```bash
   cd android
   ./gradlew :app:installDebug
   ```
2. Launch **Cryptum** on the device/emulator.
3. **Onboarding (`Onboarding.kt`)** — first launch only, verify:
   - Matrix-rain intro slides appear and are skippable.
   - PIN setup and biometric enroll screens follow, reusing the same
     `PinDots`/`PinPad`/circular hold-target used by the lock screen.
   - Completing onboarding (or skipping) never reappears on subsequent
     launches — persisted via `OnboardingPrefs` (`SharedPreferences`,
     ticket 29). To force onboarding again without reinstalling:
     `adb shell pm clear com.cryptum` (this also clears everything else
     app-local, so treat it as a full reset).
4. **Lock screen (`LockGate` / `SealTheme`)** — verify:
   - App opens locked; the sealed surface is visible with no secret content.
   - Tapping to unlock triggers the biometric prompt (`BiometricGate`).
     On an emulator without biometrics enrolled, use the emulator's
     fingerprint simulation (`Extended controls → Fingerprint → Touch
     sensor`) or device credential fallback.
   - Successful unlock immediately crashes with the `error(...)` message
     from `Repositories.forSignedInUser()` — expected, per the limitation
     above. This confirms the lock gate itself hands off correctly; it is
     as far as this build goes.
   - Backgrounding the app while unlocked (`ReLockOnBackground`) and
     returning should show the lock screen again, not the last unlocked
     state.
5. **Screenshot/recents check:** with the app in the foreground showing any
   screen, open the Recents/App Switcher view. The Cryptum card should show
   a blank or system placeholder thumbnail, never live content —
   `FLAG_SECURE` is set for the whole window lifetime in `MainActivity`.

## 6. Seal grain contrast (ticket 16)

Manual-only — the emulator cannot answer this, a real screen is required.

1. On a physical device, set screen brightness to minimum.
2. If possible, also test in direct sunlight or a very bright room.
3. Launch the app, observe the locked/sealed screen.
4. Look for the grain texture distinguishing `Seal.Mass` (#1A1D22) from
   `Seal.Grain` (#23272E) — a subtle noise/pattern, not a color, is what's
   being checked.
5. **If the grain is visible:** no action, note the device/brightness tested
   in ticket 16.
6. **If the grain has vanished** (flat dark rectangle): raise the
   `Seal.Grain` value in `android/feature-lock/src/main/kotlin/com/cryptum/lock/SealTheme.kt`
   until it survives at that brightness, and record the floor as a comment
   next to the value so it isn't quietly lowered again later.

## 7. CI verification

After pushing, confirm the pipeline is actually green rather than assuming it:

```bash
gh run list --branch main --limit 1
gh run watch <run-id>          # if still in progress
```

A local `check.sh`/`test-fast.sh` pass is not equivalent to CI passing — CI
runs on a clean machine and is the only gate that catches an environment
assumption baked into your local setup.

## Summary — what's actually exercisable today

| Layer | Automated | Manual |
|---|---|---|
| Domain/API logic | ✅ `test-fast.sh`, `test-full.sh` | ⚠️ `/health` only, needs personal Azure resources |
| API contract | ✅ `contract.sh` | — |
| Android crypto/lock/vault-screen logic | ✅ JVM unit tests + instrumented tests (against fakes) | ✅ lock screen + biometric flow |
| Full sign-in → real Vault round trip | ❌ blocked on tickets 06, 14 | ❌ same |
| Seal grain contrast | ❌ can't be automated | ✅ this is the only way (ticket 16) |

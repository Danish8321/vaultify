# 10 — No Gradle; Android tasks 2.9–2.13 cannot start

Status: blocked
Severity: high
Source: attempting plan task 2.9

## Problem

The Android toolchain is *almost* there:

| Component | Status |
|---|---|
| JDK | 17.0.12 LTS ✓ |
| Android SDK | `C:\Users\MOGAMBO\AppData\Local\Android\Sdk` ✓ |
| Platforms | android-33-ext4, 35, 36, 36.1 ✓ |
| AVD | `Android12` ✓ (not booted) |
| **Gradle** | **missing — `gradle: command not found`, and no wrapper in the repo** |

There is no `android/` directory yet, so there is no `gradlew` to fall back on.
Bootstrapping one needs Gradle once, and the download was denied:

```
Permission to use Bash with command curl ... services.gradle.org/distributions/gradle-8.14-bin.zip has been denied
```

## Why it matters

Blocks 2.9 crypto core, 2.10 auth/token storage, 2.11 generated API client,
2.12 app lock, 2.13 Secret create/view — the entire client half of the vertical
slice. Without a build I cannot run a test, so anything written would be
unverified code committed in bulk: horizontal slicing, and a direct violation of
the verification contract.

## Options

1. **Install Gradle** — `winget install Gradle.Gradle`, or unzip a distribution
   and put it on `PATH`. Then `gradle wrapper` once and the wrapper is committed;
   Gradle is never needed globally again.
2. **Approve the download** — re-run the `curl` above and allow it, then
   `gradle wrapper` from the unzipped distribution.
3. **Android Studio** — if installed, it ships Gradle; point at its bundled copy.

Option 1 is cleanest: the committed wrapper is what CI will use anyway, so the
version gets pinned in the repo rather than depending on a machine.

## Also needs deciding

Versions are not yet ratified anywhere: Gradle, Android Gradle Plugin, Kotlin,
`compileSdk`, `minSdk`. `minSdk` is a security decision, not just a compatibility
one — StrongBox and the Keystore guarantees task 2.10 depends on vary by API
level. Proposing compileSdk 36 / minSdk 26 unless told otherwise.

## Done when

`./gradlew --version` runs from `android/`, and the wrapper is committed.

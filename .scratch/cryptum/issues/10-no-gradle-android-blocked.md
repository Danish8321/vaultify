# 10 — No Gradle; Android tasks 2.9–2.13 cannot start

Status: unblocked — option 3 (2026-08-16); wrapper still to be committed
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

## Resolution (2026-08-16)

No install and no download were needed. Android Studio 2025.3.4 is present and
Gradle distributions are already unpacked in the wrapper cache:

```
~/.gradle/wrapper/dists/gradle-{8.11.1,8.13,8.14.3,9.0.0,9.3.1,9.4.1}-bin/
$ .../gradle-9.4.1/bin/gradle --version   ->  Gradle 9.4.1
```

A wrapper pinned to 9.4.1 therefore resolves from cache without touching the
network, which also means the denied download was never actually necessary —
worth remembering before filing the next "tool missing" ticket: check the cache
before the installer.

Versions ratified with this: Gradle 9.4.1, compileSdk 36, minSdk 26. `minSdk 26`
is the security-relevant one — it is the floor at which the Keystore guarantees
task 2.10 relies on (hardware-backed keys, `setUserAuthenticationRequired` with
`setInvalidatedByBiometricEnrollment`) are dependable rather than best-effort.
AGP and Kotlin versions get pinned when the wrapper is generated, against what
Gradle 9.4.1 actually accepts, rather than guessed now.

## Done when

`./gradlew --version` runs from `android/`, and the wrapper is committed.

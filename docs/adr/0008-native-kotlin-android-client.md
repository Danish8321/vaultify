# Native Kotlin Android client, not .NET MAUI

Status: accepted 2026-08-16

The client is a native Android application written in Kotlin. .NET MAUI was the
considered alternative, and a reasonable one: the backend is .NET, so MAUI would
have meant one language across the whole system, contract types shared with
`Cryptum.Api` rather than generated from OpenAPI, and a path to iOS without a
second application.

We chose Kotlin because of what this particular client spends its time doing.

## Context

This ADR exists because the choice had never actually been made. Every design
document said "Android (Kotlin)" — ARCHITECTURE.md, ADR-0001, ADR-0007, and
plan tasks 2.9–2.13 — but that wording was inherited from the original one-line
brief and had never been argued against an alternative. A decision nobody made
is not the same as a decision everybody agreed to, and it was cheap to settle
here: only `core-crypto` existed at the time, roughly a day's work.

## Why Kotlin

The client's security-critical surface is almost entirely Android platform API:

- Keystore key generation with `setUserAuthenticationRequired`, and
  `setInvalidatedByBiometricEnrollment` so enrolling a new fingerprint
  invalidates the key rather than silently extending trust to a new face
- StrongBox when the device has it
- `BiometricPrompt` with device-credential fallback (task 2.12)
- `FLAG_SECURE` on every screen that renders plaintext (task 2.13)

Under MAUI each of these is reached through platform interop or through a
wrapper such as `SecureStorage`. The wrapper is competent, but it decides the
key parameters on your behalf, and those parameters are the security properties.
For a secrets vault, the code most worth reading plainly would become the code
with an abstraction layer over it — and the layer is thickest exactly where the
review needs to be sharpest.

The MVP is Android-only in any case (ARCHITECTURE.md), so MAUI's strongest
argument — one codebase for two platforms — buys nothing until an iOS client is
actually on the roadmap.

## Consequences

- Task 2.11 keeps its generated API client. Contract types are generated from
  `artifacts/openapi.json`, never hand-written, and `contract.sh` fails on
  drift. Under MAUI this task would have largely disappeared; here it stays and
  the gate has to keep earning its place.
- Two languages, so a contract change is a two-sided change. That is the cost
  and it is paid on every endpoint edit.
- An iOS client, if it is ever wanted, is a second application rather than a
  target. Revisit this ADR then rather than pre-paying for it now.
- `core-crypto` is deliberately a plain Kotlin library with no Android
  dependency, which keeps its tests on the JVM and fast. The on-device
  confirmation against Conscrypt is a separate instrumented module
  (`core-crypto-android`).

# 13 — core-crypto is proven on the JVM, not on Android

Status: open
Severity: medium
Source: task 2.9

## Problem

Task 2.9's stated verification says *instrumented* tests. What exists is JVM
unit tests: `core-crypto` is a plain Kotlin library with no Android dependency,
so `./gradlew :core-crypto:test` runs in about two seconds and needs no
emulator.

That was a deliberate trade and it is not free. On Android these primitives come
from **Conscrypt**, not the JDK providers the tests exercised. The two agree on
AES-256-GCM — it is a specified algorithm, not an implementation detail — but
"should agree" is exactly the class of claim this repo does not accept without
evidence.

## Why the trade was made anyway

The red-green loop for a crypto core needs to run in seconds. Requiring a booted
emulator per cycle would have meant fewer cycles and, in practice, weaker tests:
the mutation testing that killed all six assertions here would not have been
affordable at emulator speed.

Keeping the module Android-free also has a design benefit worth stating: nothing
in the crypto core can quietly reach for an Android API, so the compiler
enforces that the primitives stay portable and separately reviewable.

## What is actually proven today

`./gradlew :core-crypto:test` — 8 tests, all mutation-verified:

| Property | Killed by mutation |
|---|---|
| round trip | — (had a genuine red phase) |
| tampered ciphertext / tag / nonce, wrong DEK all throw | `open` swallowing `AEADBadTagException` killed all four |
| nonce and DEK never repeat over 20,000 generations | fixing the nonce to zeros killed it |
| `use` zeroes the DEK on the throwing path | commenting out `dek.fill(0)` killed it |

One honest gap found while mutating: *"sealing the same plaintext twice produces
different ciphertext"* survived the fixed-nonce mutation, because the DEK is
still fresh. It does not cover nonce freshness — the dedicated property test
does. Left in place because it asserts the user-visible consequence, but it must
not be mistaken for the nonce guarantee.

## Done when

An instrumented test (`androidTest`) runs the same vectors on a device or
emulator and agrees with the JVM results. `Android12` AVD exists but has never
been booted. Cheapest sufficient version: seal on device, open on device, plus
one known-answer test with a fixed key/nonce/plaintext asserted against a vector
computed independently — that last one is what would actually catch a provider
disagreement, since a round trip is self-consistent even if both halves are
wrong in the same way.

Also still to do: `zeroing is best-effort on a managed runtime` is asserted in
KDoc but nothing tests that the array is unreachable afterwards, and nothing can
— it is a documented limitation, not a testable property.

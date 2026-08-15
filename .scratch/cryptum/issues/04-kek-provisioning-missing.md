# 04 — No KEK provisioning on first authenticated request

Status: resolved
Severity: high
Source: plan task 2.4

## Problem

`KeyVaultKeyWrapper.WrapAsync` assumes the user's KEK already exists. Nothing creates it. A genuinely new user's first write would fail against real Key Vault. The in-memory fake hides this: it creates a KEK on demand via `GetOrAdd`, so every test passes while the production path has no provisioning step at all.

This is the most dangerous shape of gap — a test double that is more forgiving than production.

## Why it matters

It is the first thing every new user hits, and it is currently untested and unimplemented.

## Done when

- First authenticated request for an unknown B2C subject creates that user's KEK and User row, idempotently.
- Two concurrent first-requests for one subject produce exactly one KEK, not two. A second KEK would orphan every DEK wrapped under the first — silent, unrecoverable data loss.
- No key material derives from the B2C password (ADR-0004): a password reset must never cost a user their Vault.

## Comments

**Resolved.** `UserProvisioning` + `UserProvisioningMiddleware` provision on the first authenticated request; `Users` table added (PK on the derived subject, so the database rejects the duplicate rather than the app checking first).

Three things the work turned up:

1. The first version of the concurrency test was **vacuous**. The in-memory fakes complete synchronously, so the eight "concurrent" calls ran in sequence and the first finished before the second began. Caught by mutation — removing idempotency did not fail the test. Fixed with a gate that holds every caller inside the existence check until all eight have entered it. The corrected test reports 8 KEKs under the same mutation.
2. `ConcurrentDictionary.GetOrAdd` may invoke its factory **more than once** under contention and discard the losers, so counting inside the factory counted attempts, not installed keys, and reported a race that never happened. Now only the winner is counted.
3. `InMemoryKeyWrapper.WrapAsync` used to create a KEK on demand, making the fake **more forgiving than production** — exactly the shape that hides the bug it should catch. It now throws, and `KeyVaultKeyWrapper.WrapAsync` no longer creates either: a crypto-shredded account must not regrow a Vault on its next write.

Residual, accepted: Key Vault has no create-if-absent, so two concurrent provisioning calls can create two *versions* of one key. Survivable rather than destructive — each Item records the version that wrapped it, so both stay unwrappable. Wasteful, not lossy.

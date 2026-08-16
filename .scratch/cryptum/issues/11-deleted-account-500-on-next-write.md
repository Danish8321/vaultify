# 11 — A deleted account got a 500 on its next write

Status: resolved
Severity: high
Source: found while building task 4.1

## Problem

`DeleteAccountAsync` crypto-shredded the KEK and soft-deleted the Items, but left
the `Users` row in place. The access token survives the account, so the next
request from that client was authenticated normally —
`UserProvisioningMiddleware` saw an existing `Users` row, skipped provisioning,
and the write then hit a KEK that no longer existed:

```
POST /items after DELETE /account  ->  500 InternalServerError
```

Not hypothetical: reproduced with a probe test against the running API before
the fix.

## Why it matters

Two things, one worse than the other.

The visible one is that deletion left the account in a state where every write
failed with an unhandled 500 until the token expired.

The structural one: the `Users` row is the record that *a KEK exists*. Once it
outlives the KEK it is a lie, and the one component that reads it — provisioning
— reads it precisely to decide whether to create a key. Any row that can outlive
what it asserts will eventually be believed.

## Resolution

`DeleteAccountAsync` now removes the `Users` row last, after the shred and the
soft-delete. The same identity can then start a fresh Vault, which recovers
nothing: the new KEK cannot unwrap a single DEK the old one wrapped, and the
old Items stay soft-deleted and invisible regardless.

Ordering is deliberate and matches the existing rationale: KEK first (so a
mid-failure leaves unreadable data rather than a half-deleted readable Vault),
rows second, the `Users` row last.

## Decision — confirmed 2026-08-16

**Start over.** No tombstone, no 410. The implemented behaviour stands and this
question is closed; reopening it means a new table, a migration and a
provisioning check, so it should be reopened deliberately or not at all.

Original framing, kept for the reasoning:

"Deleting your account" now means **start over**, not **never come back**. The
B2C identity still exists, so nothing stops that user re-registering anyway;
refusing them at the API would be a speed bump, not a control. If the intended
product behaviour is a permanent block, that is a different feature — a
tombstone row and an explicit 410 — and it needs deciding before launch rather
than after.

Verified: the previously-failing integration test
`A_deleted_account_can_start_over_without_recovering_anything` now passes, and it
asserts both halves — the fresh vault works, and the old Item is still gone.

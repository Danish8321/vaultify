# 22 — Crypto-shred deleted the KEK but never purged it

**Status:** code fixed, verification blocked
**Severity:** high
**Found:** 2026-08-18, reviewing plan task 4.3

## What

`KeyVaultKeyWrapper.CryptoShredAsync` called `StartDeleteKeyAsync` and stopped
there. In Key Vault, delete only moves a key into the soft-delete window — here
7 days — during which an Azure admin can recover it.

ADR-0003 states the opposite outcome as its central claim: "Deletion is
therefore unrecoverable within the time one Key Vault call takes, and the
promise made to the user — 'your data is unreadable now' — is literally true
rather than true in seven days." It also says explicitly that "the KEK deletion
path must issue delete *and* purge".

So for seven days after a user deleted their account, their Vault was still
recoverable by someone, while the product told them it was not.

## Why it survived

The ADR recorded the requirement; nothing checked it. The only test double is
`InMemoryKeyWrapper`, which has no concept of a soft-delete window, so it cannot
express the difference between delete and purge — the fake made the two look the
same. Same shape as tickets 07, 17 and 5.2: a gate that could only pass.

## Fix

`PurgeDeletedKeyAsync` after the delete completes, with the existing 404 handler
widened to cover "already purged" as well as "already deleted".

## Not yet proven

No test covers this. `KeyVaultKeyWrapper` needs a live vault, and the Key Vault
contract test is blocked on ticket 06 (deploying billable Azure resources is not
authorised). When that test is written it must assert the post-shred state via
`GetDeletedKeyAsync` returning 404 — asserting that `GetKeyAsync` 404s would
pass on a merely soft-deleted key and would have missed this exact bug.

`InMemoryKeyWrapper` should also grow a soft-delete window so the distinction is
representable in tests that do not need Azure.

## Related

- ADR-0003 — the requirement this violated
- Ticket 06 — blocks the contract test

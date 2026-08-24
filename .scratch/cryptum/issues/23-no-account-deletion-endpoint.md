# 23 — DeleteAccountScreen's confirm button is a no-op hook

**Status:** closed
**Severity:** medium
**Found:** 2026-08-23, implementing design-sync-android plan task 8
**Closed:** 2026-08-23

## Resolution

`AccountApi.deleteAccount()` (`DELETE /account`) already existed in the
generated client — same shape as ticket 24, no cross-tier contract work
needed. Added:

- `VaultRepository.delete()` + `ApiVaultRepository` impl calling
  `AccountApi.deleteAccount()`. `ApiVaultRepository` now takes `AccountApi` as
  a second constructor argument.
- `AppLock.onAccountDeleted()` — forces the gate locked once the key is
  destroyed server-side, so the caller can't be left holding an unlocked Vault
  whose key no longer exists.
- `VaultScreen` gained real navigation: a "settings" entry point on the list
  screen (`TAG_SETTINGS`) → `SettingsScreen` → `DeleteAccountScreen` →
  (on confirm) `repository.delete()` → `ShreddingScreen` → `onAccountDeleted`
  callback, wired in `MainActivity` to `lock.onAccountDeleted()`. A failed
  `delete()` call leaves the confirm screen up rather than animating a
  destruction that didn't happen.
- `VaultScreenTest.deleting_the_account_calls_the_repository_and_notifies_the_caller`:
  drives the real UI through settings → confirm → shred, then asserts both
  that the repository was actually called (`repository.list()` empty
  afterward) and that `onAccountDeleted` fired.

Verified: `:feature-vault:test`, `:feature-vault:compileDebugAndroidTestKotlin`,
`:feature-lock:test`, `:app:compileDebugKotlin` all pass clean.

Note: `ActivityScreen` still has no navigation entry point — that's pending
item #4 on the follow-up list (separate from this ticket), not blocked by
anything closed here.

## What

`DeleteAccountScreen` (android/feature-vault/src/main/kotlin/com/cryptum/vault/DeleteAccountScreen.kt)
renders the delete-your-vault copy, gates the "Delete permanently" button on
typing `DELETE` exactly, and on confirm calls a caller-supplied
`onConfirmDelete: () -> Unit`. That callback has nothing behind it. `VaultRepository`
(android/feature-vault/src/main/kotlin/com/cryptum/vault/VaultRepository.kt) only
exposes `list`, `create`, and `read` — there is no delete/crypto-shred method, and
`core-api`'s generated `ItemsApi` was not checked to expose one either.

So a user can type `DELETE`, tap the button, and the app will call whatever the
caller wired to `onConfirmDelete` — which today, since nothing calls this screen,
is nothing at all. The screen has no way to actually destroy a key yet.

## Why it survived

This task was scoped explicitly to the screen and its confirm gate, not to the
deletion API — the plan's task 8 says not to fabricate an API call if no
endpoint exists. That's the right call for this slice, but it means the
irreversible action described in ticket 22's ADR-0003 language ("Deletion is
therefore unrecoverable within the time one Key Vault call takes") still has no
client-reachable trigger.

## Fix

Not yet designed. Needs, at minimum:
- A server-side delete endpoint (or reuse of the crypto-shred path from ticket
  22, once that's proven against a live Key Vault) exposed through `core-api`'s
  contract.
- A concrete `VaultRepository.delete()` (or similarly named) method wired to
  that endpoint.
- A caller of `DeleteAccountScreen` (from `SettingsScreen`'s existing
  "Delete account" row) that wires `onConfirmDelete` to it.

## Not yet proven

`DeleteAccountScreenTest` only proves the button-enabled predicate
(`isDeleteConfirmed`) — it says nothing about what happens after the button is
tapped, because nothing happens after the button is tapped.

## Related

- Ticket 22 — the crypto-shred path this screen would eventually need to
  trigger
- design-sync-android plan, Task 8

# 24 — `VaultRepository` has no `update()`, so Edit doesn't persist

**Status:** closed
**Severity:** medium
**Found:** 2026-08-23, implementing design-sync-android plan Task 10
**Closed:** 2026-08-23

## Resolution

`ItemsApi.updateSecret(id, UpdateSecretRequest)` already existed in the generated
client (`PUT /items/{id}`) — no cross-tier contract work needed. Added:

- `SecretEnvelope.sealForUpdate(title, payload): UpdateSecretRequest` — same
  seal pattern as `seal()`, different wire type.
- `VaultRepository.update(id, title, payload)` + `ApiVaultRepository` impl,
  zeroing the DEK after the call same as `create()`.
- `VaultScreen`'s `onSaveEdit` now calls `repository.update(...)` and refreshes
  the list; on failure it falls back to the last known-good payload rather than
  showing the unpersisted edit as saved.
- `SecretEnvelopeTest`: round-trip test for `sealForUpdate`/`open`.
- `VaultScreenTest.editing_a_Secret_persists_through_the_repository`: edits
  through the real UI, then reads the Secret back through the same fake
  repository to prove the write actually landed.

Verified: `:feature-vault:test` and `:feature-vault:compileDebugAndroidTestKotlin`
both pass clean.

## What

`VaultScreen.kt` now has a real edit UI: opening a Secret exposes an "edit"
button next to "reseal now" (only while the Secret is unwrapped, consistent
with "nothing auto-opens"), which routes to `ComposeSecret` in edit mode —
prefilled fields, "E D I T" heading, and the verbatim caption "This replaces
the stored value. The old one isn't recoverable."

But `VaultRepository` only declares `list()`, `create()`, and `read()`. There
is no `update(id, payload)`. Saving an edit today closes back to the (unopened)
Secret with the newly typed payload held only in that screen's local state —
nothing is sent to the server, and the next `read()` returns the old value.

## Why

Task 10 explicitly forbids inventing a repository method or a fake persistence
call to make the UI feel complete. So `Screen.Edit`'s save path
(`onSaveEdit` in `VaultScreen`) is a real callback with a comment explaining
why it doesn't call the API, not a TODO stub.

## Fix

Add `suspend fun update(id: UUID, title: String, payload: SecretPayload): Unit`
(or similar) to `VaultRepository` and `ApiVaultRepository`, following the same
seal/zero-DEK pattern already used by `create()`/`read()` in
`ApiVaultRepository.kt`. This likely needs a corresponding `core-api`
`ItemsApi` endpoint (e.g. `updateSecret`) — check the OpenAPI contract before
assuming one exists; if not, that's a cross-tier slice (persistence → domain →
API contract → client), not an Android-only change.

Once `update()` exists, wire `VaultScreen`'s `onSaveEdit` to call it and
`refresh()` on success, the same shape as `Screen.Compose`'s `onSave`.

## Not yet proven

No test covers edit persistence, because there is nothing to persist yet.
Once `update()` lands, add a `VaultRepository` fake-backed unit test asserting
`onSaveEdit` calls `update` with the edited payload and that `read()` reflects
it afterward.

## Related

- `.scratch/cryptum/plans/design-sync-android.md` — Task 10
- `android/feature-vault/src/main/kotlin/com/cryptum/vault/VaultRepository.kt`
- `android/feature-vault/src/main/kotlin/com/cryptum/vault/VaultScreen.kt`

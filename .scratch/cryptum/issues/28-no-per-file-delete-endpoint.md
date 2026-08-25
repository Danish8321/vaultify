# 28 — No per-file delete endpoint

**Status:** closed
**Severity:** medium
**Found:** 2026-08-24, during Files-feature Android wiring
**Closed:** 2026-08-25

## What

`FilesScreen.kt`'s multi-select delete UI (`TAG_FILES_DELETE_SELECTED`)
has nothing real to call — same gap Secrets had before ticket 23, except
Secrets got a real per-item delete and Files didn't. There's no
`DELETE /items/files/{id}` endpoint, no `VaultService.DeleteFileAsync`,
and no blob cleanup (crypto-shred per ADR-0003 — destroy the KEK/DEK
wrapping first, then soft-delete the row, then async-purge the blob,
mirroring the existing Secret deletion + `PurgeService` pattern).

## Fix shape

- `VaultService.DeleteFileAsync(owner, id)` — owner-scoped (ADR-0002),
  same 404-not-403 convention as the rest of `ItemEndpoints`.
- Purge path needs to also delete the underlying blob, not just the SQL
  row — `PurgeService`/`IBlobStore` currently has no delete method.
- Android: wire `FileRepository.delete(id)`, replace the dead button.

## Resolution

Went generic rather than File-specific: `DELETE /items/{id}` handles both
Secrets and Files through one route (`VaultService.DeleteItemAsync`, not
a separate `DeleteFileAsync`) — no reason to duplicate the owner-scoped
soft-delete logic per kind. For a File, it also deletes the blob
immediately rather than waiting on `PurgeService` (which still only
purges rows, not blobs — that gap is now this ticket's blob-cleanup
half, done, but purge-time blob cleanup for a File whose row survives
past delete some other way remains unaddressed; not a live path today
since delete is the only route to a File's row being marked gone).

`IBlobStore.DeleteAsync` added; `IItemRepository.SoftDeleteAsync`
(single-item, alongside the existing account-wide `SoftDeleteAllAsync`)
added; `AuditAction.ItemDeleted` added. Android: `FileRepository.delete`
and `VaultRepository.deleteItem` both wired; `FilesScreen`'s "Delete
selected" now calls the real repository. Secrets list has no per-item
delete UI yet — the repository method exists but nothing in
`VaultScreen` calls it (out of scope, not raised as a new ticket since
it's a UI-only gap the user can request when wanted).

Backend commit `d6117f8`, Android commit `a3218f4`. Verified:
`check.sh`, `test-full.sh` (64 unit + 33 integration, backend); Android
compile + unit tests clean, instrumented tests not run (ticket 30).

## Related

- Ticket 23 — the Secret-delete precedent this mirrors
- Ticket 30 — same on-device verification gap
- `src/Cryptum.Domain/VaultService.cs`, `IBlobStore.cs`
- `android/feature-vault/src/main/kotlin/com/cryptum/vault/FilesScreen.kt`

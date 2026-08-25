# 28 — No per-file delete endpoint

**Status:** open
**Severity:** medium
**Found:** 2026-08-24, during Files-feature Android wiring

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

## Related

- Ticket 23 — the Secret-delete precedent this mirrors
- `src/Cryptum.Domain/VaultService.cs`, `IBlobStore.cs`
- `android/feature-vault/src/main/kotlin/com/cryptum/vault/FilesScreen.kt`

# 31 — Files backend never verified against a live deployment

**Status:** open
**Severity:** medium
**Found:** 2026-08-24, during Files-feature backend build (fork a7f857bf21e410956) and Android wiring

## What

`VaultService.CreateFileAsync`/`ReadFileAsync`, `BlobStore` (Azure Blob
Storage via managed identity, user-delegation SAS), the new
`AddItemSizeBytes` migration, and the Android `FileRepository`'s direct
SAS PUT/GET have only been verified against `FakeBlobStore` in
integration tests and local compilation — never against a real Azure
Storage account or an applied database migration. Same root blocker as
ticket 06 (Phase 1 Azure infra deferred, marked "unblocked" 2026-08-16
but apparently still not actually deployed).

## Fix shape

Once Azure infra exists: apply the `AddItemSizeBytes` migration via
`schema.sh`, deploy `Cryptum.Api`/`Cryptum.Infrastructure` against a real
Storage account, and run the Android app against it end to end (upload,
list, hold-to-open download+decrypt) — this is the only way the
user-delegation SAS flow, quota enforcement, and blob-path scheme get
falsified for real.

## Related

- Ticket 06 — the underlying infra-deferred blocker
- `src/Cryptum.Infrastructure/BlobStore.cs`, `src/Cryptum.Data/Migrations/20260824071502_AddItemSizeBytes.cs`

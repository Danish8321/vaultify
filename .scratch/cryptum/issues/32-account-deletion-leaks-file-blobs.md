# 32 — Account deletion leaks File blobs past purge

**Status:** closed
**Severity:** medium
**Found:** 2026-08-26, while writing IMPLEMENTATION-PLAN.md's as-built note for 4.2 (Async purge worker)
**Closed:** 2026-08-26

## What

`VaultService.DeleteAccountAsync` crypto-shreds the KEK and soft-deletes
every Item row (`SoftDeleteAllAsync`). `PurgeStore.PurgeBatchAsync`
(`src/Cryptum.Data/PurgeStore.cs`) later hard-deletes those rows in
batches — but only rows. It never calls `IBlobStore.DeleteAsync`, so a
File Item's blob outlives the row that pointed to it. The ciphertext is
already unreadable (its DEK died with the KEK), so this isn't a
confidentiality break, but it is a real storage leak: deleted accounts'
blobs accumulate forever.

Ticket 28's single-item delete (`VaultService.DeleteItemAsync`) does not
have this gap — it deletes the blob synchronously in the same call. Only
the bulk/deferred account-deletion path is affected.

## Fix shape

`PurgeStore.PurgeBatchAsync` needs to know which of the Items it's about
to hard-delete are Files with a `BlobPath`, and call
`IBlobStore.DeleteAsync` for each before (or after) the row delete —
`PurgeStore` currently has no `IBlobStore` dependency at all. Order
matters less here than in the row/version ordering (nothing else points
at the blob once the row is gone), but the batch should still commit the
blob deletes in a way that's safe to interrupt and resume, matching the
resumability property `PurgeService`'s tests already prove for rows.

## Resolution

`PurgeStore` now takes `IBlobStore` alongside `CryptumDbContext`. Before
hard-deleting the batch's rows, it queries the File items in that batch
for their `BlobPath` and calls `IBlobStore.DeleteAsync` for each — blobs
before rows, so an interruption mid-batch leaves the row still
soft-deleted and the next run safely re-deletes the (already-gone) blob
as a no-op.

`Cryptum.Worker/Program.cs` previously had no Azure Blob Storage DI
registration at all — `PurgeStore`'s new constructor dependency exposed
that gap. Added `TokenCredential`/`BlobServiceClient`/`IBlobStore`
registration mirroring `Cryptum.Api/Program.cs` exactly (managed-identity
only, no client secret).

New test: `PurgeTests.Purge_also_deletes_the_blob_behind_a_soft_deleted_File`
seeds a soft-deleted File item via a new `SeedFileAsync` helper, purges,
and asserts `FakeBlobStore.WasDeleted(blobPath)`. `test-full.sh` green,
34/34 integration tests passing.

## Related

- Ticket 28 — the single-item path that doesn't have this gap
- `src/Cryptum.Data/PurgeStore.cs`, `IBlobStore.cs`
- `docs/IMPLEMENTATION-PLAN.md` 4.2's as-built note

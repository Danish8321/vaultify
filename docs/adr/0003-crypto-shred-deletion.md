# Account deletion via crypto-shred, not immediate hard-delete

Status: accepted

Account deletion deletes the User's KEK from Key Vault first (crypto-shred — every wrapped DEK becomes immediately unusable by the running system, subject to the retention caveat below), then soft-deletes DB rows, then purges rows and blobs asynchronously. We rejected synchronous hard-delete of all rows/blobs/KEK together: it isn't transactional across SQL + Blob Storage + Key Vault, and is slow for large vaults.

## Consequences

Key Vault soft-delete means the KEK remains recoverable by an Azure admin for a retention window after "deletion."

**Decided (2026-08-16): 7-day retention, purge protection disabled, and the shred purges the key rather than letting the window elapse.** Deletion is therefore unrecoverable within the time one Key Vault call takes, and the promise made to the user — "your data is unreadable now" — is literally true rather than true in seven days.

The 7-day window is retained even though the shred does not rely on it. It is the floor Azure enforces, and it covers the case where the purge itself fails: the key is already deleted and inert, and the purge can be retried by the same worker that purges rows and blobs.

This choice was taken over a 90-day protected window, which would have left deleted accounts recoverable by an Azure admin for a quarter and forced the user-facing wording to become "within 90 days".

Consequences, including one that is genuinely uncomfortable:

- Purge protection is off permanently, so an attacker holding Key Vault administrative rights can irreversibly destroy *live* users' Vaults. That is the price of being able to purge on demand, and it is accepted rather than mitigated by this ADR. The compensating controls are elsewhere: least-privilege access policy (task 1.2), no standing human administrative access, and the unwrap-volume alerting of ADR-0002. A Key Vault admin compromise is a total-loss event for this product either way — with purge protection on it becomes a ransom event instead, which is not obviously better for the user.
- Infrastructure must set the 7-day window explicitly rather than inheriting a default, and must assert `enablePurgeProtection: false` explicitly, because the property cannot be reversed once true. Silence here is how a vault ends up permanently unable to keep its central promise.
- The deletion timeline must be stated in user-facing privacy copy rather than implied.
- The KEK deletion path must issue delete *and* purge, and must treat "already purged" as success — deletion is idempotent and the worker retries.

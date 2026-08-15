# Account deletion via crypto-shred, not immediate hard-delete

Status: accepted

Account deletion deletes the User's KEK from Key Vault first (crypto-shred — every wrapped DEK becomes immediately unusable by the running system, subject to the retention caveat below), then soft-deletes DB rows, then purges rows and blobs asynchronously. We rejected synchronous hard-delete of all rows/blobs/KEK together: it isn't transactional across SQL + Blob Storage + Key Vault, and is slow for large vaults.

## Consequences

Key Vault soft-delete means the KEK remains recoverable by an Azure admin for a retention window after "deletion."

**Decided: 7-day retention (the minimum), purge protection disabled.** Crypto-shred is effective immediately against the application and against any attacker without Key Vault administrative rights, which is the threat that matters for user data. A short, documented deletion timeline is a defensible reading of erasure obligations, and it avoids the trade the alternatives demand: forcing an immediate purge requires purge protection to stay off permanently, which would also let an attacker with Key Vault admin rights irreversibly destroy *live* users' Vaults, while a 90-day protected window would leave deleted accounts recoverable for a quarter.

Consequences of this choice: infrastructure must set the 7-day window explicitly rather than inheriting a default, the deletion timeline must be stated in user-facing privacy copy rather than implied, and if a future compliance regime demands immediate unrecoverable erasure this decision has to be reopened along with the purge-protection posture it depends on.

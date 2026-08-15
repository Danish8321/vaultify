# Account deletion via crypto-shred, not immediate hard-delete

Status: accepted

Account deletion deletes the User's KEK from Key Vault first (crypto-shred — every wrapped DEK becomes immediately unusable by the running system, subject to the retention caveat below), then soft-deletes DB rows, then purges rows and blobs asynchronously. We rejected synchronous hard-delete of all rows/blobs/KEK together: it isn't transactional across SQL + Blob Storage + Key Vault, and is slow for large vaults.

## Consequences

Key Vault soft-delete (and possibly purge-protection, depending on policy) means the KEK itself may remain recoverable by an Azure admin for a retention window (typically 7–90 days) after "deletion." Crypto-shred still achieves the practical goal — the backend and any attacker without Key Vault admin access cannot decrypt anything — but if a compliance requirement demands immediate, unrecoverable purge, the Key Vault soft-delete retention window must be explicitly reviewed against that requirement before launch, not assumed compliant.

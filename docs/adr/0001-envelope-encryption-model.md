# Envelope encryption: client-side DEK per Item, per-User KEK in Azure Key Vault

Status: accepted

Cryptum stores credentials and files that must stay confidential even from us. We chose client-side (Android) encryption of Item content with a per-Item AES-256-GCM DEK, wrapped by a per-User RSA-2048-OAEP-256 KEK stored in Azure Key Vault (standard tier). The backend and all Azure storage hold only ciphertext and wrapped DEKs; Item plaintext exists only on the device.

This is **server-blind**, not zero-knowledge. Because the backend mediates every unwrap (ADR-0002), it is cryptographically *capable* of decrypting any Item; it is prevented from doing so by authorization policy and audit, not by mathematics. Product and marketing copy must not claim zero-knowledge or end-to-end encryption. Earning that claim would require deriving the KEK from a user secret the server never sees, which trades away password-reset recovery and much of the Azure AD B2C model (ADR-0004) — deliberately not taken.

Alternatives considered: server-side encryption (backend sees plaintext — rejected, backend compromise would expose everything at once); single shared master key (rejected, no per-user crypto-shred, no isolation between users); per-user Azure AD identity calling Key Vault directly (rejected, unmanageable at scale for thousands of consumer users — see ADR-0002).

## Consequences

The backend's Managed Identity has unwrap access to every User's KEK (see ADR-0002) — this is an accepted residual risk, not eliminated by this design, only bounded by it. Per-Item DEK granularity means account deletion via crypto-shred (delete the KEK) instantly invalidates all Items at once, without touching individual rows.

# Backend Managed Identity mediates all Key Vault wrap/unwrap calls

Status: accepted

The .NET backend uses a system-assigned Managed Identity to call Azure Key Vault; it authorizes each wrap/unwrap request by checking the caller's B2C JWT against Item/KEK ownership in application code. We rejected giving each User their own Azure AD identity with direct Key Vault RBAC — that model is the stronger isolation boundary, but is unmanageable at consumer scale (per-user access policies, provisioning/deprovisioning on signup/deletion, B2C-to-AD-identity mapping).

## Consequences

This is a deliberate trust concentration: the backend's Managed Identity can unwrap *any* User's KEK, so a backend compromise or a missed authorization check on one endpoint is a full-vault breach, not a single-user breach. Per-User KEKs limit blast radius between *keys* but not between what the backend process itself can reach.

On every read the plaintext DEK passes through the backend's memory and back over TLS to the client (see [ARCHITECTURE.md](../ARCHITECTURE.md) read flow) — a compromised backend process could retain DEKs and decrypt ciphertext it already stores. This is what makes Cryptum server-blind rather than zero-knowledge (ADR-0001).

Required compensating controls (tracked in full in [security-requirements.md](../security-requirements.md)):
- Key Vault access policy grants `wrapKey`/`unwrapKey` only — never `get`, list, or export of key material.
- Every data-access path must filter by owner at the query level, not via a post-fetch `if` check — this is the actual line of defense standing in for per-user Key Vault RBAC.
- Key Vault diagnostic logs enabled; alert on anomalous unwrap volume per identity.

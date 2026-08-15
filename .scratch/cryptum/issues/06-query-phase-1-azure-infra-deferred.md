# 06 — QUERY: Phase 1 Azure infra is deferred, and it now blocks real work

Status: needs decision from user
Type: query
Severity: high
Source: user instruction "commit phase 0 and move to phase 2"

## Situation

Phase 1 was skipped by your instruction to reach a working slice sooner. That was the right call at the time and it worked — the backend slice is built. But the deferred work is now the binding constraint on several controls that cannot be finished without it:

- **01** — INSERT-only audit principal needs a database.
- **KeyVaultKeyWrapper** — has never run against real Key Vault. Wrap/unwrap correctness, RSA-OAEP-256 compatibility, and the 404-to-`KeyUnavailableException` path are all unexercised.
- **TLS floor, HSTS at the edge** — App Service settings; nothing is deployed.
- **Managed Identity, Azure AD SQL auth** — no resource exists to hold an identity.
- **04** — KEK provisioning can be built and unit-tested, but "it works against real Key Vault" needs the vault.

The longer this runs, the more code accumulates that has only ever met a test double. The in-memory key wrapper is deliberately more forgiving than Key Vault (see 04), so the gap is not neutral — it hides failures.

## Decision needed

Which next:

1. **Phase 1 now** — Bicep, deploy a dev environment, then retro-fit the blocked tests. Unblocks 01, 04 and the Key Vault contract test.
2. **Keep going on backend/Android** — accept a growing pile of never-deployed code, and take the integration pain later in one lump.

My recommendation is 1, but the cost is real: it is Azure spend and a chunk of infra work before any new user-visible capability.

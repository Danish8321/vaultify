# Security requirements (from architecture review)

Must-implement controls, not architectural decisions — no real alternative was considered, these are baseline correctness/hardening requirements tracked here so they aren't lost before implementation.

**This is a list of obligations, not of accomplishments.** Status as of 2026-08-15 is tracked below so the document is not misread as a description of the system as it stands.

| Control | Status |
|---|---|
| Owner predicate in every query (IDOR) | **implemented**, and mutation-tested — removing the predicate makes the IDOR tests fail |
| Unwrap path rate-limited more strictly than CRUD | wired; the test proving the buckets are separate is not written |
| 96-bit CSPRNG nonce, fresh DEK per write | **implemented** in the domain; the Android half does not exist yet |
| `PlaintextDek` zeroed after use | **implemented** and unit-tested |
| "Server-blind", never "zero-knowledge", in all copy | **implemented** across docs; no app-store copy exists yet |
| TLS 1.2+, HSTS | HSTS set in the API; TLS floor is an App Service setting and nothing is deployed |
| INSERT-only audit principal | **not implemented** — needs a database; the interface exposes no update or delete, which is a code-level constraint, not a database-level one |
| System-assigned Managed Identity; Azure AD SQL auth | **not implemented** — no Azure resource exists |
| Android Keystore refresh-token storage | **not implemented** — no Android client exists |
| File size cap and per-User quota | **not implemented** — Files are Phase 3 |
| No request/response body logging | asserted in review, not yet enforced by a test |

- Every data-access query filters by owning User at the query level (IDOR prevention) — see ADR-0002.
- Audit log writes go through a DB principal with INSERT-only rights (no UPDATE/DELETE), or ship to Azure Monitor/Log Analytics as the tamper-resistant source of truth — app DB write access must not be able to alter audit history.
- Android refresh token stored via Keystore-backed encrypted storage (EncryptedSharedPreferences or equivalent) — never plain SharedPreferences.
- File uploads: enforce max size cap per file and a per-User storage quota (ciphertext can't be content-inspected, so size/quota are the only meaningful upload controls).
- Unwrap endpoint (triggers Key Vault unwrap) has a stricter rate limit than general CRUD endpoints — it's the highest-value target per call.
- TLS 1.2+ enforced on App Service, HSTS header set.
- App Service uses system-assigned Managed Identity — no client secrets anywhere in the credential chain.
- Azure SQL access via Azure AD authentication, not a SQL-auth connection string.
- Item title is stored in plaintext (for list view) — accepted, documented information disclosure limited to titles only, never other Secret fields.
- AES-GCM nonces are 96-bit, CSPRNG-generated, fresh per encryption, and never reused with a given DEK (see [ARCHITECTURE.md](./ARCHITECTURE.md)).
- Request/response bodies are excluded from API logging and error reporting — logging a body would capture ciphertext and, on the unwrap path, plaintext DEKs.
- Public claims about Cryptum must say "server-blind", never "zero-knowledge" or "end-to-end encrypted" (see ADR-0001).
- Unwrapped DEKs are held in `PlaintextDek` and zeroed on disposal. This bounds how long key material survives in process memory; it does not protect against a live compromised process, which remains ADR-0002's accepted residual risk.
- Item ownership is enforced as a predicate inside every data-access query. `IItemRepository` deliberately exposes no by-id lookup, so the unsafe call is unwriteable rather than merely discouraged. Covered by `ItemRepositoryIdorTests`, which is mutation-tested: removing the owner predicate makes it fail.

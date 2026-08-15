# Security requirements (from architecture review)

Must-implement controls, not architectural decisions — no real alternative was considered, these are baseline correctness/hardening requirements tracked here so they aren't lost before implementation.

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

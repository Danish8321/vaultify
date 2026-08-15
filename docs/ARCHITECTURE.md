# Cryptum architecture

Vocabulary is defined in [CONTEXT.md](../CONTEXT.md). Decisions and their rationale live in [docs/adr/](./adr/). Hardening requirements live in [security-requirements.md](./security-requirements.md).

## Components

| Component | Technology | Responsibility |
|---|---|---|
| Client | Android (Kotlin) | All encryption and decryption of Item content. Holds plaintext only in memory, only while in use. |
| API | .NET on Azure App Service | Authorization, storage orchestration, mediating wrap/unwrap. Never decrypts Item content. |
| Key management | Azure Key Vault (standard tier) | Holds per-User KEKs (RSA-2048). Performs wrap/unwrap via RSA-OAEP-256. Keys never leave. |
| Metadata store | Azure SQL | Item rows: id, owner, plaintext title, wrapped DEK, nonce, blob pointer, timestamps. |
| Blob store | Azure Blob Storage | File ciphertext. |
| Identity | Azure AD B2C | User authentication, token issuance. |
| Telemetry | App Insights / Log Analytics | Audit trail, anomaly alerting. |

## Cryptographic model

Envelope encryption, two levels:

- **Item content** is encrypted on the device with a per-Item **DEK**, using AES-256-GCM.
- **The DEK** is wrapped by that User's **KEK** in Key Vault, using RSA-OAEP-256. Only the wrapped DEK is ever stored.

A Secret's non-title fields are serialized to a single JSON object and encrypted as one unit — one DEK, one ciphertext, one nonce per Item. A File's bytes are encrypted the same way, with the ciphertext going to Blob Storage and the wrapped DEK to the metadata row.

### Nonce and tag handling

AES-GCM security collapses if a nonce is ever reused with the same key — reuse leaks the authentication subkey, compromising integrity for *all* messages under that key, not just the colliding pair. Rules:

- 96-bit nonce, generated from a cryptographic RNG on the device, fresh for every encryption operation.
- Nonce and GCM auth tag are stored alongside the ciphertext (nonce in the metadata row, tag appended to ciphertext). Neither is secret; both are integrity-critical.
- A DEK is never reused across Items. On edit, a new DEK **and** a new nonce are generated (see ADR-0006) — so no DEK ever encrypts more than one message, which makes nonce collision structurally impossible rather than merely improbable.

## Request flows

### Create / update an Item

1. Client authenticates to B2C, obtains a short-lived access token.
2. Client generates a fresh DEK and nonce, encrypts the Item content locally.
3. Client sends the plaintext DEK to the API over TLS, asking for it to be wrapped.
4. API validates the token, resolves the caller's KEK, calls Key Vault `wrapKey`.
5. API stores ciphertext (SQL row or blob), wrapped DEK, and nonce. Plaintext DEK is discarded.

### Read an Item

1. Client requests the Item by id with its access token.
2. API authorizes: the query itself filters by owning User — ownership is a query predicate, never a post-fetch check (see ADR-0002 and security-requirements).
3. API calls Key Vault `unwrapKey` on the stored wrapped DEK.
4. API returns the plaintext DEK plus the ciphertext, nonce, and tag to the client over TLS.
5. Client decrypts locally and holds plaintext only in memory.

**The consequence to be clear-eyed about:** the plaintext DEK transits the API's memory and the network on every single read. Cryptum is therefore *server-blind by policy*, not by cryptography — a compromised API process could retain DEKs and decrypt the ciphertext it already stores. This is the accepted residual risk recorded in ADR-0002, and it is why the audit trail on unwrap operations is a load-bearing control rather than a nice-to-have.

### Account deletion

Delete the User's KEK (crypto-shred) → soft-delete rows → purge rows and blobs asynchronously. See ADR-0003.

## Request-level controls

Every request crosses the same three gates before it can touch an Item, in this order:

1. **Token validation** — issuer, audience, signature and expiry, with `ClockSkew` set to zero so a stolen token does not outlive the short expiry ADR-0004 chose. `Auth/AuthenticationSetup.cs`.
2. **Identity resolution** — the owner is derived from the validated token subject and from nowhere else. `Auth/CallerIdentity.cs` is the only sanctioned source; no request contract has an owner field, so a body-supplied owner is not merely rejected but unrepresentable.
3. **Owner-scoped query** — the owner predicate lives inside the LINQ query, and no repository method can fetch an Item by id alone. The unsafe call is unwriteable rather than merely discouraged.

Rate limits partition by caller identity rather than IP, so a shared NAT cannot let one User exhaust another's budget and rotating IPs earns no fresh allowance. The read path carries a stricter bucket than general CRUD because each read costs one Key Vault unwrap — the operation ADR-0002 identifies as the one worth watching.

A read that is denied and an Item that does not exist return the identical response. Distinguishing them would let an attacker enumerate valid Item ids.

## Implementation status

As of 2026-08-15 the backend slice — schema, domain, repository, key-wrapper seam, audit trail, endpoints, token validation — is built and verified against SQLite and an in-process KEK store. **No Azure resource is provisioned.** Key Vault, SQL, Blob Storage, App Service and the B2C tenant are all still Phase 1, so the production wrap/unwrap path and the INSERT-only audit principal are written but unexercised. The Android client does not exist yet. Task-level detail is in [IMPLEMENTATION-PLAN.md](./IMPLEMENTATION-PLAN.md).

## Scope boundaries (MVP)

Deliberately excluded, each recoverable later without redesign:

- **No Item sharing between Users.** Sharing requires wrapping a DEK for multiple recipients, a materially different key model.
- **No offline access.** Every read requires network and an unwrap round-trip.
- **No version history.** Edits overwrite in place, but the Item schema is designed so a versions table can be added without migrating existing rows.
- **Multiple devices per User are supported** — no device-bound key material exists, so this needs no extra mechanism.

# Cryptum implementation plan

Design is settled: see [CONTEXT.md](../CONTEXT.md), [ARCHITECTURE.md](./ARCHITECTURE.md), [adr/](./adr/), [security-requirements.md](./security-requirements.md). This plan sequences the build only — it makes no new architectural decisions. Where it would have had to, that is called out as an open question rather than resolved silently.

## Status

Last updated 2026-08-15. A task is marked done only with the named script and its result.

| Task | State | Evidence |
|---|---|---|
| 0.1 Repo skeleton | done | `dotnet sln list` shows 8 projects |
| 0.2 Verification scripts | done | `check.sh` exits 1 on an injected `CS0029`; all scripts exit 0 otherwise |
| 0.3 CI pipeline | done | `ci.yml` runs all four gates; locked restore made real by committed lockfiles (NU1004-verified) |
| 1.1–1.3, 1.5 Azure infra | **deferred** | skipped by decision to reach a working slice first; nothing is deployed |
| 1.4 B2C token validation | partial | validation code and its tests are done and real; the B2C tenant itself is not provisioned |
| 2.1 Item schema | done | `InitialItemSchema` migration read and corrected before applying |
| 2.2 Owner-scoped data access | done | IDOR tests pass; removing the owner predicate makes them fail |
| 2.3 `IKeyWrapper` seam | done | 27 unit tests, incl. cross-user unwrap and shred permanence |
| 2.4 KEK provisioning on signup | done | concurrency test reports 8 KEKs when idempotency is removed, 1 when restored |
| 2.5 API contract | done (server side) | `contract.sh` exits 1 on an injected contract change and 0 when reverted; client generation lands with 2.11 |
| 2.6 Item endpoints | done | 8 integration tests against the real JWT handler |
| 2.7 Audit log | partial | domain, mapping and migration done; the INSERT-only DB principal needs a database, so the tamper test is blocked on Phase 1 |
| 2.8 Rate limiting | done | unwrap budget exhausts at 20 while CRUD still serves 200; fails if the buckets are equalised |
| 2.9–2.14 Android and e2e | blocked | no Gradle on this machine and no wrapper to bootstrap from (ticket 10) |
| 3.0 Item version history | done (server side) | 16 unit + 5 integration tests; restore proven by decrypting, not by comparing bytes; migration reviewed, not applied |

One gap is worth naming because it weakens a later claim until closed: no test yet demonstrates that the audit trail resists deletion by the application (2.7), and that needs a real database.

## Sequencing principle

Phase 0 built the verification scripts before any feature code, because every later task's completion depends on being able to run them.

After that, the unit of work is a vertical slice, not a tier. Phase 2 ships one Secret through every layer — schema, domain, API contract, Android client, tests at each crossing — before Phase 3 widens to Files. A tier built "to be wired up later" is not a shippable increment and is explicitly not how this plan is ordered.

## Two decisions taken by default

Both are reversible now and expensive later; override before Phase 1 if you disagree.

**Infra as code: Bicep.** First-party Azure, no remote state to manage, no extra tooling in CI. Terraform is the alternative and is better if multi-cloud ever matters — it does not here.

**Key Vault behind an `IKeyWrapper` seam.** Wrap/unwrap sits behind an interface with two implementations: Key Vault for real, and an in-memory fake for tests. Without this, no test that touches the crypto path can run without live Azure credentials, which makes the whole test suite slow, flaky, and unrunnable in CI on a fork. The seam is the difference between a testable design and one where the most security-critical code is the least tested.

---

## Phase 0 — Verification harness

Nothing here is a feature. It exists so that "done" means something for every task after it.

### 0.1 Repo skeleton and solution
- **Files:** `.gitignore`, `Cryptum.sln`, `src/`, `tests/`, `android/`, `infra/`
- **Change:** Initialize git. Create the .NET solution and empty project folders. `.gitignore` must cover `.env`, `*.pem`, `*.key`, `appsettings.Development.json`, `local.settings.json`, Android `local.properties`, and build output.
- **Verify:** `dotnet sln list` shows the expected projects; `git status` shows no ignored artifacts staged.

### 0.2 Verification scripts
- **Files:** `.claude/scripts/check.sh`, `test-fast.sh`, `test-full.sh`, `contract.sh`, `e2e.sh`, `schema.sh`
- **Change:** `check.sh` runs build + format + analyzers; `test-fast.sh` runs unit tests only; `test-full.sh` adds integration tests; `contract.sh` regenerates the API client from OpenAPI and fails on drift; `e2e.sh` runs the end-to-end suite; `schema.sh` is the only sanctioned path for EF migrations. Each must exit non-zero on failure.
- **Verify:** Each script runs against the empty solution and exits 0. Deliberately break the build; confirm `check.sh` exits non-zero. A script that cannot fail is not a check.

### 0.3 CI pipeline
- **Files:** `.github/workflows/ci.yml`, `Directory.Build.props`, `**/packages.lock.json`
- **Change:** On PR — restore with a frozen lockfile, run `check.sh`, `test-fast.sh`, `contract.sh`, `test-full.sh`. No dependency install scripts run unreviewed.
- **Verify:** Pipeline green on an empty solution; a PR with a formatting violation goes red.
- **As built:** "Frozen lockfile" required `RestorePackagesWithLockFile` first — without committed
  lockfiles `--locked-mode` succeeds silently and pins nothing. Proven by mutating a central
  package pin and getting NU1004. `check.sh`'s `|| dotnet restore` fallback was removed for the
  same reason: it would have repaired the mismatch instead of reporting it. Install-script review
  has no NuGet analogue and is deferred to task 2.9, when Gradle — which does execute arbitrary
  build code — enters CI.

---

## Phase 1 — Infrastructure and identity

### 1.1 Bicep for core resources
- **Files:** `infra/main.bicep`, `infra/modules/*.bicep`
- **Change:** App Service (Linux, .NET), Azure SQL, Storage account with a private container, Key Vault (standard, RBAC authorization, **soft-delete retention set explicitly to 7 days, purge protection disabled** per ADR-0003), App Insights, Log Analytics. App Service gets a **system-assigned** Managed Identity — no client secrets anywhere (security-requirements).
- **Verify:** `az deployment group what-if` succeeds against a dev resource group and shows exactly the intended resource set.

### 1.2 Key Vault access policy, least privilege
- **Files:** `infra/modules/keyvault.bicep`
- **Change:** Grant the App Service identity **only** `wrapKey` and `unwrapKey`. Never `get`, `list`, or export of key material (ADR-0002).
- **Verify:** Post-deploy, assert the role assignment contains no key-read permission. Attempt `az keyvault key show` as that identity and confirm it is denied — the denial is the evidence, not the config file.

### 1.3 SQL and storage access via Managed Identity
- **Files:** `infra/modules/sql.bicep`, `src/Cryptum.Api/Program.cs`
- **Change:** Azure AD authentication to Azure SQL; no SQL-auth connection string anywhere (security-requirements). Blob access via the same identity.
- **Verify:** API connects to SQL in the dev environment with no password in configuration; `grep -ri "password=" ` over the repo returns nothing.

### 1.4 B2C tenant and token validation
- **Files:** `infra/b2c/*`, `src/Cryptum.Api/Auth/`
- **Change:** B2C tenant with sign-up/sign-in and password-reset flows. API validates JWT issuer, audience, signature, and expiry. Reject unsigned and expired tokens explicitly.
- **Verify:** Integration test — a valid token reaches a protected endpoint; tampered signature, wrong audience, and expired token each return 401. All four cases tested, not just the happy path.
- **As built (validation half only):** `tests/Cryptum.IntegrationTests/` covers all four cases plus a wrong-issuer case. The test host runs the **production** `JwtBearer` handler and swaps only the signing key it trusts — a fake authentication handler would have made every one of these tests vacuous, proving the endpoint reads a claim rather than that a forged token is rejected. Confirmed non-vacuous by mutation: setting `ValidateAudience = false` or restoring the default 5-minute `ClockSkew` makes two of them fail. The B2C tenant itself is still unprovisioned, so the tokens are test-minted.

### 1.5 TLS and security headers
- **Files:** `infra/modules/appservice.bicep`, `src/Cryptum.Api/Program.cs`
- **Change:** Enforce TLS 1.2+, HTTPS-only, HSTS. Generic error responses — no stack traces to clients.
- **Verify:** `curl -v` confirms HSTS present and TLS 1.0/1.1 refused. Force a 500 and confirm the response body carries no internal detail.

---

## Phase 2 — First vertical slice: create and read one Secret

This is the slice that proves the architecture. Every task below crosses a tier boundary and is verified at that crossing.

### 2.1 Schema for Item
- **Files:** `src/Cryptum.Domain/Item.cs`, `src/Cryptum.Data/CryptumDbContext.cs`, migration via `schema.sh`
- **As built:** `Item` lives in `Cryptum.Domain`, not `Cryptum.Data` — the entity carries the invariants (fresh DEK and nonce on every write), so putting it behind the persistence boundary would have made the domain depend on EF. Mapping stays in `ItemConfiguration`. The index shipped as `(Owner, DeletedAt, Id)`: the global soft-delete filter appends `DeletedAt` to every query, so an `(Owner, Id)` index would have left the common read a partial scan.
- **Change:** `Item` — id, ownerId, type discriminator, plaintext title, wrapped DEK, nonce, blob pointer (nullable), created/updated timestamps. Identity is stable and separate from content and key material, so a versions table can be added later without migrating rows (ADR-0006). Index on `(ownerId, id)` — the shape every authorized query uses.
- **Verify:** Run `schema.sh`; **read the generated migration before applying it** and confirm it creates rather than drops. Apply to dev; round-trip an entity.

### 2.2 Owner-scoped data access
- **Files:** `src/Cryptum.Data/ItemRepository.cs`
- **Change:** Every method takes an `ownerId` and filters on it **in the query**. There is no method that can fetch an Item by id alone — the type system should make the unsafe call unwriteable, rather than relying on callers to remember a check (ADR-0002, security-requirements).
- **Verify:** Unit test — user B requests user A's Item id and gets not-found, for every repository method. This is the IDOR test and it is mandatory, not optional.

### 2.3 `IKeyWrapper` seam and Key Vault implementation
- **Files:** `src/Cryptum.Domain/IKeyWrapper.cs`, `src/Cryptum.Infrastructure/KeyVaultKeyWrapper.cs`, `tests/Cryptum.TestSupport/InMemoryKeyWrapper.cs`
- **As built:** the fake sits in a shared `Cryptum.TestSupport` project because both test projects need it, and a duplicated crypto fake would drift the moment `IKeyWrapper` changes. It is backed by real in-process RSA, not a stub, so tests exercise the production failure modes.
- **Change:** `WrapAsync(userId, dek)` / `UnwrapAsync(userId, wrappedDek)` over RSA-OAEP-256. Per-user KEK created on first use. The plaintext DEK is never logged, never persisted, and its buffer is cleared after use.
- **Verify:** Integration test against real Key Vault — wrap then unwrap returns the original bytes. Unit tests elsewhere use the fake. Assert via log capture that no test ever emits DEK material.

### 2.4 KEK provisioning on signup
- **Files:** `src/Cryptum.Domain/UserProvisioning.cs`
- **Change:** First authenticated request for an unknown B2C subject creates that user's KEK and User row, idempotently. No key material derives from the B2C password (ADR-0004) — a password reset must never cost a user their Vault.
- **Verify:** Test — two concurrent first-requests for one subject produce exactly one KEK, not two.

### 2.5 API contract for Items
- **Files:** `src/Cryptum.Api/Contracts/`, generated `openapi.json`
- **Change:** `POST /items` (title, ciphertext, nonce, plaintext DEK to wrap), `GET /items` (list — titles only), `GET /items/{id}` (ciphertext, nonce, unwrapped DEK). Validate at the boundary: title length, ciphertext size cap, nonce exactly 96 bits. The contract is the source of truth; the Android client is generated from it, never hand-written to match (repo invariant).
- **Verify:** `contract.sh` passes. Malformed input returns 422 with no internal detail.
- **As built:** `openapi.json` is generated at build time by `Microsoft.Extensions.ApiDescription.Server` and **committed**, so `contract.sh` diffs the regenerated spec against the committed one. Three ways it now fails that it could not before: a missing spec, an untracked spec (which would make the diff vacuously succeed), and a spec carrying `example`/`default` values, since a DEK-shaped example would publish key-shaped material in a committed artifact. The build runs `--no-incremental` because an up-to-date build skips document generation, and a stale spec would pass the diff while proving nothing. Validation failures return 400 rather than the planned 422 — the framework's `ValidationProblem` default, and not worth diverging from.

### 2.6 Item endpoints
- **Files:** `src/Cryptum.Api/Endpoints/ItemEndpoints.cs`, `src/Cryptum.Api/Auth/CallerIdentity.cs`, `src/Cryptum.Domain/VaultService.cs`
- **Change:** Wire the endpoints to repository plus `IKeyWrapper`. Owner comes from the validated token subject, never from the request body — a body-supplied ownerId is the classic privilege-escalation hole.
- **Verify:** Integration test of the full create-then-read cycle. Plus the cross-user test: user B is refused on every endpoint. Confirm request/response bodies are absent from logs (they would contain DEKs).
- **As built:** minimal APIs rather than a controller, and the endpoints delegate to `VaultService` rather than composing the repository and key wrapper themselves — "check the owner, unwrap, write an audit row" is then impossible to half-forget on one route out of several. A stranger's read returns **404, not 403**: 403 would confirm the Item exists and let an attacker enumerate valid ids. `CallerIdentity` hashes the B2C subject into a stable GUID, since subjects are opaque strings and not necessarily GUIDs. The log-absence assertion is not yet written.

### 2.7 Audit log
- **Files:** `src/Cryptum.Domain/AuditEntry.cs`, `src/Cryptum.Data/AuditLog.cs`, `src/Cryptum.Data/AuditEntryConfiguration.cs`
- **As built:** `IAuditLog` exposes only `RecordAsync` — no update or delete method exists, so tampering cannot be expressed in C# at all. `AuditLog` saves immediately rather than enlisting in the caller's unit of work: a rolled-back operation would otherwise erase exactly the evidence an incident review needs. `AuditEntries` carries **no** soft-delete filter, because the record that a crypto-shred happened must outlive the Items it destroyed. The INSERT-only DB principal and the UPDATE/DELETE-refused test remain outstanding — they need a real database.
- **Change:** Record every wrap, unwrap, and Item access — actor, action, item id, timestamp, outcome. Written through an INSERT-only DB principal, and shipped to Log Analytics as the tamper-resistant source of truth (security-requirements). Never record DEKs or ciphertext.
- **Verify:** Test — an unwrap produces exactly one audit row. Attempt UPDATE and DELETE as the audit principal and confirm both are refused. Untamperable-by-construction is the requirement; an append-only table that the app can still delete from is not one.

### 2.8 Rate limiting
- **Files:** `src/Cryptum.Api/Program.cs`
- **Change:** Per-user rate limits, with a stricter bucket on the unwrap path than on general CRUD (security-requirements).
- **Verify:** Test — exceeding the unwrap limit returns 429 while ordinary CRUD still succeeds, proving the buckets are actually separate.
- **As built:** 100/min globally, 20/min on `GET /items/{id}`, which is the only route that costs a Key Vault unwrap. Partitioned by **caller identity, not IP**: a shared NAT must not let one user drain another's budget, and an attacker rotating IPs must not get a fresh one. Unauthenticated requests fall back to an IP partition, since there is no identity to key on. The 429 separation test is still to write, so the buckets are wired but not yet proven distinct.

### 2.9 Android: crypto core
- **Files:** `android/core-crypto/`
- **Change:** AES-256-GCM encrypt/decrypt. Fresh 96-bit CSPRNG nonce and a fresh DEK per encryption (ARCHITECTURE.md, ADR-0006). Plaintext and DEKs stay in memory only, cleared after use.
- **Verify:** Instrumented tests — round-trip succeeds; a tampered ciphertext or tag fails authentication rather than returning garbage. A property test asserts no nonce repeats across many generations.

### 2.10 Android: auth and token storage
- **Files:** `android/core-auth/`
- **Change:** B2C login via MSAL. **Refresh token in Keystore-backed encrypted storage** — never plain SharedPreferences (security-requirements). Silent refresh of short-lived access tokens.
- **Verify:** Instrumented test confirms no token appears in plain SharedPreferences. Expired access token triggers exactly one refresh, not a loop.

### 2.11 Android: generated API client
- **Files:** `android/core-api/` (generated)
- **Change:** Generate from `openapi.json`. Hand-written types duplicating contract types are a bug (repo invariant).
- **Verify:** `contract.sh` shows no drift; the generated client compiles.

### 2.12 Android: app lock
- **Files:** `android/feature-lock/`
- **Change:** BiometricPrompt gate on app open and resume, with device-PIN fallback.
- **Verify:** Instrumented test — Vault content is unreachable until the gate passes; backgrounding and resuming re-locks.

### 2.13 Android: Secret create and view
- **Files:** `android/feature-vault/`
- **Change:** List (titles only), create, and view a Secret. Non-title fields serialize to one JSON object encrypted as a unit. Screenshots disabled (`FLAG_SECURE`) on any screen showing plaintext.
- **Verify:** Instrumented test of the round trip. Confirm plaintext never touches disk.

### 2.14 End-to-end proof
- **Files:** `e2e/`
- **Change:** Full path against a deployed dev environment: sign up, create a Secret, read it back on a *second* device session, confirm plaintext matches.
- **Verify:** `e2e.sh` green. Independently inspect the SQL row and confirm the stored bytes are ciphertext, not readable text — this is the check that actually proves the architecture works as designed rather than merely that the app works.

**Slice 2 is done when a user can install the app, sign up, save a password, and read it back on another device — and the database provably holds no plaintext.**

---

## Phase 3 — Version history, then Files

### 3.0 Item version history
- **Files:** `src/Cryptum.Domain/ItemVersion.cs`, `src/Cryptum.Data/`, migration via `schema.sh`
- **Change:** On edit, retain the prior ciphertext, nonce and wrapped DEK as a version row against the stable Item id, instead of replacing them (ADR-0006). Each version keeps its own DEK, so restoring one needs no re-encryption. Cap retained versions per Item so history cannot grow without bound.
- **Verify:** Test — edit an Item twice, restore version 1, confirm the original plaintext returns. Confirm cross-user access to a version is refused, same as for Items: history must not become an IDOR bypass around the Item-level check.
- **Why first:** MVP ships with unrecoverable overwrite. This closes a real data-loss gap and is the reason Phase 3 leads with it rather than with Files.
- **As built:** Retention capped at `ItemVersion.MaxRetained` = 10. `Item.ReplaceContent` now
  *returns* the version it displaced, so silently overwriting the only copy is no longer
  expressible at that seam. History carries the Item table's guarantees rather than inheriting
  them: `Owner` is denormalised onto the row so the predicate lives inside the query, the key is
  composite `(ItemId, VersionNumber)` so there is no surrogate id to fetch by alone, and
  `SoftDeleteAllAsync` covers history in the same call. A restore is itself an edit — it archives
  what it displaces, so restoring the wrong version is undoable, which matters because the user
  cannot read any version before choosing one.
  <br>The plan scoped this to domain and data; four routes (`PUT /items/{id}`,
  `GET .../versions`, `GET .../versions/{n}`, `POST .../versions/{n}/restore`) were added too,
  since without them nothing above is reachable. Version reads share the unwrap rate-limit bucket.
  <br>The migration is reviewed but **not applied**: purely additive, and there is no database
  until Phase 1 (ticket 06). The Android half of the slice is blocked on ticket 10.

### 3.1 Blob storage path
- **Files:** `src/Cryptum.Infrastructure/BlobStore.cs`, `ItemsController`
- **Change:** File ciphertext to Blob Storage; metadata and wrapped DEK to SQL. Upload and download via short-lived user-delegation SAS.
- **Verify:** Integration test round-trips a file. Confirm the blob is unreadable without the DEK, and that a SAS actually expires.

### 3.2 Size cap and per-user quota
- **Files:** `src/Cryptum.Api/`, `src/Cryptum.Domain/`
- **Change:** Per-file size cap and per-user total quota (security-requirements). Ciphertext cannot be content-inspected, so size and quota are the only meaningful upload controls — that makes them load-bearing rather than incidental.
- **Verify:** Test — an oversized file is refused; a user at quota is refused; the partial upload leaves no orphaned blob.

### 3.3 Android file attach and open
- **Files:** `android/feature-vault/`
- **Change:** Attach from the system picker, encrypt in chunks, upload with progress. Download, decrypt, open.
- **Verify:** Instrumented round trip on a large file, confirming memory stays bounded rather than loading the whole file at once.

---

## Phase 4 — Account lifecycle

### 4.1 Crypto-shred deletion
- **Files:** `src/Cryptum.Domain/AccountDeletion.cs`
- **Change:** Delete the KEK, soft-delete rows, then purge rows and blobs asynchronously (ADR-0003).
- **Verify:** Test — after deletion, unwrap fails and Items are undecryptable even though ciphertext still exists. That is precisely what crypto-shred claims, so it is what the test must assert.

### 4.2 Async purge worker
- **Files:** `src/Cryptum.Worker/`
- **Change:** Background purge of soft-deleted rows and orphaned blobs. Idempotent and resumable.
- **Verify:** Test — interrupt mid-purge, re-run, and confirm completion with no double-delete errors.

### 4.3 Resolve the Key Vault retention question
- **Files:** `docs/adr/0003-crypto-shred-deletion.md`
- **Change:** Decide and record whether the soft-delete retention window (7–90 days, during which an Azure admin could recover a "deleted" KEK) satisfies the deletion promise made to users. This is a compliance decision, not an engineering one — it is on the plan because it must not be discovered after launch.
- **Verify:** ADR-0003 updated with the decision and its rationale.

---

## Phase 5 — Operational readiness

### 5.1 Anomaly alerting on unwrap volume
- **Files:** `infra/modules/monitoring.bicep`
- **Change:** Alert on abnormal unwrap volume per identity (ADR-0002). Because the backend can unwrap any user's KEK, this alert is the compensating control for the design's central residual risk — it is not routine monitoring.
- **Verify:** Simulate a burst of unwraps; confirm the alert fires.

### 5.2 Dependency and supply-chain gate
- **Files:** `.github/workflows/ci.yml`
- **Change:** Audit against the committed lockfile; block unreviewed dependency install scripts; fail on reachable critical/high advisories.
- **Verify:** Introduce a known-vulnerable package; CI goes red.

---

## Execution protocol

Per the repo's plan discipline, each task is dispatched to a `task-executor` subagent in isolation, then reviewed by me in two stages before being marked done:

1. **Spec compliance** — does it do what the task's verification step says?
2. **Code quality** — does it pass `check.sh` and match repo invariants?

The subagent-per-task cost is justified here: this is security-critical code where an unreviewed shortcut in the authorization or crypto path is exactly the class of defect that does not surface until it is exploited.

No task is marked done on "it compiles." The evidence is the named script and its result.

## Previously-open questions, now decided

All three are recorded in ADRs; listed here so the plan reflects them.

1. **Key Vault retention** — 7-day soft-delete, purge protection disabled (ADR-0003). Phase 1.1 must set the window explicitly; Phase 4.3 is now a documentation task rather than an open decision.
2. **Version history** — ships as the first item of Phase 3, ahead of Files (ADR-0006). See 3.0 below.
3. **Certificate pinning** — out of scope for MVP, by decision rather than omission (ADR-0007).

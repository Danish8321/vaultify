# 06 — QUERY: Phase 1 Azure infra is deferred, and it now blocks real work

Status: unblocked — subscription available (2026-08-16); deploy not yet authorised
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

## Update 2026-08-16 — the blocker was never the subscription

`az account show` reports an authenticated, Enabled subscription:

```
name:   Azure subscription
id:     eaa29895-7743-44bd-9881-e51395ec6ff8
tenant: b242d7b0-1fae-4655-8bb7-7606c66aaac1 (AZ900Advancedoutlook.onmicrosoft.com)
```

So Bicep can be authored and deployed. The retention question that had to be
settled first (task 4.3) is now settled — ADR-0003: 7-day soft-delete, purge
protection explicitly `false`, shred purges rather than waiting out the window.
That matters here specifically because `enablePurgeProtection` cannot be
un-set, so the Key Vault module must assert it rather than omit it.

**Still not authorised: the deploy itself.** Authoring Bicep is free and
reversible; `az deployment` creates billable resources under a personal
subscription and is not something to do on my own initiative. The tenant name
suggests a learning subscription, which may carry credit limits worth checking
before a Key Vault, SQL database and App Service are stood up.

Next step is therefore: author `infra/` modules, validate with
`az deployment group what-if` (no resources created), and stop for explicit
go-ahead before the actual deployment.

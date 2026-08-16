# 14 — Task 2.10's MSAL half is deferred until B2C exists

Status: deferred (decision taken 2026-08-16)
Severity: medium
Source: task 2.10

## What was built

The Keystore-backed token store — the security-critical half, and the half whose
verification clause ("no token in plain SharedPreferences") needs no identity
provider. `:core-auth`, 9 instrumented tests, green on API 37.

## What was not

B2C login via MSAL, and silent refresh of short-lived access tokens. Two
reasons, both blocking:

1. No B2C tenant exists. Phase 1 is unbuilt, so there is no authority, no
   client id and no redirect URI to configure against.
2. MSAL is a heavyweight new dependency, and adding it to satisfy a flow that
   cannot be exercised end to end would mean committing unrunnable code.

The plan's second verify clause for 2.10 — *"expired access token triggers
exactly one refresh, not a loop"* — is therefore still unproven. It is the more
interesting of the two clauses: a refresh loop is a self-inflicted denial of
service against your own identity provider, and it typically only appears under
a specific failure (refresh succeeds, the new token is also rejected).

## What the store deliberately does not do

`setUserAuthenticationRequired(false)`. Silent refresh has to work while the app
is backgrounded or locked, and a key gated on user presence would make
background refresh fail in a way indistinguishable from a server fault. The app
lock (task 2.12) gates the UI instead.

This is the module's one security-for-function trade, so it is asserted by a
test rather than left to a comment — reversing it by accident would break
background refresh subtly.

## Also worth knowing

StrongBox is requested and falls back when unavailable. The emulator has no
StrongBox, so **the fallback path is what the tests actually exercised** — the
StrongBox path has never run. On a device that has it, key generation takes the
first branch, which is untested. Worth one run on real hardware before release.

## Done when

B2C exists; MSAL is ratified as a dependency; login works end to end; and an
expired access token is shown to trigger exactly one refresh.

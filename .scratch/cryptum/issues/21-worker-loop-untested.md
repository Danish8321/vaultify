# 21 — Worker's timer loop has no test

**Status:** resolved
**Severity:** medium
**Found:** 2026-08-18, plan task 4.2

## What

`PurgeService` and `PurgeStore` are covered by 5 integration tests against real
SQLite. `Cryptum.Worker/Worker.cs` — the `PeriodicTimer` loop that drives them —
is covered by nothing.

Untested behaviour in that file:

- the loop keeps running after a `DbException` (the whole point of the narrow
  catch), and logs at error rather than swallowing
- `stoppingToken` cancellation exits the loop instead of being logged as a fault
- `Grace` is subtracted from `clock.GetUtcNow()` before it reaches the service
- one scope is created per tick, not one for the process lifetime (a leaked
  `DbContext` here would accumulate every tracked entity the worker ever saw)

## Why not fixed now

Testing a `PeriodicTimer` loop deterministically needs a fake `TimeProvider`.
The obvious one is `Microsoft.Extensions.TimeProvider.Testing`, which is a new
dependency — CLAUDE.md says do not add one without asking. `Worker` already
takes `TimeProvider` by injection specifically so this test is possible without
changing production code, so the seam is in place and only the package is
missing.

## Resolved

Package approved and added (`Microsoft.Extensions.TimeProvider.Testing` 10.9.0,
test-only). `WorkerTests` covers all four, plus a fifth that the mutation run
forced into existence.

Every test mutation-checked against the production file:

| Mutation | Result |
|---|---|
| drop `TimeoutException` from the transient catch | fails |
| pass `clock.GetUtcNow()` without subtracting `Grace` | fails |
| hoist the scope out of the loop | fails |
| log the shutdown path at error | fails |

The fourth mutation initially **survived**. The reason is worth keeping: on a
normal stop, `WaitForNextTickAsync` throws at the `while` condition, which is
outside the `try` — so the loop's `OperationCanceledException` handler never
runs at all and the original test was asserting over a path it never reached.
Only stopping *during* a purge exercises it. That is now
`Shutdown_during_a_purge_is_not_logged_as_a_fault`, and it kills the mutation.

Same lesson as tickets 07, 17 and 22: the test looked right and proved nothing
until something was broken on purpose to see it complain.

## Related

- Ticket 06 — deployment of the worker is blocked on Azure authorisation, so
  this code is not running anywhere yet.

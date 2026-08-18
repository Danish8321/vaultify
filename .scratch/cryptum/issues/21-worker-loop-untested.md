# 21 — Worker's timer loop has no test

**Status:** open
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

## Next step

Ask before adding `Microsoft.Extensions.TimeProvider.Testing`, then write the
four tests above. The hand-rolled alternative — a `TimeProvider` subclass with a
controllable timer — is roughly 60 lines of test infrastructure that exists to
avoid one first-party Microsoft package, which is the worse trade.

## Related

- Ticket 06 — deployment of the worker is blocked on Azure authorisation, so
  this code is not running anywhere yet.

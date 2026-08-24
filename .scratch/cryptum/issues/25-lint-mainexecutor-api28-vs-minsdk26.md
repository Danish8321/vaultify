# 25 — `getMainExecutor()` requires API 28, minSdk is 26

Status: closed
Severity: medium
Source: design-sync-android plan, Task 12 (full `./gradlew build`)

## Problem

`android:app:lintDebug` fails:

```
MainActivity.kt:70: Error: Call requires API level 28 (current min is 26): android.content.ContextWrapper#getMainExecutor [NewApi]
    onUnlockRequested = { promptToUnlock(this, lock, mainExecutor) },
```

Pre-existing — not introduced by the design-sync-android plan's UI work (`git diff` on
`MainActivity.kt` shows no changes from that plan). `./gradlew test` passes; only the lint task in
a full `build` catches this, so it was never surfaced by `test-fast.sh` (which only runs `test`,
not `build`/`lint`).

## Why it matters

`minSdk 26` was deliberately ratified in ticket 10 for Keystore/StrongBox guarantees. `mainExecutor`
(the `Context.getMainExecutor()` convenience property) needs API 28. Two ways to close the gap:

1. Raise `minSdk` to 28 — narrows the supported device floor; needs the same security-tradeoff
   review ticket 10 gave `minSdk 26`, not a decision to make incidentally while fixing a lint error.
2. Keep `minSdk 26` and construct the executor manually for API 26/27
   (`ContextCompat.getMainExecutor(context)` from `androidx.core` is API-26-safe and is the
   standard fix for exactly this gap — likely the smaller change).

## Done when

`./gradlew build` (including `:app:lintDebug`) passes with `minSdk` unchanged at 26, or an explicit
decision is recorded to raise it.

## Resolution

Took option 2: `MainActivity.kt`'s `mainExecutor` (Activity property, API 28) replaced with
`ContextCompat.getMainExecutor(this)` (androidx.core, API-26-safe) — `minSdk` unchanged at 26.

Fixing this surfaced a second, unrelated pre-existing lint error in the same composable —
`remember(lock) { lock.onLockStateChanged { locked = it } }` (`RememberReturnType`: a `remember`
returning `Unit` is always wrong, since composition failure/replay can silently drop the
registration). Fixed in the same pass since it blocked the same done-criterion
(`:app:lintDebug`/`build` passing) and touched the same three-line block: replaced with
`SideEffect { lock.onLockStateChanged { locked = it } }`. Re-registering the listener every
recomposition is harmless — `AppLock.onLockStateChanged` just overwrites a single-slot field, no
accumulation.

Verified: `:app:lintDebug` clean, and `./gradlew test build` (full, all modules) clean.

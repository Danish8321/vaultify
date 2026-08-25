# 29 — Onboarding flow has no persistence

**Status:** closed
**Severity:** medium
**Found:** 2026-08-24, during design-sync onboarding build (fork a9e3b7ffc54de91b5)
**Closed:** 2026-08-25

## What

`MainActivity.kt` gates `OnboardingScreen` before `LockGate` with an
in-memory `onboarded` flag. It replays on every process start — there's
no `User.HasOnboarded` (or local prefs equivalent) to make it a one-time
flow. Explicitly scoped OUT of the Files-backend work ("Files backend
only" decision, 2026-08-24) — raising as its own ticket rather than
building it unprompted.

## Fix shape

Needs a decision: server-tracked (`User` row gains a flag, ADR-0004 says
User carries no credentials/keys but a boolean completion flag isn't
that) vs. purely local (DataStore/SharedPreferences, reset on reinstall
only). Local is simpler and doesn't need a backend change — likely the
right default unless there's a cross-device requirement.

## Resolution

Went local: `OnboardingPrefs` (new,
`android/app/src/main/kotlin/com/cryptum/app/OnboardingPrefs.kt`), plain
`SharedPreferences` — no new dependency, no server round trip, no schema
change. `MainActivity` reads `OnboardingPrefs.isOnboarded(this)` for the
initial state and calls `OnboardingPrefs.setOnboarded(this)` in
`onFinished`. Resets on reinstall only, which is the accepted tradeoff —
onboarding is device UI state, not an account fact, so ADR-0004 (User
carries no non-essential state) never came into play.

No test added: too thin a wrapper to be worth an instrumented test given
no emulator is available to run one anyway (ticket 30). Verified via
`:app:compileDebugKotlin` and `:app:compileDebugAndroidTestKotlin`, both
clean.

## Related

- Ticket 30 — same on-device verification gap
- `android/app/src/main/kotlin/com/cryptum/app/MainActivity.kt`
- `android/feature-lock/src/main/kotlin/com/cryptum/lock/Onboarding.kt`

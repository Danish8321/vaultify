# 29 — Onboarding flow has no persistence

**Status:** open
**Severity:** medium
**Found:** 2026-08-24, during design-sync onboarding build (fork a9e3b7ffc54de91b5)

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

## Related

- `android/app/src/main/kotlin/com/cryptum/app/MainActivity.kt`
- `android/feature-lock/src/main/kotlin/com/cryptum/lock/Onboarding.kt`

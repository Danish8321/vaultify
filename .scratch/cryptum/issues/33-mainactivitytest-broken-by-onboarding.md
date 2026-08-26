# 33 — MainActivityTest.the_app_opens_sealed fails on real device

**Status:** closed
**Severity:** low
**Found:** 2026-08-26, running `connectedAndroidTest` on a real connected device (ticket 30)
**Closed:** 2026-08-26

## What

`MainActivityTest.the_app_opens_sealed` (`android/app/src/androidTest/kotlin/com/cryptum/app/MainActivityTest.kt`)
asserts `TAG_SEAL` exists on first compose. Ticket 29 added persisted
onboarding (`OnboardingPrefs`) — on a fresh install/test run `onboarded`
is false, so `MainActivity` shows `OnboardingScreen` first, never
`LockGate`. The test was never updated for that and fails for real,
not a flake:

```
java.lang.AssertionError: Failed: assertExists.
Reason: Expected exactly '1' node but could not find any node that satisfies: (TestTag = 'seal')
```

## Fix shape

Either mark onboarding complete before asserting (seed
`OnboardingPrefs.setOnboarded` via instrumentation context before
`createAndroidComposeRule` launches the Activity), or drive through the
onboarding screen's finish action first. Seeding prefs is simpler and
matches what a real "already onboarded" user's state looks like.

## Resolution

Seeded `OnboardingPrefs.setOnboarded` in an `init` block on the test
class, ahead of the `@get:Rule` compose rule's field declaration — the
rule launches the Activity during rule application, before any
`@Before` runs, so `@Before` was too late; the `init` block runs at
construction, ahead of that. Verified: both `app` instrumented tests
green on connected device SM-E625F (`connectedDebugAndroidTest`
BUILD SUCCESSFUL, 2/2). `check.sh` clean.

## Related

- Ticket 29 — onboarding persistence
- `android/app/src/main/kotlin/com/cryptum/app/MainActivity.kt`

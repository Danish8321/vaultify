# 26 — ActivityScreen had no navigation entry point

**Status:** closed
**Severity:** low
**Found:** noted in ticket 23's close-out (follow-up item #4)
**Closed:** 2026-08-24

## What

`ActivityScreen` (android/feature-vault/src/main/kotlin/com/cryptum/vault/ActivityScreen.kt)
rendered the activity log but had no caller anywhere in the app — no way for
a user to reach it.

## Resolution

Checked `core-api` for an activity-log endpoint: none exists. Same situation
ticket 23 was in before its fix — no backend to wire real data to. Scoped
this ticket to navigation only, same principle as "don't fabricate an API
call if no endpoint exists":

- `SettingsScreen` gained a "View activity" row (`TAG_VIEW_ACTIVITY_ROW`).
- `VaultScreen`'s `Screen` sealed interface gained `Screen.Activity`, wired
  `Settings -> Activity -> ActivityScreen(entries = emptyList())`. The empty
  list is honest — a real empty activity log, not fake data.
- `ActivityScreen` gained a root `testTag` (`TAG_ACTIVITY_SCREEN`) so the nav
  path is provable.
- `VaultScreenTest.settings_navigates_to_the_activity_screen`: drives
  Settings -> View activity -> asserts the real `ActivityScreen` composed.

Verified: `:feature-vault:compileDebugKotlin`,
`:feature-vault:compileDebugAndroidTestKotlin`, `:feature-vault:test`,
`:feature-lock:test`, `:app:compileDebugKotlin` all pass clean.

**Not yet verified:** the new instrumented test
(`settings_navigates_to_the_activity_screen`) has not run on-device —
the connected physical device dropped mid-session (`adb devices` empty,
USB/authorization issue) before `:feature-vault:connectedAndroidTest` could
run against it. Compiles clean and follows the exact same pattern as the
already-device-verified `deleting_the_account_calls_the_repository_and_notifies_the_caller`
test, so risk is low, but this is not the same as an on-device pass. Run
`:feature-vault:connectedAndroidTest` once the device reconnects.

## Related

- Ticket 23 — same "no fabricated data" principle, same nav pattern
  (Settings -> screen)
- Not yet designed: a real activity-log data source (backend endpoint +
  `VaultRepository` method), needed before `entries` can be anything but
  empty.

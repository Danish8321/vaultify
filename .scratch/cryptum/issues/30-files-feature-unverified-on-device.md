# 30 — Files feature unverified on-device / instrumented

**Status:** open
**Severity:** medium
**Found:** 2026-08-24, during Files-feature Android wiring

## What

The Files feature (Onboarding, `FilesScreen`, `FileRepository`, hold-to-open
gesture, SAF pickers) has only compiled and unit-tested clean —
`:feature-vault:compileDebugKotlin`, `:app:compileDebugKotlin`,
`:feature-vault:testDebugUnitTest`, `:feature-vault:compileDebugAndroidTestKotlin`.
No emulator/device was available in either build session, so
`:feature-vault:connectedAndroidTest` (instrumented tests, including the
new `SealedFakeFileRepository`-backed screen tests and the
`capture_the_onboarding_screens`/`capture_the_files_tab` screenshot tests)
has not actually run. Same category of gap as tickets 10/20/26's
device-verification notes.

## Fix shape

Run `:feature-vault:connectedAndroidTest` and `:feature-lock:connectedAndroidTest`
once a device/emulator is reachable; capture and visually check the
screenshot outputs against the prototype.

## Related

- Ticket 10 (Gradle/Android environment), 20 (instrumentation
  intermittently crashed), 26 (same "not yet verified" pattern)

# 20 — :app instrumentation crashed twice, unexplained

Status: open
Severity: medium
Source: task 2.13 follow-up, building the :app module

## Problem

`:app:connectedAndroidTest` failed twice with:

    Test run failed to complete. Instrumentation run failed due to Process crashed.

Once while three modules' instrumented tests ran in one Gradle invocation
against a single emulator, and once immediately afterwards on its own. Three
subsequent runs with `--rerun-tasks` were green, as were the runs before it.

No stack trace was recovered: the crash is a process death rather than a test
assertion, and by the time logcat was read it had already rolled past.

## Why it is not dismissed as emulator noise

It might well be. But "the CI machine will be fine" is the assumption this repo
has been wrong about four times already, and an intermittent crash on the one
module that hosts a real Activity is worth more than a shrug. A test suite that
fails one run in five is a suite people learn to re-run instead of read.

## Resolution

Next time `:app` tests are run, clear logcat first and capture it on failure.
If it recurs, get the stack trace before changing anything — the temptation
will be to add a retry, which converts a real crash into an invisible one.

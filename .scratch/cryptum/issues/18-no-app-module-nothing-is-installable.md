# 18 — there is no :app module, so nothing is installable

Status: resolved 2026-08-17 in commit bb7693a
Severity: high
Source: task 2.13

## Problem

The Android build has six library modules and no application module. Every
screen built so far — the lock gate, the Vault list, create and view — exists
only as a library that instrumented tests compose directly.

Slice 2's stated done-condition is "a user can install the app, sign up, save a
password, and read it back on another device". Right now there is nothing to
install. The tests pass because a test harness can host a composable without an
Activity; a user cannot.

## Why it was not caught earlier

Every task in slice 2 names a library module in its **Files** line, and each
task's verification is satisfiable inside that library. The plan never has a
task whose file is `android/app/`, so no individual task is incomplete — the gap
is between the tasks, which is exactly where a task list stops being able to see.

## What it blocks

Task 2.14 outright: an end-to-end proof needs an installed app on two device
sessions. Also the honest version of the FLAG_SECURE claim — `SecureScreen`
reaches for the window through `LocalContext as? ComponentActivity`, and under a
Compose test rule that cast may not be the Activity a real app would supply.

## Resolution

Add `android/app`: one Activity that hosts `LockGate` wrapping `VaultScreen`,
wired to `ApiVaultRepository` and the Keystore token store. Then confirm
FLAG_SECURE on a real Activity rather than a test host, since that is the
claim currently resting on the weakest evidence.

## Resolved

`android/app` exists: one FragmentActivity composing `LockGate` around
`VaultScreen`, backup and device-transfer excluded at the root, and FLAG_SECURE
set on the window rather than per screen — which also let `SecureScreen` and its
LocalContext-to-Activity cast be deleted.

The FLAG_SECURE claim now rests on an assertion against the real window, proven
non-vacuous by deleting the setFlags call and watching the test fail. That was
the weakest evidence in the project and is now among the strongest.

Still true: the app throws on unlock, deliberately, because sign-in (14) and a
deployed environment (06) are both missing. 2.14 remains blocked on those, not
on this.

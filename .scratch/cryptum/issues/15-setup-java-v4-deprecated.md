# 15 — CI warns that actions/setup-java@v4 is deprecated

Status: closed
Severity: low
Source: CI run for commit 10c61f9

## Problem

The `verify` job emits:

    setup-java v4 is deprecated and will no longer receive updates.
    Please migrate to actions/setup-java@v5.

A warning, not a failure — the job is green. But "no longer receives updates"
means the JDK-provisioning step of the only gate that runs on a clean machine
stops getting security fixes.

## Why it is not fixed in the same commit

Bumping the action is a one-line change, but it is unrelated to the LockGate
slice and would make that commit non-atomic. It also needs its own green run to
prove the bump, which is the whole content of the change.

## Resolution

Bumped to `actions/setup-java@v5` in commit 7d7d558, pushed, CI run
32487537024 green — Android JVM tests still run under it.

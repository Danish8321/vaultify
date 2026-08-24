# 20 — :app instrumentation crashed twice, unexplained

Status: resolved 2026-08-24 — root cause found, not an app bug
Severity: medium
Source: task 2.13 follow-up, building the :app module

## 2026-08-18 rerun

Cleared logcat, ran `:app:connectedAndroidTest --rerun-tasks` on RZ8R20CRB9T
(physical device, not CI emulator). BUILD SUCCESSFUL, 2/2 tests passed.
`adb logcat -d | grep com.cryptum | grep -iE "FATAL|AndroidRuntime|crash|died"`
returned nothing for the app process. No recurrence — leaving open per the
ticket's own instruction: absence of one crash on a different device doesn't
clear an intermittent CI-emulator failure. Next CI recurrence should still
capture logcat before touching the test.

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

## 2026-08-24 reproduction and root cause

Booted the local `Android12` AVD (`hw.ramSize=2048`), cleared logcat, ran
`:app:connectedDebugAndroidTest --rerun-tasks`. Reproduced on the first try —
`the_app_opens_sealed` failed with the same `Instrumentation run failed due
to Process crashed.`

Logcat (captured per the ticket's own instruction, before touching anything)
has no `FATAL EXCEPTION` or `AndroidRuntime` — this was never an app crash.
The kernel's low-memory-killer reaped the process directly:

    lowmemorykiller: Kill 'com.cryptum' (16350), uid 10230, oom_score_adj 0
      to free 218864kB rss, 121616kB anon rss, 32012kB swap, 0kB dmabuf_pss,
      0kB dmabuf_rss; reason: min watermark is breached and swap is low
      (35360kB < 37744kB)
    Zygote: Process 16350 exited due to signal 9 (Killed)

`/proc/meminfo` on the device at the time: `MemTotal: 2013492 kB`,
`MemFree: 45820 kB`. The AVD is configured for 2 GB RAM
(`hw.ramSize=2048`), which is thin for Gradle's test orchestration process
plus `com.cryptum` plus `com.cryptum.test` plus system services all resident
at once — the swap watermark breach that triggers the killer is a resource
ceiling, not a code path.

## Resolution

Not an app bug — no code change. Raised the local `Android12` AVD's
`hw.ramSize` from 2048 to 4096 in `config.ini`. Rebooted the AVD (confirmed
`MemTotal: 4008504 kB`), cleared logcat, and reran
`:app:connectedDebugAndroidTest :feature-vault:connectedDebugAndroidTest
--rerun-tasks`: 2/2 and 7/7 passed, no process death.

CI's emulator config was not inspected here — this repo's CI Android job is
still blocked per ticket 06/10, so there is nothing running today to check.
If/when CI runs `:app:connectedAndroidTest`, its emulator profile should get
the same RAM headroom before this is called fully closed there too.

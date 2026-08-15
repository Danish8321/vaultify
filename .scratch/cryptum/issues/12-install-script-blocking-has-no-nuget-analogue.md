# 12 — "Block unreviewed dependency install scripts" has no NuGet analogue

Status: deferred
Severity: low (for the .NET half); reopens as medium when Gradle enters CI
Source: task 5.2

## Problem

Task 5.2 asks CI to "block unreviewed dependency install scripts". The clause is
written from an npm/pnpm mental model, where `postinstall` runs arbitrary code on
the machine that restores. NuGet has no equivalent: a package cannot execute code
at restore time. `install.ps1`/`init.ps1` ran only under the legacy
`packages.config` tooling in Visual Studio, and PackageReference — which every
project here uses — ignores them entirely.

So there is nothing to block on the .NET side, and adding a "script policy" step
to CI would be a gate that cannot fail: exactly the shape of the lockfile problem
found in task 07.

## What was done instead

The reachable half of 5.2 is real and is now enforced. `Directory.Build.props`
sets `NuGetAudit=true`, `NuGetAuditMode=all`, `NuGetAuditLevel=low`; combined
with `TreatWarningsAsErrors`, advisories NU1901-NU1904 fail the build.

`all` rather than the default direct-only, because the vulnerability that
actually arrives is three levels down a dependency nobody chose. `low` rather
than a higher floor, because triage against the advisory is a human judgement —
a threshold silently drops findings before anyone reads them.

Proven by mutation, not by assertion: pinning `Newtonsoft.Json` 12.0.1 produced

```
error NU1903: Warning As Error: Package 'Newtonsoft.Json' 12.0.1 has a known
high severity vulnerability, https://github.com/advisories/GHSA-5crp-9r3c-p9vr
```

and the pin was reverted immediately. That was a mutation test, not a dependency
addition.

## Still open

Gradle *does* execute arbitrary code at configuration time, and the Android build
is a real script-execution surface. When ticket 10 unblocks and the Android
module enters CI, this clause becomes live and needs:

- a Gradle dependency-verification metadata file (`gradle/verification-metadata.xml`)
  with checksums and/or signatures, committed;
- `--dependency-verification=strict` in the CI invocation;
- the Gradle wrapper JAR validated (`gradle/actions/wrapper-validation`), since a
  tampered wrapper executes before any verification the build itself declares.

None of that can be written or verified today — Gradle is not installed.

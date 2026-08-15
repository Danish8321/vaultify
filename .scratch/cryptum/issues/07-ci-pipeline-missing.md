# 07 — No CI pipeline; the gates only run when I run them

Status: resolved
Severity: medium
Source: plan task 0.3

## Problem

`.github/workflows/ci.yml` does not exist. `check.sh`, `test-fast.sh` and `contract.sh` are real gates now, but nothing runs them on push or PR. The repo has a remote (`github.com/Danish8321/vaultify`) so this is straightforward to close.

## Why it matters

`contract.sh` in particular only has value if it runs without being remembered. Its whole purpose is catching drift between the API contract and the generated client — drift appears exactly when someone changes a contract and does not think about the client.

## Done when

On PR: restore with a locked lockfile, run `check.sh`, `test-fast.sh`, `contract.sh`. No dependency install scripts run unreviewed. A PR with a formatting violation goes red.

## Comments

Closed by `.github/workflows/ci.yml` — push and PR to `main`, `permissions: contents: read`,
then locked restore, `check.sh`, `test-fast.sh`, `contract.sh`, `test-full.sh`.

Closing this uncovered a second, larger defect. `dotnet restore --locked-mode`
was **passing while proving nothing**: no `packages.lock.json` existed anywhere
in the repo, and a locked restore with no lockfile silently succeeds. Same class
of defect as the original `contract.sh` — a gate that cannot fail. Fixed by
setting `RestorePackagesWithLockFile` in `Directory.Build.props`; the 8 generated
lockfiles are committed.

Mutation-verified: changing the central `Azure.Identity` pin 1.21.0 to 1.20.0
produced `error NU1004: The packages lock file is inconsistent with the project
dependencies so restore can't be run in locked mode`; restoring the pin gave 0 errors.

Also removed the `|| dotnet restore` fallback from `check.sh`. It would have
caught the NU1004 and then silently repaired it, turning the one check that
detects an unexpected dependency change into a step that hides it.

Not covered here, deliberately: dependency install scripts. NuGet has no
install-script execution model, so the "no unreviewed scripts" half of *Done when*
has no NuGet analogue. It becomes real at task 2.9 when Gradle enters CI —
Gradle plugins **do** execute arbitrary build code.

Verified: `check.sh` OK, `contract.sh` OK, `test-full.sh` OK (31 unit + 11 integration).
CI itself is unverified until the first push runs it.

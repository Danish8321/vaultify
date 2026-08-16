# 09 — CI is pushed but unverified; `gh` is not authenticated

Status: resolved 2026-08-16 — and it was hiding a real failure
Severity: low
Source: closing ticket 07

## Problem

`.github/workflows/ci.yml` is committed and pushed (85514bf), but I cannot see
whether it ran or passed:

```
gh run list
failed to get runs: HTTP 401: Bad credentials
```

So ticket 07 is closed on the strength of the four gates passing locally, not on
a green pipeline. The workflow itself is untested — a YAML typo, a missing
`bash` on the runner, or a path that only resolves on Windows would all show up
only in the run.

## Why it matters

Small in itself, but it is the same shape as the two defects ticket 07 already
found: a check nobody can observe is a check that does not exist. An unverified
CI file is worse than none, because the repo now *looks* covered.

## Done when

Either `gh auth login` is run (`! gh auth login` in this session), or the run's
status is confirmed from the browser and reported here. Then confirm the first
run is green, and confirm a deliberate formatting violation goes red — the
*Done when* clause of ticket 07 that is still unproven.

## Comments

Needs the user: authentication is interactive and cannot be done from a tool call.

## Update 2026-08-16 — chosen route is a token, which is not set yet

Chosen: supply a token via the environment rather than fix the keyring. The
underlying failure is keyring-specific, not credential-specific:

```
X Failed to log in to github.com account Danish8321 (keyring)
```

`GH_TOKEN` and `GITHUB_TOKEN` are both unset in this session, so nothing has
changed yet. To unblock, run in this session:

```
! export GH_TOKEN=ghp_xxx   # fine-grained token, scope: Actions read
```

Least privilege applies to this too — reading workflow runs needs only Actions
read on this one repository. A classic `repo`-scoped token would hand every
tool in this session write access to the source of a secrets product, which is
a poor trade for watching a pipeline.

The token lives in the shell environment for the session's lifetime, so it
should be short-lived and should not be written into any file in the repo.

## Resolution 2026-08-16

Token supplied at Windows User scope. This shell predates it, so it is read
per-command via `[Environment]::GetEnvironmentVariable('GH_TOKEN','User')`
rather than echoed anywhere.

The first thing visibility revealed: **CI had been red on every single run since
the workflow landed** — six consecutive failures across 31900479326 …
31958300540, while every push was reported locally as passing all four gates.

```
.claude/scripts/test-full.sh: line 6: .claude/scripts/test-fast.sh: Permission denied
Process completed with exit code 126
```

The scripts were committed `100644`. Windows ignores the exec bit, so all four
gates genuinely passed locally while the Linux runner could not execute them.
Fixed in a01eb17 with `git update-index --chmod=+x`, chosen over prefixing the
call with `bash` so the shebang stays authoritative and the next sibling call
does not fall into the same hole.

This is precisely the failure mode the ticket named: *a check nobody can observe
is a check that does not exist*. Worth generalising — every gate in this repo
has now been wrong once in the direction of falsely passing (lockfile in 07,
exec bit here), and never in the direction of falsely failing.

### Evidence

- `31958607322` — **green**, the first genuine pass of the pipeline.
- `31958733488` — **red on purpose**, proving ticket 07's last unproven clause:

```
src/Cryptum.Domain/BadlyFormatted.cs(4,21): error WHITESPACE:
Fix whitespace formatting. Delete 2 characters.
```

The probe branch `ci-mutation-probe` and its throwaway file are deleted; the
workflow triggers are back to `[main]`.

A PR could not be used for the probe: `gh pr create` returned *"Resource not
accessible by personal access token"*, which is the fine-grained Actions-read
scope behaving exactly as intended. The `pull_request` trigger is therefore
still unexercised — the `push` path is proven, the PR path is inferred from
sharing one job definition.

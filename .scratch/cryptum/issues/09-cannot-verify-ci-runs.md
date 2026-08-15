# 09 — CI is pushed but unverified; `gh` is not authenticated

Status: open
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

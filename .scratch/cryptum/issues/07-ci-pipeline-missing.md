# 07 — No CI pipeline; the gates only run when I run them

Status: open
Severity: medium
Source: plan task 0.3

## Problem

`.github/workflows/ci.yml` does not exist. `check.sh`, `test-fast.sh` and `contract.sh` are real gates now, but nothing runs them on push or PR. The repo has a remote (`github.com/Danish8321/vaultify`) so this is straightforward to close.

## Why it matters

`contract.sh` in particular only has value if it runs without being remembered. Its whole purpose is catching drift between the API contract and the generated client — drift appears exactly when someone changes a contract and does not think about the client.

## Done when

On PR: restore with a locked lockfile, run `check.sh`, `test-fast.sh`, `contract.sh`. No dependency install scripts run unreviewed. A PR with a formatting violation goes red.

# 08 — QUERY: local branch name no longer matches what it holds

Status: resolved
Type: query
Severity: low

## Situation

Local branch is `phase-0-verification-harness`, tracking `origin/main`. It stopped being a phase-0 branch several commits ago — it now holds the whole backend slice. Pushes work (`git push origin phase-0-verification-harness:main`), but the mismatch invites a mistake: an ordinary `git push` from a fresh clone would not do what this one does.

## Decision needed

1. Rename local to `main` and track it directly (simplest; the repo has one line of history and no other contributors).
2. Keep feature branches and open PRs into `main` — better once CI exists (see 07), heavier now.

Recommend 1 now, moving to 2 when CI lands.

## Comments

**Resolved 2026-08-15.** Local branch renamed `phase-0-verification-harness` -> `main`, tracking `origin/main` directly. A plain `git push` now does the right thing; the explicit refspec mapping is no longer needed.

Option 2 (PR workflow) remains worth revisiting once CI exists — see 07.

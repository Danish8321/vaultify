# No KEK rotation in MVP

Status: accepted

Per-User KEKs are created once at signup and not rotated. Rotation is expensive in this design: because every DEK is wrapped by the User's KEK, rotating it means unwrapping and re-wrapping every DEK that User owns — the backend would briefly hold every one of that User's DEKs in plaintext, which is the exact exposure the architecture otherwise minimizes. For an MVP with no compliance-driven rotation requirement, that cost and that risk window are not justified.

## Consequences

There is currently **no procedure to recover from a suspected KEK compromise** other than crypto-shredding the account. If rotation later becomes necessary (compliance mandate, or an actual incident), it must be built as an explicit, resumable, audited batch operation — not improvised under incident pressure.

Recording this now because the absence of rotation is invisible in the code and would otherwise be discovered at the worst possible moment. DEKs, by contrast, effectively rotate on every write: each edit generates a fresh DEK (ADR-0006).

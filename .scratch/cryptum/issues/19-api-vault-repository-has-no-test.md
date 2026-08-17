# 19 — ApiVaultRepository is the one untested thing in the Vault path

Status: open (deliberate, not an oversight)
Severity: medium
Source: task 2.13

## Problem

`ApiVaultRepository` seals, calls the generated client, and opens. The sealing
and opening are covered by `SecretEnvelopeTest`; the screens are covered by
`VaultScreenTest` against a fake. The HTTP call itself is covered by nothing.

## Why it is deliberate

The only failure modes left in that class are wrong URL, wrong auth header,
wrong status handling and wrong deserialization at the boundary — every one of
which needs a real server to falsify. A mock HTTP engine would assert that the
client sends what I already believe it sends, which is the tautological shape
the tdd skill warns about: it would pass by construction and disagree with
nothing.

Recording it rather than quietly leaving a hole, because "the Vault is tested"
would otherwise be a broader claim than the evidence supports.

## Resolution

Task 2.14's `e2e.sh` against a deployed environment. Until that runs, the honest
statement is: the envelope is proven, the screens are proven, the transport is
not.

# 03 — Nothing prevents a DEK reaching the logs

Status: open
Severity: high
Source: plan tasks 2.3 and 2.6, security-requirements

## Problem

"No request/response body logging" is asserted in the security requirements and in review, but nothing enforces it. `ItemResponse` carries the plaintext DEK on every read. Any future addition of request logging, a verbose exception filter, or a well-meant `LogInformation("{@Response}", response)` would write user key material to App Insights in the clear.

## Why it matters

The plaintext DEK crossing the network is already the accepted cost of being server-blind rather than end-to-end encrypted (ADR-0001). That trade is only defensible while the DEK's life is short and its exposure bounded. A DEK in a log is a DEK at rest, in a system with a different access model and a long retention period.

## Done when

A test asserts that no log output from a create-then-read cycle contains the DEK bytes. Log capture, real endpoints, real DEK — searching the captured output for the actual byte sequence.

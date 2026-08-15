# 02 — Rate-limit buckets wired but not proven distinct

Status: resolved
Severity: medium
Source: plan task 2.8, security-requirements

## Problem

`Program.cs` declares a 100/min global limiter and a 20/min `unwrap` policy on `GET /items/{id}`. Nothing proves the two are actually separate partitions. A configuration mistake — both resolving to the same partition key, or the policy silently not applying to the route — would leave the unwrap path on the general budget, and every test would still pass.

## Why it matters

The unwrap route is the highest-value target per call: each request costs one Key Vault unwrap of that user's DEK. Security-requirements calls for a stricter limit specifically here.

## Done when

Exceeding the unwrap limit returns 429 while ordinary CRUD by the same caller still succeeds — that asymmetry is what proves the buckets are distinct rather than one shared budget.

## Comments

**Resolved.** `RateLimitTests` exhausts the 20/min unwrap budget and then shows `GET /items` still returns 200 on the same caller. Non-vacuous by mutation: raising the unwrap limit to the global 100 makes it fail with "the unwrap route served more than 20 requests in one window".

Uses its own factory rather than the shared fixture, since it deliberately exhausts a limiter and that state is process-wide.

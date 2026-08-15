# 01 — Audit trail is not yet tamper-resistant

Status: blocked
Blocked by: Phase 1 Azure infra (no database exists)
Severity: high
Source: plan task 2.7, security-requirements

## Problem

`IAuditLog` exposes only `RecordAsync` — no update or delete method exists, so tampering cannot be expressed in C#. That is a **code-level** constraint. The control the security requirements actually call for is a **database-level** one: an INSERT-only DB principal, so that application-level write access cannot alter audit history.

Right now the application's DB principal is the same one that writes Items. Anything able to run arbitrary SQL through that connection can rewrite the audit trail. The C# interface is a speed bump, not the control.

## Why it matters

ADR-0002 accepts a large residual risk — the backend can unwrap any user's DEK — and names audit logging as the compensating control. A compensating control that the compromised component can edit does not compensate for anything. This is the single most load-bearing untested claim in the system.

## Done when

- Audit writes go through a principal with INSERT and no UPDATE/DELETE, **or** ship to Log Analytics as the source of truth.
- A test attempts UPDATE and DELETE as that principal and both are refused. The refusal is the evidence; a table the app can still delete from is not append-only.

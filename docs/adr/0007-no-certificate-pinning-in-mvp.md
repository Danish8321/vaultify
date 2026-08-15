# No certificate pinning in the Android client for MVP

Status: accepted

The Android client relies on TLS 1.2+ with the platform's standard CA validation, and does not pin the server certificate or public key. Pinning was considered because plaintext DEKs transit the network on every read (ADR-0002, ARCHITECTURE.md), which makes transport security unusually load-bearing here — but it defends only against a compromised or mis-issued CA, and it carries an asymmetric failure mode: a botched pin rotation bricks every installed app with no server-side remedy.

## Consequences

An adversary able to obtain a fraudulent certificate from a trusted CA could observe wrapped DEKs, ciphertext, and unwrapped DEKs in transit. Accepted for MVP as a low-likelihood, high-effort attack relative to the operational risk of pinning.

Recorded because the absence is invisible in the code. If pinning is added later it needs backup pins and a rotation procedure agreed *before* the first pin ships, not after.

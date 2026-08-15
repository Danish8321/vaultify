# Architecture decision records

| # | Decision | Status |
|---|---|---|
| [0001](./0001-envelope-encryption-model.md) | Envelope encryption: client-side DEK per Item, per-User KEK in Key Vault | accepted |
| [0002](./0002-backend-mediated-key-vault-access.md) | Backend Managed Identity mediates all wrap/unwrap calls | accepted |
| [0003](./0003-crypto-shred-deletion.md) | Account deletion via crypto-shred, not immediate hard-delete | accepted |
| [0004](./0004-azure-ad-b2c-for-user-identity.md) | Azure AD B2C for user identity | accepted |
| [0005](./0005-no-kek-rotation-in-mvp.md) | No KEK rotation in MVP | accepted |
| [0006](./0006-overwrite-on-edit-version-history-deferred.md) | Edits overwrite in place with a fresh DEK; version history deferred | accepted |

ADRs 0001, 0002 and 0004 are entangled: they are three views of the same choice to let the service mediate key access rather than derive keys from a user secret. Revisiting any one of them means revisiting all three.

See also [ARCHITECTURE.md](../ARCHITECTURE.md) for system shape and request flows, and [security-requirements.md](../security-requirements.md) for hardening controls.

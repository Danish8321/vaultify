# Edits overwrite in place with a fresh DEK; version history deferred

Status: accepted

Editing an Item re-encrypts it under a newly generated DEK and nonce, replacing the stored ciphertext and wrapped DEK. No prior version is retained in the MVP. Version history is a wanted feature, not a rejected one — so the Item schema is designed to accept a versions table later without migrating existing rows (stable Item identity, with content and key material addressed separately from that identity).

## Consequences

Generating a fresh DEK per write means no DEK ever encrypts more than one message, which makes AES-GCM nonce reuse structurally impossible rather than merely unlikely — a meaningful safety property, and a reason to keep this behaviour even after version history lands.

Until history exists, an accidental overwrite is unrecoverable: the previous DEK is gone and the previous ciphertext is replaced. For a password manager this is a genuine user-facing gap (overwriting a working password loses it), and it is the strongest argument for prioritising version history soon after MVP.

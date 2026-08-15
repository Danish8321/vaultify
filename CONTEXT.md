# Cryptum

Encrypted secrets vault. Users store credentials and files; content is encrypted on the user's device and the service stores only ciphertext.

## Language

**Vault**:
The full set of Items belonging to one User.
_Avoid_: Account data, storage

**Item**:
A single stored unit — a Secret or a File. Owned by exactly one User. Has its own DEK.
_Avoid_: Entry, record

**Secret**:
An Item holding structured credential fields: title, username, password, url, notes. The title is stored unencrypted so Vaults can be listed; every other field is encrypted.
_Avoid_: Password, credential (too narrow — a Secret is more than a password)

**File**:
An Item holding an arbitrary user-supplied attachment.
_Avoid_: Attachment, document

**DEK (Data Encryption Key)**:
The symmetric key that encrypts one Item's content. Generated on the device, and only ever handed to the service in wrapped form.
_Avoid_: Item key, content key

**KEK (Key Encryption Key)**:
A User's own key-wrapping key, held by the key management service. Wraps and unwraps that User's DEKs and is never released to any caller.
_Avoid_: Master key, vault key (confusable with "Vault" the domain term)

**Wrap / Unwrap**:
Encrypting (wrap) or decrypting (unwrap) a DEK with a KEK, performed inside the key management service. These operations act on DEKs only, never on Item content.
_Avoid_: Encrypt/decrypt the key (ambiguous with Item-level encryption)

**Crypto-shred**:
Destroying a User's KEK so that every DEK it wrapped becomes permanently unusable, rendering that User's Items undecryptable regardless of whether the ciphertext itself has been deleted yet.
_Avoid_: Hard delete, purge (those refer to the separate cleanup of stored ciphertext, not the key destruction)

**Server-blind**:
The property that Cryptum's servers and storage hold only ciphertext and wrapped DEKs, and that Item plaintext is produced only on the User's device. The service is *capable* of unwrapping a DEK (it mediates every unwrap), so this is a policy-and-audit guarantee, not a cryptographic impossibility.
_Avoid_: Zero-knowledge, end-to-end encrypted (both claim the service **cannot** decrypt; Cryptum's service can, and must not claim otherwise)

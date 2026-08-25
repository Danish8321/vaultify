# 27 — Opening a File doesn't launch an external viewer

**Status:** open
**Severity:** medium
**Found:** 2026-08-24, during Files-feature Android wiring (fork task, resumed after ticket for the OpenAPI sizeBytes bug)

## What

`FilesScreen.kt`'s hold-to-open gesture downloads the ciphertext (via the
blob SAS from `FileRepository.download`) and decrypts it, proving the
round trip end to end — but the decrypted bytes are never handed off to
Android for viewing. There's no `FileProvider`, no manifest `<provider>`
entry, and no `Intent.ACTION_VIEW` dispatch. The feature currently proves
"can fetch and decrypt" but not "can open."

## Fix shape

- Add a `FileProvider` (androidx.core) with a matching `<provider>` entry
  in `app/src/main/AndroidManifest.xml` and a `file_paths.xml` res.
  Decrypted bytes get written to `context.cacheDir` (never external
  storage — plaintext at rest is the thing this app exists to avoid),
  under a per-open-session file, cleaned up after the intent fires or
  on next app start.
- Wire `Intent.ACTION_VIEW` with `FLAG_GRANT_READ_URI_PERMISSION`,
  MIME type inferred from the file's title extension (server doesn't
  store a MIME type — see whether that needs adding to `FileResponse`
  or can be inferred client-side).

## Related

- `android/feature-vault/src/main/kotlin/com/cryptum/vault/FilesScreen.kt`
- `android/feature-vault/src/main/kotlin/com/cryptum/vault/FileRepository.kt`

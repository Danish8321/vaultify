namespace Cryptum.Domain;

/// <summary>
/// Issues short-lived, scoped access to blob storage for File ciphertext.
/// </summary>
/// <remarks>
/// The server never proxies file bytes — the client PUTs and GETs directly
/// against blob storage using the SAS this returns, so the API host is never
/// on the data path for a large upload/download (docs/IMPLEMENTATION-PLAN.md
/// 3.1). Implementations must never grant access broader than the single blob
/// path requested, and must keep expiry short: a SAS that outlives the request
/// that produced it is a standing credential, not a one-time favor.
/// </remarks>
public interface IBlobStore
{
    /// <summary>
    /// Returns a URI, valid only for <see cref="UploadSasLifetime"/>, that
    /// permits a single write to exactly <paramref name="blobPath"/>.
    /// </summary>
    Task<Uri> GetUploadSasUriAsync(string blobPath, CancellationToken cancellationToken = default);

    /// <summary>
    /// Returns a URI, valid only for <see cref="DownloadSasLifetime"/>, that
    /// permits a single read of exactly <paramref name="blobPath"/>.
    /// </summary>
    Task<Uri> GetDownloadSasUriAsync(string blobPath, CancellationToken cancellationToken = default);

    /// <summary>
    /// Deletes the blob at <paramref name="blobPath"/>, if it exists. A no-op,
    /// not an error, when the blob is already gone — a registered File whose
    /// upload never completed (see <see cref="VaultService.CreateFileAsync"/>'s
    /// remarks) has no blob to delete, and its row must still be removable.
    /// </summary>
    Task DeleteAsync(string blobPath, CancellationToken cancellationToken = default);
}

/// <summary>SAS lifetimes shared by every <see cref="IBlobStore"/> implementation.</summary>
public static class BlobSasLifetime
{
    /// <summary>
    /// How long an issued upload SAS remains valid. Short enough that a leaked
    /// URL (a proxy log, a crash report) is worthless within minutes, long
    /// enough that a slow mobile upload of a file at <see cref="FileLimits.MaxFileBytes"/> completes.
    /// </summary>
    public static readonly TimeSpan Upload = TimeSpan.FromMinutes(10);

    /// <summary>Shorter than upload: a download is one GET, not a sustained transfer.</summary>
    public static readonly TimeSpan Download = TimeSpan.FromMinutes(5);
}

/// <summary>
/// The only meaningful upload controls, since ciphertext cannot be
/// content-inspected (docs/security-requirements.md).
/// </summary>
/// <remarks>
/// Sized for a password-manager attachment, not general file storage: a
/// scanned document or a small archive fits comfortably; the quota keeps one
/// account from silently becoming a bulk file host. Both are policy, not
/// protocol — revisit if real usage says otherwise.
/// </remarks>
public static class FileLimits
{
    public const long MaxFileBytes = 25L * 1024 * 1024;

    public const long MaxUserQuotaBytes = 250L * 1024 * 1024;
}

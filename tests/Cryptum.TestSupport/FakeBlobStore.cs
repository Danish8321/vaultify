using System.Collections.Concurrent;
using Cryptum.Domain;

namespace Cryptum.TestSupport;

/// <summary>
/// Test double for <see cref="IBlobStore"/>. Records which blob paths were
/// issued a SAS and with which lifetime, rather than talking to Azure.
/// </summary>
/// <remarks>
/// A real user-delegation SAS cannot be constructed without a live Key Vault
/// and Storage account, so this stands in with the one thing tests actually
/// need to assert: that a URI was issued, that it is scoped to exactly the
/// requested blob path, and that the requested lifetime matches
/// <see cref="BlobSasLifetime"/>.
/// </remarks>
public sealed class FakeBlobStore : IBlobStore
{
    private readonly ConcurrentDictionary<string, int> uploadSasIssued = new();
    private readonly ConcurrentDictionary<string, int> downloadSasIssued = new();
    private readonly ConcurrentDictionary<string, bool> deleted = new();

    public int UploadSasCountFor(string blobPath) => uploadSasIssued.GetValueOrDefault(blobPath);

    public int DownloadSasCountFor(string blobPath) => downloadSasIssued.GetValueOrDefault(blobPath);

    public bool WasDeleted(string blobPath) => deleted.ContainsKey(blobPath);

    /// <summary>Paths an upload SAS was ever issued for — the only way a test can
    /// learn the blob path <see cref="VaultService.CreateFileAsync"/> generated,
    /// since it is not part of any response contract.</summary>
    public IReadOnlyCollection<string> UploadedPaths => uploadSasIssued.Keys.ToList();

    public Task<Uri> GetUploadSasUriAsync(string blobPath, CancellationToken cancellationToken = default)
    {
        uploadSasIssued.AddOrUpdate(blobPath, 1, (_, count) => count + 1);
        return Task.FromResult(BuildUri(blobPath, "upload", BlobSasLifetime.Upload));
    }

    public Task<Uri> GetDownloadSasUriAsync(string blobPath, CancellationToken cancellationToken = default)
    {
        downloadSasIssued.AddOrUpdate(blobPath, 1, (_, count) => count + 1);
        return Task.FromResult(BuildUri(blobPath, "download", BlobSasLifetime.Download));
    }

    public Task DeleteAsync(string blobPath, CancellationToken cancellationToken = default)
    {
        deleted[blobPath] = true;
        return Task.CompletedTask;
    }

    private static Uri BuildUri(string blobPath, string operation, TimeSpan lifetime) =>
        new($"https://fake.blob.test/vault/{blobPath}?op={operation}&ttl={lifetime.TotalSeconds}");
}

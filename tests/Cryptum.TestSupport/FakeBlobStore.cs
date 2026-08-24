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

    public int UploadSasCountFor(string blobPath) => uploadSasIssued.GetValueOrDefault(blobPath);

    public int DownloadSasCountFor(string blobPath) => downloadSasIssued.GetValueOrDefault(blobPath);

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

    private static Uri BuildUri(string blobPath, string operation, TimeSpan lifetime) =>
        new($"https://fake.blob.test/vault/{blobPath}?op={operation}&ttl={lifetime.TotalSeconds}");
}

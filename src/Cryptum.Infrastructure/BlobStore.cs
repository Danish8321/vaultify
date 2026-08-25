using Azure.Storage.Blobs;
using Azure.Storage.Sas;
using Cryptum.Domain;

namespace Cryptum.Infrastructure;

/// <summary>
/// <see cref="IBlobStore"/> backed by Azure Blob Storage (docs/IMPLEMENTATION-PLAN.md 3.1).
/// </summary>
/// <remarks>
/// Every SAS is scoped to exactly one blob path and one operation (write-only
/// for upload, read-only for download) — never container-level, and never
/// both operations on the same token. The user-delegation key is fetched per
/// call rather than cached, since it is itself short-lived and re-fetching is
/// cheap next to a Key Vault round trip.
/// </remarks>
public sealed class BlobStore(BlobServiceClient serviceClient, string containerName) : IBlobStore
{
    public async Task<Uri> GetUploadSasUriAsync(string blobPath, CancellationToken cancellationToken = default) =>
        await BuildSasUriAsync(blobPath, BlobSasLifetime.Upload, BlobSasPermissions.Write, cancellationToken)
            .ConfigureAwait(false);

    public async Task<Uri> GetDownloadSasUriAsync(string blobPath, CancellationToken cancellationToken = default) =>
        await BuildSasUriAsync(blobPath, BlobSasLifetime.Download, BlobSasPermissions.Read, cancellationToken)
            .ConfigureAwait(false);

    public async Task DeleteAsync(string blobPath, CancellationToken cancellationToken = default) =>
        await serviceClient.GetBlobContainerClient(containerName).GetBlobClient(blobPath)
            .DeleteIfExistsAsync(cancellationToken: cancellationToken)
            .ConfigureAwait(false);

    private async Task<Uri> BuildSasUriAsync(
        string blobPath, TimeSpan lifetime, BlobSasPermissions permissions, CancellationToken cancellationToken)
    {
        var blobClient = serviceClient.GetBlobContainerClient(containerName).GetBlobClient(blobPath);

        var now = DateTimeOffset.UtcNow;
        var userDelegationKey = await serviceClient
            .GetUserDelegationKeyAsync(now.AddMinutes(-5), now.Add(lifetime), cancellationToken)
            .ConfigureAwait(false);

        var sasBuilder = new BlobSasBuilder
        {
            BlobContainerName = containerName,
            BlobName = blobPath,
            Resource = "b",
            StartsOn = now.AddMinutes(-5),
            ExpiresOn = now.Add(lifetime),
        };
        sasBuilder.SetPermissions(permissions);

        var sasQuery = sasBuilder.ToSasQueryParameters(userDelegationKey.Value, serviceClient.AccountName);

        var uriBuilder = new UriBuilder(blobClient.Uri) { Query = sasQuery.ToString() };
        return uriBuilder.Uri;
    }
}

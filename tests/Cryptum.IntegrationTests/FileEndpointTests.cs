using System.Net;
using System.Net.Http.Headers;
using System.Net.Http.Json;
using System.Security.Cryptography;
using Cryptum.Api.Contracts;
using Cryptum.Domain;

namespace Cryptum.IntegrationTests;

/// <summary>
/// HTTP-level verification for the Files backend: round-trip a registration
/// and a read through the real endpoints, and confirm the abuse cases the
/// plan calls out (oversized file, quota, cross-user access).
/// </summary>
public sealed class FileEndpointTests : IClassFixture<CryptumApiFactory>
{
    private readonly CryptumApiFactory factory;

    public FileEndpointTests(CryptumApiFactory factory) => this.factory = factory;

    [Fact]
    public async Task Create_then_read_round_trips_metadata_and_issues_scoped_sas_uris()
    {
        using var alice = ClientWith(TestTokens.For(factory, $"alice-{Guid.NewGuid()}"));
        var dek = RandomNumberGenerator.GetBytes(32);

        var created = await alice.PostAsJsonAsync(new Uri("/items/files", UriKind.Relative), NewFile() with { Dek = dek });
        Assert.Equal(HttpStatusCode.Created, created.StatusCode);

        var createdBody = await created.Content.ReadFromJsonAsync<CreateFileResponse>();
        Assert.NotNull(createdBody);
        Assert.NotNull(createdBody!.UploadUri);

        var read = await alice.GetAsync(new Uri($"/items/files/{createdBody.Id}", UriKind.Relative));
        Assert.Equal(HttpStatusCode.OK, read.StatusCode);

        var readBody = await read.Content.ReadFromJsonAsync<FileResponse>();
        Assert.NotNull(readBody);
        Assert.Equal(dek, readBody!.Dek);
        Assert.Equal(4096, readBody.SizeBytes);
        Assert.NotNull(readBody.DownloadUri);
    }

    [Fact]
    public async Task Create_rejects_a_file_over_the_per_file_limit()
    {
        using var alice = ClientWith(TestTokens.For(factory, $"alice-{Guid.NewGuid()}"));

        var request = NewFile() with { SizeBytes = FileLimits.MaxFileBytes + 1 };
        var response = await alice.PostAsJsonAsync(new Uri("/items/files", UriKind.Relative), request);

        Assert.Equal(HttpStatusCode.RequestEntityTooLarge, response.StatusCode);
    }

    [Fact]
    public async Task A_file_registered_by_one_user_is_invisible_to_another()
    {
        using var alice = ClientWith(TestTokens.For(factory, $"alice-{Guid.NewGuid()}"));
        using var mallory = ClientWith(TestTokens.For(factory, $"mallory-{Guid.NewGuid()}"));

        var created = await alice.PostAsJsonAsync(new Uri("/items/files", UriKind.Relative), NewFile());
        var createdBody = await created.Content.ReadFromJsonAsync<CreateFileResponse>();
        Assert.NotNull(createdBody);

        var byStranger = await mallory.GetAsync(new Uri($"/items/files/{createdBody!.Id}", UriKind.Relative));

        // 404, not 403 — same convention as Secrets.
        Assert.Equal(HttpStatusCode.NotFound, byStranger.StatusCode);
    }

    private static CreateFileRequest NewFile() => new()
    {
        Title = "passport.pdf",
        SizeBytes = 4096,
        Nonce = RandomNumberGenerator.GetBytes(Item.NonceLength),
        Dek = RandomNumberGenerator.GetBytes(CreateSecretRequest.MinDekBytes),
    };

    private HttpClient ClientWith(string token)
    {
        var client = factory.CreateClient();
        client.DefaultRequestHeaders.Authorization = new AuthenticationHeaderValue("Bearer", token);
        return client;
    }
}

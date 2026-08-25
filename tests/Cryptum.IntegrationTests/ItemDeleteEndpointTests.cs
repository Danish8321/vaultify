using System.Net;
using System.Net.Http.Headers;
using System.Net.Http.Json;
using System.Security.Cryptography;
using Cryptum.Api.Contracts;
using Cryptum.Domain;
using Cryptum.TestSupport;
using Microsoft.Extensions.DependencyInjection;

namespace Cryptum.IntegrationTests;

/// <summary>
/// HTTP-level verification for deleting a single Item — the endpoint ticket
/// 28 named as missing (neither Secrets nor Files had one before this).
/// </summary>
public sealed class ItemDeleteEndpointTests : IClassFixture<CryptumApiFactory>
{
    private readonly CryptumApiFactory factory;

    public ItemDeleteEndpointTests(CryptumApiFactory factory) => this.factory = factory;

    [Fact]
    public async Task Deleting_a_secret_removes_it_from_the_list_and_returns_404_on_read()
    {
        using var alice = ClientWith(TestTokens.For(factory, $"alice-{Guid.NewGuid()}"));

        var created = await alice.PostAsJsonAsync(new Uri("/items/", UriKind.Relative), NewSecret());
        var createdBody = await created.Content.ReadFromJsonAsync<CreatedItemResponse>();
        Assert.NotNull(createdBody);

        var deleted = await alice.DeleteAsync(new Uri($"/items/{createdBody!.Id}", UriKind.Relative));
        Assert.Equal(HttpStatusCode.NoContent, deleted.StatusCode);

        var read = await alice.GetAsync(new Uri($"/items/{createdBody.Id}", UriKind.Relative));
        Assert.Equal(HttpStatusCode.NotFound, read.StatusCode);

        var list = await alice.GetFromJsonAsync<List<ItemSummaryResponse>>(new Uri("/items/", UriKind.Relative));
        Assert.DoesNotContain(list!, i => i.Id == createdBody.Id);
    }

    [Fact]
    public async Task Deleting_a_file_also_deletes_its_blob()
    {
        using var alice = ClientWith(TestTokens.For(factory, $"alice-{Guid.NewGuid()}"));

        var created = await alice.PostAsJsonAsync(new Uri("/items/files", UriKind.Relative), NewFile());
        var createdBody = await created.Content.ReadFromJsonAsync<CreateFileResponse>();
        Assert.NotNull(createdBody);

        var blobStore = (FakeBlobStore)factory.Services.GetRequiredService<IBlobStore>();
        var blobPath = Assert.Single(blobStore.UploadedPaths);

        var deleted = await alice.DeleteAsync(new Uri($"/items/{createdBody!.Id}", UriKind.Relative));
        Assert.Equal(HttpStatusCode.NoContent, deleted.StatusCode);

        Assert.True(blobStore.WasDeleted(blobPath));
    }

    [Fact]
    public async Task Deleting_someone_elses_item_returns_404()
    {
        using var alice = ClientWith(TestTokens.For(factory, $"alice-{Guid.NewGuid()}"));
        using var mallory = ClientWith(TestTokens.For(factory, $"mallory-{Guid.NewGuid()}"));

        var created = await alice.PostAsJsonAsync(new Uri("/items/", UriKind.Relative), NewSecret());
        var createdBody = await created.Content.ReadFromJsonAsync<CreatedItemResponse>();
        Assert.NotNull(createdBody);

        var deleted = await mallory.DeleteAsync(new Uri($"/items/{createdBody!.Id}", UriKind.Relative));
        Assert.Equal(HttpStatusCode.NotFound, deleted.StatusCode);
    }

    [Fact]
    public async Task Deleting_a_nonexistent_item_returns_404()
    {
        using var alice = ClientWith(TestTokens.For(factory, $"alice-{Guid.NewGuid()}"));

        var deleted = await alice.DeleteAsync(new Uri($"/items/{Guid.NewGuid()}", UriKind.Relative));
        Assert.Equal(HttpStatusCode.NotFound, deleted.StatusCode);
    }

    private static CreateSecretRequest NewSecret() => new()
    {
        Title = "note",
        Ciphertext = RandomNumberGenerator.GetBytes(32),
        Nonce = RandomNumberGenerator.GetBytes(Item.NonceLength),
        Dek = RandomNumberGenerator.GetBytes(CreateSecretRequest.MinDekBytes),
    };

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

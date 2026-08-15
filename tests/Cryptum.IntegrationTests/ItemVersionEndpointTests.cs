using System.Net;
using System.Net.Http.Headers;
using System.Net.Http.Json;
using System.Security.Cryptography;
using Cryptum.Api.Contracts;
using Cryptum.Domain;

namespace Cryptum.IntegrationTests;

/// <summary>
/// The history routes over the production JWT handler (plan task 3.0).
/// </summary>
/// <remarks>
/// History adds three routes that reach the same ciphertext the Item routes do.
/// These assert the HTTP surface enforces what VaultService already does — an
/// endpoint that forgot to pass the caller's identity through would leave the
/// domain tests passing and the API wide open.
/// </remarks>
public sealed class ItemVersionEndpointTests : IClassFixture<CryptumApiFactory>
{
    private readonly CryptumApiFactory factory;

    public ItemVersionEndpointTests(CryptumApiFactory factory) => this.factory = factory;

    private HttpClient ClientFor(string subject)
    {
        var client = factory.CreateClient();
        client.DefaultRequestHeaders.Authorization =
            new AuthenticationHeaderValue("Bearer", TestTokens.For(factory, subject));
        return client;
    }

    private static UpdateSecretRequest AnUpdate(string title = "Bank") => new()
    {
        Title = title,
        Ciphertext = RandomNumberGenerator.GetBytes(48),
        Nonce = RandomNumberGenerator.GetBytes(Item.NonceLength),
        Dek = RandomNumberGenerator.GetBytes(CreateSecretRequest.MinDekBytes),
    };

    private static async Task<Guid> CreateAsync(HttpClient client)
    {
        var created = await client.PostAsJsonAsync(new Uri("/items", UriKind.Relative), new CreateSecretRequest
        {
            Title = "Bank",
            Ciphertext = RandomNumberGenerator.GetBytes(48),
            Nonce = RandomNumberGenerator.GetBytes(Item.NonceLength),
            Dek = RandomNumberGenerator.GetBytes(CreateSecretRequest.MinDekBytes),
        });
        Assert.Equal(HttpStatusCode.Created, created.StatusCode);

        var body = await created.Content.ReadFromJsonAsync<CreatedItemResponse>();
        Assert.NotNull(body);
        return body.Id;
    }

    [Fact]
    public async Task An_edit_then_a_restore_returns_the_original_ciphertext_and_its_dek()
    {
        using var client = ClientFor($"history-{Guid.NewGuid()}");
        var id = await CreateAsync(client);

        var original = await client.GetFromJsonAsync<ItemResponse>(new Uri($"/items/{id}", UriKind.Relative));
        Assert.NotNull(original);

        var edit = await client.PutAsJsonAsync(new Uri($"/items/{id}", UriKind.Relative), AnUpdate("Bank v2"));
        Assert.Equal(HttpStatusCode.NoContent, edit.StatusCode);

        var restore = await client.PostAsync(
            new Uri($"/items/{id}/versions/1/restore", UriKind.Relative), content: null);
        Assert.Equal(HttpStatusCode.NoContent, restore.StatusCode);

        var restored = await client.GetFromJsonAsync<ItemResponse>(new Uri($"/items/{id}", UriKind.Relative));
        Assert.NotNull(restored);
        Assert.Equal(original.Ciphertext, restored.Ciphertext);
        Assert.Equal(original.Nonce, restored.Nonce);

        // The DEK is what makes the ciphertext usable. Matching bytes with a
        // different DEK would be a restore that returned unreadable data.
        Assert.Equal(original.Dek, restored.Dek);
    }

    [Fact]
    public async Task Another_user_gets_404_for_every_history_route()
    {
        using var owner = ClientFor($"owner-{Guid.NewGuid()}");
        using var stranger = ClientFor($"stranger-{Guid.NewGuid()}");

        var id = await CreateAsync(owner);
        Assert.Equal(
            HttpStatusCode.NoContent,
            (await owner.PutAsJsonAsync(new Uri($"/items/{id}", UriKind.Relative), AnUpdate())).StatusCode);

        // 404 rather than 403 throughout: a 403 would confirm the id is real.
        Assert.Equal(
            HttpStatusCode.NotFound,
            (await stranger.PutAsJsonAsync(new Uri($"/items/{id}", UriKind.Relative), AnUpdate("Pwned"))).StatusCode);
        Assert.Equal(
            HttpStatusCode.NotFound,
            (await stranger.GetAsync(new Uri($"/items/{id}/versions/1", UriKind.Relative))).StatusCode);
        Assert.Equal(
            HttpStatusCode.NotFound,
            (await stranger.PostAsync(new Uri($"/items/{id}/versions/1/restore", UriKind.Relative), null)).StatusCode);

        var strangersView = await stranger.GetFromJsonAsync<List<ItemVersionSummaryResponse>>(
            new Uri($"/items/{id}/versions", UriKind.Relative));
        Assert.Empty(strangersView!);
    }

    [Fact]
    public async Task The_history_list_never_carries_ciphertext_or_a_dek()
    {
        // Asserted on the wire rather than on the response type: the type could be
        // right while a serializer setting or a later widening put content on it.
        using var client = ClientFor($"history-{Guid.NewGuid()}");
        var id = await CreateAsync(client);
        await client.PutAsJsonAsync(new Uri($"/items/{id}", UriKind.Relative), AnUpdate());

        var json = await client.GetStringAsync(new Uri($"/items/{id}/versions", UriKind.Relative));

        Assert.DoesNotContain("dek", json, StringComparison.OrdinalIgnoreCase);
        Assert.DoesNotContain("cipher", json, StringComparison.OrdinalIgnoreCase);
        Assert.DoesNotContain("nonce", json, StringComparison.OrdinalIgnoreCase);
    }

    [Fact]
    public async Task Unauthenticated_callers_reach_no_history_route()
    {
        using var anonymous = factory.CreateClient();
        var id = Guid.CreateVersion7();

        Assert.Equal(
            HttpStatusCode.Unauthorized,
            (await anonymous.GetAsync(new Uri($"/items/{id}/versions", UriKind.Relative))).StatusCode);
        Assert.Equal(
            HttpStatusCode.Unauthorized,
            (await anonymous.PostAsync(new Uri($"/items/{id}/versions/1/restore", UriKind.Relative), null)).StatusCode);
        Assert.Equal(
            HttpStatusCode.Unauthorized,
            (await anonymous.PutAsJsonAsync(new Uri($"/items/{id}", UriKind.Relative), AnUpdate())).StatusCode);
    }

    [Fact]
    public async Task An_edit_with_a_wrong_sized_dek_is_refused()
    {
        using var client = ClientFor($"history-{Guid.NewGuid()}");
        var id = await CreateAsync(client);

        var response = await client.PutAsJsonAsync(new Uri($"/items/{id}", UriKind.Relative), new UpdateSecretRequest
        {
            Title = "Bank",
            Ciphertext = RandomNumberGenerator.GetBytes(48),
            Nonce = RandomNumberGenerator.GetBytes(Item.NonceLength),
            Dek = RandomNumberGenerator.GetBytes(16), // AES-128, not AES-256.
        });

        Assert.Equal(HttpStatusCode.BadRequest, response.StatusCode);
    }
}

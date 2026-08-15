using System.Net;
using System.Net.Http.Headers;
using System.Net.Http.Json;
using System.Security.Cryptography;
using Cryptum.Api.Contracts;
using Cryptum.Domain;

namespace Cryptum.IntegrationTests;

/// <summary>
/// Account deletion over the production JWT handler (plan task 4.1).
/// </summary>
/// <remarks>
/// The most destructive route in the API, and the only one whose effects cannot
/// be undone by any means. Its own factory: these tests destroy KEKs, and a
/// shared key wrapper would let that damage reach unrelated tests.
/// </remarks>
public sealed class AccountEndpointTests : IDisposable
{
    private readonly CryptumApiFactory factory = new();

    private HttpClient ClientFor(string subject)
    {
        var client = factory.CreateClient();
        client.DefaultRequestHeaders.Authorization =
            new AuthenticationHeaderValue("Bearer", TestTokens.For(factory, subject));
        return client;
    }

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
    public async Task Deleting_an_account_makes_its_items_unreachable()
    {
        using var client = ClientFor($"deleter-{Guid.NewGuid()}");
        var id = await CreateAsync(client);

        var deleted = await client.DeleteAsync(new Uri("/account", UriKind.Relative));
        Assert.Equal(HttpStatusCode.NoContent, deleted.StatusCode);

        Assert.Equal(
            HttpStatusCode.NotFound,
            (await client.GetAsync(new Uri($"/items/{id}", UriKind.Relative))).StatusCode);

        var remaining = await client.GetFromJsonAsync<List<ItemSummaryResponse>>(
            new Uri("/items", UriKind.Relative));
        Assert.Empty(remaining!);
    }

    [Fact]
    public async Task Deleting_one_account_does_not_touch_another()
    {
        using var victim = ClientFor($"victim-{Guid.NewGuid()}");
        using var deleter = ClientFor($"deleter-{Guid.NewGuid()}");

        var victimsItem = await CreateAsync(victim);
        await CreateAsync(deleter);

        Assert.Equal(
            HttpStatusCode.NoContent,
            (await deleter.DeleteAsync(new Uri("/account", UriKind.Relative))).StatusCode);

        // Still readable, DEK and all — the shred was scoped to one KEK.
        var stillThere = await victim.GetFromJsonAsync<ItemResponse>(
            new Uri($"/items/{victimsItem}", UriKind.Relative));
        Assert.NotNull(stillThere);
        Assert.NotEmpty(stillThere.Dek);
    }

    [Fact]
    public async Task Repeating_the_delete_is_not_an_error()
    {
        using var client = ClientFor($"deleter-{Guid.NewGuid()}");
        await CreateAsync(client);

        Assert.Equal(
            HttpStatusCode.NoContent,
            (await client.DeleteAsync(new Uri("/account", UriKind.Relative))).StatusCode);
        Assert.Equal(
            HttpStatusCode.NoContent,
            (await client.DeleteAsync(new Uri("/account", UriKind.Relative))).StatusCode);
    }

    [Fact]
    public async Task A_deleted_account_can_start_over_without_recovering_anything()
    {
        // The access token outlives the account, so this request happens whether
        // or not it is designed for. Before the User row was removed on delete,
        // provisioning was skipped and the missing KEK surfaced as a 500.
        using var client = ClientFor($"returner-{Guid.NewGuid()}");
        var oldId = await CreateAsync(client);

        Assert.Equal(
            HttpStatusCode.NoContent,
            (await client.DeleteAsync(new Uri("/account", UriKind.Relative))).StatusCode);

        var newId = await CreateAsync(client);
        Assert.NotEqual(oldId, newId);

        // The fresh vault is usable...
        var fresh = await client.GetFromJsonAsync<ItemResponse>(new Uri($"/items/{newId}", UriKind.Relative));
        Assert.NotNull(fresh);

        // ...and the shred still holds: a new KEK cannot resurrect old ciphertext.
        Assert.Equal(
            HttpStatusCode.NotFound,
            (await client.GetAsync(new Uri($"/items/{oldId}", UriKind.Relative))).StatusCode);
    }

    [Fact]
    public async Task An_unauthenticated_caller_cannot_delete_anything()
    {
        using var anonymous = factory.CreateClient();

        var response = await anonymous.DeleteAsync(new Uri("/account", UriKind.Relative));

        Assert.Equal(HttpStatusCode.Unauthorized, response.StatusCode);
    }

    public void Dispose() => factory.Dispose();
}

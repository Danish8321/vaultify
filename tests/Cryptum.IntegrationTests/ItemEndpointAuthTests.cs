using System.Net;
using System.Net.Http.Headers;
using System.Net.Http.Json;
using System.Security.Cryptography;
using Cryptum.Api.Contracts;
using Cryptum.Domain;

namespace Cryptum.IntegrationTests;

/// <summary>
/// Authentication and authorization at the HTTP boundary, against the real JWT
/// handler. These are the abuse cases, not the happy path.
/// </summary>
public sealed class ItemEndpointAuthTests : IClassFixture<CryptumApiFactory>
{
    private readonly CryptumApiFactory factory;

    public ItemEndpointAuthTests(CryptumApiFactory factory) => this.factory = factory;

    [Fact]
    public async Task Unauthenticated_request_is_rejected()
    {
        using var client = factory.CreateClient();

        var response = await client.GetAsync(new Uri("/items", UriKind.Relative));

        Assert.Equal(HttpStatusCode.Unauthorized, response.StatusCode);
    }

    [Fact]
    public async Task Token_with_a_tampered_signature_is_rejected()
    {
        var forged = TestTokens.WithBrokenSignature(TestTokens.For(factory, "alice"));
        using var client = ClientWith(forged);

        var response = await client.GetAsync(new Uri("/items", UriKind.Relative));

        Assert.Equal(HttpStatusCode.Unauthorized, response.StatusCode);
    }

    [Fact]
    public async Task Token_for_another_audience_is_rejected()
    {
        using var client = ClientWith(TestTokens.For(factory, "alice", audience: "some-other-api"));

        var response = await client.GetAsync(new Uri("/items", UriKind.Relative));

        Assert.Equal(HttpStatusCode.Unauthorized, response.StatusCode);
    }

    [Fact]
    public async Task Token_from_another_issuer_is_rejected()
    {
        using var client = ClientWith(TestTokens.For(factory, "alice", issuer: "https://attacker.invalid/"));

        var response = await client.GetAsync(new Uri("/items", UriKind.Relative));

        Assert.Equal(HttpStatusCode.Unauthorized, response.StatusCode);
    }

    [Fact]
    public async Task Expired_token_is_rejected_with_no_clock_skew_grace()
    {
        // Expired by well under the five-minute default skew: this fails only
        // because ClockSkew is TimeSpan.Zero (ADR-0004).
        using var client = ClientWith(TestTokens.For(factory, "alice", lifetime: TimeSpan.FromSeconds(-30)));

        var response = await client.GetAsync(new Uri("/items", UriKind.Relative));

        Assert.Equal(HttpStatusCode.Unauthorized, response.StatusCode);
    }

    [Fact]
    public async Task An_item_created_by_one_user_is_invisible_to_another()
    {
        using var alice = ClientWith(TestTokens.For(factory, $"alice-{Guid.NewGuid()}"));
        using var mallory = ClientWith(TestTokens.For(factory, $"mallory-{Guid.NewGuid()}"));

        var created = await alice.PostAsJsonAsync(new Uri("/items", UriKind.Relative), NewSecret());
        Assert.Equal(HttpStatusCode.Created, created.StatusCode);

        var body = await created.Content.ReadFromJsonAsync<CreatedItem>();
        Assert.NotNull(body);
        var id = body.Id;

        var byOwner = await alice.GetAsync(new Uri($"/items/{id}", UriKind.Relative));
        Assert.Equal(HttpStatusCode.OK, byOwner.StatusCode);

        // 404, not 403: telling Mallory the Item exists would let her enumerate
        // valid Item ids.
        var byStranger = await mallory.GetAsync(new Uri($"/items/{id}", UriKind.Relative));
        Assert.Equal(HttpStatusCode.NotFound, byStranger.StatusCode);

        var strangerList = await mallory.GetFromJsonAsync<List<ItemSummaryResponse>>(new Uri("/items", UriKind.Relative));
        Assert.DoesNotContain(strangerList ?? [], s => s.Id == id);
    }

    [Fact]
    public async Task Create_rejects_a_nonce_of_the_wrong_length()
    {
        using var client = ClientWith(TestTokens.For(factory, $"alice-{Guid.NewGuid()}"));

        var request = NewSecret() with { Nonce = RandomNumberGenerator.GetBytes(Item.NonceLength - 1) };
        var response = await client.PostAsJsonAsync(new Uri("/items", UriKind.Relative), request);

        Assert.Equal(HttpStatusCode.BadRequest, response.StatusCode);
    }

    [Fact]
    public async Task Create_rejects_a_dek_that_is_not_aes_256_sized()
    {
        using var client = ClientWith(TestTokens.For(factory, $"alice-{Guid.NewGuid()}"));

        var request = NewSecret() with { Dek = RandomNumberGenerator.GetBytes(16) };
        var response = await client.PostAsJsonAsync(new Uri("/items", UriKind.Relative), request);

        Assert.Equal(HttpStatusCode.BadRequest, response.StatusCode);
    }

    private static CreateSecretRequest NewSecret() => new()
    {
        Title = "GitHub",
        Ciphertext = RandomNumberGenerator.GetBytes(64),
        Nonce = RandomNumberGenerator.GetBytes(Item.NonceLength),
        Dek = RandomNumberGenerator.GetBytes(CreateSecretRequest.MinDekBytes),
    };

    private HttpClient ClientWith(string token)
    {
        var client = factory.CreateClient();
        client.DefaultRequestHeaders.Authorization = new AuthenticationHeaderValue("Bearer", token);
        return client;
    }

    private sealed record CreatedItem(Guid Id);
}

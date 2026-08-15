using System.Net;
using System.Net.Http.Headers;
using System.Net.Http.Json;
using System.Security.Cryptography;
using Cryptum.Api.Contracts;
using Cryptum.Domain;

namespace Cryptum.IntegrationTests;

/// <summary>
/// Proves the unwrap bucket is a separate budget from general CRUD (plan task 2.8).
/// </summary>
/// <remarks>
/// Its own factory, not the shared fixture: these tests deliberately exhaust a
/// limiter, and limiter state is process-wide for the host.
/// </remarks>
public sealed class RateLimitTests : IDisposable
{
    private readonly CryptumApiFactory factory = new();

    [Fact]
    public async Task Exhausting_the_unwrap_budget_does_not_stop_ordinary_crud()
    {
        // A distinct subject per test run: limits partition by caller identity,
        // so a fresh identity is a fresh budget and these requests cannot be
        // starved by, or starve, any other test.
        using var client = ClientFor($"rate-limited-{Guid.NewGuid()}");

        var id = await CreateSecretAsync(client);

        var sawTooManyRequests = false;
        for (var attempt = 0; attempt < UnwrapPermitLimit + 5; attempt++)
        {
            var response = await client.GetAsync(new Uri($"/items/{id}", UriKind.Relative));

            if (response.StatusCode == HttpStatusCode.TooManyRequests)
            {
                sawTooManyRequests = true;
                break;
            }

            Assert.Equal(HttpStatusCode.OK, response.StatusCode);
        }

        Assert.True(sawTooManyRequests, $"the unwrap route served more than {UnwrapPermitLimit} requests in one window");

        // The point of the test: the read budget is spent, but the general
        // budget is not. If both routes shared one bucket this would be a 429,
        // and the stricter unwrap limit would be doing nothing.
        var list = await client.GetAsync(new Uri("/items", UriKind.Relative));
        Assert.Equal(HttpStatusCode.OK, list.StatusCode);
    }

    /// <summary>Mirrors the unwrap policy's permit limit in Program.cs.</summary>
    private const int UnwrapPermitLimit = 20;

    private static async Task<Guid> CreateSecretAsync(HttpClient client)
    {
        var response = await client.PostAsJsonAsync(new Uri("/items", UriKind.Relative), new CreateSecretRequest
        {
            Title = "GitHub",
            Ciphertext = RandomNumberGenerator.GetBytes(64),
            Nonce = RandomNumberGenerator.GetBytes(Item.NonceLength),
            Dek = RandomNumberGenerator.GetBytes(CreateSecretRequest.MinDekBytes),
        });

        Assert.Equal(HttpStatusCode.Created, response.StatusCode);

        var body = await response.Content.ReadFromJsonAsync<CreatedItemResponse>();
        Assert.NotNull(body);
        return body.Id;
    }

    private HttpClient ClientFor(string subject)
    {
        var client = factory.CreateClient();
        client.DefaultRequestHeaders.Authorization =
            new AuthenticationHeaderValue("Bearer", TestTokens.For(factory, subject));
        return client;
    }

    public void Dispose() => factory.Dispose();
}

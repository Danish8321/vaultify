using System.Net;
using System.Net.Http.Headers;
using System.Net.Http.Json;
using System.Security.Cryptography;
using Cryptum.Api.Contracts;
using Cryptum.Domain;

namespace Cryptum.IntegrationTests;

/// <summary>
/// Key material must never reach the logs (security-requirements, ticket 03).
/// </summary>
/// <remarks>
/// Its own factory so the captured log contains only this test's traffic.
///
/// The DEK crossing the network on every read is the accepted cost of being
/// server-blind rather than end-to-end encrypted (ADR-0001). That trade holds
/// only while the DEK's exposure stays short-lived and bounded. A DEK in a log
/// is a DEK at rest, in a system with a different access model and a far longer
/// retention period.
/// </remarks>
public sealed class KeyMaterialLoggingTests : IDisposable
{
    private readonly CryptumApiFactory factory = new();

    [Fact]
    public async Task A_create_then_read_cycle_never_logs_the_dek()
    {
        using var client = ClientFor($"logging-{Guid.NewGuid()}");

        var dek = RandomNumberGenerator.GetBytes(CreateSecretRequest.MinDekBytes);
        var ciphertext = RandomNumberGenerator.GetBytes(64);

        var created = await client.PostAsJsonAsync(new Uri("/items", UriKind.Relative), new CreateSecretRequest
        {
            Title = "GitHub",
            Ciphertext = ciphertext,
            Nonce = RandomNumberGenerator.GetBytes(Item.NonceLength),
            Dek = dek,
        });
        Assert.Equal(HttpStatusCode.Created, created.StatusCode);

        var body = await created.Content.ReadFromJsonAsync<CreatedItemResponse>();
        Assert.NotNull(body);

        var read = await client.GetAsync(new Uri($"/items/{body.Id}", UriKind.Relative));
        Assert.Equal(HttpStatusCode.OK, read.StatusCode);

        // Search for the key material in the encodings it would realistically
        // appear in: base64 is how JSON serialises a byte[], hex is how a
        // debugger or a hand-rolled dump would render it.
        var log = string.Join('\n', factory.Logs.Entries);

        Assert.DoesNotContain(Convert.ToBase64String(dek), log, StringComparison.Ordinal);
        Assert.DoesNotContain(Convert.ToHexString(dek), log, StringComparison.OrdinalIgnoreCase);
    }

    [Fact]
    public async Task A_create_then_read_cycle_never_logs_the_ciphertext()
    {
        using var client = ClientFor($"logging-{Guid.NewGuid()}");

        var ciphertext = RandomNumberGenerator.GetBytes(64);

        var created = await client.PostAsJsonAsync(new Uri("/items", UriKind.Relative), new CreateSecretRequest
        {
            Title = "GitHub",
            Ciphertext = ciphertext,
            Nonce = RandomNumberGenerator.GetBytes(Item.NonceLength),
            Dek = RandomNumberGenerator.GetBytes(CreateSecretRequest.MinDekBytes),
        });
        Assert.Equal(HttpStatusCode.Created, created.StatusCode);

        var log = string.Join('\n', factory.Logs.Entries);

        Assert.DoesNotContain(Convert.ToBase64String(ciphertext), log, StringComparison.Ordinal);
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

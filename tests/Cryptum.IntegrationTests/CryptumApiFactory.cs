using System.Security.Cryptography;
using Cryptum.Data;
using Cryptum.Domain;
using Cryptum.TestSupport;
using Microsoft.AspNetCore.Authentication.JwtBearer;
using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.Mvc.Testing;
using Microsoft.Data.Sqlite;
using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Infrastructure;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.DependencyInjection.Extensions;
using Microsoft.Extensions.Logging;
using Microsoft.Extensions.Options;
using Microsoft.IdentityModel.Tokens;

namespace Cryptum.IntegrationTests;

/// <summary>
/// Hosts the real API against SQLite and an in-process KEK store.
/// </summary>
/// <remarks>
/// The one thing deliberately NOT replaced is JWT validation. A fake
/// authentication handler would make every authorization test vacuous — it would
/// prove the endpoint reads a claim, not that a forged token is rejected. So the
/// production <c>JwtBearer</c> handler stays, and only the signing key it trusts
/// is swapped for one the test owns. Signature, issuer, audience and lifetime
/// checks all run exactly as they will in production.
/// </remarks>
public sealed class CryptumApiFactory : WebApplicationFactory<Program>
{
    public const string Issuer = "https://test.cryptum.invalid/";
    public const string Audience = "cryptum-api-tests";

    private readonly SqliteConnection connection = CreateOpenConnection();

    public RsaSecurityKey SigningKey { get; } = new(RSA.Create(2048)) { KeyId = "test-key" };

    public InMemoryKeyWrapper KeyWrapper { get; } = new();

    public FakeBlobStore BlobStore { get; } = new();

    /// <summary>Everything the host logged during the test.</summary>
    public CapturingLoggerProvider Logs { get; } = new();

    protected override void ConfigureWebHost(IWebHostBuilder builder)
    {
        ArgumentNullException.ThrowIfNull(builder);

        builder.UseEnvironment("Development");

        builder.ConfigureServices(services =>
        {
            // All three: EF 10 registers the provider through
            // IDbContextOptionsConfiguration, so dropping only the options
            // leaves SqlServer registered alongside SQLite.
            services.RemoveAll<DbContextOptions<CryptumDbContext>>();
            services.RemoveAll<IDbContextOptionsConfiguration<CryptumDbContext>>();
            services.RemoveAll<CryptumDbContext>();
            services.AddDbContext<CryptumDbContext>(options => options.UseSqlite(connection));

            services.RemoveAll<IKeyWrapper>();
            services.AddSingleton<IKeyWrapper>(KeyWrapper);

            services.RemoveAll<IBlobStore>();
            services.RemoveAll<Azure.Storage.Blobs.BlobServiceClient>();
            services.AddSingleton<IBlobStore>(BlobStore);

            services.AddSingleton<ILoggerProvider>(Logs);

            services.AddSingleton<IPostConfigureOptions<JwtBearerOptions>>(
                new TestSigningKeyConfiguration(SigningKey));

            using var scope = services.BuildServiceProvider().CreateScope();
            scope.ServiceProvider.GetRequiredService<CryptumDbContext>().Database.EnsureCreated();
        });
    }

    private static SqliteConnection CreateOpenConnection()
    {
        // Shared in-memory database, kept alive by holding this connection open
        // for the lifetime of the factory.
        var connection = new SqliteConnection("Filename=:memory:");
        connection.Open();
        return connection;
    }

    protected override void Dispose(bool disposing)
    {
        if (disposing)
        {
            connection.Dispose();
            KeyWrapper.Dispose();
            SigningKey.Rsa.Dispose();
        }

        base.Dispose(disposing);
    }

    /// <summary>
    /// Points token validation at the test signing key, leaving every validation
    /// switch the production setup turned on exactly as it was.
    /// </summary>
    private sealed class TestSigningKeyConfiguration(RsaSecurityKey signingKey)
        : IPostConfigureOptions<JwtBearerOptions>
    {
        public void PostConfigure(string? name, JwtBearerOptions options)
        {
            // No Authority: there is no discovery endpoint to fetch metadata from.
            options.Authority = null;
            options.MetadataAddress = string.Empty;
            options.RequireHttpsMetadata = false;

            options.TokenValidationParameters.ValidIssuer = Issuer;
            options.TokenValidationParameters.ValidAudience = Audience;
            options.TokenValidationParameters.IssuerSigningKey = signingKey;
        }
    }
}

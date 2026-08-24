using System.Security.Claims;
using System.Threading.RateLimiting;
using Azure.Security.KeyVault.Keys;
using Cryptum.Api.Auth;
using Cryptum.Api.Endpoints;
using Cryptum.Data;
using Cryptum.Domain;
using Cryptum.Infrastructure;
using Microsoft.EntityFrameworkCore;

var builder = WebApplication.CreateBuilder(args);

builder.Services.AddOpenApi();
builder.Services.AddCryptumAuthentication(builder.Configuration);

builder.Services.AddDbContext<CryptumDbContext>(options =>
    options.UseSqlServer(builder.Configuration.GetConnectionString("Cryptum")));

builder.Services.AddScoped<IItemRepository, ItemRepository>();
builder.Services.AddScoped<IAuditLog, AuditLog>();
builder.Services.AddScoped<IUserRepository, UserRepository>();
builder.Services.AddScoped<UserProvisioning>();
builder.Services.AddScoped<VaultService>();

// The purge runs in Cryptum.Worker, not here — no API request triggers it. It is
// registered in this host so the integration tests can exercise it against the
// same database the endpoints use, which is the only place its batching and
// resumability can actually be falsified.
builder.Services.AddScoped<IPurgeStore, PurgeStore>();
builder.Services.AddScoped<PurgeService>();
builder.Services.AddSingleton(TimeProvider.System);

// Managed Identity only — no client secret exists to leak (ADR-0002).
builder.Services.AddSingleton<Azure.Core.TokenCredential>(_ => new Azure.Identity.DefaultAzureCredential());
builder.Services.AddSingleton(sp => new KeyClient(
    new Uri(builder.Configuration["KeyVault:Uri"] ?? throw new InvalidOperationException("KeyVault:Uri is not configured.")),
    sp.GetRequiredService<Azure.Core.TokenCredential>()));
builder.Services.AddSingleton<IKeyWrapper, KeyVaultKeyWrapper>();

builder.Services.AddSingleton(sp => new Azure.Storage.Blobs.BlobServiceClient(
    new Uri(builder.Configuration["BlobStorage:Uri"] ?? throw new InvalidOperationException("BlobStorage:Uri is not configured.")),
    sp.GetRequiredService<Azure.Core.TokenCredential>()));
builder.Services.AddSingleton<IBlobStore>(sp => new BlobStore(
    sp.GetRequiredService<Azure.Storage.Blobs.BlobServiceClient>(),
    builder.Configuration["BlobStorage:Container"] ?? throw new InvalidOperationException("BlobStorage:Container is not configured.")));

builder.Services.AddRateLimiter(options =>
{
    options.RejectionStatusCode = StatusCodes.Status429TooManyRequests;

    // Partitioned by caller, not by IP: a shared NAT must not let one user
    // exhaust another's budget, and an attacker changing IP must not get a
    // fresh one.
    options.GlobalLimiter = PartitionedRateLimiter.Create<HttpContext, string>(
        context => RateLimitPartition.GetFixedWindowLimiter(
            PartitionKey(context),
            _ => new FixedWindowRateLimiterOptions
            {
                PermitLimit = 100,
                Window = TimeSpan.FromMinutes(1),
            }));

    // Stricter than general CRUD: each permitted request costs one Key Vault
    // unwrap, which is the operation ADR-0002 says to watch.
    options.AddPolicy(ItemEndpoints.UnwrapPolicy, context =>
        RateLimitPartition.GetFixedWindowLimiter(
            PartitionKey(context),
            _ => new FixedWindowRateLimiterOptions
            {
                PermitLimit = 20,
                Window = TimeSpan.FromMinutes(1),
            }));
});

var app = builder.Build();

if (app.Environment.IsDevelopment())
{
    app.MapOpenApi();
}

app.UseHttpsRedirection();

// HSTS is set unconditionally in non-development: downgrade to plain HTTP would
// expose the plaintext DEK that every read returns.
if (!app.Environment.IsDevelopment())
{
    app.UseHsts();
}

app.UseAuthentication();
app.UseAuthorization();
app.UseRateLimiter();

// After authentication (it needs the resolved identity) and before the
// endpoints, so a new User's first request provisions rather than 500s.
app.UseMiddleware<UserProvisioningMiddleware>();

app.MapGet("/health", () => Results.Ok(new { status = "ok" })).AllowAnonymous();
app.MapItemEndpoints();
app.MapAccountEndpoints();

await app.RunAsync();

static string PartitionKey(HttpContext context) =>
    CallerIdentity.TryResolve(context.User, out var owner)
        ? owner.Value.ToString("N", System.Globalization.CultureInfo.InvariantCulture)
        : $"anon:{context.Connection.RemoteIpAddress}";

// Exposed so WebApplicationFactory can bootstrap the API in integration tests.
public partial class Program;

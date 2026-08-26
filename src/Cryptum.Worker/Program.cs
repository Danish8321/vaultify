using Cryptum.Data;
using Cryptum.Domain;
using Cryptum.Infrastructure;
using Cryptum.Worker;
using Microsoft.EntityFrameworkCore;

var builder = Host.CreateApplicationBuilder(args);

builder.Services.Configure<PurgeOptions>(builder.Configuration.GetSection("Purge"));
builder.Services.AddSingleton(TimeProvider.System);

// Managed Identity only — no client secret exists to leak (ADR-0002), same as the API.
builder.Services.AddSingleton<Azure.Core.TokenCredential>(_ => new Azure.Identity.DefaultAzureCredential());
builder.Services.AddSingleton(sp => new Azure.Storage.Blobs.BlobServiceClient(
    new Uri(builder.Configuration["BlobStorage:Uri"] ?? throw new InvalidOperationException("BlobStorage:Uri is not configured.")),
    sp.GetRequiredService<Azure.Core.TokenCredential>()));
builder.Services.AddSingleton<IBlobStore>(sp => new BlobStore(
    sp.GetRequiredService<Azure.Storage.Blobs.BlobServiceClient>(),
    builder.Configuration["BlobStorage:Container"] ?? throw new InvalidOperationException("BlobStorage:Container is not configured.")));

// Azure AD authentication, no connection-string secret — same rule as the API
// (security-requirements). The worker deletes rows; it is the last place that
// should be holding a password.
builder.Services.AddDbContext<CryptumDbContext>(options =>
    options.UseSqlServer(builder.Configuration.GetConnectionString("Cryptum")));

builder.Services.AddScoped<IPurgeStore, PurgeStore>();
builder.Services.AddScoped<PurgeService>();
builder.Services.AddHostedService<Worker>();

var host = builder.Build();
host.Run();

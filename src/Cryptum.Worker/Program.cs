using Cryptum.Data;
using Cryptum.Domain;
using Cryptum.Worker;
using Microsoft.EntityFrameworkCore;

var builder = Host.CreateApplicationBuilder(args);

builder.Services.Configure<PurgeOptions>(builder.Configuration.GetSection("Purge"));
builder.Services.AddSingleton(TimeProvider.System);

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

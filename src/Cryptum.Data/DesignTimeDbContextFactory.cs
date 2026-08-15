using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Design;

namespace Cryptum.Data;

/// <summary>
/// Builds a context for <c>dotnet ef</c> only (via .claude/scripts/schema.sh).
/// </summary>
/// <remarks>
/// Deliberately uses a placeholder connection string: generating a migration
/// reads the model, never the database. Nothing here is a runtime credential
/// path, and no real connection string belongs in source (see
/// docs/security-requirements.md — the running app authenticates to Azure SQL
/// with its Managed Identity).
/// </remarks>
internal sealed class DesignTimeDbContextFactory : IDesignTimeDbContextFactory<CryptumDbContext>
{
    public CryptumDbContext CreateDbContext(string[] args)
    {
        var options = new DbContextOptionsBuilder<CryptumDbContext>()
            .UseSqlServer("Server=(localdb)\\design-time-only;Database=Cryptum;Trusted_Connection=True;")
            .Options;

        return new CryptumDbContext(options);
    }
}

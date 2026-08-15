using Cryptum.Domain;

namespace Cryptum.Data;

/// <summary>
/// Writes audit entries. Insert only — see <see cref="IAuditLog"/>.
/// </summary>
/// <remarks>
/// Saved immediately rather than enlisting in the caller's unit of work: an
/// action that happened must be recorded even if the surrounding operation
/// later fails, and a rolled-back audit row would erase exactly the evidence
/// an incident review needs.
/// </remarks>
public sealed class AuditLog(CryptumDbContext db) : IAuditLog
{
    public async Task RecordAsync(AuditEntry entry, CancellationToken cancellationToken = default)
    {
        await db.AuditEntries.AddAsync(entry, cancellationToken).ConfigureAwait(false);
        await db.SaveChangesAsync(cancellationToken).ConfigureAwait(false);
    }
}

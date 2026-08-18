using Cryptum.Domain;
using Microsoft.EntityFrameworkCore;

namespace Cryptum.Data;

/// <summary>
/// Purges soft-deleted rows in committed batches.
/// </summary>
/// <remarks>
/// Every query here uses <c>IgnoreQueryFilters</c>, because the global filter
/// hides exactly the rows this class exists to delete. That is the one place in
/// the codebase where bypassing the filter is correct rather than suspicious,
/// which is why it lives here and nowhere else.
/// </remarks>
public sealed class PurgeStore(CryptumDbContext db) : IPurgeStore
{
    public async Task<PurgeResult> PurgeBatchAsync(
        DateTimeOffset deletedBefore,
        int batchSize,
        CancellationToken cancellationToken = default)
    {
        ArgumentOutOfRangeException.ThrowIfLessThan(batchSize, 1);

        var ids = await db.Items
            .IgnoreQueryFilters()
            .Where(i => i.DeletedAt != null && i.DeletedAt <= deletedBefore)
            .OrderBy(i => i.DeletedAt)
            .ThenBy(i => i.Id)
            .Select(i => i.Id)
            .Take(batchSize)
            .ToListAsync(cancellationToken)
            .ConfigureAwait(false);

        if (ids.Count == 0)
        {
            return new PurgeResult(0, 0);
        }

        // History goes first. If the process dies between these two statements,
        // what survives is an Item still marked deleted with fewer versions —
        // which the next run picks up and finishes. The reverse order would
        // orphan version rows behind a deleted parent, where nothing would ever
        // look for them again.
        var versions = await db.ItemVersions
            .IgnoreQueryFilters()
            .Where(v => ids.Contains(v.ItemId))
            .ExecuteDeleteAsync(cancellationToken)
            .ConfigureAwait(false);

        var items = await db.Items
            .IgnoreQueryFilters()
            .Where(i => ids.Contains(i.Id))
            .ExecuteDeleteAsync(cancellationToken)
            .ConfigureAwait(false);

        return new PurgeResult(items, versions);
    }
}

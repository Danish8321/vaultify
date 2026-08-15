using Cryptum.Domain;
using Microsoft.EntityFrameworkCore;

namespace Cryptum.Data;

/// <summary>
/// EF Core implementation of <see cref="IItemRepository"/>.
/// </summary>
/// <remarks>
/// Every read carries the owner as a predicate inside the LINQ query, so the
/// database — not a C# branch that could be forgotten, reordered, or short-
/// circuited — is what enforces ownership. A row belonging to someone else is
/// never materialized in the first place, which is why this is a defense
/// against IDOR rather than a check on top of one.
/// </remarks>
public sealed class ItemRepository(CryptumDbContext db) : IItemRepository
{
    public async Task<Item?> FindAsync(UserId owner, ItemId id, CancellationToken cancellationToken = default) =>
        await db.Items
            .Where(i => i.Owner == owner && i.Id == id)
            .SingleOrDefaultAsync(cancellationToken)
            .ConfigureAwait(false);

    public async Task<IReadOnlyList<ItemSummary>> ListAsync(UserId owner, CancellationToken cancellationToken = default)
    {
        // Projected before materializing: ciphertext and wrapped DEKs are never
        // fetched for a list view, so a list response cannot leak them even if
        // serialization is later changed carelessly.
        List<ItemSummary> summaries = await db.Items
            .Where(i => i.Owner == owner)
            .OrderByDescending(i => i.UpdatedAt)
            .Select(i => new ItemSummary(i.Id, i.Kind, i.Title, i.UpdatedAt))
            .ToListAsync(cancellationToken)
            .ConfigureAwait(false);

        return summaries;
    }

    public async Task AddAsync(Item item, CancellationToken cancellationToken = default) =>
        await db.Items.AddAsync(item, cancellationToken).ConfigureAwait(false);

    public async Task SaveChangesAsync(CancellationToken cancellationToken = default) =>
        await db.SaveChangesAsync(cancellationToken).ConfigureAwait(false);

    // The global filter already hides rows that are soft-deleted, which is exactly
    // the semantics wanted here: re-running account deletion must not rewrite the
    // original DeletedAt stamp that the purge job schedules from. So no
    // IgnoreQueryFilters().
    public async Task<int> SoftDeleteAllAsync(UserId owner, DateTimeOffset now, CancellationToken cancellationToken = default)
    {
        // History is soft-deleted in the same call rather than in a separate one
        // the caller has to remember. The KEK is already destroyed by this point,
        // so these rows are unreadable either way — but leaving them visible would
        // still expose edit timestamps and revision counts.
        await db.ItemVersions
            .Where(v => v.Owner == owner)
            .ExecuteUpdateAsync(s => s.SetProperty(v => v.DeletedAt, now), cancellationToken)
            .ConfigureAwait(false);

        // The Item count is what is returned: it is the count callers reason
        // about, and it stays stable as retention policy changes the version count.
        return await db.Items
            .Where(i => i.Owner == owner)
            .ExecuteUpdateAsync(s => s.SetProperty(i => i.DeletedAt, now), cancellationToken)
            .ConfigureAwait(false);
    }

    public async Task AddVersionAsync(ItemVersion version, CancellationToken cancellationToken = default) =>
        await db.ItemVersions.AddAsync(version, cancellationToken).ConfigureAwait(false);

    public async Task<ItemVersion?> FindVersionAsync(
        UserId owner, ItemId id, int versionNumber, CancellationToken cancellationToken = default) =>
        await db.ItemVersions
            .Where(v => v.Owner == owner && v.ItemId == id && v.VersionNumber == versionNumber)
            .SingleOrDefaultAsync(cancellationToken)
            .ConfigureAwait(false);

    public async Task<IReadOnlyList<ItemVersionSummary>> ListVersionsAsync(
        UserId owner, ItemId id, CancellationToken cancellationToken = default)
    {
        List<ItemVersionSummary> summaries = await db.ItemVersions
            .Where(v => v.Owner == owner && v.ItemId == id)
            .OrderByDescending(v => v.VersionNumber)
            .Select(v => new ItemVersionSummary(v.VersionNumber, v.ArchivedAt))
            .ToListAsync(cancellationToken)
            .ConfigureAwait(false);

        return summaries;
    }

    public async Task<int> PruneVersionsAsync(
        UserId owner, ItemId id, CancellationToken cancellationToken = default)
    {
        // Hard delete, not soft: a pruned version is unreachable by policy, and
        // keeping the row would leave ciphertext the user believes is gone.
        // Selecting the survivors and deleting the complement — rather than
        // "delete where VersionNumber <= max - MaxRetained" — keeps this correct
        // when numbering has gaps from an earlier prune.
        var keep = db.ItemVersions
            .Where(v => v.Owner == owner && v.ItemId == id)
            .OrderByDescending(v => v.VersionNumber)
            .Take(ItemVersion.MaxRetained)
            .Select(v => v.VersionNumber);

        return await db.ItemVersions
            .Where(v => v.Owner == owner && v.ItemId == id && !keep.Contains(v.VersionNumber))
            .ExecuteDeleteAsync(cancellationToken)
            .ConfigureAwait(false);
    }
}

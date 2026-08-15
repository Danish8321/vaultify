namespace Cryptum.Domain;

/// <summary>
/// Owner-scoped access to Items.
/// </summary>
/// <remarks>
/// Every method takes the owner and filters on it inside the query. There is
/// deliberately no <c>GetById(ItemId)</c> overload: the unsafe call is not
/// merely discouraged, it is unwriteable. This is the primary defense against
/// IDOR, and it stands in for the per-user key-service RBAC that ADR-0002
/// consciously traded away.
/// </remarks>
public interface IItemRepository
{
    /// <summary>Returns the Item only if <paramref name="owner"/> owns it; otherwise null.</summary>
    Task<Item?> FindAsync(UserId owner, ItemId id, CancellationToken cancellationToken = default);

    /// <summary>Titles and metadata only — no ciphertext, no wrapped DEKs.</summary>
    Task<IReadOnlyList<ItemSummary>> ListAsync(UserId owner, CancellationToken cancellationToken = default);

    Task AddAsync(Item item, CancellationToken cancellationToken = default);

    Task SaveChangesAsync(CancellationToken cancellationToken = default);

    /// <summary>Soft-deletes every Item owned by the User, history included (ADR-0003).</summary>
    Task<int> SoftDeleteAllAsync(UserId owner, DateTimeOffset now, CancellationToken cancellationToken = default);

    Task AddVersionAsync(ItemVersion version, CancellationToken cancellationToken = default);

    /// <summary>Returns the archived version only if <paramref name="owner"/> owns it; otherwise null.</summary>
    Task<ItemVersion?> FindVersionAsync(
        UserId owner, ItemId id, int versionNumber, CancellationToken cancellationToken = default);

    /// <summary>Version metadata only — no ciphertext, no wrapped DEKs.</summary>
    Task<IReadOnlyList<ItemVersionSummary>> ListVersionsAsync(
        UserId owner, ItemId id, CancellationToken cancellationToken = default);

    /// <summary>
    /// Drops all but the newest <see cref="ItemVersion.MaxRetained"/> versions of an Item,
    /// so history cannot grow without bound. Returns the number removed.
    /// </summary>
    Task<int> PruneVersionsAsync(UserId owner, ItemId id, CancellationToken cancellationToken = default);
}

/// <summary>List-view projection. Carries no secret material by construction.</summary>
public sealed record ItemSummary(ItemId Id, ItemKind Kind, string Title, DateTimeOffset UpdatedAt);

/// <summary>History list-view projection. Carries no secret material by construction.</summary>
public sealed record ItemVersionSummary(int VersionNumber, DateTimeOffset ArchivedAt);

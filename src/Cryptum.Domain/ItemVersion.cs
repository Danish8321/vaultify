namespace Cryptum.Domain;

/// <summary>
/// Content an <see cref="Item"/> used to hold, displaced by an edit (ADR-0006).
/// </summary>
/// <remarks>
/// A version keeps the DEK it was encrypted under rather than being re-encrypted
/// under the Item's current one. That is what lets a restore be a metadata move:
/// the server never needs the plaintext, so history costs nothing in exposure
/// beyond the ciphertext it already stores.
///
/// <para>
/// <see cref="Owner"/> is denormalised from the Item so version queries carry the
/// same owner predicate as Item queries (see <see cref="IItemRepository"/>).
/// Reaching history through the Item alone would work, but it would make the
/// unsafe query writeable — and history must not become an IDOR bypass around
/// the Item-level check.
/// </para>
/// </remarks>
public sealed class ItemVersion
{
    /// <summary>
    /// How many versions of one Item are retained. Ciphertext the user can no
    /// longer reach is pure liability, so history is bounded rather than kept
    /// forever — but deep enough that a mistaken edit noticed a few edits later
    /// is still recoverable.
    /// </summary>
    public const int MaxRetained = 10;

    private ItemVersion() { } // EF Core.

    public ItemId ItemId { get; private init; }

    public UserId Owner { get; private init; }

    /// <summary>1 for the first content ever displaced, counting up in edit order.</summary>
    public int VersionNumber { get; private init; }

    public byte[] Ciphertext { get; private init; } = [];

    public byte[] WrappedDek { get; private init; } = [];

    public string KekVersion { get; private init; } = string.Empty;

    public byte[] Nonce { get; private init; } = [];

    /// <summary>When this content stopped being current — the moment of the edit.</summary>
    public DateTimeOffset ArchivedAt { get; private init; }

    /// <summary>Set when the account is deleted, mirroring <see cref="Item.DeletedAt"/> (ADR-0003).</summary>
    public DateTimeOffset? DeletedAt { get; private set; }

    /// <summary>Only <see cref="Item"/> archives content; there is no other legitimate caller.</summary>
    internal static ItemVersion Archive(Item item, int versionNumber, DateTimeOffset now) => new()
    {
        ItemId = item.Id,
        Owner = item.Owner,
        VersionNumber = versionNumber,
        Ciphertext = item.Ciphertext ?? [],
        WrappedDek = item.WrappedDek,
        KekVersion = item.KekVersion,
        Nonce = item.Nonce,
        ArchivedAt = now,
    };
}

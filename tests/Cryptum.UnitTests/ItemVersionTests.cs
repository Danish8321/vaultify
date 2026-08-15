using Cryptum.Domain;

namespace Cryptum.UnitTests;

/// <summary>
/// Edits must archive the content they displace (plan task 3.0, ADR-0006).
/// </summary>
/// <remarks>
/// The MVP overwrites in place, so a mistaken edit destroys the only copy. The
/// archive keeps its own DEK rather than re-encrypting under the current one:
/// restoring is then a metadata move, and no version's plaintext has to exist
/// on the server to bring it back.
/// </remarks>
public sealed class ItemVersionTests
{
    private static readonly DateTimeOffset Created = new(2026, 8, 15, 12, 0, 0, TimeSpan.Zero);
    private static readonly DateTimeOffset Edited = Created.AddHours(1);

    private static readonly UserId Alice = new(Guid.CreateVersion7());

    private static Item ASecret() => Item.CreateSecret(
        Alice, "Bank", [1, 2, 3], Nonce(1), new WrappedDek([9, 9], "kek-v1"), Created);

    private static byte[] Nonce(byte fill) => [.. Enumerable.Repeat(fill, Item.NonceLength)];

    [Fact]
    public void An_edit_archives_the_content_it_replaced()
    {
        var item = ASecret();

        var archived = item.ReplaceContent(
            "Bank", [4, 5, 6], Nonce(2), new WrappedDek([8, 8], "kek-v2"), Edited);

        // The archive holds the *old* content, not the new — the direction is the
        // whole point, and getting it backwards would still produce two rows.
        Assert.Equal<byte>([1, 2, 3], archived.Ciphertext);
        Assert.Equal(Nonce(1), archived.Nonce);
        Assert.Equal<byte>([9, 9], archived.WrappedDek);
        Assert.Equal("kek-v1", archived.KekVersion);
    }

    [Fact]
    public void An_archived_version_stays_bound_to_its_item_and_owner()
    {
        // The owner is denormalised onto the version so version queries can carry
        // the same owner predicate as Item queries. Without it, history would be
        // reachable by id alone — an IDOR bypass around the Item-level check.
        var item = ASecret();

        var archived = item.ReplaceContent(
            "Bank", [4, 5, 6], Nonce(2), new WrappedDek([8, 8], "kek-v2"), Edited);

        Assert.Equal(item.Id, archived.ItemId);
        Assert.Equal(Alice, archived.Owner);
    }

    [Fact]
    public void Version_numbers_count_up_from_one_in_edit_order()
    {
        var item = ASecret();

        var first = item.ReplaceContent("Bank", [4], Nonce(2), new WrappedDek([8], "kek-v2"), Edited);
        var second = item.ReplaceContent("Bank", [5], Nonce(3), new WrappedDek([7], "kek-v3"), Edited.AddHours(1));

        Assert.Equal(1, first.VersionNumber);
        Assert.Equal(2, second.VersionNumber);
    }

    [Fact]
    public void The_item_itself_holds_the_new_content_after_an_edit()
    {
        // Guards the archive being built by *copying* rather than by moving: if
        // ReplaceContent archived and then forgot to update the Item, every
        // assertion above would still pass.
        var item = ASecret();

        item.ReplaceContent("Bank v2", [4, 5, 6], Nonce(2), new WrappedDek([8, 8], "kek-v2"), Edited);

        Assert.Equal("Bank v2", item.Title);
        Assert.Equal<byte>([4, 5, 6], item.Ciphertext!);
        Assert.Equal("kek-v2", item.KekVersion);
        Assert.Equal(Edited, item.UpdatedAt);
    }
}

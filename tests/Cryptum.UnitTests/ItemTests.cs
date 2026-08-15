using Cryptum.Domain;

namespace Cryptum.UnitTests;

public sealed class ItemTests
{
    private static readonly UserId Owner = new(Guid.CreateVersion7());
    private static readonly DateTimeOffset Now = new(2026, 8, 15, 12, 0, 0, TimeSpan.Zero);
    private static readonly WrappedDek Dek = new([1, 2, 3], "v1");

    private static byte[] ValidNonce() => new byte[Item.NonceLength];

    [Fact]
    public void CreateSecret_keeps_title_in_plaintext_and_content_as_ciphertext()
    {
        var item = Item.CreateSecret(Owner, "GitHub", [9, 9, 9], ValidNonce(), Dek, Now);

        Assert.Equal("GitHub", item.Title);
        Assert.Equal([9, 9, 9], item.Ciphertext);
        Assert.Equal(ItemKind.Secret, item.Kind);
        Assert.Null(item.DeletedAt);
    }

    [Theory]
    [InlineData(0)]
    [InlineData(11)]
    [InlineData(13)]
    [InlineData(16)]
    public void CreateSecret_rejects_any_nonce_that_is_not_96_bits(int length)
    {
        // AES-GCM is specified for a 96-bit nonce; other lengths are re-hashed
        // internally and silently weaken the construction, so they are refused
        // at the boundary rather than accepted and hoped about.
        var wrongSize = new byte[length];

        Assert.Throws<ArgumentException>(
            () => Item.CreateSecret(Owner, "t", [1], wrongSize, Dek, Now));
    }

    [Fact]
    public void CreateSecret_rejects_title_over_the_limit()
    {
        var tooLong = new string('x', Item.MaxTitleLength + 1);

        Assert.Throws<ArgumentException>(
            () => Item.CreateSecret(Owner, tooLong, [1], ValidNonce(), Dek, Now));
    }

    [Theory]
    [InlineData("")]
    [InlineData("   ")]
    public void CreateSecret_rejects_blank_title(string title)
    {
        Assert.Throws<ArgumentException>(
            () => Item.CreateSecret(Owner, title, [1], ValidNonce(), Dek, Now));
    }

    [Fact]
    public void ReplaceContent_keeps_identity_but_swaps_key_material()
    {
        // Identity must survive an edit so a versions table can attach to it
        // later (ADR-0006), while the DEK and nonce must not survive at all.
        var item = Item.CreateSecret(Owner, "old", [1], ValidNonce(), Dek, Now);
        var originalId = item.Id;
        var newNonce = new byte[Item.NonceLength];
        newNonce[0] = 7;
        var newDek = new WrappedDek([4, 5, 6], "v1");

        item.ReplaceContent("new", [2], newNonce, newDek, Now.AddMinutes(1));

        Assert.Equal(originalId, item.Id);
        Assert.Equal("new", item.Title);
        Assert.Equal([4, 5, 6], item.WrappedDek);
        Assert.Equal(newNonce, item.Nonce);
        Assert.Equal(Now.AddMinutes(1), item.UpdatedAt);
        Assert.Equal(Now, item.CreatedAt);
    }

    [Fact]
    public void CreateFile_stores_a_blob_pointer_and_no_inline_ciphertext()
    {
        var item = Item.CreateFile(Owner, "passport.pdf", "vault/abc123", ValidNonce(), Dek, Now);

        Assert.Equal(ItemKind.File, item.Kind);
        Assert.Equal("vault/abc123", item.BlobPath);
        Assert.Null(item.Ciphertext);
    }

    [Fact]
    public void CreateSecret_stores_inline_ciphertext_and_no_blob_pointer()
    {
        // The two kinds are mutually exclusive in storage; asserting both
        // directions stops a future refactor from quietly populating both.
        var item = Item.CreateSecret(Owner, "GitHub", [1], ValidNonce(), Dek, Now);

        Assert.Null(item.BlobPath);
        Assert.NotNull(item.Ciphertext);
    }

    [Fact]
    public void ItemId_and_UserId_cannot_be_transposed()
    {
        // Compile-time guarantee, asserted here so the property is not silently
        // lost if someone later "simplifies" both types back to Guid.
        Assert.NotEqual(typeof(ItemId), typeof(UserId));
    }
}

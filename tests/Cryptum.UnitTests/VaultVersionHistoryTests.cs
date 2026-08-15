using System.Security.Cryptography;
using Cryptum.Data;
using Cryptum.Domain;
using Cryptum.TestSupport;
using Microsoft.Data.Sqlite;
using Microsoft.EntityFrameworkCore;

namespace Cryptum.UnitTests;

/// <summary>
/// The plan's stated verification for task 3.0: edit an Item twice, restore
/// version 1, confirm the original plaintext returns.
/// </summary>
/// <remarks>
/// Deliberately asserted on decrypted plaintext rather than on ciphertext bytes.
/// A restore that moved the ciphertext but lost its DEK — or paired it with the
/// wrong one — would satisfy a byte comparison and still leave the user with
/// permanently unreadable data. Only decryption proves the version was restored
/// as a usable whole.
/// </remarks>
public sealed class VaultVersionHistoryTests : IDisposable
{
    private static readonly DateTimeOffset Start = new(2026, 8, 15, 12, 0, 0, TimeSpan.Zero);

    private readonly SqliteConnection connection;
    private readonly CryptumDbContext db;
    private readonly InMemoryKeyWrapper keyWrapper = new();
    private readonly TestClock clock = new(Start);
    private readonly VaultService vault;

    private readonly UserId alice = new(Guid.CreateVersion7());
    private readonly UserId mallory = new(Guid.CreateVersion7());

    public VaultVersionHistoryTests()
    {
        connection = new SqliteConnection("Filename=:memory:");
        connection.Open();

        db = new CryptumDbContext(
            new DbContextOptionsBuilder<CryptumDbContext>().UseSqlite(connection).Options);
        db.Database.EnsureCreated();

        vault = new VaultService(new ItemRepository(db), keyWrapper, new AuditLog(db), new UserRepository(db), clock);

        // Production provisions on the first authenticated request, via
        // UserProvisioningMiddleware. Neither user gets a KEK for free here —
        // Mallory is provisioned too, so the cross-user tests below fail on
        // ownership rather than on a missing key.
        keyWrapper.EnsureKekAsync(alice).GetAwaiter().GetResult();
        keyWrapper.EnsureKekAsync(mallory).GetAwaiter().GetResult();
    }

    /// <summary>Encrypts under a fresh DEK and nonce, as the client would.</summary>
    private static (byte[] Ciphertext, byte[] Nonce, byte[] Dek) Encrypt(string plaintext)
    {
        var dek = RandomNumberGenerator.GetBytes(32);
        var nonce = RandomNumberGenerator.GetBytes(Item.NonceLength);
        var plain = System.Text.Encoding.UTF8.GetBytes(plaintext);
        var ciphertext = new byte[plain.Length];
        var tag = new byte[16];

        using var gcm = new AesGcm(dek, tag.Length);
        gcm.Encrypt(nonce, plain, ciphertext, tag);

        return ([.. ciphertext, .. tag], nonce, dek);
    }

    private static string Decrypt(byte[] ciphertextAndTag, byte[] nonce, ReadOnlySpan<byte> dek)
    {
        var tag = ciphertextAndTag[^16..];
        var ciphertext = ciphertextAndTag[..^16];
        var plain = new byte[ciphertext.Length];

        using var gcm = new AesGcm(dek, tag.Length);
        gcm.Decrypt(nonce, ciphertext, tag, plain);

        return System.Text.Encoding.UTF8.GetString(plain);
    }

    private async Task<ItemId> GivenAnItemEditedTwiceAsync()
    {
        var (c1, n1, d1) = Encrypt("hunter2");
        var item = await vault.CreateSecretAsync(alice, "Bank", c1, n1, d1);

        clock.Advance(TimeSpan.FromHours(1));
        var (c2, n2, d2) = Encrypt("correct-horse");
        await vault.UpdateSecretAsync(alice, item.Id, "Bank", c2, n2, d2);

        clock.Advance(TimeSpan.FromHours(1));
        var (c3, n3, d3) = Encrypt("battery-staple");
        await vault.UpdateSecretAsync(alice, item.Id, "Bank", c3, n3, d3);

        return item.Id;
    }

    [Fact]
    public async Task Restoring_version_one_returns_the_original_plaintext()
    {
        var id = await GivenAnItemEditedTwiceAsync();

        await vault.RestoreVersionAsync(alice, id, versionNumber: 1);

        var read = await vault.ReadAsync(alice, id);
        Assert.NotNull(read);
        using var dek = read.Value.Dek;
        Assert.Equal("hunter2", Decrypt(read.Value.Item.Ciphertext!, read.Value.Item.Nonce, dek.Span));
    }

    [Fact]
    public async Task The_content_displaced_by_a_restore_is_itself_archived()
    {
        // A restore is an edit, not a rewind. If it discarded the current content
        // instead of archiving it, restoring the wrong version would destroy the
        // very data the feature exists to protect.
        var id = await GivenAnItemEditedTwiceAsync();

        await vault.RestoreVersionAsync(alice, id, versionNumber: 1);

        var history = await vault.ListVersionsAsync(alice, id);
        Assert.Equal(3, history.Count);

        var read = await vault.ReadAsync(alice, id);
        using var dek = read!.Value.Dek;
        var restoredFromHistory = await vault.ReadVersionAsync(alice, id, versionNumber: 3);
        using var archivedDek = restoredFromHistory!.Value.Dek;
        Assert.Equal(
            "battery-staple",
            Decrypt(restoredFromHistory.Value.Version.Ciphertext, restoredFromHistory.Value.Version.Nonce, archivedDek.Span));
    }

    [Fact]
    public async Task Every_edit_leaves_a_version_behind()
    {
        var id = await GivenAnItemEditedTwiceAsync();

        var history = await vault.ListVersionsAsync(alice, id);

        Assert.Equal([2, 1], history.Select(v => v.VersionNumber));
    }

    [Fact]
    public async Task History_is_capped_so_it_cannot_grow_without_bound()
    {
        var (c, n, d) = Encrypt("v0");
        var item = await vault.CreateSecretAsync(alice, "Bank", c, n, d);

        for (var i = 0; i < ItemVersion.MaxRetained + 5; i++)
        {
            clock.Advance(TimeSpan.FromMinutes(1));
            var (ci, ni, di) = Encrypt($"v{i + 1}");
            await vault.UpdateSecretAsync(alice, item.Id, "Bank", ci, ni, di);
        }

        var history = await vault.ListVersionsAsync(alice, item.Id);

        Assert.Equal(ItemVersion.MaxRetained, history.Count);
        // The newest survive: the oldest edit is the least likely to be wanted back.
        Assert.Equal(ItemVersion.MaxRetained + 5, history[0].VersionNumber);
    }

    [Fact]
    public async Task A_stranger_cannot_read_history()
    {
        var id = await GivenAnItemEditedTwiceAsync();

        Assert.Empty(await vault.ListVersionsAsync(mallory, id));
        Assert.Null(await vault.ReadVersionAsync(mallory, id, versionNumber: 1));
    }

    [Fact]
    public async Task A_stranger_cannot_restore_a_version()
    {
        // The most damaging history operation: a restore mutates the Item. If
        // owner-scoping were missing here, history would be a write-side IDOR,
        // not merely a read leak.
        var id = await GivenAnItemEditedTwiceAsync();

        var restored = await vault.RestoreVersionAsync(mallory, id, versionNumber: 1);

        Assert.False(restored);

        var read = await vault.ReadAsync(alice, id);
        using var dek = read!.Value.Dek;
        Assert.Equal("battery-staple", Decrypt(read.Value.Item.Ciphertext!, read.Value.Item.Nonce, dek.Span));
    }

    [Fact]
    public async Task A_stranger_cannot_edit_an_item()
    {
        var id = await GivenAnItemEditedTwiceAsync();
        var (c, n, d) = Encrypt("mallory-was-here");

        var updated = await vault.UpdateSecretAsync(mallory, id, "Pwned", c, n, d);

        Assert.False(updated);

        var read = await vault.ReadAsync(alice, id);
        using var dek = read!.Value.Dek;
        Assert.Equal("battery-staple", Decrypt(read.Value.Item.Ciphertext!, read.Value.Item.Nonce, dek.Span));
    }

    public void Dispose()
    {
        db.Dispose();
        keyWrapper.Dispose();
        connection.Dispose();
    }
}

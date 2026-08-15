using Cryptum.Data;
using Cryptum.Domain;
using Microsoft.Data.Sqlite;
using Microsoft.EntityFrameworkCore;

namespace Cryptum.UnitTests;

/// <summary>
/// Version history under the same owner-scoping rules as Items (plan task 3.0).
/// </summary>
/// <remarks>
/// History is a second door into the same ciphertext. Every guarantee the Item
/// table makes — owner predicate inside the query, soft-deleted rows invisible,
/// no fetch by id alone — has to hold here too, or the Item-level checks are
/// simply routed around.
/// </remarks>
public sealed class ItemVersionRepositoryTests : IDisposable
{
    private static readonly DateTimeOffset Now = new(2026, 8, 15, 12, 0, 0, TimeSpan.Zero);

    private readonly SqliteConnection connection;
    private readonly CryptumDbContext db;
    private readonly ItemRepository repository;

    private readonly UserId alice = new(Guid.CreateVersion7());
    private readonly UserId mallory = new(Guid.CreateVersion7());

    public ItemVersionRepositoryTests()
    {
        connection = new SqliteConnection("Filename=:memory:");
        connection.Open();

        db = new CryptumDbContext(
            new DbContextOptionsBuilder<CryptumDbContext>().UseSqlite(connection).Options);
        db.Database.EnsureCreated();

        repository = new ItemRepository(db);
    }

    private static byte[] Nonce(byte fill) => [.. Enumerable.Repeat(fill, Item.NonceLength)];

    private async Task<Item> GivenAliceOwnsAnEditedItemAsync()
    {
        var item = Item.CreateSecret(
            alice, "Bank", [1, 2, 3], Nonce(1), new WrappedDek([9, 9], "kek-v1"), Now);
        await repository.AddAsync(item);

        var archived = item.ReplaceContent(
            "Bank", [4, 5, 6], Nonce(2), new WrappedDek([8, 8], "kek-v2"), Now.AddHours(1));
        await repository.AddVersionAsync(archived);

        await repository.SaveChangesAsync();
        return item;
    }

    [Fact]
    public async Task An_archived_version_survives_a_round_trip_with_its_own_dek()
    {
        var item = await GivenAliceOwnsAnEditedItemAsync();
        db.ChangeTracker.Clear();

        var version = await repository.FindVersionAsync(alice, item.Id, 1);

        Assert.NotNull(version);
        Assert.Equal<byte>([1, 2, 3], version.Ciphertext);
        Assert.Equal<byte>([9, 9], version.WrappedDek);
        Assert.Equal("kek-v1", version.KekVersion);
        Assert.Equal(Nonce(1), version.Nonce);
    }

    [Fact]
    public async Task FindVersionAsync_refuses_a_version_of_someone_elses_item()
    {
        var item = await GivenAliceOwnsAnEditedItemAsync();
        db.ChangeTracker.Clear();

        var stolen = await repository.FindVersionAsync(mallory, item.Id, 1);

        Assert.Null(stolen);
    }

    [Fact]
    public async Task ListVersionsAsync_never_includes_another_users_history()
    {
        await GivenAliceOwnsAnEditedItemAsync();
        db.ChangeTracker.Clear();

        Assert.Empty(await repository.ListVersionsAsync(mallory, (await repository.ListAsync(alice))[0].Id));
    }

    [Fact]
    public async Task ListVersionsAsync_carries_no_secret_material()
    {
        // Same reasoning as the Item list view: a history list is a per-Item
        // enumeration, so widening it to carry ciphertext would turn one unwrap
        // into a bulk download of every revision.
        var item = await GivenAliceOwnsAnEditedItemAsync();
        db.ChangeTracker.Clear();

        var summaries = await repository.ListVersionsAsync(alice, item.Id);

        Assert.Single(summaries);
        Assert.DoesNotContain(
            typeof(ItemVersionSummary).GetProperties(),
            p => p.Name.Contains("Dek", StringComparison.OrdinalIgnoreCase)
              || p.Name.Contains("Cipher", StringComparison.OrdinalIgnoreCase)
              || p.Name.Contains("Nonce", StringComparison.OrdinalIgnoreCase));
    }

    [Fact]
    public async Task Crypto_shredding_the_account_hides_history_too()
    {
        // The KEK is gone, so these rows are unreadable regardless — but leaving
        // them visible would still expose edit timestamps and revision counts,
        // and would contradict ADR-0003's claim that deletion removes the Vault.
        var item = await GivenAliceOwnsAnEditedItemAsync();
        await repository.SoftDeleteAllAsync(alice, Now.AddHours(2));
        db.ChangeTracker.Clear();

        Assert.Null(await repository.FindVersionAsync(alice, item.Id, 1));
        Assert.Empty(await repository.ListVersionsAsync(alice, item.Id));
    }

    public void Dispose()
    {
        db.Dispose();
        connection.Dispose();
    }
}

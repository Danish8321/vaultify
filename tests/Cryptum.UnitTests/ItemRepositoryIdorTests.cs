using Cryptum.Data;
using Cryptum.Domain;
using Microsoft.Data.Sqlite;
using Microsoft.EntityFrameworkCore;

namespace Cryptum.UnitTests;

/// <summary>
/// Cross-user access attempts against every repository method.
/// </summary>
/// <remarks>
/// ADR-0002 concentrates trust in the backend: its identity can unwrap any User's
/// KEK, so owner-scoping in the data layer is what stands between one user and
/// everyone else's Vault. These tests are therefore not routine coverage — they
/// are the assertion that the compensating control the ADR promises actually holds.
/// </remarks>
public sealed class ItemRepositoryIdorTests : IDisposable
{
    private static readonly DateTimeOffset Now = new(2026, 8, 15, 12, 0, 0, TimeSpan.Zero);

    private readonly SqliteConnection connection;
    private readonly CryptumDbContext db;
    private readonly ItemRepository repository;

    private readonly UserId alice = new(Guid.CreateVersion7());
    private readonly UserId mallory = new(Guid.CreateVersion7());

    public ItemRepositoryIdorTests()
    {
        // A real relational engine, in memory: query filters, converters and
        // predicates all behave as they will in Azure SQL, without a live database.
        connection = new SqliteConnection("Filename=:memory:");
        connection.Open();

        db = new CryptumDbContext(
            new DbContextOptionsBuilder<CryptumDbContext>().UseSqlite(connection).Options);
        db.Database.EnsureCreated();

        repository = new ItemRepository(db);
    }

    private async Task<Item> GivenAliceOwnsAnItemAsync(string title = "Alice's bank")
    {
        var item = Item.CreateSecret(
            alice, title, [1, 2, 3], new byte[Item.NonceLength], new WrappedDek([9, 9], "v1"), Now);

        await repository.AddAsync(item);
        await repository.SaveChangesAsync();
        return item;
    }

    [Fact]
    public async Task FindAsync_refuses_an_item_owned_by_someone_else()
    {
        var item = await GivenAliceOwnsAnItemAsync();

        var stolen = await repository.FindAsync(mallory, item.Id);

        Assert.Null(stolen);
    }

    [Fact]
    public async Task FindAsync_returns_the_item_to_its_owner()
    {
        // The negative test above is only meaningful if the positive path works;
        // otherwise a repository that returns null for everything would "pass".
        var item = await GivenAliceOwnsAnItemAsync();

        var found = await repository.FindAsync(alice, item.Id);

        Assert.NotNull(found);
        Assert.Equal(item.Id, found.Id);
    }

    [Fact]
    public async Task ListAsync_never_includes_another_users_items()
    {
        await GivenAliceOwnsAnItemAsync();

        var mallorysView = await repository.ListAsync(mallory);

        Assert.Empty(mallorysView);
    }

    [Fact]
    public async Task ListAsync_carries_no_secret_material()
    {
        // ItemSummary has no ciphertext or wrapped-DEK member, so this asserts the
        // projection stays that way — a list view must not become a bulk exfiltration
        // route if someone later "conveniently" widens it.
        await GivenAliceOwnsAnItemAsync();

        var summaries = await repository.ListAsync(alice);

        var summary = Assert.Single(summaries);
        Assert.Equal("Alice's bank", summary.Title);
        Assert.DoesNotContain(
            typeof(ItemSummary).GetProperties(),
            p => p.Name.Contains("Dek", StringComparison.OrdinalIgnoreCase)
              || p.Name.Contains("Cipher", StringComparison.OrdinalIgnoreCase)
              || p.Name.Contains("Nonce", StringComparison.OrdinalIgnoreCase));
    }

    [Fact]
    public async Task SoftDeleteAllAsync_cannot_delete_another_users_items()
    {
        // Deletion is the most destructive operation exposed, so a missing owner
        // predicate here would be catastrophic rather than merely a leak.
        var item = await GivenAliceOwnsAnItemAsync();

        var affected = await repository.SoftDeleteAllAsync(mallory, Now);

        Assert.Equal(0, affected);
        Assert.NotNull(await repository.FindAsync(alice, item.Id));
    }

    [Fact]
    public async Task SoftDeleteAllAsync_hides_the_owners_items_afterwards()
    {
        var item = await GivenAliceOwnsAnItemAsync();

        var affected = await repository.SoftDeleteAllAsync(alice, Now);

        Assert.Equal(1, affected);
        Assert.Null(await repository.FindAsync(alice, item.Id));
    }

    [Fact]
    public async Task Soft_deleted_items_stay_hidden_from_every_read_path()
    {
        var item = await GivenAliceOwnsAnItemAsync();
        await repository.SoftDeleteAllAsync(alice, Now);

        Assert.Null(await repository.FindAsync(alice, item.Id));
        Assert.Empty(await repository.ListAsync(alice));
    }

    public void Dispose()
    {
        db.Dispose();
        connection.Dispose();
    }
}

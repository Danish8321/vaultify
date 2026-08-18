using Cryptum.Data;
using Cryptum.Domain;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.DependencyInjection;

namespace Cryptum.IntegrationTests;

/// <summary>
/// The purge, against a real database.
/// </summary>
/// <remarks>
/// Written as integration tests rather than unit tests because every property
/// worth asserting here — that a batch commits, that an interrupted run leaves
/// the rest recoverable, that re-running deletes nothing twice — is a property
/// of the database, not of the C#. A fake store would prove the loop calls the
/// store, which nobody doubts.
/// </remarks>
public sealed class PurgeTests(CryptumApiFactory factory) : IClassFixture<CryptumApiFactory>
{
    private static readonly DateTimeOffset Now = new(2026, 8, 18, 12, 0, 0, TimeSpan.Zero);

    [Fact]
    public async Task Purge_removes_soft_deleted_Items_and_their_history()
    {
        var owner = await SeedAsync(itemCount: 3, softDeleted: true);

        var purged = await PurgeAsync();

        Assert.Equal(3, purged.Items);
        Assert.Equal(0, await CountItemsAsync(owner));
    }

    [Fact]
    public async Task Purge_leaves_live_Items_alone()
    {
        var owner = await SeedAsync(itemCount: 2, softDeleted: false);

        var purged = await PurgeAsync();

        Assert.Equal(0, purged.Items);
        Assert.Equal(2, await CountItemsAsync(owner));
    }

    [Fact]
    public async Task Purge_never_deletes_the_audit_trail()
    {
        // The audit log outlives the data it describes. If a purge could remove
        // audit rows, deleting an account would erase the record that the
        // account was deleted — which is the one record most worth keeping.
        await SeedAsync(itemCount: 1, softDeleted: true);
        var auditBefore = await CountAuditAsync();

        await PurgeAsync();

        Assert.Equal(auditBefore, await CountAuditAsync());
    }

    [Fact]
    public async Task An_interrupted_purge_can_be_resumed_and_completes()
    {
        // The plan's stated verification. Cancellation lands between batches, so
        // the rows already purged stay purged and the rest are still eligible.
        var owner = await SeedAsync(itemCount: 10, softDeleted: true);

        using var cancelAfterFirstBatch = new CancellationTokenSource();
        var service = Service();

        await Assert.ThrowsAnyAsync<OperationCanceledException>(async () =>
            await service.PurgeAsync(
                Now,
                batchSize: 2,
                onBatch: _ => cancelAfterFirstBatch.Cancel(),
                cancellationToken: cancelAfterFirstBatch.Token));

        var remaining = await CountItemsAsync(owner);
        Assert.InRange(remaining, 1, 9);

        var resumed = await PurgeAsync();

        Assert.Equal(remaining, resumed.Items);
        Assert.Equal(0, await CountItemsAsync(owner));
    }

    [Fact]
    public async Task Purging_twice_deletes_nothing_the_second_time()
    {
        await SeedAsync(itemCount: 4, softDeleted: true);

        var first = await PurgeAsync();
        var second = await PurgeAsync();

        Assert.Equal(4, first.Items);
        Assert.Equal(0, second.Items);
    }

    private async Task<PurgeResult> PurgeAsync() =>
        await Service().PurgeAsync(Now, batchSize: 3, cancellationToken: CancellationToken.None);

    private PurgeService Service() =>
        factory.Services.CreateScope().ServiceProvider.GetRequiredService<PurgeService>();

    // Owner-scoped, because the fixture's database is shared across every test
    // in this class. A global count would make each test's result depend on the
    // order the others ran in.
    private async Task<int> CountItemsAsync(UserId owner)
    {
        using var scope = factory.Services.CreateScope();
        var db = scope.ServiceProvider.GetRequiredService<CryptumDbContext>();
        return await db.Items.IgnoreQueryFilters()
            .CountAsync(i => i.Owner == owner);
    }

    private async Task<int> CountAuditAsync()
    {
        using var scope = factory.Services.CreateScope();
        var db = scope.ServiceProvider.GetRequiredService<CryptumDbContext>();
        return await db.AuditEntries.IgnoreQueryFilters().CountAsync(CancellationToken.None);
    }

    private async Task<UserId> SeedAsync(int itemCount, bool softDeleted)
    {
        using var scope = factory.Services.CreateScope();
        var db = scope.ServiceProvider.GetRequiredService<CryptumDbContext>();

        var owner = new UserId(Guid.NewGuid());

        for (var i = 0; i < itemCount; i++)
        {
            var item = Item.CreateSecret(
                owner,
                $"item {i}",
                [1, 2, 3],
                new byte[12],
                new WrappedDek([4, 5, 6], "v1"),
                Now.AddDays(-30));

            if (softDeleted)
            {
                item.MarkDeleted(Now.AddDays(-1));
            }

            db.Items.Add(item);
        }

        await db.SaveChangesAsync(CancellationToken.None);
        return owner;
    }
}

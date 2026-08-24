using System.Security.Cryptography;
using Cryptum.Data;
using Cryptum.Domain;
using Cryptum.TestSupport;
using Microsoft.Data.Sqlite;
using Microsoft.EntityFrameworkCore;

namespace Cryptum.UnitTests;

/// <summary>
/// The plan's stated verification for task 4.1: after deletion, unwrap fails and
/// Items are undecryptable even though ciphertext still exists.
/// </summary>
/// <remarks>
/// That last clause is the whole claim of crypto-shred (ADR-0003), and it is the
/// one an ordinary deletion test would miss. "The rows are gone" proves nothing
/// about a backup, a replica, or a disk that outlives the delete. What makes the
/// promise real is that the surviving ciphertext is unreadable — so the test
/// reads the ciphertext back deliberately and shows it cannot be used.
/// </remarks>
public sealed class AccountDeletionTests : IDisposable
{
    private static readonly DateTimeOffset Start = new(2026, 8, 15, 12, 0, 0, TimeSpan.Zero);

    private readonly SqliteConnection connection;
    private readonly CryptumDbContext db;
    private readonly InMemoryKeyWrapper keyWrapper = new();
    private readonly TestClock clock = new(Start);
    private readonly VaultService vault;

    private readonly UserId alice = new(Guid.CreateVersion7());
    private readonly UserId bob = new(Guid.CreateVersion7());

    public AccountDeletionTests()
    {
        connection = new SqliteConnection("Filename=:memory:");
        connection.Open();

        db = new CryptumDbContext(
            new DbContextOptionsBuilder<CryptumDbContext>().UseSqlite(connection).Options);
        db.Database.EnsureCreated();

        vault = new VaultService(new ItemRepository(db), keyWrapper, new FakeBlobStore(), new AuditLog(db), new UserRepository(db), clock);

        keyWrapper.EnsureKekAsync(alice).GetAwaiter().GetResult();
        keyWrapper.EnsureKekAsync(bob).GetAwaiter().GetResult();
    }

    private async Task<ItemId> GivenAliceOwnsASecretAsync()
    {
        var item = await vault.CreateSecretAsync(
            alice,
            "Bank",
            RandomNumberGenerator.GetBytes(48),
            RandomNumberGenerator.GetBytes(Item.NonceLength),
            RandomNumberGenerator.GetBytes(32));

        return item.Id;
    }

    [Fact]
    public async Task After_deletion_the_ciphertext_survives_but_cannot_be_unwrapped()
    {
        var id = await GivenAliceOwnsASecretAsync();

        // Captured before the shred: this is the wrapped DEK an attacker would
        // hold if they had taken a database backup a moment earlier.
        var stored = await db.Items.AsNoTracking().SingleAsync(i => i.Id == id);
        var wrapped = new WrappedDek(stored.WrappedDek, stored.KekVersion);

        await vault.DeleteAccountAsync(alice);
        db.ChangeTracker.Clear();

        // The row is still on disk — soft-deleted, pending the purge worker.
        var surviving = await db.Items.IgnoreQueryFilters().SingleAsync(i => i.Id == id);
        Assert.NotNull(surviving.DeletedAt);
        Assert.NotEmpty(surviving.Ciphertext!);

        // And it is now useless: the KEK that could unwrap its DEK is gone.
        await Assert.ThrowsAsync<KeyUnavailableException>(
            () => keyWrapper.UnwrapAsync(alice, wrapped));
    }

    [Fact]
    public async Task Deletion_hides_the_vault_from_every_read_path()
    {
        var id = await GivenAliceOwnsASecretAsync();

        await vault.DeleteAccountAsync(alice);
        db.ChangeTracker.Clear();

        Assert.Null(await vault.ReadAsync(alice, id));
        Assert.Empty(await vault.ListAsync(alice));
    }

    [Fact]
    public async Task Deletion_takes_version_history_with_it()
    {
        // History holds ciphertext under its own DEKs. If the shred missed those
        // KEKs the account would be "deleted" while every prior revision stayed
        // readable — the failure mode most likely to go unnoticed.
        var id = await GivenAliceOwnsASecretAsync();
        clock.Advance(TimeSpan.FromHours(1));
        await vault.UpdateSecretAsync(
            alice, id, "Bank", RandomNumberGenerator.GetBytes(48),
            RandomNumberGenerator.GetBytes(Item.NonceLength), RandomNumberGenerator.GetBytes(32));

        var archived = await db.ItemVersions.AsNoTracking().SingleAsync(v => v.ItemId == id);
        var wrapped = new WrappedDek(archived.WrappedDek, archived.KekVersion);

        await vault.DeleteAccountAsync(alice);
        db.ChangeTracker.Clear();

        Assert.Empty(await vault.ListVersionsAsync(alice, id));
        await Assert.ThrowsAsync<KeyUnavailableException>(() => keyWrapper.UnwrapAsync(alice, wrapped));
    }

    [Fact]
    public async Task Deleting_one_account_leaves_every_other_vault_intact()
    {
        // A shred is irreversible, so an over-broad predicate here is the single
        // most destructive bug available in the system.
        var alicesItem = await GivenAliceOwnsASecretAsync();
        var bobsItem = await vault.CreateSecretAsync(
            bob, "Bob's bank", RandomNumberGenerator.GetBytes(48),
            RandomNumberGenerator.GetBytes(Item.NonceLength), RandomNumberGenerator.GetBytes(32));

        await vault.DeleteAccountAsync(alice);
        db.ChangeTracker.Clear();

        Assert.Null(await vault.ReadAsync(alice, alicesItem));

        var bobsRead = await vault.ReadAsync(bob, bobsItem.Id);
        Assert.NotNull(bobsRead);
        bobsRead.Value.Dek.Dispose();
    }

    [Fact]
    public async Task Deletion_is_idempotent()
    {
        // The API is externally reachable and the purge worker retries, so a
        // second call must not throw on the already-missing KEK.
        var id = await GivenAliceOwnsASecretAsync();

        await vault.DeleteAccountAsync(alice);
        await vault.DeleteAccountAsync(alice);

        db.ChangeTracker.Clear();
        Assert.Null(await vault.ReadAsync(alice, id));
    }

    [Fact]
    public async Task Deletion_preserves_the_original_deletion_timestamp()
    {
        // The purge worker schedules from DeletedAt. If a repeat call rewrote it,
        // a caller could postpone the purge indefinitely by re-deleting.
        var id = await GivenAliceOwnsASecretAsync();

        await vault.DeleteAccountAsync(alice);
        var firstStamp = (await db.Items.IgnoreQueryFilters().AsNoTracking()
            .SingleAsync(i => i.Id == id)).DeletedAt;

        clock.Advance(TimeSpan.FromDays(3));
        await vault.DeleteAccountAsync(alice);

        var secondStamp = (await db.Items.IgnoreQueryFilters().AsNoTracking()
            .SingleAsync(i => i.Id == id)).DeletedAt;

        Assert.Equal(firstStamp, secondStamp);
    }

    public void Dispose()
    {
        db.Dispose();
        keyWrapper.Dispose();
        connection.Dispose();
    }
}

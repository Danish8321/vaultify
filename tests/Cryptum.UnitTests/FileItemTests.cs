using System.Security.Cryptography;
using Cryptum.Data;
using Cryptum.Domain;
using Cryptum.TestSupport;
using Microsoft.Data.Sqlite;
using Microsoft.EntityFrameworkCore;

namespace Cryptum.UnitTests;

/// <summary>
/// Verification for the Files backend (docs/IMPLEMENTATION-PLAN.md 3.1-3.2):
/// registration issues a scoped upload SAS, an oversized file is refused, an
/// account at quota is refused, and reading unwraps the DEK and issues a
/// scoped download SAS.
/// </summary>
public sealed class FileItemTests : IDisposable
{
    private static readonly DateTimeOffset Start = new(2026, 8, 15, 12, 0, 0, TimeSpan.Zero);

    private readonly SqliteConnection connection;
    private readonly CryptumDbContext db;
    private readonly InMemoryKeyWrapper keyWrapper = new();
    private readonly FakeBlobStore blobStore = new();
    private readonly TestClock clock = new(Start);
    private readonly VaultService vault;

    private readonly UserId alice = new(Guid.CreateVersion7());
    private readonly UserId mallory = new(Guid.CreateVersion7());

    public FileItemTests()
    {
        connection = new SqliteConnection("Filename=:memory:");
        connection.Open();

        db = new CryptumDbContext(
            new DbContextOptionsBuilder<CryptumDbContext>().UseSqlite(connection).Options);
        db.Database.EnsureCreated();

        vault = new VaultService(new ItemRepository(db), keyWrapper, blobStore, new AuditLog(db), new UserRepository(db), clock);

        keyWrapper.EnsureKekAsync(alice).GetAwaiter().GetResult();
        keyWrapper.EnsureKekAsync(mallory).GetAwaiter().GetResult();
    }

    [Fact]
    public async Task CreateFileAsync_registers_the_item_and_issues_a_scoped_upload_sas()
    {
        var (item, uploadUri) = await vault.CreateFileAsync(
            alice, "passport.pdf", 4096, RandomNumberGenerator.GetBytes(Item.NonceLength), RandomNumberGenerator.GetBytes(32));

        Assert.Equal(ItemKind.File, item.Kind);
        Assert.Equal(4096, item.SizeBytes);
        Assert.NotNull(item.BlobPath);
        Assert.Contains(item.BlobPath!, uploadUri.ToString());
        Assert.Equal(1, blobStore.UploadSasCountFor(item.BlobPath!));
    }

    [Fact]
    public async Task CreateFileAsync_rejects_a_file_over_the_per_file_limit()
    {
        await Assert.ThrowsAsync<FileQuotaExceededException>(() => vault.CreateFileAsync(
            alice, "big.bin", FileLimits.MaxFileBytes + 1,
            RandomNumberGenerator.GetBytes(Item.NonceLength), RandomNumberGenerator.GetBytes(32)));
    }

    [Fact]
    public async Task CreateFileAsync_rejects_a_file_that_would_exceed_the_account_quota()
    {
        // Fill the quota with one file just under the per-file cap, then try to
        // register another that would push the total over MaxUserQuotaBytes.
        var perFileNearCap = FileLimits.MaxFileBytes;
        var filesToFillQuota = FileLimits.MaxUserQuotaBytes / perFileNearCap;

        for (var i = 0; i < filesToFillQuota; i++)
        {
            await vault.CreateFileAsync(
                alice, $"file{i}.bin", perFileNearCap,
                RandomNumberGenerator.GetBytes(Item.NonceLength), RandomNumberGenerator.GetBytes(32));
        }

        await Assert.ThrowsAsync<FileQuotaExceededException>(() => vault.CreateFileAsync(
            alice, "onemore.bin", 1024,
            RandomNumberGenerator.GetBytes(Item.NonceLength), RandomNumberGenerator.GetBytes(32)));
    }

    [Fact]
    public async Task ReadFileAsync_unwraps_the_dek_and_issues_a_scoped_download_sas()
    {
        var nonce = RandomNumberGenerator.GetBytes(Item.NonceLength);
        var dek = RandomNumberGenerator.GetBytes(32);
        var (created, _) = await vault.CreateFileAsync(alice, "passport.pdf", 4096, nonce, dek);

        var result = await vault.ReadFileAsync(alice, created.Id);

        Assert.NotNull(result);
        var (item, unwrappedDek, downloadUri) = result!.Value;
        Assert.Equal(dek, unwrappedDek.Span.ToArray());
        Assert.Contains(item.BlobPath!, downloadUri.ToString());
        Assert.Equal(1, blobStore.DownloadSasCountFor(item.BlobPath!));
    }

    [Fact]
    public async Task ReadFileAsync_returns_null_for_a_file_owned_by_someone_else()
    {
        var (created, _) = await vault.CreateFileAsync(
            alice, "passport.pdf", 4096, RandomNumberGenerator.GetBytes(Item.NonceLength), RandomNumberGenerator.GetBytes(32));

        var result = await vault.ReadFileAsync(mallory, created.Id);

        Assert.Null(result);
    }

    public void Dispose()
    {
        connection.Dispose();
        keyWrapper.Dispose();
        db.Dispose();
    }
}

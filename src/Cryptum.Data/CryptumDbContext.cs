using Cryptum.Domain;
using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Metadata.Builders;
using Microsoft.EntityFrameworkCore.Storage.ValueConversion;

namespace Cryptum.Data;

/// <summary>
/// The metadata store's EF Core context.
/// </summary>
/// <remarks>
/// The mapping is where several security properties become structural rather
/// than conventional: the (Owner, Id) index makes the owner-scoped query the
/// cheap one, and the global soft-delete filter means a forgotten
/// <c>DeletedAt == null</c> predicate cannot resurrect crypto-shredded rows.
/// </remarks>
public sealed class CryptumDbContext(DbContextOptions<CryptumDbContext> options) : DbContext(options)
{
    public DbSet<Item> Items => Set<Item>();

    public DbSet<ItemVersion> ItemVersions => Set<ItemVersion>();

    public DbSet<AuditEntry> AuditEntries => Set<AuditEntry>();

    public DbSet<User> Users => Set<User>();

    protected override void OnModelCreating(ModelBuilder modelBuilder)
    {
        ArgumentNullException.ThrowIfNull(modelBuilder);
        modelBuilder.ApplyConfiguration(new ItemConfiguration());
        modelBuilder.ApplyConfiguration(new ItemVersionConfiguration());
        modelBuilder.ApplyConfiguration(new AuditEntryConfiguration());
        modelBuilder.ApplyConfiguration(new UserConfiguration());

        if (Database.IsSqlite())
        {
            // SQLite (tests only) cannot ORDER BY a DateTimeOffset. Storing the
            // UTC tick count preserves ordering exactly, so the test provider
            // answers the same question Azure SQL will rather than the domain
            // being reshaped to suit a test dependency.
            foreach (var property in modelBuilder.Model.GetEntityTypes()
                         .SelectMany(t => t.GetProperties())
                         .Where(p => p.ClrType == typeof(DateTimeOffset) || p.ClrType == typeof(DateTimeOffset?)))
            {
                property.SetValueConverter(property.ClrType == typeof(DateTimeOffset)
                    ? new ValueConverter<DateTimeOffset, long>(
                        v => v.UtcTicks,
                        v => new DateTimeOffset(v, TimeSpan.Zero))
                    : new ValueConverter<DateTimeOffset?, long?>(
                        v => v == null ? null : v.Value.UtcTicks,
                        v => v == null ? null : new DateTimeOffset(v.Value, TimeSpan.Zero)));
            }
        }

        base.OnModelCreating(modelBuilder);
    }
}

/// <summary>
/// Maps <see cref="Item"/> so that the storage shape mirrors the threat model,
/// not just the object graph.
/// </summary>
internal sealed class ItemConfiguration : IEntityTypeConfiguration<Item>
{
    public void Configure(EntityTypeBuilder<Item> builder)
    {
        ArgumentNullException.ThrowIfNull(builder);

        builder.ToTable("Items");
        builder.HasKey(i => i.Id);

        // The identifiers are distinct structs precisely so they cannot be
        // transposed in C#; converters keep that guarantee without asking the
        // database to know about it.
        builder.Property(i => i.Id)
            .HasConversion(id => id.Value, value => new ItemId(value))
            .ValueGeneratedNever();

        builder.Property(i => i.Owner)
            .HasConversion(owner => owner.Value, value => new UserId(value))
            .IsRequired();

        builder.Property(i => i.Kind)
            .HasConversion<int>()
            .IsRequired();

        builder.Property(i => i.Title)
            .IsRequired()
            .HasMaxLength(Item.MaxTitleLength);

        // Null for a File, whose ciphertext lives in blob storage.
        builder.Property(i => i.Ciphertext);

        // Null for a Secret, which has no blob. Bounded: a blob path is a
        // container-relative name, not free text.
        builder.Property(i => i.BlobPath)
            .HasMaxLength(Item.MaxBlobPathLength);

        // Null for a Secret. Populated for a File at creation and never
        // revised — an edit is a whole new File Item, not an in-place resize.
        builder.Property(i => i.SizeBytes);

        builder.Property(i => i.WrappedDek)
            .IsRequired();

        // A Key Vault key version is a 32-character hex string. Bounded so the
        // column stays indexable, which a future KEK rotation (ADR-0005) will
        // need in order to find DEKs still wrapped under an old version.
        builder.Property(i => i.KekVersion)
            .IsRequired()
            .HasMaxLength(Item.MaxKekVersionLength);

        // Fixed width: a nonce that is not exactly 96 bits is a bug, and the
        // column type should say so rather than tolerate it.
        builder.Property(i => i.Nonce)
            .IsRequired()
            .HasMaxLength(Item.NonceLength)
            .IsFixedLength();

        builder.Property(i => i.CreatedAt).IsRequired();
        builder.Property(i => i.UpdatedAt).IsRequired();
        builder.Property(i => i.DeletedAt);

        // Owner leads because every authorized query filters on it first; an
        // Id-only lookup is deliberately not a supported access path. DeletedAt
        // follows because the global soft-delete filter appends it to every
        // query, so leaving it out would make the common read a partial scan.
        builder.HasIndex(i => new { i.Owner, i.DeletedAt, i.Id });

        builder.HasQueryFilter(i => i.DeletedAt == null);
    }
}

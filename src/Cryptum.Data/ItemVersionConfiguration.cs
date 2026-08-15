using Cryptum.Domain;
using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Metadata.Builders;

namespace Cryptum.Data;

/// <summary>
/// Maps <see cref="ItemVersion"/>. History is a second door into the same
/// ciphertext, so it carries the same structural guarantees as the Item table.
/// </summary>
internal sealed class ItemVersionConfiguration : IEntityTypeConfiguration<ItemVersion>
{
    public void Configure(EntityTypeBuilder<ItemVersion> builder)
    {
        ArgumentNullException.ThrowIfNull(builder);

        builder.ToTable("ItemVersions");

        // Composite natural key. A surrogate id would add a second way to name a
        // version — and the only reason to want one is a fetch by id alone,
        // which is exactly the access path IItemRepository refuses to offer.
        builder.HasKey(v => new { v.ItemId, v.VersionNumber });

        builder.Property(v => v.ItemId)
            .HasConversion(id => id.Value, value => new ItemId(value))
            .ValueGeneratedNever();

        builder.Property(v => v.Owner)
            .HasConversion(owner => owner.Value, value => new UserId(value))
            .IsRequired();

        builder.Property(v => v.VersionNumber)
            .ValueGeneratedNever();

        builder.Property(v => v.Ciphertext).IsRequired();

        builder.Property(v => v.WrappedDek).IsRequired();

        builder.Property(v => v.KekVersion)
            .IsRequired()
            .HasMaxLength(Item.MaxKekVersionLength);

        builder.Property(v => v.Nonce)
            .IsRequired()
            .HasMaxLength(Item.NonceLength)
            .IsFixedLength();

        builder.Property(v => v.ArchivedAt).IsRequired();
        builder.Property(v => v.DeletedAt);

        // Owner leads for the same reason it does on Items: the owner-scoped
        // query is the only supported one, so it should also be the cheap one.
        builder.HasIndex(v => new { v.Owner, v.DeletedAt, v.ItemId, v.VersionNumber });

        // Deliberately no navigation to Item and no FK cascade. A cascade would
        // make a hard Item delete silently take history with it; ADR-0003 wants
        // deletion to be an explicit, auditable act, not a referential side effect.
        builder.HasQueryFilter(v => v.DeletedAt == null);
    }
}

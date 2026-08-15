using Cryptum.Domain;
using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Metadata.Builders;

namespace Cryptum.Data;

/// <summary>
/// Maps the audit trail.
/// </summary>
/// <remarks>
/// No soft-delete filter here, deliberately: Items disappear when an account is
/// crypto-shredded, but the record that the shredding happened must survive it.
/// </remarks>
internal sealed class AuditEntryConfiguration : IEntityTypeConfiguration<AuditEntry>
{
    public void Configure(EntityTypeBuilder<AuditEntry> builder)
    {
        ArgumentNullException.ThrowIfNull(builder);

        builder.ToTable("AuditEntries");
        builder.HasKey(a => a.Id);

        builder.Property(a => a.Id).ValueGeneratedOnAdd();

        builder.Property(a => a.Actor)
            .HasConversion(actor => actor.Value, value => new UserId(value))
            .IsRequired();

        builder.Property(a => a.Action).HasConversion<int>().IsRequired();
        builder.Property(a => a.ItemId);
        builder.Property(a => a.Succeeded).IsRequired();
        builder.Property(a => a.OccurredAt).IsRequired();

        // "What happened to this user, most recent first" is the question an
        // incident review asks, so it is the one the index answers.
        builder.HasIndex(a => new { a.Actor, a.OccurredAt });
    }
}

using Cryptum.Domain;
using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Metadata.Builders;

namespace Cryptum.Data;

/// <summary>
/// Maps <see cref="User"/>.
/// </summary>
/// <remarks>
/// The primary key is the identity derived from the B2C subject, which is what
/// makes provisioning idempotent: a concurrent second insert violates the key
/// and is rejected by the database rather than relying on the application to
/// check first. No soft-delete filter — the row is the record that the account
/// existed, and it must outlive a crypto-shred.
/// </remarks>
internal sealed class UserConfiguration : IEntityTypeConfiguration<User>
{
    public void Configure(EntityTypeBuilder<User> builder)
    {
        ArgumentNullException.ThrowIfNull(builder);

        builder.ToTable("Users");
        builder.HasKey(u => u.Id);

        builder.Property(u => u.Id)
            .HasConversion(id => id.Value, value => new UserId(value))
            .ValueGeneratedNever();

        builder.Property(u => u.ProvisionedAt).IsRequired();
    }
}

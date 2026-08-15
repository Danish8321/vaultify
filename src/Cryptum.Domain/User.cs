namespace Cryptum.Domain;

/// <summary>
/// A Cryptum account, keyed by the identity provider's subject.
/// </summary>
/// <remarks>
/// Holds no credential and no key material. Authentication belongs to B2C, and
/// the KEK lives in Key Vault — this row exists to record that the account was
/// provisioned and when, nothing more. Adding a password hash or a key here
/// would undo ADR-0004.
/// </remarks>
public sealed class User
{
    private User()
    {
        // EF.
    }

    private User(UserId id, DateTimeOffset provisionedAt)
    {
        Id = id;
        ProvisionedAt = provisionedAt;
    }

    public UserId Id { get; private set; }

    public DateTimeOffset ProvisionedAt { get; private set; }

    public static User Provision(UserId id, DateTimeOffset now) => new(id, now);
}

/// <summary>Stores <see cref="User"/> rows.</summary>
public interface IUserRepository
{
    Task<bool> ExistsAsync(UserId id, CancellationToken cancellationToken = default);

    /// <summary>
    /// Adds the User unless one already exists, and reports whether it inserted.
    /// </summary>
    /// <remarks>
    /// Idempotent by contract rather than by convention: two concurrent first
    /// requests must produce one User, and the loser of that race needs to learn
    /// it lost rather than fail.
    /// </remarks>
    Task<bool> AddIfAbsentAsync(User user, CancellationToken cancellationToken = default);

    /// <summary>
    /// Removes the User row, if present. Reports whether it removed one.
    /// </summary>
    /// <remarks>
    /// A hard delete, unlike Items. The row exists only to record that a KEK was
    /// provisioned; once the KEK is shredded the row is a lie, and leaving it
    /// behind makes provisioning skip a User who has no key — which surfaces as
    /// a 500 on their next write rather than as anything diagnosable.
    /// </remarks>
    Task<bool> RemoveAsync(UserId id, CancellationToken cancellationToken = default);
}

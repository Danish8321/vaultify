namespace Cryptum.Domain;

/// <summary>
/// Provisions a User on their first authenticated request (plan task 2.4).
/// </summary>
/// <remarks>
/// The KEK is created here and never derived from the B2C password (ADR-0004):
/// a password reset must not cost a User their Vault. Provisioning is a separate
/// step from the first write so that the race between concurrent first requests
/// is resolved in one place rather than inside every caller.
/// </remarks>
public sealed class UserProvisioning(
    IKeyWrapper keyWrapper,
    IUserRepository users,
    TimeProvider clock)
{
    /// <summary>Ensures the User has a KEK and a row. Safe to call on every request.</summary>
    public async Task EnsureProvisionedAsync(UserId id, CancellationToken cancellationToken = default)
    {
        if (await users.ExistsAsync(id, cancellationToken).ConfigureAwait(false))
        {
            return;
        }

        // KEK first. If the row write fails afterwards, the next request retries
        // and finds the existing KEK — a User with a KEK and no row is
        // recoverable, whereas a row promising a Vault whose KEK was never
        // created is not.
        await keyWrapper.EnsureKekAsync(id, cancellationToken).ConfigureAwait(false);

        await users.AddIfAbsentAsync(
            User.Provision(id, clock.GetUtcNow()), cancellationToken).ConfigureAwait(false);
    }
}

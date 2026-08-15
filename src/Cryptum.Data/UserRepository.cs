using Cryptum.Domain;
using Microsoft.EntityFrameworkCore;

namespace Cryptum.Data;

/// <summary>Stores <see cref="User"/> rows.</summary>
public sealed class UserRepository(CryptumDbContext db) : IUserRepository
{
    public Task<bool> ExistsAsync(UserId id, CancellationToken cancellationToken = default) =>
        db.Users.AnyAsync(u => u.Id == id, cancellationToken);

    public async Task<bool> AddIfAbsentAsync(User user, CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(user);

        db.Users.Add(user);

        try
        {
            await db.SaveChangesAsync(cancellationToken).ConfigureAwait(false);
            return true;
        }
        catch (DbUpdateException)
        {
            // The primary key is the identity provider's subject, so a duplicate
            // means a concurrent request provisioned this User first. That is the
            // expected outcome of the race, not a fault — losing it is success.
            db.Entry(user).State = EntityState.Detached;
            return false;
        }
    }
}

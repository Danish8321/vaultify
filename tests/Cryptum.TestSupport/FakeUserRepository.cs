using System.Collections.Concurrent;
using Cryptum.Domain;

namespace Cryptum.TestSupport;

/// <summary>
/// In-memory <see cref="IUserRepository"/>.
/// </summary>
/// <remarks>
/// <see cref="AddIfAbsentAsync"/> uses <c>TryAdd</c> so the insert is genuinely
/// atomic, matching what a unique constraint gives in SQL. A fake that checked
/// then inserted would never lose the race the real database can.
/// </remarks>
public sealed class FakeUserRepository : IUserRepository
{
    private readonly ConcurrentDictionary<UserId, User> users = new();

    public int Count => users.Count;

    public Task<bool> ExistsAsync(UserId id, CancellationToken cancellationToken = default) =>
        Task.FromResult(users.ContainsKey(id));

    public Task<bool> AddIfAbsentAsync(User user, CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(user);
        return Task.FromResult(users.TryAdd(user.Id, user));
    }
}

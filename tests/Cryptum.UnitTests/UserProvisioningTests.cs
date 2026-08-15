using Cryptum.Domain;
using Cryptum.TestSupport;

namespace Cryptum.UnitTests;

/// <summary>
/// Provisioning a User on their first authenticated request (plan task 2.4).
/// </summary>
public sealed class UserProvisioningTests
{
    private const int Racers = 8;

    [Fact]
    public async Task Concurrent_first_requests_for_one_subject_create_exactly_one_kek()
    {
        using var keyWrapper = new InMemoryKeyWrapper();

        // Without this gate the test proves nothing: the in-memory fakes complete
        // synchronously, so the first caller would finish provisioning before the
        // second even started and no race would occur.
        var gate = new ConcurrencyGate(Racers);
        var users = new GatedUserRepository(new FakeUserRepository(), gate);

        var provisioning = new UserProvisioning(keyWrapper, users, TimeProvider.System);
        var owner = new UserId(Guid.NewGuid());

        await Task.WhenAll(Enumerable.Range(0, Racers)
            .Select(_ => Task.Run(() => provisioning.EnsureProvisionedAsync(owner)))
            .ToArray());

        // A second KEK would orphan every DEK wrapped under the first —
        // silent, unrecoverable data loss.
        Assert.Equal(1, keyWrapper.KeksCreatedFor(owner));
    }

    [Fact]
    public async Task Provisioning_an_already_provisioned_user_creates_no_second_kek()
    {
        using var keyWrapper = new InMemoryKeyWrapper();
        var provisioning = new UserProvisioning(keyWrapper, new FakeUserRepository(), TimeProvider.System);
        var owner = new UserId(Guid.NewGuid());

        await provisioning.EnsureProvisionedAsync(owner);
        await provisioning.EnsureProvisionedAsync(owner);

        Assert.Equal(1, keyWrapper.KeksCreatedFor(owner));
    }

    [Fact]
    public async Task A_provisioned_user_can_wrap_and_unwrap()
    {
        using var keyWrapper = new InMemoryKeyWrapper();
        var provisioning = new UserProvisioning(keyWrapper, new FakeUserRepository(), TimeProvider.System);
        var owner = new UserId(Guid.NewGuid());
        var dek = new byte[32];
        Random.Shared.NextBytes(dek);

        await provisioning.EnsureProvisionedAsync(owner);

        var wrapped = await keyWrapper.WrapAsync(owner, dek);
        using var unwrapped = await keyWrapper.UnwrapAsync(owner, wrapped);

        Assert.Equal(dek, unwrapped.Span.ToArray());
    }

    [Fact]
    public async Task An_unprovisioned_user_cannot_wrap()
    {
        using var keyWrapper = new InMemoryKeyWrapper();
        var owner = new UserId(Guid.NewGuid());

        // Wrap must not quietly create a KEK: a crypto-shredded account would
        // then regrow a Vault on its next write (ADR-0003).
        await Assert.ThrowsAsync<KeyUnavailableException>(
            () => keyWrapper.WrapAsync(owner, new byte[32]));
    }

    /// <summary>Releases its waiters only once <paramref name="count"/> of them have arrived.</summary>
    private sealed class ConcurrencyGate(int count)
    {
        private readonly TaskCompletionSource open = new(TaskCreationOptions.RunContinuationsAsynchronously);
        private int arrived;

        public Task WaitForAllAsync()
        {
            if (Interlocked.Increment(ref arrived) >= count)
            {
                open.TrySetResult();
            }

            return open.Task;
        }
    }

    /// <summary>Holds every caller inside the existence check until all have entered it.</summary>
    private sealed class GatedUserRepository(IUserRepository inner, ConcurrencyGate gate) : IUserRepository
    {
        public async Task<bool> ExistsAsync(UserId id, CancellationToken cancellationToken = default)
        {
            var exists = await inner.ExistsAsync(id, cancellationToken);
            await gate.WaitForAllAsync();
            return exists;
        }

        public Task<bool> AddIfAbsentAsync(User user, CancellationToken cancellationToken = default) =>
            inner.AddIfAbsentAsync(user, cancellationToken);
    }
}

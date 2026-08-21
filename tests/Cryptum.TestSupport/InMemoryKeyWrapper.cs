using System.Collections.Concurrent;
using System.Security.Cryptography;
using Cryptum.Domain;

namespace Cryptum.TestSupport;

/// <summary>
/// Test double for <see cref="IKeyWrapper"/>, backed by real in-process RSA keys.
/// </summary>
/// <remarks>
/// Uses genuine RSA-OAEP rather than a stub so tests exercise the same failure
/// modes as production (wrong key fails to unwrap, shredded key is gone). Without
/// this seam every test touching the crypto path would need live Azure
/// credentials, which would leave the most security-critical code the least tested.
/// </remarks>
public sealed class InMemoryKeyWrapper : IKeyWrapper, IDisposable
{
    private readonly ConcurrentDictionary<UserId, RSA> keks = new();
    private readonly ConcurrentDictionary<UserId, RSA> softDeletedKeks = new();
    private readonly ConcurrentDictionary<UserId, int> kekCreations = new();

    public int UnwrapCount { get; private set; }

    /// <summary>
    /// True while the owner's KEK is soft-deleted but not yet purged — the
    /// window in which a real Key Vault admin could still recover it (ADR-0003,
    /// ticket 22). Lets a test tell "delete only" from "delete and purge" without
    /// live Azure, which a fake that removes the key in one step cannot.
    /// </summary>
    public bool IsRecoverable(UserId owner) => softDeletedKeks.ContainsKey(owner);

    /// <summary>Soft-deletes the KEK without purging it — the incomplete shred ticket 22 found in production.</summary>
    public Task DeleteWithoutPurgeAsync(UserId owner, CancellationToken cancellationToken = default)
    {
        if (keks.TryRemove(owner, out var kek))
        {
            softDeletedKeks[owner] = kek;
        }

        return Task.CompletedTask;
    }

    /// <summary>How many distinct KEKs this wrapper has created for the owner.</summary>
    /// <remarks>
    /// Exists so a test can assert that a provisioning race produced exactly one
    /// KEK. Counting creations is the only way to observe the second KEK — the
    /// dictionary would look identical either way.
    /// </remarks>
    public int KeksCreatedFor(UserId owner) => kekCreations.GetValueOrDefault(owner);

    public Task EnsureKekAsync(UserId owner, CancellationToken cancellationToken = default)
    {
        // Counted this way on purpose. ConcurrentDictionary.GetOrAdd may invoke
        // its factory more than once under contention and discard the losers, so
        // counting inside the factory would count attempts rather than the KEK
        // that actually ended up installed — and the test would report a race
        // that never happened.
        var candidate = RSA.Create(2048);
        var installed = keks.GetOrAdd(owner, candidate);

        if (ReferenceEquals(installed, candidate))
        {
            kekCreations.AddOrUpdate(owner, 1, (_, count) => count + 1);
        }
        else
        {
            candidate.Dispose();
        }

        return Task.CompletedTask;
    }

    public Task<WrappedDek> WrapAsync(UserId owner, ReadOnlyMemory<byte> dek, CancellationToken cancellationToken = default)
    {
        // Deliberately does NOT create the KEK on demand. A fake more forgiving
        // than production hides exactly the bug it should catch: an unprovisioned
        // User whose first write would fail against real Key Vault.
        if (!keks.TryGetValue(owner, out var kek))
        {
            throw new KeyUnavailableException($"No KEK for {owner}; the User was never provisioned.");
        }

        var wrapped = kek.Encrypt(dek.ToArray(), RSAEncryptionPadding.OaepSHA256);
        return Task.FromResult(new WrappedDek(wrapped, "v1"));
    }

    public Task<PlaintextDek> UnwrapAsync(UserId owner, WrappedDek wrapped, CancellationToken cancellationToken = default)
    {
        UnwrapCount++;

        if (!keks.TryGetValue(owner, out var kek))
        {
            throw new KeyUnavailableException($"No KEK for {owner}.");
        }

        return Task.FromResult(new PlaintextDek(kek.Decrypt(wrapped.Value, RSAEncryptionPadding.OaepSHA256)));
    }

    public Task CryptoShredAsync(UserId owner, CancellationToken cancellationToken = default)
    {
        // Mirrors KeyVaultKeyWrapper.CryptoShredAsync: delete moves the key into
        // the soft-delete window, purge is the step that makes it unrecoverable.
        if (keks.TryRemove(owner, out var kek))
        {
            softDeletedKeks[owner] = kek;
        }

        if (softDeletedKeks.TryRemove(owner, out var deleted))
        {
            deleted.Dispose();
        }

        return Task.CompletedTask;
    }

    public void Dispose()
    {
        foreach (var kek in keks.Values)
        {
            kek.Dispose();
        }

        foreach (var kek in softDeletedKeks.Values)
        {
            kek.Dispose();
        }

        keks.Clear();
        softDeletedKeks.Clear();
    }
}

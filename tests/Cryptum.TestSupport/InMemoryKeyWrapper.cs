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

    public int UnwrapCount { get; private set; }

    public Task<WrappedDek> WrapAsync(UserId owner, ReadOnlyMemory<byte> dek, CancellationToken cancellationToken = default)
    {
        var kek = keks.GetOrAdd(owner, _ => RSA.Create(2048));
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
        if (keks.TryRemove(owner, out var kek))
        {
            kek.Dispose();
        }

        return Task.CompletedTask;
    }

    public void Dispose()
    {
        foreach (var kek in keks.Values)
        {
            kek.Dispose();
        }

        keks.Clear();
    }
}

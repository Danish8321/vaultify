using System.Security.Cryptography;
using Cryptum.Domain;
using Cryptum.TestSupport;

namespace Cryptum.UnitTests;

/// <summary>
/// The behaviour any <see cref="IKeyWrapper"/> must exhibit. Run here against the
/// in-memory fake; the Key Vault implementation is held to the same assertions in
/// the integration suite once infrastructure exists (plan task 2.3).
/// </summary>
public sealed class KeyWrapperContractTests
{
    private static byte[] NewDek()
    {
        var dek = new byte[32]; // AES-256.
        RandomNumberGenerator.Fill(dek);
        return dek;
    }

    [Fact]
    public async Task Wrap_then_unwrap_returns_the_original_dek()
    {
        using var wrapper = new InMemoryKeyWrapper();
        var owner = new UserId(Guid.CreateVersion7());
        await wrapper.EnsureKekAsync(owner);
        var dek = NewDek();

        var wrapped = await wrapper.WrapAsync(owner, dek);
        using var unwrapped = await wrapper.UnwrapAsync(owner, wrapped);

        Assert.True(unwrapped.Span.SequenceEqual(dek));
    }

    [Fact]
    public async Task Wrapped_dek_does_not_contain_the_plaintext_dek()
    {
        using var wrapper = new InMemoryKeyWrapper();
        var owner = new UserId(Guid.CreateVersion7());
        await wrapper.EnsureKekAsync(owner);
        var dek = NewDek();

        var wrapped = await wrapper.WrapAsync(owner, dek);

        Assert.NotEqual(dek, wrapped.Value);
        Assert.False(ContainsSequence(wrapped.Value, dek), "wrapped DEK leaked the plaintext key");
    }

    [Fact]
    public async Task One_users_kek_cannot_unwrap_another_users_dek()
    {
        // The isolation ADR-0001 buys with per-User KEKs. Asserted so a future
        // "optimization" to a shared KEK cannot pass silently.
        using var wrapper = new InMemoryKeyWrapper();
        var alice = new UserId(Guid.CreateVersion7());
        var mallory = new UserId(Guid.CreateVersion7());
        await wrapper.EnsureKekAsync(alice);
        await wrapper.EnsureKekAsync(mallory);

        var wrapped = await wrapper.WrapAsync(alice, NewDek());

        await Assert.ThrowsAnyAsync<CryptographicException>(
            () => wrapper.UnwrapAsync(mallory, wrapped));
    }

    [Fact]
    public async Task Unwrap_after_crypto_shred_fails_permanently()
    {
        // This is the whole promise of ADR-0003: the ciphertext may still exist,
        // but without the KEK it can never be read again.
        using var wrapper = new InMemoryKeyWrapper();
        var owner = new UserId(Guid.CreateVersion7());
        await wrapper.EnsureKekAsync(owner);
        var wrapped = await wrapper.WrapAsync(owner, NewDek());

        await wrapper.CryptoShredAsync(owner);

        await Assert.ThrowsAsync<KeyUnavailableException>(
            () => wrapper.UnwrapAsync(owner, wrapped));
    }

    [Fact]
    public async Task Crypto_shred_is_idempotent()
    {
        // The async purge worker (plan task 4.2) retries; a second shred must not throw.
        using var wrapper = new InMemoryKeyWrapper();
        var owner = new UserId(Guid.CreateVersion7());
        await wrapper.EnsureKekAsync(owner);
        await wrapper.WrapAsync(owner, NewDek());

        await wrapper.CryptoShredAsync(owner);
        await wrapper.CryptoShredAsync(owner);
    }

    [Fact]
    public async Task Wrapping_the_same_dek_twice_yields_different_ciphertext()
    {
        // RSA-OAEP is randomized. Deterministic output would let an observer
        // correlate identical DEKs across Items.
        using var wrapper = new InMemoryKeyWrapper();
        var owner = new UserId(Guid.CreateVersion7());
        await wrapper.EnsureKekAsync(owner);
        var dek = NewDek();

        var first = await wrapper.WrapAsync(owner, dek);
        var second = await wrapper.WrapAsync(owner, dek);

        Assert.NotEqual(first.Value, second.Value);
    }

    [Fact]
    public async Task Disposing_an_unwrapped_dek_zeroes_it()
    {
        // Bounds how long key material survives in process memory after use.
        // Asserted because "we clear the buffer" is the kind of claim that
        // silently stops being true.
        using var wrapper = new InMemoryKeyWrapper();
        var owner = new UserId(Guid.CreateVersion7());
        await wrapper.EnsureKekAsync(owner);
        var wrapped = await wrapper.WrapAsync(owner, NewDek());

        var unwrapped = await wrapper.UnwrapAsync(owner, wrapped);
        Assert.False(unwrapped.Span.IndexOfAnyExcept((byte)0) < 0, "DEK was all zeroes before disposal");

        unwrapped.Dispose();

        Assert.Throws<ObjectDisposedException>(() => _ = unwrapped.Span.Length);
    }

    [Fact]
    public async Task Disposing_a_dek_twice_is_safe()
    {
        // Disposal runs from `using` blocks and from explicit cleanup paths;
        // a double dispose must not throw during error handling.
        using var wrapper = new InMemoryKeyWrapper();
        var owner = new UserId(Guid.CreateVersion7());
        await wrapper.EnsureKekAsync(owner);
        var wrapped = await wrapper.WrapAsync(owner, NewDek());

        var unwrapped = await wrapper.UnwrapAsync(owner, wrapped);
        unwrapped.Dispose();
        unwrapped.Dispose();
    }

    private static bool ContainsSequence(byte[] haystack, byte[] needle)
    {
        for (var i = 0; i + needle.Length <= haystack.Length; i++)
        {
            if (haystack.AsSpan(i, needle.Length).SequenceEqual(needle))
            {
                return true;
            }
        }

        return false;
    }
}

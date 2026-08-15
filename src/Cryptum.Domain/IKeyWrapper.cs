namespace Cryptum.Domain;

/// <summary>
/// Wraps and unwraps per-Item DEKs using a per-User KEK that never leaves the
/// key management service (ADR-0001, ADR-0002).
/// </summary>
/// <remarks>
/// Implementations must never log, persist, or otherwise retain the plaintext
/// DEK passed to or returned from these methods.
/// </remarks>
public interface IKeyWrapper
{
    /// <summary>
    /// Creates the User's KEK if it does not already exist. Idempotent.
    /// </summary>
    /// <remarks>
    /// Provisioning is explicit rather than a side effect of the first wrap:
    /// concurrent first requests must converge on one KEK, and a second KEK
    /// would orphan every DEK wrapped under the first. Implementations must
    /// treat a concurrent create as success, not as an error.
    /// </remarks>
    Task EnsureKekAsync(UserId owner, CancellationToken cancellationToken = default);

    /// <summary>Wraps a plaintext DEK under the User's KEK.</summary>
    /// <exception cref="KeyUnavailableException">The User has not been provisioned, or was crypto-shredded.</exception>
    Task<WrappedDek> WrapAsync(UserId owner, ReadOnlyMemory<byte> dek, CancellationToken cancellationToken = default);

    /// <summary>Unwraps a DEK previously wrapped under the same User's KEK.</summary>
    /// <remarks>The caller owns the returned key and must dispose it; see <see cref="PlaintextDek"/>.</remarks>
    /// <exception cref="KeyUnavailableException">The User's KEK does not exist or has been crypto-shredded (ADR-0003).</exception>
    Task<PlaintextDek> UnwrapAsync(UserId owner, WrappedDek wrapped, CancellationToken cancellationToken = default);

    /// <summary>Destroys the User's KEK, rendering every DEK it wrapped permanently unusable (ADR-0003).</summary>
    Task CryptoShredAsync(UserId owner, CancellationToken cancellationToken = default);
}

/// <summary>
/// A DEK in wrapped form. This is the only form in which a DEK may be persisted.
/// </summary>
/// <param name="Value">Ciphertext of the DEK under the owner's KEK.</param>
/// <param name="KekVersion">
/// Identifies which KEK version produced this wrapping, so a future rotation
/// (ADR-0005 defers it) can tell re-wrapped DEKs from stale ones.
/// </param>
public readonly record struct WrappedDek(byte[] Value, string KekVersion);

/// <summary>Raised when a KEK is absent — typically because the account was crypto-shredded.</summary>
public sealed class KeyUnavailableException(string message) : Exception(message);

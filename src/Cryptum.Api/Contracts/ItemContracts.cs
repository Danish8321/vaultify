using System.ComponentModel.DataAnnotations;
using Cryptum.Domain;

namespace Cryptum.Api.Contracts;

/// <summary>
/// Create a Secret. The client has already encrypted the content; the server
/// receives ciphertext plus the DEK that produced it, and wraps that DEK.
/// </summary>
/// <remarks>
/// There is deliberately no owner field. Ownership comes from the validated
/// token (see <see cref="Auth.CallerIdentity"/>); accepting it here would let a
/// caller write into someone else's Vault.
/// </remarks>
public sealed record CreateSecretRequest
{
    [Required]
    [StringLength(Item.MaxTitleLength, MinimumLength = 1)]
    public required string Title { get; init; }

    /// <summary>AES-256-GCM ciphertext of the serialized secret fields, with the tag appended.</summary>
    [Required]
    [MaxLength(MaxCiphertextBytes)]
    public required byte[] Ciphertext { get; init; }

    /// <summary>The 96-bit nonce used for this ciphertext.</summary>
    [Required]
    [MinLength(Item.NonceLength)]
    [MaxLength(Item.NonceLength)]
    public required byte[] Nonce { get; init; }

    /// <summary>The plaintext DEK, to be wrapped and discarded. Never persisted as given.</summary>
    [Required]
    [MinLength(MinDekBytes)]
    [MaxLength(MaxDekBytes)]
    public required byte[] Dek { get; init; }

    /// <summary>
    /// Bound on inline secret ciphertext. A Secret is a handful of short fields;
    /// anything larger is a File and belongs in blob storage. Also caps how much
    /// a single request can commit to the database.
    /// </summary>
    public const int MaxCiphertextBytes = 64 * 1024;

    // AES-256 is 32 bytes. The range is tight because a DEK outside it means the
    // client is not doing what the protocol says it is.
    public const int MinDekBytes = 32;
    public const int MaxDekBytes = 32;
}

/// <summary>An Item as returned for reading: ciphertext plus the unwrapped DEK to decrypt it.</summary>
public sealed record ItemResponse
{
    public required Guid Id { get; init; }

    public required string Title { get; init; }

    public required byte[] Ciphertext { get; init; }

    public required byte[] Nonce { get; init; }

    /// <summary>
    /// The unwrapped DEK, for this caller, for this Item, right now.
    /// </summary>
    /// <remarks>
    /// This field is the reason Cryptum is server-blind rather than
    /// zero-knowledge (ADR-0001): the plaintext DEK necessarily crosses the
    /// network on every read. It must never be logged (see
    /// docs/security-requirements.md) and must not be cached client-side.
    /// </remarks>
    public required byte[] Dek { get; init; }

    public required DateTimeOffset UpdatedAt { get; init; }
}

/// <summary>List-view row. Carries no secret material by construction.</summary>
public sealed record ItemSummaryResponse
{
    public required Guid Id { get; init; }

    public required string Kind { get; init; }

    public required string Title { get; init; }

    public required DateTimeOffset UpdatedAt { get; init; }
}

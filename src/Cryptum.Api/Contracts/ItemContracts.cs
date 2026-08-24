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

/// <summary>
/// The id of a newly created Item.
/// </summary>
/// <remarks>
/// A named type rather than an anonymous object: the Android client is generated
/// from this contract, and an anonymous shape would generate nothing.
/// </remarks>
public sealed record CreatedItemResponse
{
    public required Guid Id { get; init; }
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

/// <summary>
/// Replace a Secret's content. Same shape as <see cref="CreateSecretRequest"/>
/// because an edit is a fresh encryption, not a patch.
/// </summary>
/// <remarks>
/// A partial update is deliberately not offered. The server cannot read the
/// fields inside the ciphertext, so it could not merge them — the client must
/// re-encrypt the whole secret under a fresh DEK and nonce (ADR-0006). No owner
/// field, and no version field: ownership and numbering are both server-decided.
/// </remarks>
public sealed record UpdateSecretRequest
{
    [Required]
    [StringLength(Item.MaxTitleLength, MinimumLength = 1)]
    public required string Title { get; init; }

    [Required]
    [MaxLength(CreateSecretRequest.MaxCiphertextBytes)]
    public required byte[] Ciphertext { get; init; }

    [Required]
    [MinLength(Item.NonceLength)]
    [MaxLength(Item.NonceLength)]
    public required byte[] Nonce { get; init; }

    [Required]
    [MinLength(CreateSecretRequest.MinDekBytes)]
    [MaxLength(CreateSecretRequest.MaxDekBytes)]
    public required byte[] Dek { get; init; }
}

/// <summary>
/// Register a File. The client encrypts and uploads the ciphertext directly to
/// blob storage using the returned SAS — this call never carries file bytes.
/// </summary>
public sealed record CreateFileRequest
{
    [Required]
    [StringLength(Item.MaxTitleLength, MinimumLength = 1)]
    public required string Title { get; init; }

    /// <summary>
    /// Ciphertext size in bytes, as the client is about to upload it. An
    /// int, not a long: the 25MB cap fits comfortably. No [Range] here —
    /// .NET's OpenAPI generator emits a `type: [integer, string]` union
    /// with a regex pattern (JS-safe-integer precision) for ANY property
    /// carrying [Range], regardless of which constructor overload it
    /// resolves to. openapi-generator's Kotlin/Ktor client can't represent
    /// that union and silently produces an empty stub class. The bound is
    /// instead enforced where it already was redundantly enforced: the
    /// endpoint's manual validation and VaultService.CreateFileAsync's
    /// quota check (FileQuotaExceededException).
    /// </summary>
    [Required]
    public required int SizeBytes { get; init; }

    /// <summary>The 96-bit nonce used for this ciphertext.</summary>
    [Required]
    [MinLength(Item.NonceLength)]
    [MaxLength(Item.NonceLength)]
    public required byte[] Nonce { get; init; }

    /// <summary>The plaintext DEK, to be wrapped and discarded. Never persisted as given.</summary>
    [Required]
    [MinLength(CreateSecretRequest.MinDekBytes)]
    [MaxLength(CreateSecretRequest.MaxDekBytes)]
    public required byte[] Dek { get; init; }
}

/// <summary>A newly registered File, with the SAS to upload its ciphertext to.</summary>
public sealed record CreateFileResponse
{
    public required Guid Id { get; init; }

    /// <summary>Valid for <see cref="BlobSasLifetime.Upload"/>. A single write only.</summary>
    public required Uri UploadUri { get; init; }
}

/// <summary>A File as returned for reading: metadata plus a SAS to download its ciphertext.</summary>
public sealed record FileResponse
{
    public required Guid Id { get; init; }

    public required string Title { get; init; }

    /// <summary>See the remarks on <see cref="CreateFileRequest.SizeBytes"/> for why this is an int.</summary>
    public required int SizeBytes { get; init; }

    public required byte[] Nonce { get; init; }

    /// <summary>Same exposure and handling rules as <see cref="ItemResponse.Dek"/>.</summary>
    public required byte[] Dek { get; init; }

    /// <summary>Valid for <see cref="BlobSasLifetime.Download"/>. A single read only.</summary>
    public required Uri DownloadUri { get; init; }

    public required DateTimeOffset UpdatedAt { get; init; }
}

/// <summary>
/// One entry in an Item's history list. Metadata only.
/// </summary>
/// <remarks>
/// No ciphertext and no DEK: a history list is a per-Item enumeration, so
/// carrying content here would turn one request into a bulk download of every
/// revision. Reading a version is a separate, rate-limited call.
/// </remarks>
public sealed record ItemVersionSummaryResponse
{
    public required int VersionNumber { get; init; }

    public required DateTimeOffset ArchivedAt { get; init; }
}

/// <summary>An archived version, with the DEK it was encrypted under.</summary>
public sealed record ItemVersionResponse
{
    public required int VersionNumber { get; init; }

    public required byte[] Ciphertext { get; init; }

    public required byte[] Nonce { get; init; }

    /// <summary>
    /// The version's own unwrapped DEK — not the Item's current one.
    /// </summary>
    /// <remarks>
    /// Same exposure as <see cref="ItemResponse.Dek"/> and the same rule: never
    /// logged. Each version keeps its own DEK, so restoring never re-encrypts.
    /// </remarks>
    public required byte[] Dek { get; init; }

    public required DateTimeOffset ArchivedAt { get; init; }
}

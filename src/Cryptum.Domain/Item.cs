namespace Cryptum.Domain;

/// <summary>What kind of content an <see cref="Item"/> holds.</summary>
public enum ItemKind
{
    Secret = 1,
    File = 2,
}

/// <summary>
/// A single stored unit belonging to exactly one User, encrypted under its own DEK.
/// </summary>
/// <remarks>
/// The title is deliberately stored unencrypted so Vaults can be listed without an
/// unwrap per row. That is an accepted, documented disclosure limited to titles
/// (see docs/security-requirements.md); no other field may follow it into plaintext.
/// </remarks>
public sealed class Item
{
    public const int MaxTitleLength = 200;

    /// <summary>Nonce length for AES-256-GCM, in bytes (96 bits).</summary>
    public const int NonceLength = 12;

    /// <summary>Container-relative blob name, not free text.</summary>
    public const int MaxBlobPathLength = 512;

    /// <summary>An Azure Key Vault key version is a 32-character hex string.</summary>
    public const int MaxKekVersionLength = 64;

    private Item() { } // EF Core.

    public ItemId Id { get; private init; }

    public UserId Owner { get; private init; }

    public ItemKind Kind { get; private init; }

    /// <summary>Unencrypted. See remarks on <see cref="Item"/>.</summary>
    public string Title { get; private set; } = string.Empty;

    /// <summary>Ciphertext for a Secret. Null for a File, whose ciphertext lives in blob storage.</summary>
    public byte[]? Ciphertext { get; private set; }

    /// <summary>Blob path for a File. Null for a Secret.</summary>
    public string? BlobPath { get; private set; }

    /// <summary>The Item's DEK, wrapped under the owner's KEK. Never stored unwrapped.</summary>
    public byte[] WrappedDek { get; private set; } = [];

    public string KekVersion { get; private set; } = string.Empty;

    /// <summary>The AES-GCM nonce for <see cref="Ciphertext"/>. Not secret, but never reused.</summary>
    public byte[] Nonce { get; private set; } = [];

    public DateTimeOffset CreatedAt { get; private init; }

    public DateTimeOffset UpdatedAt { get; private set; }

    /// <summary>Set when the account is deleted; rows are purged asynchronously afterwards (ADR-0003).</summary>
    public DateTimeOffset? DeletedAt { get; private set; }

    public static Item CreateSecret(
        UserId owner,
        string title,
        byte[] ciphertext,
        byte[] nonce,
        WrappedDek wrappedDek,
        DateTimeOffset now)
    {
        ValidateTitle(title);
        ValidateNonce(nonce);
        ArgumentNullException.ThrowIfNull(ciphertext);

        return new Item
        {
            Id = ItemId.New(),
            Owner = owner,
            Kind = ItemKind.Secret,
            Title = title,
            Ciphertext = ciphertext,
            Nonce = nonce,
            WrappedDek = wrappedDek.Value,
            KekVersion = wrappedDek.KekVersion,
            CreatedAt = now,
            UpdatedAt = now,
        };
    }

    /// <summary>
    /// A File Item. The ciphertext lives in blob storage; only the pointer,
    /// the wrapped DEK and the nonce are stored here.
    /// </summary>
    public static Item CreateFile(
        UserId owner,
        string title,
        string blobPath,
        byte[] nonce,
        WrappedDek wrappedDek,
        DateTimeOffset now)
    {
        ValidateTitle(title);
        ValidateNonce(nonce);
        ArgumentException.ThrowIfNullOrWhiteSpace(blobPath);
        if (blobPath.Length > MaxBlobPathLength)
        {
            throw new ArgumentException($"Blob path exceeds {MaxBlobPathLength} characters.", nameof(blobPath));
        }

        return new Item
        {
            Id = ItemId.New(),
            Owner = owner,
            Kind = ItemKind.File,
            Title = title,
            BlobPath = blobPath,
            Nonce = nonce,
            WrappedDek = wrappedDek.Value,
            KekVersion = wrappedDek.KekVersion,
            CreatedAt = now,
            UpdatedAt = now,
        };
    }

    /// <summary>
    /// Number of versions this Item has ever archived. The next version's number,
    /// not the number currently retained — pruning must not renumber history.
    /// </summary>
    public int VersionCount { get; private set; }

    /// <summary>
    /// Replaces content under a freshly generated DEK and nonce, returning the
    /// content it displaced as an archived version (ADR-0006).
    /// </summary>
    /// <remarks>
    /// Requiring a new DEK on every write is what makes AES-GCM nonce reuse
    /// structurally impossible rather than merely unlikely: no DEK ever encrypts
    /// more than one message.
    ///
    /// <para>
    /// The archive is returned rather than held in a collection on the Item so
    /// the caller cannot edit without dealing with the displaced content: the
    /// old MVP behaviour, silently overwriting the only copy, is no longer
    /// expressible at this seam.
    /// </para>
    /// </remarks>
    public ItemVersion ReplaceContent(
        string title,
        byte[] ciphertext,
        byte[] nonce,
        WrappedDek wrappedDek,
        DateTimeOffset now)
    {
        ValidateTitle(title);
        ValidateNonce(nonce);
        ArgumentNullException.ThrowIfNull(ciphertext);

        // Archived before any field is touched — it captures the outgoing state.
        var archived = ItemVersion.Archive(this, ++VersionCount, now);

        Title = title;
        Ciphertext = ciphertext;
        Nonce = nonce;
        WrappedDek = wrappedDek.Value;
        KekVersion = wrappedDek.KekVersion;
        UpdatedAt = now;

        return archived;
    }

    public void MarkDeleted(DateTimeOffset now) => DeletedAt = now;

    private static void ValidateTitle(string title)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(title);
        if (title.Length > MaxTitleLength)
        {
            throw new ArgumentException($"Title exceeds {MaxTitleLength} characters.", nameof(title));
        }
    }

    private static void ValidateNonce(byte[] nonce)
    {
        ArgumentNullException.ThrowIfNull(nonce);
        if (nonce.Length != NonceLength)
        {
            throw new ArgumentException($"Nonce must be exactly {NonceLength} bytes.", nameof(nonce));
        }
    }
}

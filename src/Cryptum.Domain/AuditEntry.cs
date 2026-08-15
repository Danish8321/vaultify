namespace Cryptum.Domain;

/// <summary>What happened. Kept coarse so the log stays readable under volume.</summary>
public enum AuditAction
{
    ItemCreated = 1,
    ItemRead = 2,
    ItemListed = 3,
    DekWrapped = 4,
    DekUnwrapped = 5,
    AccountCryptoShredded = 6,
    AccessDenied = 7,
}

/// <summary>
/// One immutable record of a security-relevant action.
/// </summary>
/// <remarks>
/// ADR-0002 concentrates the ability to unwrap any User's DEK in the backend's
/// identity. Nothing in the architecture prevents that capability from being
/// abused; the audit trail is what makes the abuse visible afterwards, which is
/// why it is a control rather than diagnostics. It therefore records that an
/// action happened and to which Item — never key material, ciphertext, or
/// request bodies.
/// </remarks>
public sealed class AuditEntry
{
    private AuditEntry() { } // EF Core.

    public long Id { get; private init; }

    public UserId Actor { get; private init; }

    public AuditAction Action { get; private init; }

    /// <summary>Null for account-wide actions that name no single Item.</summary>
    public Guid? ItemId { get; private init; }

    public bool Succeeded { get; private init; }

    public DateTimeOffset OccurredAt { get; private init; }

    public static AuditEntry Record(
        UserId actor,
        AuditAction action,
        DateTimeOffset now,
        ItemId? itemId = null,
        bool succeeded = true) =>
        new()
        {
            Actor = actor,
            Action = action,
            ItemId = itemId?.Value,
            Succeeded = succeeded,
            OccurredAt = now,
        };
}

/// <summary>Append-only sink for <see cref="AuditEntry"/>.</summary>
/// <remarks>
/// Exposes no update or delete operation: the interface itself refuses to
/// describe a way to alter history, so tampering cannot be expressed through it.
/// The database principal is separately restricted to INSERT (see
/// docs/security-requirements.md), because an interface convention alone would
/// not stop direct SQL.
/// </remarks>
public interface IAuditLog
{
    Task RecordAsync(AuditEntry entry, CancellationToken cancellationToken = default);
}

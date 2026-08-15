namespace Cryptum.Domain;

/// <summary>
/// A Cryptum User, derived from the Azure AD B2C subject claim (ADR-0004).
/// </summary>
/// <remarks>
/// Distinct from <see cref="ItemId"/> so the two can never be transposed at a
/// call site — the compiler enforces what a convention would only suggest.
/// </remarks>
public readonly record struct UserId(Guid Value)
{
    public override string ToString() => Value.ToString();
}

/// <summary>Identity of an <c>Item</c>. Stable across edits (ADR-0006).</summary>
public readonly record struct ItemId(Guid Value)
{
    public static ItemId New() => new(Guid.CreateVersion7());

    public override string ToString() => Value.ToString();
}

using System.Security.Claims;
using Cryptum.Domain;

namespace Cryptum.Api.Auth;

/// <summary>
/// Resolves the acting <see cref="UserId"/> from the validated access token.
/// </summary>
/// <remarks>
/// The only sanctioned source of caller identity. A request body must never
/// supply an owner id — that is the classic privilege-escalation hole, and
/// routing every endpoint through this method is what makes it unavailable
/// rather than merely discouraged.
/// </remarks>
public static class CallerIdentity
{
    /// <summary>The B2C subject claim, stable per user across tokens and devices (ADR-0004).</summary>
    public const string SubjectClaim = "sub";

    /// <summary>
    /// Maps the token subject onto a Cryptum <see cref="UserId"/>.
    /// </summary>
    /// <remarks>
    /// B2C subjects are opaque strings, not necessarily GUIDs, so the subject is
    /// hashed into a stable v5-style GUID rather than parsed. Deterministic, so
    /// the same user resolves to the same Vault on every device and after any
    /// token refresh.
    /// </remarks>
    public static bool TryResolve(ClaimsPrincipal? principal, out UserId userId)
    {
        userId = default;

        var subject = principal?.FindFirstValue(SubjectClaim)
                   ?? principal?.FindFirstValue(ClaimTypes.NameIdentifier);

        if (string.IsNullOrWhiteSpace(subject))
        {
            return false;
        }

        userId = UserIdFromSubject(subject);
        return true;
    }

    internal static UserId UserIdFromSubject(string subject)
    {
        var hash = System.Security.Cryptography.SHA256.HashData(
            System.Text.Encoding.UTF8.GetBytes(subject));

        return new UserId(new Guid(hash.AsSpan(0, 16)));
    }
}

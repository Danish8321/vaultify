using System.Security.Claims;
using Cryptum.Api.Auth;
using Cryptum.Domain;

namespace Cryptum.Api.Endpoints;

/// <summary>Account lifecycle. Currently one route, and it is irreversible.</summary>
public static class AccountEndpoints
{
    public static IEndpointRouteBuilder MapAccountEndpoints(this IEndpointRouteBuilder app)
    {
        ArgumentNullException.ThrowIfNull(app);

        var group = app.MapGroup("/account")
            .RequireAuthorization()
            .WithTags("Account")
            .ProducesProblem(StatusCodes.Status401Unauthorized)
            .ProducesProblem(StatusCodes.Status429TooManyRequests);

        // No id in the route. The account deleted is always the caller's, so
        // there is no parameter an attacker could point at someone else.
        group.MapDelete("/", DeleteAsync)
            .WithName("DeleteAccount")
            .Produces(StatusCodes.Status204NoContent)
            .RequireRateLimiting(ItemEndpoints.UnwrapPolicy);

        return app;
    }

    /// <summary>
    /// Crypto-shreds the caller's KEK and soft-deletes their rows (ADR-0003).
    /// </summary>
    /// <remarks>
    /// Irreversible: once the KEK is destroyed, no ciphertext belonging to this
    /// User can ever be decrypted again, including by an operator. The
    /// confirmation step belongs in the client, because a server-side "are you
    /// sure" round trip would only be a second call the same script could make.
    ///
    /// <para>
    /// Rate-limited under the unwrap bucket rather than the general one. It is
    /// not an unwrap, but it is the most destructive route in the API and the
    /// cheapest to replay, so it gets the tighter budget.
    /// </para>
    /// </remarks>
    private static async Task<IResult> DeleteAsync(
        ClaimsPrincipal principal,
        VaultService vault,
        CancellationToken cancellationToken)
    {
        if (!CallerIdentity.TryResolve(principal, out var owner))
        {
            return Results.Unauthorized();
        }

        // Idempotent: a repeat call is 204, not 404. The caller cannot tell
        // whether they were the one who deleted it, and re-running after a
        // network failure is the normal case, not an error.
        await vault.DeleteAccountAsync(owner, cancellationToken).ConfigureAwait(false);

        return Results.NoContent();
    }
}

using Cryptum.Domain;

namespace Cryptum.Api.Auth;

/// <summary>
/// Provisions the caller's KEK and User row on their first authenticated request.
/// </summary>
/// <remarks>
/// Middleware rather than a call inside each endpoint: a new User's very first
/// action might be any route, and an endpoint that forgot to provision would
/// fail only for brand-new accounts — the hardest case to notice in testing and
/// the worst one to get wrong in production.
/// </remarks>
public sealed class UserProvisioningMiddleware(RequestDelegate next)
{
    public async Task InvokeAsync(HttpContext context, UserProvisioning provisioning)
    {
        ArgumentNullException.ThrowIfNull(context);
        ArgumentNullException.ThrowIfNull(provisioning);

        if (CallerIdentity.TryResolve(context.User, out var owner))
        {
            await provisioning.EnsureProvisionedAsync(owner, context.RequestAborted).ConfigureAwait(false);
        }

        await next(context).ConfigureAwait(false);
    }
}

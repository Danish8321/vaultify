using System.Security.Claims;
using Cryptum.Api.Auth;
using Cryptum.Api.Contracts;
using Cryptum.Domain;
using Microsoft.AspNetCore.Mvc;

namespace Cryptum.Api.Endpoints;

/// <summary>Vault endpoints. Every route resolves the owner from the token, never the body.</summary>
public static class ItemEndpoints
{
    /// <summary>Rate-limit bucket for routes that cause a Key Vault unwrap.</summary>
    public const string UnwrapPolicy = "unwrap";

    public static IEndpointRouteBuilder MapItemEndpoints(this IEndpointRouteBuilder app)
    {
        ArgumentNullException.ThrowIfNull(app);

        var group = app.MapGroup("/items")
            .RequireAuthorization()
            .WithTags("Items")
            .ProducesProblem(StatusCodes.Status401Unauthorized)
            .ProducesProblem(StatusCodes.Status429TooManyRequests);

        group.MapPost("/", CreateSecretAsync)
            .WithName("CreateSecret")
            .Produces<CreatedItemResponse>(StatusCodes.Status201Created)
            .ProducesValidationProblem();

        group.MapGet("/", ListAsync)
            .WithName("ListItems")
            .Produces<IReadOnlyList<ItemSummaryResponse>>();

        // Stricter bucket than general CRUD: each call unwraps one DEK, so this
        // is the route worth abusing (docs/security-requirements.md).
        group.MapGet("/{id:guid}", ReadAsync)
            .WithName("ReadItem")
            .Produces<ItemResponse>()
            .ProducesProblem(StatusCodes.Status404NotFound)
            .RequireRateLimiting(UnwrapPolicy);

        return app;
    }

    private static async Task<IResult> CreateSecretAsync(
        [FromBody] CreateSecretRequest request,
        ClaimsPrincipal principal,
        VaultService vault,
        CancellationToken cancellationToken)
    {
        if (!CallerIdentity.TryResolve(principal, out var owner))
        {
            return Results.Unauthorized();
        }

        if (request.Nonce.Length != Item.NonceLength
            || request.Dek.Length != CreateSecretRequest.MinDekBytes
            || request.Ciphertext.Length > CreateSecretRequest.MaxCiphertextBytes
            || string.IsNullOrWhiteSpace(request.Title)
            || request.Title.Length > Item.MaxTitleLength)
        {
            // Generic: the specific violation is not echoed back, so probing the
            // endpoint reveals nothing beyond "rejected".
            return Results.ValidationProblem(new Dictionary<string, string[]>
            {
                ["request"] = ["Invalid request."],
            });
        }

        var item = await vault.CreateSecretAsync(
            owner, request.Title, request.Ciphertext, request.Nonce, request.Dek, cancellationToken)
            .ConfigureAwait(false);

        return Results.Created(
            $"/items/{item.Id.Value}",
            new CreatedItemResponse { Id = item.Id.Value });
    }

    private static async Task<IResult> ReadAsync(
        Guid id,
        ClaimsPrincipal principal,
        VaultService vault,
        CancellationToken cancellationToken)
    {
        if (!CallerIdentity.TryResolve(principal, out var owner))
        {
            return Results.Unauthorized();
        }

        var result = await vault.ReadAsync(owner, new ItemId(id), cancellationToken).ConfigureAwait(false);

        if (result is null)
        {
            // Same answer whether the Item belongs to someone else or does not
            // exist — anything else would enumerate valid Item ids.
            return Results.NotFound();
        }

        var (item, dek) = result.Value;

        // The DEK is copied out and the buffer zeroed before the response is
        // written, so key material does not outlive the request.
        using (dek)
        {
            return Results.Ok(new ItemResponse
            {
                Id = item.Id.Value,
                Title = item.Title,
                Ciphertext = item.Ciphertext ?? [],
                Nonce = item.Nonce,
                Dek = dek.Span.ToArray(),
                UpdatedAt = item.UpdatedAt,
            });
        }
    }

    private static async Task<IResult> ListAsync(
        ClaimsPrincipal principal,
        VaultService vault,
        CancellationToken cancellationToken)
    {
        if (!CallerIdentity.TryResolve(principal, out var owner))
        {
            return Results.Unauthorized();
        }

        var summaries = await vault.ListAsync(owner, cancellationToken).ConfigureAwait(false);

        return Results.Ok(summaries.Select(s => new ItemSummaryResponse
        {
            Id = s.Id.Value,
            Kind = s.Kind.ToString(),
            Title = s.Title,
            UpdatedAt = s.UpdatedAt,
        }));
    }
}

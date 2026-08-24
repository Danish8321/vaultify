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

        group.MapPut("/{id:guid}", UpdateSecretAsync)
            .WithName("UpdateSecret")
            .Produces(StatusCodes.Status204NoContent)
            .ProducesProblem(StatusCodes.Status404NotFound)
            .ProducesValidationProblem();

        group.MapGet("/{id:guid}/versions", ListVersionsAsync)
            .WithName("ListItemVersions")
            .Produces<IReadOnlyList<ItemVersionSummaryResponse>>();

        // Unwraps a DEK, so it shares the stricter bucket with reading an Item.
        group.MapGet("/{id:guid}/versions/{versionNumber:int}", ReadVersionAsync)
            .WithName("ReadItemVersion")
            .Produces<ItemVersionResponse>()
            .ProducesProblem(StatusCodes.Status404NotFound)
            .RequireRateLimiting(UnwrapPolicy);

        // POST, not PUT: a restore is not idempotent — each call archives the
        // content it displaces, so replaying one is a further edit, not a no-op.
        group.MapPost("/{id:guid}/versions/{versionNumber:int}/restore", RestoreVersionAsync)
            .WithName("RestoreItemVersion")
            .Produces(StatusCodes.Status204NoContent)
            .ProducesProblem(StatusCodes.Status404NotFound);

        // Registered ahead of the read-item route so "files" is never captured
        // by the {id:guid} pattern.
        group.MapPost("/files", CreateFileAsync)
            .WithName("CreateFile")
            .Produces<CreateFileResponse>(StatusCodes.Status201Created)
            .ProducesProblem(StatusCodes.Status413PayloadTooLarge)
            .ProducesValidationProblem();

        // Unwraps a DEK and issues a blob SAS, so it shares the stricter bucket.
        group.MapGet("/files/{id:guid}", ReadFileAsync)
            .WithName("ReadFile")
            .Produces<FileResponse>()
            .ProducesProblem(StatusCodes.Status404NotFound)
            .RequireRateLimiting(UnwrapPolicy);

        return app;
    }

    private static async Task<IResult> CreateFileAsync(
        [FromBody] CreateFileRequest request,
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
            || request.SizeBytes <= 0
            || string.IsNullOrWhiteSpace(request.Title)
            || request.Title.Length > Item.MaxTitleLength)
        {
            return Results.ValidationProblem(new Dictionary<string, string[]>
            {
                ["request"] = ["Invalid request."],
            });
        }

        try
        {
            var (item, uploadUri) = await vault.CreateFileAsync(
                owner, request.Title, request.SizeBytes, request.Nonce, request.Dek, cancellationToken)
                .ConfigureAwait(false);

            return Results.Created(
                $"/items/files/{item.Id.Value}",
                new CreateFileResponse { Id = item.Id.Value, UploadUri = uploadUri });
        }
        catch (FileQuotaExceededException ex)
        {
            return Results.Problem(ex.Message, statusCode: StatusCodes.Status413PayloadTooLarge);
        }
    }

    private static async Task<IResult> ReadFileAsync(
        Guid id,
        ClaimsPrincipal principal,
        VaultService vault,
        CancellationToken cancellationToken)
    {
        if (!CallerIdentity.TryResolve(principal, out var owner))
        {
            return Results.Unauthorized();
        }

        var result = await vault.ReadFileAsync(owner, new ItemId(id), cancellationToken).ConfigureAwait(false);

        if (result is null)
        {
            return Results.NotFound();
        }

        var (item, dek, downloadUri) = result.Value;

        using (dek)
        {
            return Results.Ok(new FileResponse
            {
                Id = item.Id.Value,
                Title = item.Title,
                // Safe: SizeBytes is validated against FileLimits.MaxFileBytes (25MB) on
                // write, well inside int range. See CreateFileRequest.SizeBytes remarks.
                SizeBytes = (int)(item.SizeBytes ?? 0),
                Nonce = item.Nonce,
                Dek = dek.Span.ToArray(),
                DownloadUri = downloadUri,
                UpdatedAt = item.UpdatedAt,
            });
        }
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

    private static async Task<IResult> UpdateSecretAsync(
        Guid id,
        [FromBody] UpdateSecretRequest request,
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
            return Results.ValidationProblem(new Dictionary<string, string[]>
            {
                ["request"] = ["Invalid request."],
            });
        }

        var updated = await vault.UpdateSecretAsync(
            owner, new ItemId(id), request.Title, request.Ciphertext, request.Nonce, request.Dek, cancellationToken)
            .ConfigureAwait(false);

        // 404 for a non-owned Item, as on the read path: 403 would confirm the id exists.
        return updated ? Results.NoContent() : Results.NotFound();
    }

    private static async Task<IResult> ListVersionsAsync(
        Guid id,
        ClaimsPrincipal principal,
        VaultService vault,
        CancellationToken cancellationToken)
    {
        if (!CallerIdentity.TryResolve(principal, out var owner))
        {
            return Results.Unauthorized();
        }

        var versions = await vault.ListVersionsAsync(owner, new ItemId(id), cancellationToken).ConfigureAwait(false);

        // An empty list for an Item that is not the caller's, same as for one with
        // no history — consistent with 404-not-403 elsewhere.
        return Results.Ok(versions.Select(v => new ItemVersionSummaryResponse
        {
            VersionNumber = v.VersionNumber,
            ArchivedAt = v.ArchivedAt,
        }));
    }

    private static async Task<IResult> ReadVersionAsync(
        Guid id,
        int versionNumber,
        ClaimsPrincipal principal,
        VaultService vault,
        CancellationToken cancellationToken)
    {
        if (!CallerIdentity.TryResolve(principal, out var owner))
        {
            return Results.Unauthorized();
        }

        var result = await vault.ReadVersionAsync(owner, new ItemId(id), versionNumber, cancellationToken)
            .ConfigureAwait(false);

        if (result is null)
        {
            return Results.NotFound();
        }

        var (version, dek) = result.Value;

        using (dek)
        {
            return Results.Ok(new ItemVersionResponse
            {
                VersionNumber = version.VersionNumber,
                Ciphertext = version.Ciphertext,
                Nonce = version.Nonce,
                Dek = dek.Span.ToArray(),
                ArchivedAt = version.ArchivedAt,
            });
        }
    }

    private static async Task<IResult> RestoreVersionAsync(
        Guid id,
        int versionNumber,
        ClaimsPrincipal principal,
        VaultService vault,
        CancellationToken cancellationToken)
    {
        if (!CallerIdentity.TryResolve(principal, out var owner))
        {
            return Results.Unauthorized();
        }

        var restored = await vault.RestoreVersionAsync(owner, new ItemId(id), versionNumber, cancellationToken)
            .ConfigureAwait(false);

        return restored ? Results.NoContent() : Results.NotFound();
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

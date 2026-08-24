using Microsoft.AspNetCore.OpenApi;
using Microsoft.OpenApi;

namespace Cryptum.Api.OpenApi;

/// <summary>
/// Strips the string-fallback branch .NET's OpenAPI schema generator adds to
/// every required integer/number property (a precision-safety accommodation
/// for JS's unsafe-integer range: the schema becomes `type: [integer,
/// string]` plus a digit-pattern). openapi-generator's Kotlin/Ktor client
/// cannot represent that union and silently emits an empty stub class in its
/// place — every required numeric field in the generated Android client was
/// affected (e.g. <c>ItemVersionResponseVersionNumber</c>,
/// <c>FileResponseSizeBytes</c>), not just the Files feature that surfaced it.
/// The API only ever emits plain JSON numbers, so the plain-integer/number
/// schema is what actually describes the wire format.
/// </summary>
public sealed class PlainIntegerSchemaTransformer : IOpenApiSchemaTransformer
{
    public Task TransformAsync(OpenApiSchema schema, OpenApiSchemaTransformerContext context, CancellationToken cancellationToken)
    {
        if (schema.Type is { } type
            && ((type & JsonSchemaType.Integer) == JsonSchemaType.Integer || (type & JsonSchemaType.Number) == JsonSchemaType.Number))
        {
            schema.Type = (type & JsonSchemaType.Integer) == JsonSchemaType.Integer ? JsonSchemaType.Integer : JsonSchemaType.Number;
            schema.Pattern = null;
        }

        return Task.CompletedTask;
    }
}

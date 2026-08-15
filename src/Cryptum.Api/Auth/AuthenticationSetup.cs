using Microsoft.AspNetCore.Authentication.JwtBearer;
using Microsoft.IdentityModel.Tokens;

namespace Cryptum.Api.Auth;

/// <summary>
/// JWT bearer validation against Azure AD B2C (ADR-0004).
/// </summary>
public static class AuthenticationSetup
{
    public static IServiceCollection AddCryptumAuthentication(
        this IServiceCollection services,
        IConfiguration configuration)
    {
        ArgumentNullException.ThrowIfNull(services);
        ArgumentNullException.ThrowIfNull(configuration);

        var authority = configuration["Auth:Authority"];
        var audience = configuration["Auth:Audience"];

        services
            .AddAuthentication(JwtBearerDefaults.AuthenticationScheme)
            .AddJwtBearer(options =>
            {
                options.Authority = authority;
                options.Audience = audience;

                // Every one of these defaults to true, but they are the whole
                // security value of the token, so they are stated explicitly:
                // a future edit that weakens one should be visible in a diff
                // rather than hidden in framework defaults.
                options.TokenValidationParameters = new TokenValidationParameters
                {
                    ValidateIssuer = true,
                    ValidateAudience = true,
                    ValidateLifetime = true,
                    ValidateIssuerSigningKey = true,
                    ValidAudience = audience,
                    ValidIssuer = authority,

                    // No leeway. The default five minutes would extend the life
                    // of a stolen token past the short expiry ADR-0004 chose.
                    ClockSkew = TimeSpan.Zero,
                };

                options.MapInboundClaims = false;
            });

        services.AddAuthorization();
        return services;
    }
}

using System.IdentityModel.Tokens.Jwt;
using System.Security.Claims;
using Microsoft.IdentityModel.Tokens;

namespace Cryptum.IntegrationTests;

/// <summary>Mints access tokens the API's real JWT handler will validate.</summary>
internal static class TestTokens
{
    public static string For(
        CryptumApiFactory factory,
        string subject,
        string? audience = null,
        string? issuer = null,
        TimeSpan? lifetime = null)
    {
        var now = DateTime.UtcNow;
        var expires = now.Add(lifetime ?? TimeSpan.FromMinutes(5));

        var token = new JwtSecurityToken(
            issuer: issuer ?? CryptumApiFactory.Issuer,
            audience: audience ?? CryptumApiFactory.Audience,
            claims: [new Claim("sub", subject)],
            notBefore: expires < now ? expires.AddMinutes(-5) : now,
            expires: expires,
            signingCredentials: new SigningCredentials(factory.SigningKey, SecurityAlgorithms.RsaSha256));

        return new JwtSecurityTokenHandler().WriteToken(token);
    }

    /// <summary>
    /// Flips one byte of the signature, leaving the payload intact — the exact
    /// shape of a forged token, and the case a fake auth handler would pass.
    /// </summary>
    public static string WithBrokenSignature(string token)
    {
        var parts = token.Split('.');
        var signature = parts[2].ToCharArray();
        signature[0] = signature[0] == 'A' ? 'B' : 'A';
        parts[2] = new string(signature);
        return string.Join('.', parts);
    }
}

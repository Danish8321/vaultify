# Azure AD B2C for user identity

Status: accepted

User authentication uses Azure AD B2C (email/password plus social login), issuing short-lived access tokens with refresh tokens over the standard OAuth2 flow; the .NET API validates those tokens and maps the subject claim to a Cryptum User. We rejected building custom auth in the backend (password hashing, MFA, reset flows and token rotation are a large surface to get right in a security product) and passwordless device-bound keys (stronger, but multi-device and account recovery become hard problems on day one).

## Consequences

This is real lock-in: migrating identity providers later means re-onboarding every user, since we never hold their credentials. Accepted deliberately — the alternative is owning credential storage in an app whose whole premise is that we hold nothing sensitive.

It also constrains the encryption model. Because B2C owns the password and the backend never sees it, we cannot derive a User's KEK from their password — which is precisely what a zero-knowledge design would require (ADR-0001). B2C and server-blind-not-zero-knowledge are the same decision viewed from two sides; revisiting one means revisiting the other.

B2C password reset must remain fully decoupled from KEK access. A User who resets their password keeps their Vault, because KEK access is mediated by the backend's Managed Identity and never derived from user credentials. No key material may ever be derived from the B2C password — that would silently convert a routine password reset into total Vault loss.

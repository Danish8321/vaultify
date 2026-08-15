using Azure;
using Azure.Core;
using Azure.Security.KeyVault.Keys;
using Azure.Security.KeyVault.Keys.Cryptography;
using Cryptum.Domain;

namespace Cryptum.Infrastructure;

/// <summary>
/// <see cref="IKeyWrapper"/> backed by Azure Key Vault (ADR-0001, ADR-0002).
/// </summary>
/// <remarks>
/// The service identity holds only <c>wrapKey</c>/<c>unwrapKey</c> — never
/// <c>get</c> or export — so KEK material cannot leave the vault even if this
/// process is compromised. What an attacker with this process could do is call
/// unwrap for any user, which is the residual risk ADR-0002 accepts and the
/// reason unwrap volume is alerted on.
/// </remarks>
public sealed class KeyVaultKeyWrapper(KeyClient keyClient, TokenCredential credential) : IKeyWrapper
{
    // RSA-OAEP with SHA-256. RSA-2048 wraps at most ~190 bytes, comfortably
    // more than a 256-bit DEK — but only the raw key is ever wrapped, never
    // key-plus-metadata, or that headroom would matter.
    private static readonly KeyWrapAlgorithm WrapAlgorithm = KeyWrapAlgorithm.RsaOaep256;

    public async Task EnsureKekAsync(UserId owner, CancellationToken cancellationToken = default)
    {
        var name = KekName(owner);
        try
        {
            await keyClient.GetKeyAsync(name, cancellationToken: cancellationToken).ConfigureAwait(false);
            return;
        }
        catch (RequestFailedException ex) when (ex.Status == 404)
        {
            // Not provisioned yet — fall through and create.
        }

        var options = new CreateRsaKeyOptions(name) { KeySize = 2048 };
        options.KeyOperations.Add(KeyOperation.WrapKey);
        options.KeyOperations.Add(KeyOperation.UnwrapKey);

        // Key Vault offers no create-if-absent, so two concurrent provisioning
        // calls can both reach this line and produce two *versions* of one key.
        // That is survivable rather than destructive: each Item records the
        // version that wrapped it, so both versions stay unwrappable and no data
        // is orphaned. It is wasteful, not lossy.
        await keyClient.CreateRsaKeyAsync(options, cancellationToken).ConfigureAwait(false);
    }

    public async Task<WrappedDek> WrapAsync(UserId owner, ReadOnlyMemory<byte> dek, CancellationToken cancellationToken = default)
    {
        KeyVaultKey key;
        try
        {
            key = await keyClient.GetKeyAsync(KekName(owner), cancellationToken: cancellationToken).ConfigureAwait(false);
        }
        catch (RequestFailedException ex) when (ex.Status == 404)
        {
            // Wrap no longer creates the KEK. Provisioning is an explicit step
            // (UserProvisioning); creating a key here would mean a
            // crypto-shredded account silently regrew a Vault on its next write.
            throw new KeyUnavailableException($"No KEK available for {owner}.");
        }

        var crypto = new CryptographyClient(key.Id, credential);

        var result = await crypto.WrapKeyAsync(WrapAlgorithm, dek.ToArray(), cancellationToken).ConfigureAwait(false);

        return new WrappedDek(result.EncryptedKey, key.Properties.Version);
    }

    public async Task<PlaintextDek> UnwrapAsync(UserId owner, WrappedDek wrapped, CancellationToken cancellationToken = default)
    {
        KeyVaultKey key;
        try
        {
            key = await keyClient.GetKeyAsync(KekName(owner), wrapped.KekVersion, cancellationToken).ConfigureAwait(false);
        }
        catch (RequestFailedException ex) when (ex.Status == 404)
        {
            // Absent KEK is the expected post-crypto-shred state (ADR-0003), not a fault.
            throw new KeyUnavailableException($"No KEK available for {owner}.");
        }

        var crypto = new CryptographyClient(key.Id, credential);
        var result = await crypto.UnwrapKeyAsync(WrapAlgorithm, wrapped.Value, cancellationToken).ConfigureAwait(false);

        return new PlaintextDek(result.Key);
    }

    public async Task CryptoShredAsync(UserId owner, CancellationToken cancellationToken = default)
    {
        try
        {
            var operation = await keyClient.StartDeleteKeyAsync(KekName(owner), cancellationToken).ConfigureAwait(false);
            await operation.WaitForCompletionAsync(cancellationToken).ConfigureAwait(false);
        }
        catch (RequestFailedException ex) when (ex.Status == 404)
        {
            // Already shredded. Deletion is idempotent so a retried purge does not fail.
        }
    }

    // One KEK per User, so deleting it crypto-shreds exactly that User (ADR-0003).
    private static string KekName(UserId owner) => $"kek-{owner.Value:N}";
}

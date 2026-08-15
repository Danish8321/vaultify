namespace Cryptum.Domain;

/// <summary>
/// The Vault use cases, with authorization and auditing applied at one place.
/// </summary>
/// <remarks>
/// Endpoints delegate here rather than composing the repository and key wrapper
/// themselves, so "check the owner, wrap or unwrap, write an audit row" cannot
/// be partially forgotten on one route out of several — the failure mode
/// ADR-0002 names as a full-vault breach.
/// </remarks>
public sealed class VaultService(
    IItemRepository items,
    IKeyWrapper keyWrapper,
    IAuditLog auditLog,
    TimeProvider clock)
{
    public async Task<Item> CreateSecretAsync(
        UserId owner,
        string title,
        byte[] ciphertext,
        byte[] nonce,
        ReadOnlyMemory<byte> dek,
        CancellationToken cancellationToken = default)
    {
        var now = clock.GetUtcNow();

        var wrapped = await keyWrapper.WrapAsync(owner, dek, cancellationToken).ConfigureAwait(false);
        await auditLog.RecordAsync(AuditEntry.Record(owner, AuditAction.DekWrapped, now), cancellationToken).ConfigureAwait(false);

        var item = Item.CreateSecret(owner, title, ciphertext, nonce, wrapped, now);

        await items.AddAsync(item, cancellationToken).ConfigureAwait(false);
        await items.SaveChangesAsync(cancellationToken).ConfigureAwait(false);

        await auditLog.RecordAsync(
            AuditEntry.Record(owner, AuditAction.ItemCreated, now, item.Id), cancellationToken).ConfigureAwait(false);

        return item;
    }

    /// <summary>
    /// Returns the Item and its unwrapped DEK, or null if the caller does not own it.
    /// </summary>
    /// <remarks>
    /// A caller asking for someone else's Item and a caller asking for one that
    /// does not exist get the identical answer. Distinguishing them would leak
    /// which Item ids are real.
    /// </remarks>
    public async Task<(Item Item, PlaintextDek Dek)?> ReadAsync(
        UserId owner,
        ItemId id,
        CancellationToken cancellationToken = default)
    {
        var now = clock.GetUtcNow();
        var item = await items.FindAsync(owner, id, cancellationToken).ConfigureAwait(false);

        if (item is null)
        {
            await auditLog.RecordAsync(
                AuditEntry.Record(owner, AuditAction.AccessDenied, now, id, succeeded: false),
                cancellationToken).ConfigureAwait(false);
            return null;
        }

        var dek = await keyWrapper.UnwrapAsync(
            owner, new WrappedDek(item.WrappedDek, item.KekVersion), cancellationToken).ConfigureAwait(false);

        await auditLog.RecordAsync(
            AuditEntry.Record(owner, AuditAction.DekUnwrapped, now, id), cancellationToken).ConfigureAwait(false);
        await auditLog.RecordAsync(
            AuditEntry.Record(owner, AuditAction.ItemRead, now, id), cancellationToken).ConfigureAwait(false);

        return (item, dek);
    }

    public async Task<IReadOnlyList<ItemSummary>> ListAsync(
        UserId owner,
        CancellationToken cancellationToken = default)
    {
        var summaries = await items.ListAsync(owner, cancellationToken).ConfigureAwait(false);

        await auditLog.RecordAsync(
            AuditEntry.Record(owner, AuditAction.ItemListed, clock.GetUtcNow()), cancellationToken).ConfigureAwait(false);

        return summaries;
    }

    /// <summary>
    /// Deletes the account: crypto-shred first, then soft-delete the rows (ADR-0003).
    /// </summary>
    /// <remarks>
    /// Order matters. Destroying the KEK first means that if the row cleanup
    /// fails midway, the data is already unreadable — the failure leaves orphaned
    /// ciphertext rather than a half-deleted, still-decryptable Vault.
    /// </remarks>
    public async Task DeleteAccountAsync(UserId owner, CancellationToken cancellationToken = default)
    {
        var now = clock.GetUtcNow();

        await keyWrapper.CryptoShredAsync(owner, cancellationToken).ConfigureAwait(false);
        await auditLog.RecordAsync(
            AuditEntry.Record(owner, AuditAction.AccountCryptoShredded, now), cancellationToken).ConfigureAwait(false);

        await items.SoftDeleteAllAsync(owner, now, cancellationToken).ConfigureAwait(false);
    }
}

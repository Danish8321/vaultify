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
    IUserRepository users,
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

    /// <summary>
    /// Replaces a Secret's content, archiving what it displaced. Returns false if
    /// the caller does not own the Item — the same answer a missing Item gives.
    /// </summary>
    public async Task<bool> UpdateSecretAsync(
        UserId owner,
        ItemId id,
        string title,
        byte[] ciphertext,
        byte[] nonce,
        ReadOnlyMemory<byte> dek,
        CancellationToken cancellationToken = default)
    {
        var now = clock.GetUtcNow();
        var item = await items.FindAsync(owner, id, cancellationToken).ConfigureAwait(false);

        if (item is null)
        {
            await auditLog.RecordAsync(
                AuditEntry.Record(owner, AuditAction.AccessDenied, now, id, succeeded: false),
                cancellationToken).ConfigureAwait(false);
            return false;
        }

        var wrapped = await keyWrapper.WrapAsync(owner, dek, cancellationToken).ConfigureAwait(false);
        await auditLog.RecordAsync(
            AuditEntry.Record(owner, AuditAction.DekWrapped, now), cancellationToken).ConfigureAwait(false);

        await items.AddVersionAsync(
            item.ReplaceContent(title, ciphertext, nonce, wrapped, now), cancellationToken).ConfigureAwait(false);
        await items.SaveChangesAsync(cancellationToken).ConfigureAwait(false);

        // Pruned after the write, so a prune failure cannot cost the user the
        // edit itself. Excess history is a retention problem, not a data-loss one.
        await items.PruneVersionsAsync(owner, id, cancellationToken).ConfigureAwait(false);

        await auditLog.RecordAsync(
            AuditEntry.Record(owner, AuditAction.ItemUpdated, now, id), cancellationToken).ConfigureAwait(false);

        return true;
    }

    /// <summary>
    /// Makes an archived version current again. Returns false if the caller does
    /// not own the Item, or if that version is not retained.
    /// </summary>
    /// <remarks>
    /// A restore is an edit, not a rewind: the content it displaces is archived
    /// in turn. Restoring the wrong version is therefore itself undoable, which
    /// matters because the user choosing a version cannot read any of them until
    /// after the fact.
    ///
    /// <para>
    /// The version's own wrapped DEK is reused rather than re-wrapped, so the
    /// server never handles this content's plaintext — the whole point of
    /// per-version DEKs (ADR-0006).
    /// </para>
    /// </remarks>
    public async Task<bool> RestoreVersionAsync(
        UserId owner,
        ItemId id,
        int versionNumber,
        CancellationToken cancellationToken = default)
    {
        var now = clock.GetUtcNow();

        var item = await items.FindAsync(owner, id, cancellationToken).ConfigureAwait(false);
        var version = await items.FindVersionAsync(owner, id, versionNumber, cancellationToken).ConfigureAwait(false);

        if (item is null || version is null)
        {
            await auditLog.RecordAsync(
                AuditEntry.Record(owner, AuditAction.AccessDenied, now, id, succeeded: false),
                cancellationToken).ConfigureAwait(false);
            return false;
        }

        await items.AddVersionAsync(
            item.ReplaceContent(
                item.Title,
                version.Ciphertext,
                version.Nonce,
                new WrappedDek(version.WrappedDek, version.KekVersion),
                now),
            cancellationToken).ConfigureAwait(false);
        await items.SaveChangesAsync(cancellationToken).ConfigureAwait(false);

        await items.PruneVersionsAsync(owner, id, cancellationToken).ConfigureAwait(false);

        await auditLog.RecordAsync(
            AuditEntry.Record(owner, AuditAction.ItemVersionRestored, now, id), cancellationToken).ConfigureAwait(false);

        return true;
    }

    /// <summary>Returns an archived version and its unwrapped DEK, or null if not the caller's.</summary>
    public async Task<(ItemVersion Version, PlaintextDek Dek)?> ReadVersionAsync(
        UserId owner,
        ItemId id,
        int versionNumber,
        CancellationToken cancellationToken = default)
    {
        var now = clock.GetUtcNow();
        var version = await items.FindVersionAsync(owner, id, versionNumber, cancellationToken).ConfigureAwait(false);

        if (version is null)
        {
            await auditLog.RecordAsync(
                AuditEntry.Record(owner, AuditAction.AccessDenied, now, id, succeeded: false),
                cancellationToken).ConfigureAwait(false);
            return null;
        }

        var dek = await keyWrapper.UnwrapAsync(
            owner, new WrappedDek(version.WrappedDek, version.KekVersion), cancellationToken).ConfigureAwait(false);

        await auditLog.RecordAsync(
            AuditEntry.Record(owner, AuditAction.DekUnwrapped, now, id), cancellationToken).ConfigureAwait(false);
        await auditLog.RecordAsync(
            AuditEntry.Record(owner, AuditAction.ItemVersionRead, now, id), cancellationToken).ConfigureAwait(false);

        return (version, dek);
    }

    public async Task<IReadOnlyList<ItemVersionSummary>> ListVersionsAsync(
        UserId owner,
        ItemId id,
        CancellationToken cancellationToken = default)
    {
        var summaries = await items.ListVersionsAsync(owner, id, cancellationToken).ConfigureAwait(false);

        await auditLog.RecordAsync(
            AuditEntry.Record(owner, AuditAction.ItemListed, clock.GetUtcNow(), id), cancellationToken).ConfigureAwait(false);

        return summaries;
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

        // Last. The User row is the record that a KEK exists, so it must not
        // outlive the KEK: provisioning skips a User who has a row, and that
        // User would then have no key — a 500 on their next write. Removing it
        // lets the same identity start a fresh Vault, which recovers nothing:
        // the new KEK cannot unwrap a single DEK the old one wrapped.
        await users.RemoveAsync(owner, cancellationToken).ConfigureAwait(false);
    }
}

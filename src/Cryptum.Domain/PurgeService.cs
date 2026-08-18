namespace Cryptum.Domain;

/// <summary>What one purge run removed.</summary>
public readonly record struct PurgeResult(int Items, int Versions);

/// <summary>
/// Permanently removes rows that account deletion soft-deleted (ADR-0003).
/// </summary>
/// <remarks>
/// <para>
/// The KEK is already destroyed by the time anything reaches here, so these rows
/// are undecryptable ciphertext. This is reclaiming space, not enforcing the
/// deletion promise — that promise was kept the moment the key died. Saying so
/// matters, because it means a purge that lags is a storage cost, not a privacy
/// incident.
/// </para>
/// <para>
/// Work happens in batches and each batch commits on its own. That is the whole
/// of the resumability design: an interrupted run leaves committed batches
/// committed and everything else still eligible, so re-running finishes the job
/// with no bookkeeping, no cursor and nothing to reconcile. Idempotence follows
/// for free — the second run finds nothing to do.
/// </para>
/// </remarks>
public sealed class PurgeService(IPurgeStore store)
{
    /// <summary>
    /// Removes every Item soft-deleted at or before <paramref name="deletedBefore"/>,
    /// along with its version history.
    /// </summary>
    /// <param name="onBatch">
    /// Called after each batch commits. Exists for tests that need to interrupt a
    /// run at a real boundary rather than simulate one.
    /// </param>
    public async Task<PurgeResult> PurgeAsync(
        DateTimeOffset deletedBefore,
        int batchSize,
        Action<PurgeResult>? onBatch = null,
        CancellationToken cancellationToken = default)
    {
        ArgumentOutOfRangeException.ThrowIfLessThan(batchSize, 1);

        var total = new PurgeResult(0, 0);

        while (true)
        {
            // Checked before the batch, not after: cancelling must not leave a
            // batch half-committed, and the natural safe point is between them.
            cancellationToken.ThrowIfCancellationRequested();

            var batch = await store.PurgeBatchAsync(deletedBefore, batchSize, cancellationToken)
                .ConfigureAwait(false);

            if (batch.Items == 0)
            {
                return total;
            }

            total = new PurgeResult(total.Items + batch.Items, total.Versions + batch.Versions);
            onBatch?.Invoke(batch);
        }
    }
}

/// <summary>Storage side of the purge.</summary>
/// <remarks>
/// A seam rather than a direct dependency on EF for the same reason
/// <see cref="IKeyWrapper"/> is one: the domain states what must happen, and the
/// data project owns how. The batch is the unit of durability, so this method
/// commits before it returns.
/// </remarks>
public interface IPurgeStore
{
    /// <summary>
    /// Permanently removes at most <paramref name="batchSize"/> soft-deleted Items
    /// and their versions, committing before returning. Returns what it removed.
    /// </summary>
    Task<PurgeResult> PurgeBatchAsync(
        DateTimeOffset deletedBefore,
        int batchSize,
        CancellationToken cancellationToken = default);
}

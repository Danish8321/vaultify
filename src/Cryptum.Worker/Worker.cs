using System.Data.Common;
using Cryptum.Domain;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Options;

namespace Cryptum.Worker;

/// <summary>How often the purge runs and how much it does at a time.</summary>
public sealed class PurgeOptions
{
    /// <summary>Gap between runs.</summary>
    public TimeSpan Interval { get; set; } = TimeSpan.FromMinutes(15);

    /// <summary>Rows removed per committed batch.</summary>
    /// <remarks>
    /// Small on purpose. The batch is the unit of durability and the unit of
    /// lock contention, and this table is also serving live reads.
    /// </remarks>
    public int BatchSize { get; set; } = 200;

    /// <summary>
    /// How long a soft-deleted row waits before removal.
    /// </summary>
    /// <remarks>
    /// Zero by default, and that is a deliberate reading of ADR-0003 rather than
    /// an unset value. A grace period exists to make a mistaken deletion
    /// recoverable — but the KEK is destroyed before any row reaches here, so the
    /// rows are already undecryptable and there is nothing left to recover. A
    /// non-zero window would keep unreadable ciphertext for no benefit, which is
    /// the opposite of what crypto-shred promises the user.
    /// </remarks>
    public TimeSpan Grace { get; set; } = TimeSpan.Zero;
}

/// <summary>Runs the purge on a timer (ADR-0003, plan task 4.2).</summary>
public sealed partial class Worker(
    IServiceScopeFactory scopeFactory,
    IOptions<PurgeOptions> options,
    TimeProvider clock,
    ILogger<Worker> logger) : BackgroundService
{
    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        var settings = options.Value;
        using var timer = new PeriodicTimer(settings.Interval, clock);

        do
        {
            try
            {
                using var scope = scopeFactory.CreateScope();
                var purge = scope.ServiceProvider.GetRequiredService<PurgeService>();

                var result = await purge.PurgeAsync(
                    clock.GetUtcNow() - settings.Grace,
                    settings.BatchSize,
                    cancellationToken: stoppingToken).ConfigureAwait(false);

                if (result.Items > 0)
                {
                    LogPurged(logger, result.Items, result.Versions);
                }
            }
            catch (OperationCanceledException) when (stoppingToken.IsCancellationRequested)
            {
                // Shutdown, not a fault. Committed batches stay committed and the
                // rest are still eligible, so the next start resumes with no
                // recovery step.
                return;
            }
            catch (Exception ex) when (ex is DbException or DbUpdateException or TimeoutException)
            {
                // A transient database failure must not kill the worker. The rows
                // are already undecryptable, so falling behind costs storage, not
                // privacy — whereas a dead worker never catches up at all.
                //
                // Deliberately not `catch (Exception)`. That would need a CA1031
                // suppression, and worse, it would turn a genuine bug in the purge
                // into a line in a log that repeats every fifteen minutes forever.
                // Anything not in this list is unexpected, and an unexpected fault
                // in a job that deletes rows should stop and be looked at.
                LogFailed(logger, ex);
            }
        }
        while (await timer.WaitForNextTickAsync(stoppingToken).ConfigureAwait(false));
    }

    [LoggerMessage(EventId = 1, Level = LogLevel.Information,
        Message = "Purged {Items} Items and {Versions} versions")]
    private static partial void LogPurged(ILogger logger, int items, int versions);

    [LoggerMessage(EventId = 2, Level = LogLevel.Error, Message = "Purge run failed; will retry on the next tick")]
    private static partial void LogFailed(ILogger logger, Exception exception);
}

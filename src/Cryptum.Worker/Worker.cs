namespace Cryptum.Worker;

/// Async purge of soft-deleted Items and orphaned blobs. See ADR-0003; implemented in plan task 4.2.
public sealed class Worker(ILogger<Worker> logger) : BackgroundService
{
    private static readonly Action<ILogger, Exception?> LogIdle =
        LoggerMessage.Define(LogLevel.Information, new EventId(1, nameof(LogIdle)), "Purge worker idle; no work scheduled yet");

    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        LogIdle(logger, null);
        await Task.Delay(Timeout.InfiniteTimeSpan, stoppingToken).ConfigureAwait(false);
    }
}

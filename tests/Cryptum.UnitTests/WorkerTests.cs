using Cryptum.Domain;
using Cryptum.Worker;
// The namespace and the type are both called Worker, so the property below needs
// a name the compiler cannot read as the namespace.
using PurgeWorker = Cryptum.Worker.Worker;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Logging;
using Microsoft.Extensions.Options;
using Microsoft.Extensions.Time.Testing;

namespace Cryptum.UnitTests;

/// <summary>
/// The purge worker's timer loop.
/// </summary>
/// <remarks>
/// What the purge itself does is covered against a real database in
/// <c>PurgeTests</c>. What is left here is only the loop around it: that it
/// survives a transient database fault, that shutdown is not a fault, that the
/// grace window reaches the service, and that each tick gets its own scope.
/// Every one of those is a property of this file and of nothing else.
/// </remarks>
public sealed class WorkerTests
{
    private static readonly DateTimeOffset Now = new(2026, 8, 18, 12, 0, 0, TimeSpan.Zero);

    [Fact]
    public async Task A_transient_database_fault_does_not_kill_the_worker()
    {
        // The whole reason the loop catches anything. A worker that dies on the
        // first deadlock stops purging forever, and nothing notices because the
        // process is still up — the host just has one fewer hosted service.
        var store = new FakeStore { ThrowOnCall = 1 };
        await using var harness = new Harness(store);

        await harness.RunTicksAsync(2);

        Assert.Equal(2, store.Calls.Count);
        Assert.Contains(harness.Logged, entry => entry.Level == LogLevel.Error);
    }

    [Fact]
    public async Task Shutdown_stops_the_loop_and_is_not_logged_as_a_fault()
    {
        var store = new FakeStore();
        await using var harness = new Harness(store);

        await harness.RunTicksAsync(1);
        await harness.Worker.StopAsync(CancellationToken.None);

        // Not faulted: shutdown is an ordinary exit, so it must neither log an
        // error nor leave a faulted task for the host to report. Cancelled is a
        // normal end state here — WaitForNextTickAsync throws on the stopping
        // token, and BackgroundService treats that as a clean stop.
        Assert.NotEqual(TaskStatus.Faulted, harness.Worker.ExecuteTask?.Status);
        Assert.DoesNotContain(harness.Logged, entry => entry.Level == LogLevel.Error);
    }

    [Fact]
    public async Task Shutdown_during_a_purge_is_not_logged_as_a_fault()
    {
        // The other shutdown case exits at the timer, where cancellation never
        // reaches the loop body at all. This one stops while a purge is in
        // flight, which is the only path that actually runs the loop's
        // OperationCanceledException handler — and the only way to tell that
        // handler apart from one that logs an ordinary stop as an error, waking
        // whoever is on call for a clean deploy.
        var store = new FakeStore { BlockUntilCancelled = true };
        await using var harness = new Harness(store);

        await harness.Worker.StartAsync(CancellationToken.None);
        await Harness.WaitUntilAsync(() => store.Calls.Count >= 1);
        await harness.Worker.StopAsync(CancellationToken.None);

        Assert.DoesNotContain(harness.Logged, entry => entry.Level == LogLevel.Error);
    }

    [Fact]
    public async Task The_grace_window_is_subtracted_from_now_before_the_purge_sees_it()
    {
        // Grace is the difference between "delete what was deleted before now"
        // and "delete what was deleted before a week ago". Passing the raw clock
        // through would silently ignore any configured window.
        var store = new FakeStore();
        await using var harness = new Harness(store, options => options.Grace = TimeSpan.FromDays(3));

        await harness.RunTicksAsync(1);

        Assert.Equal(Now.AddDays(-3), Assert.Single(store.Calls));
    }

    [Fact]
    public async Task Each_tick_runs_in_its_own_scope()
    {
        // A scope held for the process lifetime means one DbContext accumulating
        // every entity the worker has ever tracked — an unbounded leak in a
        // service whose entire job is to run forever.
        var store = new FakeStore();
        await using var harness = new Harness(store);

        await harness.RunTicksAsync(3);

        Assert.Equal(3, harness.ScopesDisposed);
    }

    /// <summary>Worker plus the DI and clock it needs, wired the way the host wires it.</summary>
    private sealed class Harness : IAsyncDisposable
    {
        private readonly FakeTimeProvider clock = new(Now);
        private readonly ServiceProvider provider;
        private readonly ScopeCounter counter = new();

        public Harness(FakeStore store, Action<PurgeOptions>? configure = null)
        {
            var options = new PurgeOptions();
            configure?.Invoke(options);

            var services = new ServiceCollection();
            services.AddSingleton<IPurgeStore>(store);
            services.AddScoped<PurgeService>();
            provider = services.BuildServiceProvider();

            Worker = new PurgeWorker(
                new CountingScopeFactory(provider.GetRequiredService<IServiceScopeFactory>(), counter),
                Options.Create(options),
                clock,
                new CapturingLogger<PurgeWorker>(Logged));

            Interval = options.Interval;
        }

        public PurgeWorker Worker { get; }

        public List<(LogLevel Level, Exception? Exception)> Logged { get; } = [];

        public int ScopesDisposed => counter.Count;

        private TimeSpan Interval { get; }

        /// <summary>Starts the worker and drives it through <paramref name="ticks"/> purge runs.</summary>
        /// <remarks>
        /// The loop body runs once before it ever waits, so starting is the first
        /// tick and each advance is one more.
        /// </remarks>
        public async Task RunTicksAsync(int ticks)
        {
            await Worker.StartAsync(CancellationToken.None);
            await WaitForAsync(() => ScopesDisposed >= 1);

            for (var i = 1; i < ticks; i++)
            {
                // One advance at a time, each awaited. PeriodicTimer buffers a
                // single tick, so advancing several intervals in a row would
                // collapse into one and the test would silently measure less
                // than it claims to.
                clock.Advance(Interval);
                await WaitForAsync(() => ScopesDisposed >= i + 1);
            }
        }

        public static async Task WaitUntilAsync(Func<bool> condition) => await WaitForAsync(condition);

        private static async Task WaitForAsync(Func<bool> condition)
        {
            for (var attempt = 0; attempt < 500; attempt++)
            {
                if (condition())
                {
                    return;
                }

                await Task.Delay(10);
            }

            Assert.Fail("The worker did not reach the expected state in time.");
        }

        public async ValueTask DisposeAsync()
        {
            await Worker.StopAsync(CancellationToken.None);
            Worker.Dispose();
            await provider.DisposeAsync();
        }
    }

    private sealed class FakeStore : IPurgeStore
    {
        private int calls;

        /// <summary>1-based call number that should fail, or 0 for none.</summary>
        public int ThrowOnCall { get; init; }

        /// <summary>Hold the purge open until the worker is stopped.</summary>
        public bool BlockUntilCancelled { get; init; }

        public List<DateTimeOffset> Calls { get; } = [];

        public async Task<PurgeResult> PurgeBatchAsync(
            DateTimeOffset deletedBefore, int batchSize, CancellationToken cancellationToken = default)
        {
            lock (Calls)
            {
                Calls.Add(deletedBefore);
            }

            if (Interlocked.Increment(ref calls) == ThrowOnCall)
            {
                // In the catch list, so it stands in for the deadlock or dropped
                // connection this loop actually has to survive.
                throw new TimeoutException("simulated transient database fault");
            }

            if (BlockUntilCancelled)
            {
                await Task.Delay(Timeout.Infinite, cancellationToken);
            }

            // Nothing to purge, so PurgeService stops after one batch per tick.
            return new PurgeResult(0, 0);
        }
    }

    /// <summary>Counts the scopes the worker opens, without the worker knowing.</summary>
    private sealed class CountingScopeFactory(IServiceScopeFactory inner, ScopeCounter counter) : IServiceScopeFactory
    {
        public IServiceScope CreateScope() => new CountedScope(inner.CreateScope(), counter);

        private sealed class CountedScope(IServiceScope inner, ScopeCounter counter) : IServiceScope
        {
            public IServiceProvider ServiceProvider => inner.ServiceProvider;

            public void Dispose()
            {
                inner.Dispose();
                counter.Disposed();
            }
        }
    }

    private sealed class ScopeCounter
    {
        private int disposed;

        public int Count => Volatile.Read(ref disposed);

        public void Disposed() => Interlocked.Increment(ref disposed);
    }

    private sealed class CapturingLogger<T>(List<(LogLevel, Exception?)> sink) : ILogger<T>
    {
        public IDisposable? BeginScope<TState>(TState state) where TState : notnull => null;

        public bool IsEnabled(LogLevel logLevel) => true;

        public void Log<TState>(
            LogLevel logLevel, EventId eventId, TState state, Exception? exception,
            Func<TState, Exception?, string> formatter)
        {
            lock (sink)
            {
                sink.Add((logLevel, exception));
            }
        }
    }
}

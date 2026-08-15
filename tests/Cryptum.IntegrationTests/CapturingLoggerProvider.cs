using System.Collections.Concurrent;
using Microsoft.Extensions.Logging;

namespace Cryptum.IntegrationTests;

/// <summary>
/// Captures everything written to <see cref="ILogger"/> so a test can assert on it.
/// </summary>
/// <remarks>
/// Captures the formatted message, the exception, and every state value
/// separately. Structured logging is the likely leak path — a
/// <c>LogInformation("returned {@Item}", response)</c> puts the DEK in the state
/// even when the format string looks harmless — so checking only the rendered
/// message would miss exactly the case worth catching.
/// </remarks>
public sealed class CapturingLoggerProvider : ILoggerProvider
{
    private readonly ConcurrentQueue<string> entries = new();

    public IReadOnlyCollection<string> Entries => entries;

    public ILogger CreateLogger(string categoryName) => new CapturingLogger(entries);

    public void Dispose()
    {
    }

    private sealed class CapturingLogger(ConcurrentQueue<string> entries) : ILogger
    {
        public IDisposable? BeginScope<TState>(TState state)
            where TState : notnull
        {
            entries.Enqueue(state.ToString() ?? string.Empty);
            return null;
        }

        public bool IsEnabled(LogLevel logLevel) => true;

        public void Log<TState>(
            LogLevel logLevel,
            EventId eventId,
            TState state,
            Exception? exception,
            Func<TState, Exception?, string> formatter)
        {
            ArgumentNullException.ThrowIfNull(formatter);

            entries.Enqueue(formatter(state, exception));
            entries.Enqueue(state?.ToString() ?? string.Empty);

            if (exception is not null)
            {
                entries.Enqueue(exception.ToString());
            }

            // Structured values, not just the rendered line.
            if (state is IReadOnlyList<KeyValuePair<string, object?>> values)
            {
                foreach (var value in values)
                {
                    entries.Enqueue($"{value.Key}={value.Value}");
                }
            }
        }
    }
}

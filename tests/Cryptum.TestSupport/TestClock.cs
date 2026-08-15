namespace Cryptum.TestSupport;

/// <summary>A <see cref="TimeProvider"/> that only moves when a test moves it.</summary>
/// <remarks>
/// Hand-rolled rather than taken from <c>Microsoft.Extensions.TimeProvider.Testing</c>:
/// the two members below are the entire surface these tests need, which does not
/// justify a dependency.
///
/// <para>
/// Controlling time matters here beyond determinism. Version ordering and the
/// "newest survive" pruning rule are assertions about time, so a test using the
/// real clock could pass on tie-broken insertion order while the ordering it
/// claims to check was never exercised.
/// </para>
/// </remarks>
public sealed class TestClock(DateTimeOffset now) : TimeProvider
{
    private DateTimeOffset utcNow = now;

    public override DateTimeOffset GetUtcNow() => utcNow;

    public void Advance(TimeSpan by) => utcNow = utcNow.Add(by);
}

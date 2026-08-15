using System.Security.Cryptography;

namespace Cryptum.Domain;

/// <summary>
/// A plaintext DEK held for the shortest possible time, zeroed on disposal.
/// </summary>
/// <remarks>
/// Every read unwraps a DEK inside the API process (see docs/ARCHITECTURE.md),
/// which is the concrete reason Cryptum is server-blind rather than
/// zero-knowledge. Zeroing does not change that — a live compromised process
/// still sees the key in use — but it bounds the window to the operation itself
/// instead of leaving keys in freed heap memory for a crash dump or a
/// heap-scraping attacker to recover long afterwards.
///
/// Wrapping the buffer in a disposable type makes "clear it afterwards" the
/// default rather than a step a caller must remember at every call site.
/// </remarks>
public sealed class PlaintextDek : IDisposable
{
    private readonly byte[] material;
    private bool disposed;

    public PlaintextDek(byte[] material)
    {
        ArgumentNullException.ThrowIfNull(material);
        this.material = material;
    }

    /// <summary>The key bytes. Valid only until disposal.</summary>
    public ReadOnlySpan<byte> Span
    {
        get
        {
            ObjectDisposedException.ThrowIf(disposed, this);
            return material;
        }
    }

    public void Dispose()
    {
        if (disposed)
        {
            return;
        }

        CryptographicOperations.ZeroMemory(material);
        disposed = true;
    }
}

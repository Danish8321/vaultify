namespace Cryptum.Domain;

/// <summary>
/// Thrown when a File registration would exceed <see cref="FileLimits.MaxFileBytes"/>
/// or push the owner's total past <see cref="FileLimits.MaxUserQuotaBytes"/>.
/// </summary>
/// <remarks>
/// Not an <see cref="ArgumentException"/>: the size itself is valid input, it
/// is the combination with existing state (or policy) that refuses it, which
/// the endpoint layer maps to 413/409 rather than 400.
/// </remarks>
public sealed class FileQuotaExceededException(string message) : Exception(message);

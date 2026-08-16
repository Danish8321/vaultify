package com.cryptum.lock

/**
 * Whether the Vault is currently readable.
 *
 * Deliberately free of Android types. The rules that matter — locked by
 * default, re-lock on background, resume never unlocks by itself — are ordering
 * rules, and ordering bugs are the ones a rendering test would miss.
 *
 * Not thread-safe by design: every caller is the main thread (lifecycle
 * callbacks and the biometric result callback both arrive there). Adding
 * synchronisation would imply a concurrency story that does not exist and would
 * hide it if one ever appeared.
 */
class AppLock {

    /** Locked until proven otherwise. */
    var isLocked: Boolean = true
        private set

    private var listener: ((Boolean) -> Unit)? = null

    /** Registers the single observer. The UI is the only legitimate one. */
    fun onLockStateChanged(listener: (Boolean) -> Unit) {
        this.listener = listener
    }

    fun onAuthenticationSucceeded() = transitionTo(locked = false)

    /**
     * A failed attempt changes nothing.
     *
     * No lockout counter here on purpose: the platform prompt already rate
     * limits attempts and falls back to device credential, and a second counter
     * in the app would be a weaker copy that a reinstall clears.
     */
    fun onAuthenticationFailed() = transitionTo(locked = true)

    /** Called when the app leaves the foreground. */
    fun onBackgrounded() = transitionTo(locked = true)

    /**
     * Called when the app returns to the foreground.
     *
     * Intentionally does nothing. Resuming is not evidence of anything — the
     * state was already set when the app was backgrounded, and treating resume
     * as authentication is precisely the bug this class exists to prevent.
     */
    fun onResumed() = Unit

    private fun transitionTo(locked: Boolean) {
        // Redundant transitions are dropped: re-emitting "locked" over an
        // already-locked vault makes the UI re-show an auth prompt that is
        // already on screen.
        if (locked == isLocked) return

        isLocked = locked
        listener?.invoke(locked)
    }
}

package com.cryptum.lock

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The lock is a state machine, kept separate from BiometricPrompt so the rules
 * can be tested without a device, an enrolled fingerprint, or a UI.
 *
 * That separation is the point: the interesting failures here are ordering
 * failures — unlocked when it should not be — and those are logic, not
 * rendering.
 */
class AppLockTest {

    @Test
    fun starts_locked() {
        // A vault that opens unlocked has no lock. This is the default that
        // matters: every other state is reached deliberately.
        assertTrue(AppLock().isLocked)
    }

    @Test
    fun a_successful_authentication_unlocks() {
        val lock = AppLock()

        lock.onAuthenticationSucceeded()

        assertFalse(lock.isLocked)
    }

    @Test
    fun a_failed_authentication_leaves_it_locked() {
        val lock = AppLock()

        lock.onAuthenticationFailed()

        assertTrue(lock.isLocked)
    }

    @Test
    fun backgrounding_re_locks() {
        // The plan's stated verification: backgrounding and resuming re-locks.
        // No grace period — a grace period is exactly the window in which a
        // handed-over phone leaks a vault.
        val lock = AppLock()
        lock.onAuthenticationSucceeded()

        lock.onBackgrounded()

        assertTrue(lock.isLocked)
    }

    @Test
    fun resuming_after_backgrounding_does_not_unlock_by_itself() {
        // The bug this guards against: treating "resumed" as "authenticated"
        // because the user was authenticated a moment ago.
        val lock = AppLock()
        lock.onAuthenticationSucceeded()
        lock.onBackgrounded()

        lock.onResumed()

        assertTrue(lock.isLocked)
    }

    @Test
    fun resuming_without_backgrounding_keeps_an_unlocked_vault_open() {
        // A configuration change — rotation, theme switch, keyboard — must not
        // demand re-authentication. If it did, users would disable the lock.
        val lock = AppLock()
        lock.onAuthenticationSucceeded()

        lock.onResumed()

        assertFalse(lock.isLocked)
    }

    @Test
    fun a_second_authentication_after_re_locking_unlocks_again() {
        val lock = AppLock()
        lock.onAuthenticationSucceeded()
        lock.onBackgrounded()

        lock.onAuthenticationSucceeded()

        assertFalse(lock.isLocked)
    }

    @Test
    fun observers_are_told_every_time_the_state_changes() {
        // The UI cannot poll for this: content has to disappear at the moment
        // of locking, not at the next frame the composer happens to run.
        val seen = mutableListOf<Boolean>()
        val lock = AppLock()
        lock.onLockStateChanged { seen += it }

        lock.onAuthenticationSucceeded()
        lock.onBackgrounded()

        assertEquals(listOf(false, true), seen)
    }

    @Test
    fun redundant_transitions_do_not_notify() {
        // Backgrounding an already-locked vault changes nothing. Emitting
        // anyway would make observers re-render, and in the UI that means
        // re-showing the auth prompt over one already on screen.
        val seen = mutableListOf<Boolean>()
        val lock = AppLock()
        lock.onLockStateChanged { seen += it }

        lock.onBackgrounded()
        lock.onBackgrounded()

        assertEquals(emptyList<Boolean>(), seen)
    }
}

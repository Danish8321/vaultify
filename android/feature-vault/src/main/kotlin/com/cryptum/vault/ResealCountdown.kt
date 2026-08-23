package com.cryptum.vault

import com.cryptum.lock.Seal

/**
 * The reseal countdown's arithmetic, kept free of Compose/coroutines so it can
 * be proven with a plain unit test rather than a coroutine test dispatcher
 * (this repo has no `kotlinx-coroutines-test` dependency yet — see the
 * design-sync-android plan's Task 5 note).
 *
 * The screen drives this with one `tick()` call per elapsed second (a
 * `LaunchedEffect` loop of `delay(1000)`); the countdown itself only knows
 * "one second passed," not wall-clock time, so it is deterministic under test.
 */
class ResealCountdown(private val totalSeconds: Int = Seal.AutoResealSeconds) {

    var secondsRemaining: Int = totalSeconds
        private set

    val progress: Float
        get() = secondsRemaining.toFloat() / totalSeconds.toFloat()

    val expired: Boolean
        get() = secondsRemaining <= 0

    /** One second has elapsed. Returns true the instant the countdown expires. */
    fun tick(): Boolean {
        if (secondsRemaining > 0) {
            secondsRemaining -= 1
        }
        return expired
    }

    fun reset() {
        secondsRemaining = totalSeconds
    }
}

package com.cryptum.vault

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The 45s auto-reseal window's arithmetic, proven without a coroutine test
 * dispatcher: `feature-vault` has no `kotlinx-coroutines-test` dependency
 * (checked against `feature-lock`'s `AppLockTest`, which doesn't use one
 * either), so [ResealCountdown] is deliberately coroutine-free and driven one
 * "second elapsed" tick at a time instead.
 */
class ResealCountdownTest {

    @Test
    fun starts_at_the_full_window() {
        val countdown = ResealCountdown(totalSeconds = 45)

        assertEquals(45, countdown.secondsRemaining)
        assertFalse(countdown.expired)
    }

    @Test
    fun does_not_expire_before_the_window_elapses() {
        val countdown = ResealCountdown(totalSeconds = 45)

        repeat(44) { countdown.tick() }

        assertFalse(countdown.expired)
        assertEquals(1, countdown.secondsRemaining)
    }

    @Test
    fun expires_exactly_on_the_forty_fifth_tick() {
        val countdown = ResealCountdown(totalSeconds = 45)

        repeat(44) { countdown.tick() }
        val expiredOnLastTick = countdown.tick()

        assertTrue(expiredOnLastTick)
        assertTrue(countdown.expired)
        assertEquals(0, countdown.secondsRemaining)
    }

    @Test
    fun does_not_go_negative_if_ticked_past_expiry() {
        val countdown = ResealCountdown(totalSeconds = 45)

        repeat(50) { countdown.tick() }

        assertEquals(0, countdown.secondsRemaining)
        assertTrue(countdown.expired)
    }

    @Test
    fun reset_returns_to_the_full_window() {
        val countdown = ResealCountdown(totalSeconds = 45)
        repeat(10) { countdown.tick() }

        countdown.reset()

        assertEquals(45, countdown.secondsRemaining)
        assertFalse(countdown.expired)
    }
}

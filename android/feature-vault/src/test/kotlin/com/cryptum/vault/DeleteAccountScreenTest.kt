package com.cryptum.vault

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The delete-permanently button's enabled predicate, kept as a plain function
 * ([isDeleteConfirmed]) so it is provable without composing the screen -
 * same pattern as [ResealCountdownTest].
 */
class DeleteAccountScreenTest {

    @Test
    fun disabled_when_nothing_typed() {
        assertFalse(isDeleteConfirmed(""))
    }

    @Test
    fun disabled_for_a_partial_match() {
        assertFalse(isDeleteConfirmed("DELET"))
    }

    @Test
    fun disabled_when_case_does_not_match() {
        assertFalse(isDeleteConfirmed("delete"))
    }

    @Test
    fun disabled_with_surrounding_whitespace() {
        assertFalse(isDeleteConfirmed(" DELETE "))
    }

    @Test
    fun enabled_on_exact_match() {
        assertTrue(isDeleteConfirmed("DELETE"))
    }
}

/**
 * [ShreddingScreen] fires `onFinished` once, after [ShreddingDurationMillis].
 * Tested via the extracted [shreddingFinished] predicate rather than the
 * composable's `LaunchedEffect` directly - this module has no
 * `kotlinx-coroutines-test` dependency to advance a virtual clock through the
 * effect (same gap noted for [ResealCountdown]'s tests).
 */
class ShreddingScreenTest {

    @Test
    fun not_finished_before_the_duration_elapses() {
        assertFalse(shreddingFinished(0))
        assertFalse(shreddingFinished(ShreddingDurationMillis - 1L))
    }

    @Test
    fun finished_once_the_duration_elapses() {
        assertTrue(shreddingFinished(ShreddingDurationMillis.toLong()))
        assertTrue(shreddingFinished(ShreddingDurationMillis + 1L))
    }
}

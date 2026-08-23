package com.cryptum.vault

import kotlin.test.Test
import kotlin.test.assertEquals

class ActivityScreenTest {

    private fun entry(group: String) = ActivityEntry(
        group = group,
        time = "10:00",
        title = "Test item",
        verb = "opened",
        failed = false,
        anomaly = false,
    )

    @Test
    fun `header shown only when group changes`() {
        val entries = listOf(
            entry("Today"),
            entry("Today"),
            entry("Today"),
            entry("Yesterday"),
            entry("Yesterday"),
            entry("Aug 21"),
        )

        val showHeader = computeShowHeader(entries)

        assertEquals(
            listOf(true, false, false, true, false, true),
            showHeader,
        )
    }

    @Test
    fun `empty list produces empty result`() {
        assertEquals(emptyList(), computeShowHeader(emptyList()))
    }
}

package com.cryptum.vault

/**
 * One row of the activity log.
 *
 * `group` is the day-header text (e.g. "Today", "Yesterday", "Aug 21") the
 * screen groups consecutive entries by; the caller is expected to hand these
 * in already grouped/ordered, per docs/design-language.md's activity log
 * mock — this type carries no timestamp math of its own.
 */
data class ActivityEntry(
    val group: String,
    val time: String,
    val title: String,
    val verb: String,
    val failed: Boolean,
    val anomaly: Boolean,
)

package com.cryptum.vault

import kotlinx.serialization.Serializable

/**
 * Everything about a Secret that the server must not be able to read.
 *
 * One object, sealed as a unit, rather than four separately encrypted fields.
 * Per-field envelopes would leak which fields a Secret has and roughly how long
 * each one is, and would multiply the number of DEKs the server holds per Item
 * for no gain.
 *
 * The title is deliberately absent: it stays in plaintext so the list can render
 * without unwrapping a DEK per row. That trade-off is recorded and accepted in
 * ADR-0002, not an oversight here.
 *
 * Every field is nullable with no default of `""`. An absent URL and a blank URL
 * are different things to a user, and collapsing them silently edits their data.
 */
@Serializable
data class SecretPayload(
    val username: String? = null,
    val password: String? = null,
    val url: String? = null,
    val notes: String? = null,
)

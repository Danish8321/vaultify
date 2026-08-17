package com.cryptum.lock

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The visual vocabulary of the seal, from docs/design-language.md.
 *
 * Kept as plain values rather than a Material colour scheme on purpose. The
 * Public because feature-vault renders the same material: two modules holding
 * two copies of the same hex values is two places for them to drift apart.
 *
 * design language forbids the stock look, and a `MaterialTheme` would supply
 * exactly that as the path of least resistance — surfaces, cards, elevation.
 * What this app needs is far smaller: two states, distinguishable by mass.
 */
object Seal {

    /** Behind everything. Near-black, not black: pure black hides the grain. */
    val Ground = Color(0xFF0B0C0E)

    /** The sealed mass. Reads as material, not as a disabled control. */
    val Mass = Color(0xFF1A1D22)

    /** Grain on the mass, so "sealed" survives a bad screen in sunlight. */
    val Grain = Color(0xFF23272E)

    /** Text on the ground. */
    val Ink = Color(0xFFE6E8EC)

    /** Secondary text: present, not competing. */
    val InkDim = Color(0xFF7C838F)

    /**
     * The single accent, spent only on the act of opening. Everything else in
     * the app is monochrome so that this one colour means something.
     */
    val Open = Color(0xFF4ADE9B)

    val TitleSize = 13.sp
    val TitleTracking = 0.32.sp
    val BodySize = 15.sp
    val CaptionSize = 12.sp

    val Gutter = 28.dp
    val HoldToOpenMillis = 600
}

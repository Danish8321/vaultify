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
    val Ground = Color(0xFF171515)

    /** The sealed mass. Reads as material, not as a disabled control. */
    val Mass = Color(0xFF3A3737)

    /** Grain on the mass, so "sealed" survives a bad screen in sunlight. */
    val Grain = Color(0xFF434143)

    /** Text on the ground. */
    val Ink = Color(0xFFF3F2F2)

    /** Secondary text: present, not competing. */
    val InkDim = Color(0xFF8F8B8B)

    /** Tertiary text: quieter still. */
    val Tertiary = Color(0xFF605D5D)

    /** Hairline separators, low-alpha over `Ink`. */
    val Divider = Color(0x1FF3F2F2)

    /**
     * The single accent, spent only on the act of opening. Everything else in
     * the app is monochrome so that this one colour means something.
     */
    val Open = Color(0xFFFF563C)

    /** Dimmed accent, for pressed/secondary accent states. */
    val AccentDim = Color(0xFFC94B39)

    /** Card/row surface fill. */
    val CardBg = Color(0xFF211F1F)

    /** Toast/snackbar surface fill. */
    val ToastBg = Color(0xFF211F1F)

    val TitleSize = 13.sp
    val TitleTracking = 0.32.sp
    val BodySize = 15.sp
    val CaptionSize = 12.sp

    val Gutter = 28.dp
    val HoldToOpenMillis = 800

    /** Sealing/opening cross-fade. Design language: 200-300ms, eased. */
    val SealTransitionMillis = 260

    /** An opened item auto-reseals after this many seconds of being open. */
    val AutoResealSeconds = 45

    /** The clipboard is cleared this many seconds after a copy. */
    val ClipboardClearSeconds = 30
}

/** Light-theme counterpart of [Seal], same property names. */
object SealLight {

    val Ground = Color(0xFFF3F2F2)
    val Mass = Color(0xFFD7D3D3)
    val Ink = Color(0xFF201E1D)
    val InkDim = Color(0xFF605D5D)
    val Tertiary = Color(0xFF7D7979)
    val Divider = Color(0x29201E1D)
    val Open = Color(0xFFEC3013)
    val AccentDim = Color(0xFFAE1800)
    val CardBg = Color(0xFFFFFFFF)
    val ToastBg = Color(0xFFEAE9E9)
}

/** Corner radii for the design system's shapes. */
object SealRadius {
    val Button = 26.dp
    val Card = 18.dp
    val ChipLarge = 16.dp
    val ChipMedium = 9.dp
    val ChipSmall = 6.dp
}

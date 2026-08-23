package com.cryptum.lock

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The states a seal chip can be in, per docs/design-language.md's sealing model.
 *
 * Exported from here (rather than a vault-layer file) because it is a leaf
 * concept the item's material is either sealed, moving or gone, and
 * `VaultRepository` and friends need to reference it without depending on
 * anything else in this file's Compose rendering.
 */
sealed class SealState {
    object Sealed : SealState()
    object Unwrapping : SealState()
    object Open : SealState()
    object Failed : SealState()
    object Shredding : SealState()
}

/**
 * A deterministic two-character "glyph" pair derived from [id].
 *
 * Mirrors the prototype's rolling hash (`h = h * 31 + charCode`) so the same
 * item id always renders the same glyph, on every device and every run -
 * no `Random`, nothing time-based.
 */
fun hashGlyph(id: String): Pair<String, String> {
    var hash = 0
    for (char in id) {
        hash = hash * 31 + char.code
    }
    val hex = hash.toLong().and(0xFFFFFFFFL).toString(16).padStart(8, '0')
    return hex.substring(0, 2) to hex.substring(hex.length - 2)
}

/**
 * A small sealed/unsealing block of material, standing in for the item it
 * represents rather than an icon chosen from a set: the glyph is derived
 * from the item's own id, not a generic lock/key pictogram.
 */
@Composable
fun SealChip(
    id: String,
    state: SealState,
    size: Dp,
    modifier: Modifier = Modifier,
) {
    val radius = when {
        size >= 40.dp -> SealRadius.ChipLarge
        size >= 24.dp -> SealRadius.ChipMedium
        else -> SealRadius.ChipSmall
    }
    val shape = RoundedCornerShape(radius)
    val (first, second) = hashGlyph(id)

    val infiniteTransition = rememberInfiniteTransition(label = "seal-chip")
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "unwrapping-rotation",
    )
    val dissolve by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "shredding-dissolve",
    )

    val chipModifier = when (state) {
        SealState.Sealed ->
            modifier
                .size(size)
                .background(Seal.Mass, shape)
                .border(1.dp, Seal.Divider, shape)

        SealState.Unwrapping ->
            modifier
                .size(size)
                .background(Seal.Mass, shape)
                .border(1.dp, Seal.Open.copy(alpha = 0.35f + 0.5f * ((rotation % 360f) / 360f)), shape)

        SealState.Open ->
            modifier
                .size(size)
                .background(Color.Transparent, shape)
                .border(1.dp, Seal.Open, shape)

        SealState.Failed ->
            modifier
                .size(size)
                .background(Color.Transparent, shape)
                .dashedBorder(1.dp, Seal.AccentDim, radius)

        SealState.Shredding ->
            modifier
                .size(size)
                .scale(0.8f + 0.2f * dissolve)
                .background(Seal.Open.copy(alpha = dissolve), shape)
    }

    Box(chipModifier, contentAlignment = Alignment.Center) {
        if (size != 16.dp) {
            val glyphText = if (state == SealState.Failed) "—" else "$first$second"
            Text(
                text = glyphText,
                color = if (state == SealState.Open) Seal.Open else Seal.InkDim,
                fontFamily = FontFamily.Monospace,
                fontSize = Seal.CaptionSize,
            )
        }
    }
}

/** A dashed rounded-rect outline, used for the failed seal state. */
private fun Modifier.dashedBorder(
    width: Dp,
    color: Color,
    cornerRadius: Dp,
): Modifier = this.drawBehind {
    val strokeWidthPx = width.toPx()
    drawRoundRect(
        color = color,
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(cornerRadius.toPx(), cornerRadius.toPx()),
        style = Stroke(
            width = strokeWidthPx,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 4f), 0f),
        ),
    )
}

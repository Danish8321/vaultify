package com.cryptum.vault

import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.unit.dp
import com.cryptum.lock.Seal
import com.cryptum.lock.SealRadius
import kotlinx.coroutines.delay

/**
 * The confirmation phrase that unlocks the delete button. Case-sensitive,
 * matching the prototype - typing "delete" is not the same as meaning it.
 */
private const val CONFIRMATION_PHRASE = "DELETE"

/**
 * Whether the typed confirmation text unlocks the delete button. Extracted
 * from the composable so the rule is provable with a plain unit test, the
 * same pattern as [ResealCountdown].
 */
fun isDeleteConfirmed(typed: String): Boolean = typed == CONFIRMATION_PHRASE

/**
 * The irreversible last step: destroy the key that makes every item
 * readable. There is no soft-delete on this screen - see docs/design-language.md's
 * Deletion section, which this copy is kept word-for-word consistent with.
 *
 * [onConfirmDelete] is a caller-supplied callback only. `VaultRepository` has
 * no delete method to call here - see .scratch/cryptum/issues/23 for the gap
 * this leaves.
 */
@Composable
fun DeleteAccountScreen(
    onConfirmDelete: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var typed by remember { mutableStateOf("") }

    Column(modifier.fillMaxWidth().background(Seal.Ground).padding(Seal.Gutter)) {
        Text(text = "Delete your vault.", color = Seal.Ink, fontSize = Seal.BodySize)
        Text(
            text = "This destroys your key. Every item becomes permanently unreadable — by " +
                "you, by us, by anyone holding a backup. There is no recovery, no grace " +
                "period, and no support ticket that undoes it.",
            color = Seal.InkDim,
            fontSize = Seal.CaptionSize,
            modifier = Modifier.padding(top = 12.dp),
        )
        OutlinedTextField(
            value = typed,
            onValueChange = { typed = it },
            modifier = Modifier.fillMaxWidth().padding(top = 20.dp),
            label = { Text("Type DELETE to confirm") },
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Seal.Ink,
                unfocusedTextColor = Seal.Ink,
            ),
        )
        Button(
            onClick = onConfirmDelete,
            enabled = isDeleteConfirmed(typed),
            shape = RoundedCornerShape(SealRadius.Button),
            colors = ButtonDefaults.buttonColors(containerColor = Seal.Open),
            modifier = Modifier.fillMaxWidth().padding(top = 20.dp),
        ) {
            Text("Delete permanently")
        }
        TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Text("Cancel", color = Seal.InkDim)
        }
    }
}

/** How long the shredding dissolve animation runs before [onFinished] fires. */
const val ShreddingDurationMillis = 1200

/**
 * Whether [elapsedMillis] since the shredding animation started is enough to
 * fire [ShreddingScreen]'s `onFinished` callback. Extracted from the
 * `LaunchedEffect`'s `delay` call so the "fires once after N ms" rule is
 * provable with a plain unit test - this module has no `kotlinx-coroutines-test`
 * dependency to drive a virtual clock through the effect itself, same
 * constraint noted by [ResealCountdown].
 */
fun shreddingFinished(elapsedMillis: Long): Boolean = elapsedMillis >= ShreddingDurationMillis

/**
 * The one place docs/design-language.md sanctions a heavier motion treatment -
 * the key-destruction moment, shown once after [DeleteAccountScreen]'s confirm
 * step. Don't reuse this dissolve/pulse pattern elsewhere in the app.
 *
 * Purely presentational: [onFinished] is fired once, caller-driven, after
 * [ShreddingDurationMillis] via `delay` - navigation away from this screen is
 * the caller's responsibility, not this composable's.
 */
@Composable
fun ShreddingScreen(
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(Unit) {
        delay(ShreddingDurationMillis.toLong())
        onFinished()
    }

    val dissolve by animateFloatAsState(
        targetValue = 0f,
        animationSpec = tween(durationMillis = ShreddingDurationMillis, easing = EaseOutCubic),
        label = "shredding-dissolve",
    )
    val pulseTransition = rememberInfiniteTransition(label = "shredding-caption-pulse")
    val captionAlpha by pulseTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 600, easing = EaseOutCubic),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "shredding-caption-pulse-alpha",
    )

    Column(
        modifier = modifier.fillMaxWidth().background(Seal.Ground).padding(Seal.Gutter),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(96.dp)
                .alpha(dissolve)
                .scale(0.8f + dissolve * 0.2f)
                .background(Seal.Open, RoundedCornerShape(SealRadius.Card)),
        )
        Text(
            text = "Destroying key…",
            color = Seal.InkDim,
            fontSize = Seal.CaptionSize,
            modifier = Modifier.padding(top = 20.dp).alpha(captionAlpha),
        )
    }
}

package com.cryptum.lock

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Text
import kotlinx.coroutines.delay

const val TAG_SEAL = "seal"
const val TAG_VAULT_CONTENT = "vault-content"

/**
 * Shows [content] only while the Vault is unlocked. Otherwise the seal.
 *
 * The gate is a real gate: locked content is not rendered at all, rather than
 * rendered and covered. A covering overlay leaves the content in the view
 * hierarchy, where it reaches the recents thumbnail, accessibility services and
 * anything that walks the tree.
 */
@Composable
fun LockGate(
    isLocked: Boolean,
    onUnlockRequested: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(modifier.fillMaxSize().background(Seal.Ground)) {
        if (isLocked) {
            SealScreen(onUnlockRequested)
        } else {
            Box(Modifier.fillMaxSize().testTag(TAG_VAULT_CONTENT)) { content() }
        }
    }
}

/**
 * The locked state, rendered as mass rather than as a dialog over a blur.
 *
 * Offers two ways in, one at a time: the biometric hold gesture (the sole
 * affordance until this task) and a PIN grid. There is still no keypad
 * shown by default, no logo lockup competing with the wordmark and no
 * illustration — switching modes is a deliberate, explicit choice via the
 * toggle line, not two controls fighting for attention at once.
 */
@Composable
internal fun SealScreen(onUnlockRequested: () -> Unit) {
    var pinMode by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .testTag(TAG_SEAL)
            .padding(horizontal = Seal.Gutter, vertical = 64.dp),
    ) {
        Text(
            text = "C R Y P T U M",
            color = Seal.InkDim,
            fontSize = Seal.TitleSize,
            letterSpacing = Seal.TitleTracking,
            fontWeight = FontWeight.Medium,
        )

        Spacer(Modifier.weight(1f))

        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            if (pinMode) {
                PinSeal(onUnlockRequested = onUnlockRequested)
            } else {
                BiometricSeal(onUnlockRequested = onUnlockRequested)
            }
        }

        Spacer(Modifier.weight(1f))

        // Both modes stay reachable from the other — locking a user into
        // whichever affordance failed once would be its own kind of lockout.
        Text(
            text = if (pinMode) "Use fingerprint instead" else "Use PIN instead",
            color = Seal.InkDim,
            fontSize = Seal.CaptionSize,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .clickable { pinMode = !pinMode },
        )
    }
}

/**
 * The existing hold-to-open biometric affordance, restyled to the current
 * token set. Interaction model unchanged: opening is a sustained gesture,
 * never a tap, so revealing the vault in public is never one accidental
 * touch away.
 */
@Composable
internal fun BiometricSeal(onUnlockRequested: () -> Unit) {
    var holding by remember { mutableStateOf(false) }
    var fired by remember { mutableStateOf(false) }

    val progress by animateFloatAsState(
        targetValue = if (holding) 1f else 0f,
        animationSpec = tween(durationMillis = if (holding) Seal.HoldToOpenMillis else 140),
        label = "hold",
    )

    LaunchedEffect(holding) {
        if (holding) {
            delay(Seal.HoldToOpenMillis.toLong())
            if (!fired) {
                fired = true
                onUnlockRequested()
            }
        } else {
            fired = false
        }
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        // The circular hold target from the prototype, not a full-width mass:
        // legibility of "sealed" here comes from the accent ring and the
        // fingerprint glyph, not from a grain field.
        Box(
            Modifier
                .size(76.dp)
                .clip(CircleShape)
                .border(1.5.dp, Seal.Open, CircleShape)
                .drawBehind {
                    if (progress > 0f) {
                        drawCircle(color = Color(Seal.Open.value), alpha = 0.14f * progress)
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onPress = {
                            holding = true
                            tryAwaitRelease()
                            holding = false
                        },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            FingerprintGlyph(color = Seal.Open, size = 32.dp)
        }

        Spacer(Modifier.height(20.dp))

        Text(
            text = if (progress > 0.02f) "opening…" else "Tap and hold to unlock",
            color = if (progress > 0.02f) Seal.Open else Seal.InkDim,
            fontSize = Seal.CaptionSize,
            fontFamily = FontFamily.Monospace,
        )

        Spacer(Modifier.height(6.dp))

        // States the security model plainly, which is the whole thesis: the
        // keys are not on this device, so this is not a cosmetic lock.
        Text(
            text = "keys unavailable until you authenticate",
            color = Seal.Tertiary,
            fontSize = Seal.CaptionSize,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * A minimal drawn fingerprint glyph — no icon library is present in this
 * module's dependencies, so this is a few nested arcs rather than a
 * pulled-in icon set, matching the pattern used elsewhere in this app.
 */
@Composable
internal fun FingerprintGlyph(color: Color, size: androidx.compose.ui.unit.Dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val strokeWidth = 1.6.dp.toPx()
        val center = androidx.compose.ui.geometry.Offset(this.size.width / 2f, this.size.height / 2f)
        val maxRadius = this.size.minDimension / 2f
        listOf(0.35f, 0.6f, 0.85f).forEach { fraction ->
            drawArc(
                color = color,
                startAngle = 200f,
                sweepAngle = 220f,
                useCenter = false,
                topLeft = androidx.compose.ui.geometry.Offset(
                    center.x - maxRadius * fraction,
                    center.y - maxRadius * fraction,
                ),
                size = androidx.compose.ui.geometry.Size(maxRadius * fraction * 2, maxRadius * fraction * 2),
                style = Stroke(width = strokeWidth),
            )
        }
    }
}

/**
 * The PIN affordance: a 6-slot dot row above a digit grid.
 *
 * Entering the sixth digit is the equivalent of the biometric hold firing
 * at the end of its gesture — a single, unambiguous completion point,
 * not a submit button competing with the grid for space.
 */
@Composable
internal fun PinSeal(onUnlockRequested: () -> Unit) {
    var pin by remember { mutableStateOf("") }

    LaunchedEffect(pin) {
        if (pin.length == PIN_LENGTH) {
            onUnlockRequested()
        }
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        PinDots(length = pin.length)

        Spacer(Modifier.height(28.dp))

        PinPad(
            onDigit = { digit -> if (pin.length < PIN_LENGTH) pin += digit },
            onBackspace = { if (pin.isNotEmpty()) pin = pin.dropLast(1) },
        )

        Spacer(Modifier.height(20.dp))

        Text(
            text = "keys unavailable until you authenticate",
            color = Seal.InkDim,
            fontSize = Seal.CaptionSize,
            textAlign = TextAlign.Start,
        )
    }
}

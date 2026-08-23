package com.cryptum.lock

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
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
            .padding(horizontal = Seal.Gutter),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "C R Y P T U M",
            color = Seal.InkDim,
            fontSize = Seal.TitleSize,
            letterSpacing = Seal.TitleTracking,
            fontWeight = FontWeight.Medium,
        )

        Spacer(Modifier.height(20.dp))

        if (pinMode) {
            PinSeal(onUnlockRequested = onUnlockRequested)
        } else {
            BiometricSeal(onUnlockRequested = onUnlockRequested)
        }

        Spacer(Modifier.height(36.dp))

        // Both modes stay reachable from the other — locking a user into
        // whichever affordance failed once would be its own kind of lockout.
        Text(
            text = if (pinMode) "Use fingerprint instead" else "Use PIN instead",
            color = Seal.InkDim,
            fontSize = Seal.CaptionSize,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.clickable { pinMode = !pinMode },
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

    Column(
        Modifier.pointerInput(Unit) {
            detectTapGestures(
                onPress = {
                    holding = true
                    tryAwaitRelease()
                    holding = false
                },
            )
        },
    ) {
        // The mass. Grain rather than a flat fill so the sealed state is
        // legible without relying on colour.
        SealMass(
            progress = progress,
            modifier = Modifier.fillMaxWidth().height(184.dp),
        )

        Spacer(Modifier.height(20.dp))

        Text(
            text = if (progress > 0.02f) "opening" else "sealed",
            color = if (progress > 0.02f) Seal.Open else Seal.Ink,
            fontSize = Seal.BodySize,
            fontFamily = FontFamily.Monospace,
        )

        Spacer(Modifier.height(6.dp))

        // States the security model plainly, which is the whole thesis: the
        // keys are not on this device, so this is not a cosmetic lock.
        Text(
            text = "keys unavailable until you authenticate",
            color = Seal.InkDim,
            fontSize = Seal.CaptionSize,
            textAlign = TextAlign.Start,
        )

        Spacer(Modifier.height(20.dp))

        Text(
            text = "press and hold",
            color = Seal.InkDim,
            fontSize = Seal.CaptionSize,
            fontFamily = FontFamily.Monospace,
        )
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

/**
 * A block of sealed material that erodes as [progress] rises.
 *
 * Drawn rather than assembled from components: the state is "how much of this
 * is still closed", which no stock progress indicator expresses. It reads as
 * material being removed, not as a bar filling up.
 */
@Composable
internal fun SealMass(progress: Float, modifier: Modifier = Modifier) {
    Box(
        modifier.drawBehind {
            val rows = 7
            val cols = 12
            val cellW = size.width / cols
            val cellH = size.height / rows

            drawRect(color = Seal.Mass, size = size)

            for (r in 0 until rows) {
                for (c in 0 until cols) {
                    // Erosion runs from the leading edge, so partial progress
                    // is directional and readable rather than a random dissolve.
                    val cellIndex = (r * cols + c).toFloat() / (rows * cols)
                    if (cellIndex < progress) continue

                    // Deterministic pseudo-grain: no Random, so the same screen
                    // renders identically every recomposition and in tests.
                    val on = ((r * 31 + c * 17) % 5) < 2
                    if (!on) continue

                    drawRect(
                        color = Seal.Grain,
                        topLeft = Offset(c * cellW + cellW * 0.18f, r * cellH + cellH * 0.18f),
                        size = Size(cellW * 0.64f, cellH * 0.64f),
                    )
                }
            }

            if (progress > 0f) {
                // The opening edge. The only accent in the app.
                drawRect(
                    color = Color(Seal.Open.value),
                    topLeft = Offset(0f, size.height * (1f - progress)),
                    size = Size(3.dp.toPx(), size.height * progress),
                )
            }
        },
    )
}

package com.cryptum.lock

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.random.Random

const val TAG_ONBOARD_SLIDES = "onboard-slides"
const val TAG_ONBOARD_SKIP = "onboard-skip"
const val TAG_ONBOARD_NEXT = "onboard-next"
const val TAG_ONBOARD_PIN = "onboard-pin"
const val TAG_ONBOARD_BIO = "onboard-bio"
const val TAG_ONBOARD_SET_UP_LATER = "onboard-set-up-later"

private enum class OnboardStage { Slides, Pin, Bio }
private enum class PinStep { Enter, Confirm }

private data class Slide(val title: String, val body: String)

private val Slides = listOf(
    Slide("Your keys, not ours", "Cryptum encrypts on this device. We never see a plaintext value, and we couldn't hand one over if asked."),
    Slide("Nothing opens by accident", "Every reveal is a deliberate hold, never a tap. Closed is the default state, not a screen you have to remember to leave."),
    Slide("Built to be watched", "Every unlock and reveal is logged right here, on the Activity tab — so a break-in leaves a trail you can actually read."),
)

/**
 * First-run flow: matrix-rain intro slides, PIN setup, then an optional
 * biometric enrollment. Gated by an in-memory flag in [MainActivity] rather
 * than a persisted one — there is no settings-persistence layer yet (same
 * gap [SettingsScreen] already lives with), so onboarding replays on a
 * fresh process today. Persisting the flag is a further slice.
 */
@Composable
fun OnboardingScreen(onFinished: () -> Unit) {
    var stage by remember { mutableStateOf(OnboardStage.Slides) }

    Box(Modifier.fillMaxSize().background(Seal.Ground)) {
        MatrixRain()
        when (stage) {
            OnboardStage.Slides -> OnboardSlides(
                onSkip = { stage = OnboardStage.Pin },
                onFinishedSlides = { stage = OnboardStage.Pin },
            )
            OnboardStage.Pin -> OnboardPinSetup(onDone = { stage = OnboardStage.Bio })
            OnboardStage.Bio -> OnboardBioEnroll(onDone = onFinished, onSkip = onFinished)
        }
    }
}

/**
 * A faded column-of-characters backdrop behind every onboarding stage,
 * standing in for the prototype's CSS `matrix-col`/`grainFlicker` animation.
 * Not a character-accurate port — a recognisable decaying-column effect
 * built from [Seal]'s own palette, cheap enough to run behind interactive
 * content the whole flow through.
 */
@Composable
private fun MatrixRain(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "matrix-rain")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(6000, easing = LinearEasing)),
        label = "phase",
    )
    // Deterministic per-column seed so recomposition doesn't reshuffle the
    // whole field every frame — only [phase] should move.
    val seeds = remember { List(13) { Random(it * 97 + 11).nextFloat() } }

    Box(
        modifier
            .fillMaxSize()
            .drawBehind {
                val colWidth = this.size.width / seeds.size
                seeds.forEachIndexed { index, seed ->
                    val columnPhase = (phase + seed) % 1f
                    val y = columnPhase * this.size.height * 1.4f - this.size.height * 0.2f
                    val x = colWidth * index + colWidth / 2f
                    val alpha = 0.10f + 0.12f * kotlin.math.sin((phase + seed) * 6.28f).let { if (it < 0) -it else it }
                    drawLine(
                        color = Seal.InkDim.copy(alpha = alpha.coerceIn(0.05f, 0.22f)),
                        start = Offset(x, y),
                        end = Offset(x, y + this.size.height * 0.18f),
                        strokeWidth = 1.5.dp.toPx(),
                    )
                }
            },
    )
}

@Composable
private fun OnboardSlides(onSkip: () -> Unit, onFinishedSlides: () -> Unit) {
    var index by remember { mutableStateOf(0) }
    val slide = Slides[index]

    Column(
        Modifier
            .fillMaxSize()
            .testTag(TAG_ONBOARD_SLIDES)
            .padding(horizontal = Seal.Gutter, vertical = 28.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("C R Y P T U M", color = Seal.InkDim, fontSize = Seal.TitleSize, letterSpacing = Seal.TitleTracking, fontWeight = FontWeight.Medium)
            Text(
                "Skip",
                color = Seal.InkDim,
                fontFamily = FontFamily.Monospace,
                fontSize = Seal.CaptionSize,
                modifier = Modifier.testTag(TAG_ONBOARD_SKIP).clickable(onClick = onSkip),
            )
        }

        Column(
            Modifier.weight(1f),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            SlideGlyph(index)
            Spacer(Modifier.height(28.dp))
            Text(slide.title, color = Seal.Ink, fontSize = 20.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
            Spacer(Modifier.height(10.dp))
            Text(
                slide.body,
                color = Seal.InkDim,
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
                style = TextStyle(lineHeight = 20.sp),
            )
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            Slides.indices.forEach { i ->
                Box(
                    Modifier
                        .padding(horizontal = 4.dp)
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(if (i == index) Seal.Open else Seal.Mass),
                )
            }
        }
        Spacer(Modifier.height(24.dp))

        Box(
            Modifier
                .fillMaxWidth()
                .height(56.dp)
                .clip(RoundedCornerShape(SealRadius.Button))
                .testTag(TAG_ONBOARD_NEXT)
                .background(Seal.Grain)
                .clickable {
                    if (index < Slides.lastIndex) index++ else onFinishedSlides()
                },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = if (index < Slides.lastIndex) "Next" else "Get started",
                color = Seal.Ink,
                fontFamily = FontFamily.Monospace,
                fontSize = 15.sp,
            )
        }
    }
}

@Composable
private fun SlideGlyph(index: Int) {
    when (index) {
        0 -> Box(Modifier.size(96.dp).clip(RoundedCornerShape(22.dp)).background(Seal.Mass))
        1 -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            repeat(3) { Box(Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)).background(Seal.Mass)) }
        }
        else -> Box(
            Modifier
                .size(96.dp)
                .clip(RoundedCornerShape(22.dp))
                .border(1.5.dp, Seal.Open, RoundedCornerShape(22.dp)),
        )
    }
}

@Composable
private fun OnboardPinSetup(onDone: () -> Unit) {
    var step by remember { mutableStateOf(PinStep.Enter) }
    var firstPin by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(pin) {
        if (pin.length == PIN_LENGTH) {
            when (step) {
                PinStep.Enter -> {
                    firstPin = pin
                    pin = ""
                    step = PinStep.Confirm
                }
                PinStep.Confirm -> {
                    if (pin == firstPin) {
                        onDone()
                    } else {
                        error = "PINs didn't match — try again"
                        pin = ""
                        firstPin = ""
                        step = PinStep.Enter
                    }
                }
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .testTag(TAG_ONBOARD_PIN)
            .padding(horizontal = Seal.Gutter),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = if (step == PinStep.Enter) "Choose a PIN" else "Confirm your PIN",
            color = Seal.Ink,
            fontSize = 18.sp,
            fontWeight = FontWeight.Medium,
        )
        Spacer(Modifier.height(8.dp))
        error?.let {
            Text(it, color = Seal.Open, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            Spacer(Modifier.height(8.dp))
        }
        Spacer(Modifier.height(20.dp))
        PinDots(length = pin.length)
        Spacer(Modifier.height(28.dp))
        PinPad(
            onDigit = { digit -> if (pin.length < PIN_LENGTH) { error = null; pin += digit } },
            onBackspace = { if (pin.isNotEmpty()) pin = pin.dropLast(1) },
        )
    }
}

@Composable
private fun OnboardBioEnroll(onDone: () -> Unit, onSkip: () -> Unit) {
    var holding by remember { mutableStateOf(false) }
    var fired by remember { mutableStateOf(false) }

    val progress by animateFloatAsState(
        targetValue = if (holding) 1f else 0f,
        animationSpec = tween(durationMillis = if (holding) Seal.HoldToOpenMillis else 140),
        label = "bio-enroll-hold",
    )

    LaunchedEffect(holding) {
        if (holding) {
            delay(Seal.HoldToOpenMillis.toLong())
            if (!fired) {
                fired = true
                onDone()
            }
        } else {
            fired = false
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .testTag(TAG_ONBOARD_BIO)
            .padding(horizontal = Seal.Gutter),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
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
            text = if (progress > 0.02f) "enrolling…" else "Hold to enroll",
            color = if (progress > 0.02f) Seal.Open else Seal.InkDim,
            fontSize = Seal.CaptionSize,
            fontFamily = FontFamily.Monospace,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "Hold to enroll your fingerprint for one-tap unlock.",
            color = Seal.Tertiary,
            fontSize = Seal.CaptionSize,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(260.dp),
        )
        Spacer(Modifier.height(20.dp))
        Text(
            "Set up later",
            color = Seal.InkDim,
            fontFamily = FontFamily.Monospace,
            fontSize = Seal.CaptionSize,
            modifier = Modifier.testTag(TAG_ONBOARD_SET_UP_LATER).clickable(onClick = onSkip),
        )
    }
}

package com.cryptum.lock

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

/** Digits a PIN may hold. Matches the prototype's 6-slot dot row. */
const val PIN_LENGTH = 6

/**
 * The 6-slot progress row above the digit grid: one dot per PIN position,
 * filled once a digit has been entered for that slot. A plain drawn row
 * rather than a stock progress indicator, for the same reason [SealMass]
 * is drawn rather than assembled — it needs to read as "how much of the PIN
 * is in", not as a generic loading bar.
 */
@Composable
internal fun PinDots(length: Int, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        repeat(PIN_LENGTH) { index ->
            val filled = index < length
            Box(
                Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(if (filled) Seal.Open else Seal.Mass),
            )
        }
    }
}

/**
 * The 3x4 digit grid: 1-9, an empty gap where the prototype leaves a blank
 * cell, 0, and backspace. Plain composable buttons only — no FAB, no stock
 * Material `ListItem` grid, per the design language's forbidden list.
 */
@Composable
internal fun PinPad(
    onDigit: (Char) -> Unit,
    onBackspace: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val rows = listOf(
        listOf('1', '2', '3'),
        listOf('4', '5', '6'),
        listOf('7', '8', '9'),
        listOf(null, '0', BACKSPACE),
    )

    Column(modifier, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                row.forEach { key ->
                    when (key) {
                        null -> Spacer(Modifier.width(PIN_KEY_SIZE))
                        BACKSPACE -> PinKey(label = "⌫", onClick = onBackspace)
                        else -> PinKey(label = key.toString(), onClick = { onDigit(key) })
                    }
                }
            }
        }
    }
}

private const val BACKSPACE = '\b'
private val PIN_KEY_SIZE = 64.dp

@Composable
private fun PinKey(label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(PIN_KEY_SIZE)
            .clip(CircleShape)
            .background(Seal.Mass)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = Seal.Ink,
            fontFamily = FontFamily.Monospace,
            fontSize = Seal.BodySize,
        )
    }
}

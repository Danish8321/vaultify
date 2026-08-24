package com.cryptum.vault

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.cryptum.lock.Seal
import com.cryptum.lock.SealChip
import com.cryptum.lock.SealRadius
import com.cryptum.lock.SealState

const val TAG_ACTIVITY_SCREEN = "activity-screen"

/**
 * For each entry, whether its group header should render — true only when
 * its `group` differs from the previous entry's (or it's the first entry).
 *
 * Extracted from the composable so the grouping rule is unit-testable
 * without standing up Compose.
 */
fun computeShowHeader(entries: List<ActivityEntry>): List<Boolean> =
    entries.mapIndexed { index, entry ->
        index == 0 || entries[index - 1].group != entry.group
    }

/**
 * The vault's activity log: a flat list of entries the caller has already
 * grouped by day, rendered with a header the first time each group appears.
 *
 * Takes its data as a parameter rather than reading a repository directly -
 * there is no audit-log endpoint/repository method yet, so wiring this to a
 * real source is out of scope for this slice.
 */
@Composable
fun ActivityScreen(entries: List<ActivityEntry>, modifier: Modifier = Modifier) {
    val showHeader = computeShowHeader(entries)

    LazyColumn(modifier = modifier.fillMaxWidth().background(Seal.Ground).testTag(TAG_ACTIVITY_SCREEN)) {
        items(entries.size) { index ->
            val entry = entries[index]
            Column {
                if (showHeader[index]) {
                    Text(
                        text = entry.group,
                        color = Seal.Tertiary,
                        fontFamily = FontFamily.Monospace,
                        fontSize = Seal.CaptionSize,
                        modifier = Modifier.padding(top = 16.dp, bottom = 8.dp, start = Seal.Gutter),
                    )
                }
                ActivityRow(entry)
            }
        }
    }
}

@Composable
private fun ActivityRow(entry: ActivityEntry, modifier: Modifier = Modifier) {
    val rowModifier = modifier
        .fillMaxWidth()
        .background(Seal.CardBg, RoundedCornerShape(SealRadius.Card))
        .let {
            if (entry.anomaly) {
                it.border(2.dp, Seal.Open, RoundedCornerShape(SealRadius.Card))
            } else {
                it
            }
        }
        .padding(vertical = 10.dp, horizontal = Seal.Gutter)

    Row(rowModifier, verticalAlignment = Alignment.CenterVertically) {
        SealChip(id = entry.title + entry.time, state = SealState.Sealed, size = 24.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = entry.title,
                color = Seal.Ink,
                fontSize = Seal.BodySize,
            )
            Text(
                text = entry.verb,
                color = Seal.InkDim,
                fontFamily = FontFamily.Monospace,
                fontSize = Seal.CaptionSize,
            )
        }
        if (entry.anomaly) {
            TriangleAlertGlyph()
            Spacer(Modifier.width(8.dp))
        }
        Text(
            text = entry.time,
            color = Seal.Tertiary,
            fontFamily = FontFamily.Monospace,
            fontSize = Seal.CaptionSize,
        )
    }
}

/**
 * A minimal drawn triangle-alert glyph. No icon library is present in this
 * module's dependencies (checked feature-vault/build.gradle.kts), so this is
 * drawn with [Canvas] rather than pulling one in for a single glyph.
 */
@Composable
private fun TriangleAlertGlyph(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(14.dp)) {
        val path = Path().apply {
            moveTo(size.width / 2f, 0f)
            lineTo(size.width, size.height)
            lineTo(0f, size.height)
            close()
        }
        drawPath(path, color = Seal.Open)
    }
}

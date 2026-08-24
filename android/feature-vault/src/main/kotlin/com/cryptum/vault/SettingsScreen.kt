package com.cryptum.vault

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.cryptum.lock.Seal
import com.cryptum.lock.SealRadius

/**
 * Read-only settings display: auto-reseal window, clipboard-clear timeout,
 * and the current appearance label, plus a delete-account entry point.
 *
 * Deliberately read-only for this slice - there is no settings-persistence
 * layer yet, so wiring toggles/editing here would be a fake control with
 * nothing behind it. That's a further slice if wanted.
 */
const val TAG_DELETE_ACCOUNT_ROW = "delete-account-row"
const val TAG_VIEW_ACTIVITY_ROW = "view-activity-row"

@Composable
fun SettingsScreen(
    themeLabel: String,
    onViewActivity: () -> Unit,
    onDeleteAccount: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth().background(Seal.Ground).padding(Seal.Gutter)) {
        SettingsRow(label = "Auto-reseal window", value = "${Seal.AutoResealSeconds}s")
        SettingsRow(label = "Clipboard clear", value = "${Seal.ClipboardClearSeconds}s")
        SettingsRow(label = "Appearance", value = themeLabel)
        NavigationRow(label = "View activity", tag = TAG_VIEW_ACTIVITY_ROW, onClick = onViewActivity)
        DeleteAccountRow(onClick = onDeleteAccount)
    }
}

@Composable
private fun NavigationRow(label: String, tag: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .testTag(tag)
            .background(Seal.CardBg, RoundedCornerShape(SealRadius.Card))
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp, horizontal = Seal.Gutter),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, color = Seal.Ink, fontSize = Seal.BodySize)
        ChevronGlyph(color = Seal.InkDim)
    }
}

@Composable
private fun SettingsRow(label: String, value: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(Seal.CardBg, RoundedCornerShape(SealRadius.Card))
            .padding(vertical = 14.dp, horizontal = Seal.Gutter),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, color = Seal.Ink, fontSize = Seal.BodySize)
        Text(text = value, color = Seal.InkDim, fontSize = Seal.BodySize)
    }
}

@Composable
private fun DeleteAccountRow(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .testTag(TAG_DELETE_ACCOUNT_ROW)
            .background(Seal.CardBg, RoundedCornerShape(SealRadius.Card))
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp, horizontal = Seal.Gutter),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = "Delete account", color = Seal.Open, fontSize = Seal.BodySize)
        ChevronGlyph(color = Seal.Open)
    }
}

/**
 * A minimal drawn chevron glyph. No icon library is present in this module's
 * dependencies, so this is drawn with [Canvas] rather than pulling one in for
 * a single glyph (matching the pattern used for the anomaly triangle in
 * ActivityScreen.kt).
 */
@Composable
private fun ChevronGlyph(color: androidx.compose.ui.graphics.Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(14.dp)) {
        val path = Path().apply {
            moveTo(0f, 0f)
            lineTo(size.width, size.height / 2f)
            lineTo(0f, size.height)
        }
        drawPath(path, color = color, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3f))
    }
}

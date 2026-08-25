package com.cryptum.vault

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cryptum.lock.Seal
import com.cryptum.lock.SealRadius
import kotlinx.coroutines.launch
import java.util.UUID

const val TAG_NEW_FILE = "new-file"
const val TAG_FILES_SELECT_TOGGLE = "files-select-toggle"
const val TAG_FILES_DELETE_SELECTED = "files-delete-selected"
const val TAG_FILE_SHEET_PHOTO = "file-sheet-photo"
const val TAG_FILE_SHEET_DOCUMENT = "file-sheet-document"
const val TAG_FILE_SHEET_OTHER = "file-sheet-other"
const val TAG_FILE_SHEET_CANCEL = "file-sheet-cancel"

/**
 * The Files tab: a sealed-file list alongside the Vault, backed by
 * [FileRepository]. Uploads run through the SAF pickers so the plaintext
 * bytes never leave the device except as ciphertext to the blob SAS
 * [FileRepository.upload] requests.
 *
 * Holding a row downloads and decrypts it — the same hold-to-reveal gesture
 * as a Secret ([HoldToOpen], from `VaultScreen.kt`) — but there is no
 * external viewer wired up yet (that needs a `FileProvider`, a separate
 * manifest-level change out of this slice's scope); a successful hold just
 * proves the round trip by flipping the row to "opened".
 *
 * "Delete selected" calls [FileRepository.delete] for each selected row,
 * then reloads the list from the server.
 */
@Composable
internal fun FilesScreen(repository: FileRepository, modifier: Modifier = Modifier) {
    var files by remember { mutableStateOf(emptyList<FileSummary>()) }
    var selecting by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(setOf<UUID>()) }
    var opened by remember { mutableStateOf(setOf<UUID>()) }
    var showAddSheet by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    suspend fun refresh() {
        files = repository.list()
    }

    LaunchedEffect(Unit) { refresh() }

    fun toggleSelect(id: UUID) {
        selected = if (id in selected) selected - id else selected + id
    }

    fun uploadPicked(uri: android.net.Uri?, fallbackTitle: String) {
        if (uri == null) return
        showAddSheet = false
        scope.launch {
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@launch
            val title = queryDisplayName(context, uri) ?: fallbackTitle
            repository.upload(title, bytes)
            refresh()
        }
    }

    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) {
        uploadPicked(it, "Photo")
    }
    val pickDocument = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
        uploadPicked(it, "Document")
    }
    val pickOther = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
        uploadPicked(it, "File")
    }

    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxWidth().padding(horizontal = Seal.Gutter)) {
            Spacer(Modifier.height(56.dp))

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("F I L E S", color = Seal.InkDim, fontSize = 13.sp, letterSpacing = 0.32.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (selecting) "Done" else "Select",
                        color = Seal.InkDim,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        modifier = Modifier
                            .testTag(TAG_FILES_SELECT_TOGGLE)
                            .clickable {
                                selecting = !selecting
                                if (!selecting) selected = emptySet()
                            },
                    )
                    Box(Modifier.testTag(TAG_NEW_FILE).clickable { showAddSheet = true }) {
                        PlusGlyphFiles(Seal.Open)
                    }
                }
            }

            Spacer(Modifier.height(10.dp))
            Text(
                "${files.size} files",
                color = Seal.InkDim,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
            )

            Spacer(Modifier.height(14.dp))

            LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.weight(1f)) {
                items(files, key = { it.id }) { file ->
                    Row(
                        Modifier.fillMaxWidth().height(64.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (selecting) {
                            val checked = file.id in selected
                            Box(
                                Modifier
                                    .size(22.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(if (checked) Seal.Open else Color.Transparent)
                                    .testTag("files-row-select-${file.id}")
                                    .clickable { toggleSelect(file.id) },
                                contentAlignment = Alignment.Center,
                            ) {
                                if (checked) CheckGlyph(Seal.Ground)
                            }
                            Spacer(Modifier.size(10.dp))
                        }
                        HoldToOpen(
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                            onActivated = {
                                scope.launch {
                                    repository.download(file.id)
                                    opened = opened + file.id
                                }
                            },
                        ) { _ ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(SealRadius.Card))
                                    .background(Seal.CardBg)
                                    .padding(horizontal = 18.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                FileRowGlyph(Seal.InkDim)
                                Spacer(Modifier.size(14.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(file.title, color = Seal.Ink, fontSize = 16.sp)
                                    Text(
                                        "Hold to open",
                                        color = Seal.InkDim,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 12.sp,
                                    )
                                }
                                Text(
                                    if (file.id in opened) "OPENED" else "SEALED",
                                    color = Seal.Tertiary,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 10.sp,
                                )
                            }
                        }
                    }
                }
            }

            if (selecting && selected.isNotEmpty()) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .clip(RoundedCornerShape(SealRadius.Button))
                        .background(Seal.Open)
                        .testTag(TAG_FILES_DELETE_SELECTED)
                        .clickable {
                            val toDelete = selected
                            selecting = false
                            selected = emptySet()
                            scope.launch {
                                toDelete.forEach { repository.delete(it) }
                                refresh()
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text("Delete ${selected.size} selected", color = Seal.Ground, fontSize = 14.sp)
                }
                Spacer(Modifier.height(16.dp))
            } else {
                Spacer(Modifier.height(12.dp))
            }
        }

        if (showAddSheet) {
            AddFileSheet(
                onCancel = { showAddSheet = false },
                onPickPhoto = { pickPhoto.launch("image/*") },
                onPickDocument = { pickDocument.launch(arrayOf("application/pdf")) },
                onPickOther = { pickOther.launch(arrayOf("*/*")) },
            )
        }
    }
}

private fun queryDisplayName(context: android.content.Context, uri: android.net.Uri): String? {
    val projection = arrayOf(android.provider.OpenableColumns.DISPLAY_NAME)
    return context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
        val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
        if (nameIndex >= 0 && cursor.moveToFirst()) cursor.getString(nameIndex) else null
    }
}

@Composable
private fun AddFileSheet(
    onCancel: () -> Unit,
    onPickPhoto: () -> Unit,
    onPickDocument: () -> Unit,
    onPickOther: () -> Unit,
) {
    Box(Modifier.fillMaxWidth().pointerInput(Unit) { detectTapGestures(onTap = { onCancel() }) }) {
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                .background(Seal.CardBg)
                .padding(horizontal = Seal.Gutter, vertical = 20.dp)
                .pointerInput(Unit) { detectTapGestures(onTap = {}) },
        ) {
            Text("Add a file", color = Seal.Ink, fontSize = 15.sp, modifier = Modifier.padding(bottom = 14.dp))
            SheetRow("Photo", TAG_FILE_SHEET_PHOTO, onPickPhoto)
            SheetRow("Document", TAG_FILE_SHEET_DOCUMENT, onPickDocument)
            SheetRow("Other file", TAG_FILE_SHEET_OTHER, onPickOther, last = true)
            Text(
                "Cancel",
                color = Seal.InkDim,
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                modifier = Modifier
                    .testTag(TAG_FILE_SHEET_CANCEL)
                    .padding(top = 12.dp)
                    .clickable(onClick = onCancel),
            )
        }
    }
}

@Composable
private fun SheetRow(label: String, tag: String, onClick: () -> Unit, last: Boolean = false) {
    Column(Modifier.fillMaxWidth().testTag(tag).clickable(onClick = onClick)) {
        Text(label, color = Seal.Ink, fontSize = 15.sp, modifier = Modifier.padding(vertical = 14.dp))
        if (!last) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(Seal.Divider))
        }
    }
}

@Composable
private fun FileRowGlyph(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(16.dp)) {
        val dogEar = size.width * 0.4f
        val path = Path().apply {
            moveTo(0f, 0f)
            lineTo(dogEar, 0f)
            lineTo(size.width, size.height * 0.3f)
            lineTo(size.width, size.height)
            lineTo(0f, size.height)
            close()
        }
        drawPath(path, color = color, style = Stroke(width = 1.2.dp.toPx()))
    }
}

@Composable
private fun PlusGlyphFiles(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(18.dp)) {
        val strokeWidth = 1.8.dp.toPx()
        drawLine(color, androidx.compose.ui.geometry.Offset(size.width / 2f, 0f), androidx.compose.ui.geometry.Offset(size.width / 2f, size.height), strokeWidth = strokeWidth)
        drawLine(color, androidx.compose.ui.geometry.Offset(0f, size.height / 2f), androidx.compose.ui.geometry.Offset(size.width, size.height / 2f), strokeWidth = strokeWidth)
    }
}

@Composable
private fun CheckGlyph(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(12.dp)) {
        val path = Path().apply {
            moveTo(0f, size.height * 0.55f)
            lineTo(size.width * 0.4f, size.height)
            lineTo(size.width, size.height * 0.1f)
        }
        drawPath(path, color = color, style = Stroke(width = 2.dp.toPx()))
    }
}

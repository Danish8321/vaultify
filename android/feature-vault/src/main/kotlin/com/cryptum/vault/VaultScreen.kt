package com.cryptum.vault

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cryptum.lock.Seal
import com.cryptum.lock.SealChip
import com.cryptum.lock.SealRadius
import com.cryptum.lock.SealState
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

const val TAG_NEW_SECRET = "new-secret"
const val TAG_FIELD_TITLE = "field-title"
const val TAG_FIELD_USERNAME = "field-username"
const val TAG_FIELD_PASSWORD = "field-password"
const val TAG_FIELD_URL = "field-url"
const val TAG_FIELD_NOTES = "field-notes"
const val TAG_SAVE = "save"
const val TAG_REVEAL = "reveal"
const val TAG_RESEAL_NOW = "reseal-now"
const val TAG_EDIT = "edit"
const val TAG_BACK = "back"
const val TAG_SETTINGS = "settings"
const val TAG_TAB_VAULT = "tab-vault"
const val TAG_TAB_FILES = "tab-files"
const val TAG_TAB_ACTIVITY = "tab-activity"
const val TAG_TAB_SETTINGS = "tab-settings"

/** Which of the four persistent tabs is showing. Independent of [Screen] —
 * a tab stays selected underneath a pushed Detail/Compose/Delete screen, the
 * same way the prototype's bottom bar never disappears behind those. */
private enum class Tab { Vault, Files, Activity, Settings }

/** The one thing pushed over the tab content at a time. Replaces two
 * nullable/boolean flags so the seal/open transition below has a single,
 * stable key to animate on. */
private sealed interface Screen {
    data object Tabs : Screen
    data object Compose : Screen
    data class Open(val id: UUID, val title: String, val payload: SecretPayload) : Screen
    /** Editing an existing Secret. Saving calls `VaultRepository.update()`. */
    data class Edit(val id: UUID, val title: String, val payload: SecretPayload) : Screen
    /** The read failed — no plaintext ever existed on this device. */
    data class Failed(val title: String) : Screen
    data object ConfirmDelete : Screen
    data object Shredding : Screen
}

/**
 * The Vault: a persistent Vault/Activity/Settings tab bar, with a list of
 * sealed things (one of which can be open at a time) pushed on top when the
 * user drills into an item, composes one, or deletes the account.
 *
 * State is held here rather than in a ViewModel because there is exactly one
 * decision to make — which Secret is open — and a ViewModel would add a
 * lifecycle that outlives the screen. In a vault, a component that survives the
 * screen is a component that can hold a plaintext past the point the user
 * thought they closed it.
 */
@Composable
fun VaultScreen(
    repository: VaultRepository,
    modifier: Modifier = Modifier,
    onAccountDeleted: () -> Unit = {},
) {
    var summaries by remember { mutableStateOf(emptyList<SecretSummary>()) }
    var tab by remember { mutableStateOf(Tab.Vault) }
    var screen by remember { mutableStateOf<Screen>(Screen.Tabs) }
    val scope = rememberCoroutineScope()

    suspend fun refresh() {
        summaries = repository.list()
    }

    LaunchedEffect(Unit) { refresh() }

    Box(modifier.fillMaxSize().background(Seal.Ground)) {
        // Sealing/opening is the only motion with real weight (design
        // language: 200-300ms, eased). Everything else in this screen is
        // instant.
        AnimatedContent(
            targetState = screen,
            transitionSpec = {
                fadeIn(tween(Seal.SealTransitionMillis)) togetherWith
                    fadeOut(tween(Seal.SealTransitionMillis))
            },
            label = "vault-seal-state",
        ) { state ->
            when (state) {
                is Screen.Compose -> ComposeSecret(
                    onCancel = { screen = Screen.Tabs },
                    onSave = { title, payload ->
                        scope.launch {
                            repository.create(title, payload)
                            screen = Screen.Tabs
                            tab = Tab.Vault
                            refresh()
                        }
                    },
                    onSaveEdit = { _, _ -> },
                )

                is Screen.Edit -> ComposeSecret(
                    editing = Triple(state.id, state.title, state.payload),
                    onCancel = { screen = Screen.Tabs },
                    onSave = { _, _ -> },
                    onSaveEdit = { id, payload ->
                        scope.launch {
                            screen = try {
                                repository.update(id, state.title, payload)
                                refresh()
                                Screen.Open(id, state.title, payload)
                            } catch (e: Exception) {
                                // Update failed server-side: show the last known-good
                                // (persisted) payload, not the unpersisted edit — a
                                // failed save must never look like a successful one.
                                Screen.Open(id, state.title, state.payload)
                            }
                        }
                    },
                )

                is Screen.Open -> ItemDetail(
                    title = state.title,
                    payload = state.payload,
                    onEdit = { screen = Screen.Edit(state.id, state.title, state.payload) },
                ) {
                    // Dropping the reference is the closing action. There is
                    // no "keep it around in case they come back" cache:
                    // coming back costs one unwrap, and holding a plaintext
                    // costs a heap dump.
                    screen = Screen.Tabs
                }

                is Screen.Failed -> FailedSecret(title = state.title, onClose = { screen = Screen.Tabs })

                Screen.Tabs -> TabScaffold(
                    tab = tab,
                    onTabSelected = { tab = it },
                ) {
                    when (tab) {
                        Tab.Vault -> SecretList(
                            summaries = summaries,
                            onNew = { screen = Screen.Compose },
                            onOpen = { summary ->
                                scope.launch {
                                    screen = try {
                                        Screen.Open(summary.id, summary.title, repository.read(summary.id))
                                    } catch (e: Exception) {
                                        Screen.Failed(summary.title)
                                    }
                                }
                            },
                        )

                        Tab.Files -> FilesScreen()

                        Tab.Activity -> ActivityScreen(entries = emptyList())

                        Tab.Settings -> SettingsScreen(
                            themeLabel = "System",
                            onViewActivity = { tab = Tab.Activity },
                            onDeleteAccount = { screen = Screen.ConfirmDelete },
                        )
                    }
                }

                Screen.ConfirmDelete -> DeleteAccountScreen(
                    onConfirmDelete = {
                        scope.launch {
                            // Fire the crypto-shred first: the transient
                            // Shredding animation is honest only if the key is
                            // already gone by the time it starts, not a promise
                            // shown before the call that could still fail. A
                            // failed call leaves the confirm screen up rather
                            // than animating a destruction that didn't happen.
                            try {
                                repository.delete()
                                screen = Screen.Shredding
                            } catch (e: Exception) {
                                // Stays on Screen.ConfirmDelete.
                            }
                        }
                    },
                    onCancel = { screen = Screen.Tabs },
                )

                Screen.Shredding -> ShreddingScreen(onFinished = onAccountDeleted)
            }
        }
    }
}

/**
 * The persistent chrome: tab content plus the bottom bar. Drawn rather than
 * `NavigationBar` — design-language.md forbids the stock component list, and
 * a hand-drawn pill-and-icon bar keeps the same "no generic Material chrome"
 * discipline the rest of the app follows.
 */
@Composable
private fun TabScaffold(
    tab: Tab,
    onTabSelected: (Tab) -> Unit,
    content: @Composable () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f)) { content() }
        BottomTabBar(tab, onTabSelected)
    }
}

@Composable
private fun BottomTabBar(tab: Tab, onTabSelected: (Tab) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(60.dp)
            .background(Seal.Ground),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TabItem("Vault", TAG_TAB_VAULT, tab == Tab.Vault, { onTabSelected(Tab.Vault) }) { color ->
            ArchiveGlyph(color)
        }
        TabItem("Files", TAG_TAB_FILES, tab == Tab.Files, { onTabSelected(Tab.Files) }) { color ->
            FileGlyph(color)
        }
        TabItem("Activity", TAG_TAB_ACTIVITY, tab == Tab.Activity, { onTabSelected(Tab.Activity) }) { color ->
            ClockGlyph(color)
        }
        // Tagged TAG_SETTINGS, not TAG_TAB_SETTINGS: existing tests reach
        // Settings via this tag, from before Settings had its own tab.
        TabItem("Settings", TAG_SETTINGS, tab == Tab.Settings, { onTabSelected(Tab.Settings) }) { color ->
            GearGlyph(color)
        }
    }
}

@Composable
private fun TabItem(
    label: String,
    tag: String,
    active: Boolean,
    onClick: () -> Unit,
    glyph: @Composable (Color) -> Unit,
) {
    val color = if (active) Seal.Open else Seal.InkDim
    Column(
        Modifier
            .testTag(tag)
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(width = 48.dp, height = 26.dp)
                .clip(RoundedCornerShape(13.dp))
                .background(if (active) Seal.Open.copy(alpha = 0.18f) else Color.Transparent),
            contentAlignment = Alignment.Center,
        ) {
            glyph(color)
        }
        Text(text = label, color = color, fontSize = 10.sp)
    }
}

/**
 * Wraps [content] with the press-and-hold gesture the design language
 * requires for every reveal: a tap is reversible by accident, a hold is
 * deliberate. [onActivated] fires once, after a sustained press of
 * [Seal.HoldToOpenMillis]; releasing early cancels with no penalty.
 */
@Composable
private fun HoldToOpen(
    modifier: Modifier = Modifier,
    onActivated: () -> Unit,
    content: @Composable (progress: Float) -> Unit,
) {
    var holding by remember { mutableStateOf(false) }
    var fired by remember { mutableStateOf(false) }

    val progress by animateFloatAsState(
        targetValue = if (holding) 1f else 0f,
        animationSpec = tween(durationMillis = if (holding) Seal.HoldToOpenMillis else 140),
        label = "hold-to-open",
    )

    LaunchedEffect(holding) {
        if (holding) {
            delay(Seal.HoldToOpenMillis.toLong())
            if (!fired) {
                fired = true
                onActivated()
            }
        } else {
            fired = false
        }
    }

    Box(
        modifier.pointerInput(Unit) {
            detectTapGestures(
                onPress = {
                    holding = true
                    tryAwaitRelease()
                    holding = false
                },
            )
        },
    ) {
        content(progress)
    }
}

@Composable
private fun SecretList(
    summaries: List<SecretSummary>,
    onNew: () -> Unit,
    onOpen: (SecretSummary) -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(horizontal = Seal.Gutter)) {
        Spacer(Modifier.height(56.dp))

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("V A U L T", color = Seal.InkDim, fontSize = 13.sp, letterSpacing = 0.32.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                SearchGlyph(Seal.InkDim)
                Box(Modifier.testTag(TAG_NEW_SECRET).clickable(onClick = onNew)) {
                    PlusGlyph(Seal.Open)
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        Text(
            "${summaries.size} items",
            color = Seal.InkDim,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
        )

        Spacer(Modifier.height(14.dp))

        // No chevrons, no cards-with-shadow, no dividers. Each row is a block
        // of the same sealed material as the lock screen, so "closed" is one
        // visual idea across the whole app rather than a different metaphor
        // per screen.
        LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.weight(1f)) {
            items(summaries, key = { it.id }) { summary ->
                HoldToOpen(
                    modifier = Modifier.fillMaxWidth().height(64.dp),
                    onActivated = { onOpen(summary) },
                ) { progress ->
                    Box {
                        Row(
                            Modifier
                                .fillMaxSize()
                                .clip(RoundedCornerShape(SealRadius.Card))
                                .background(Seal.CardBg)
                                .padding(horizontal = 18.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            SealChip(id = summary.id.toString(), state = SealState.Sealed, size = 16.dp)
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text(summary.title, color = Seal.Ink, fontSize = 16.sp)
                                if (summary.hint.isNotBlank()) {
                                    Text(
                                        summary.hint,
                                        color = Seal.InkDim,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 12.sp,
                                    )
                                }
                            }
                            Text("SEALED", color = Seal.Tertiary, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                        }
                        // The hold's only visible progress: the accent grows
                        // in from the leading edge, clipped to the row shape.
                        if (progress > 0f) {
                            Box(
                                Modifier
                                    .align(Alignment.CenterStart)
                                    .fillMaxHeight()
                                    .fillMaxWidth(progress)
                                    .clip(RoundedCornerShape(SealRadius.Card))
                                    .background(Seal.Open.copy(alpha = 0.12f)),
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun ComposeSecret(
    editing: Triple<UUID, String, SecretPayload>? = null,
    onCancel: () -> Unit,
    onSave: (String, SecretPayload) -> Unit,
    onSaveEdit: (UUID, SecretPayload) -> Unit,
) {
    var title by remember { mutableStateOf(editing?.second.orEmpty()) }
    var username by remember { mutableStateOf(editing?.third?.username.orEmpty()) }
    var password by remember { mutableStateOf(editing?.third?.password.orEmpty()) }
    var url by remember { mutableStateOf(editing?.third?.url.orEmpty()) }
    var notes by remember { mutableStateOf(editing?.third?.notes.orEmpty()) }

    Column(Modifier.fillMaxSize().padding(horizontal = Seal.Gutter)) {
        Spacer(Modifier.height(48.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Cancel",
                color = Seal.InkDim,
                fontSize = 14.sp,
                modifier = Modifier.testTag(TAG_BACK).clickable(onClick = onCancel),
            )
            Text(
                if (editing != null) "Edit secret" else "New secret",
                color = Seal.Ink,
                fontSize = 14.sp,
            )
            Spacer(Modifier.width(44.dp))
        }
        Spacer(Modifier.height(20.dp))

        if (editing != null) {
            Text(
                "This replaces the stored value. The old one isn't recoverable.",
                color = Seal.AccentDim,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                modifier = Modifier.padding(bottom = 16.dp),
            )
        }

        VaultField("title", title, TAG_FIELD_TITLE) { title = it }
        Text(
            "Titles aren't encrypted — they're how your vault gets listed. Keep them recognisable, not revealing.",
            color = Seal.Tertiary,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            modifier = Modifier.padding(bottom = 12.dp),
        )
        VaultField("username", username, TAG_FIELD_USERNAME) { username = it }
        VaultField("password", password, TAG_FIELD_PASSWORD, masked = true) { password = it }
        VaultField("url", url, TAG_FIELD_URL) { url = it }
        VaultField("notes", notes, TAG_FIELD_NOTES) { notes = it }

        Spacer(Modifier.height(24.dp))

        // Grain, not the accent: the accent is spent only on the open state
        // and destructive confirmation (design language), and saving a new
        // Secret is neither.
        Box(
            Modifier
                .fillMaxWidth()
                .height(56.dp)
                .clip(RoundedCornerShape(SealRadius.Button))
                .testTag(TAG_SAVE)
                .background(Seal.Grain)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = {
                            // Blank is not the same as absent. An empty box means the
                            // user left the field alone, so it does not go into the
                            // payload at all.
                            val payload = SecretPayload(
                                username = username.ifBlank { null },
                                password = password.ifBlank { null },
                                url = url.ifBlank { null },
                                notes = notes.ifBlank { null },
                            )
                            if (editing != null) {
                                onSaveEdit(editing.first, payload)
                            } else {
                                onSave(title, payload)
                            }
                        },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            Text(if (editing != null) "Overwrite" else "Save", color = Seal.Ink, fontFamily = FontFamily.Monospace, fontSize = 15.sp)
        }
    }
}

/**
 * The item detail screen: header (close/edit), an auto-reseal progress bar,
 * and each field either revealed (after the hold gesture) or shown as an
 * opaque cover bar — the same "closed is a mass, not a blur" idea as the
 * list's seal chip, just per-field. One [SealState] for the whole Secret, not
 * one per field: the prototype reveals username, password and url together,
 * on the same hold gesture, and design-language's "nothing auto-opens" rule
 * applies to the Secret, not to the password alone. Notes are the sole
 * exception — captions, not credentials.
 */
@Composable
private fun ItemDetail(
    title: String,
    payload: SecretPayload,
    onEdit: () -> Unit,
    onClose: () -> Unit,
) {
    var sealState by remember { mutableStateOf<SealState>(SealState.Sealed) }
    val countdown = remember { ResealCountdown() }
    var countdownProgress by remember { mutableStateOf(1f) }

    fun reseal() {
        sealState = SealState.Sealed
        countdown.reset()
        countdownProgress = 1f
    }

    // The auto-reseal clock: one second of app time per loop iteration, not
    // wall-clock time, so backgrounding the app doesn't burn the window while
    // the process is suspended.
    LaunchedEffect(sealState) {
        if (sealState == SealState.Open) {
            countdown.reset()
            countdownProgress = 1f
            while (!countdown.expired) {
                delay(1000)
                countdown.tick()
                countdownProgress = countdown.progress
            }
            reseal()
        }
    }

    val open = sealState == SealState.Open

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.testTag(TAG_BACK).clickable(onClick = onClose)) { ArrowLeftGlyph(Seal.Ink) }
            Box(Modifier.testTag(TAG_EDIT).clickable(onClick = onEdit)) { PencilGlyph(Seal.Ink) }
        }

        // Reuses the hold gesture's progress-bar visual language: a thin
        // accent line that drains as the auto-reseal window closes.
        Box(
            Modifier.fillMaxWidth().height(3.dp).padding(top = 14.dp).background(Seal.Divider),
        ) {
            if (open) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(countdownProgress).background(Seal.Open))
            }
        }

        Column(Modifier.weight(1f).padding(horizontal = Seal.Gutter).padding(top = 18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SealChip(id = title, state = sealState, size = 40.dp)
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(title, color = Seal.Ink, fontSize = 19.sp)
                    Text(
                        if (open) "OPEN 0:${countdown.secondsRemaining.toString().padStart(2, '0')}" else "SEALED",
                        color = if (open) Seal.Open else Seal.InkDim,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                    )
                }
            }

            Spacer(Modifier.height(28.dp))

            DetailField("username", payload.username, open)
            DetailField("password", payload.password, open)
            DetailField("url", payload.url, open)
            payload.notes?.let {
                Spacer(Modifier.height(4.dp))
                Text(it, color = Seal.InkDim, fontSize = 13.sp)
            }

            Spacer(Modifier.height(24.dp))
            Text(
                "This screen blocks screenshots and app-switcher previews.",
                color = Seal.Tertiary,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
            )
        }

        Column(Modifier.padding(horizontal = Seal.Gutter).padding(bottom = 24.dp, top = 8.dp)) {
            if (open) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .clip(RoundedCornerShape(SealRadius.Button))
                        .background(Color.Transparent)
                        .testTag(TAG_RESEAL_NOW)
                        .pointerInput(Unit) { detectTapGestures(onTap = { reseal() }) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text("Reseal now", color = Seal.Open, fontFamily = FontFamily.Monospace, fontSize = 14.sp)
                }
            } else {
                HoldToOpen(
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    onActivated = { sealState = SealState.Open },
                ) { progress ->
                    Box(
                        Modifier.fillMaxSize().clip(RoundedCornerShape(SealRadius.Button)).background(Seal.Mass),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (progress > 0f) {
                            Box(
                                Modifier
                                    .fillMaxHeight()
                                    .fillMaxWidth(progress)
                                    .align(Alignment.CenterStart)
                                    .background(Seal.Open),
                            )
                        }
                        Text(
                            "Hold to unwrap",
                            color = Seal.Ink,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 14.sp,
                            modifier = Modifier.testTag(TAG_REVEAL),
                        )
                    }
                }
                Text(
                    "Press and hold. Requires a round-trip — nothing is stored on this device.",
                    color = Seal.Tertiary,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

/** One field row: revealed text once [open], otherwise an opaque cover bar
 * standing in for the value's rough length — the per-field version of the
 * list's sealed mass. Fields the Secret doesn't carry (a nulled-out URL, say)
 * render nothing at all: an absent field and a blank one are different
 * things, per [SecretPayload]'s own contract, and the cover bar must not
 * imply a value that was never set. */
@Composable
private fun DetailField(label: String, value: String?, open: Boolean) {
    if (value == null) return
    Column(Modifier.padding(bottom = 22.dp)) {
        Text(label.uppercase(), color = Seal.InkDim, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
        Spacer(Modifier.height(6.dp))
        if (open) {
            Text(value, color = Seal.Ink, fontSize = 14.sp, fontFamily = FontFamily.Monospace)
        } else {
            Box(
                Modifier
                    .height(16.dp)
                    .width((value.length.coerceIn(6, 28) * 8).dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Seal.Mass),
            )
        }
    }
}

@Composable
private fun FailedSecret(title: String, onClose: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(horizontal = Seal.Gutter)) {
        Spacer(Modifier.height(56.dp))
        Box(Modifier.testTag(TAG_BACK).clickable(onClick = onClose)) {
            Text("← close", color = Seal.InkDim, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
        }
        Spacer(Modifier.height(12.dp))
        Text(title, color = Seal.Ink, fontSize = 20.sp)
        Spacer(Modifier.height(24.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            SealChip(id = title, state = SealState.Failed, size = 40.dp)
            Spacer(Modifier.width(12.dp))
            Text(
                "Can't reach the key service — try again in a moment.",
                color = Seal.InkDim,
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
            )
        }
    }
}

@Composable
private fun VaultField(
    label: String,
    value: String,
    tag: String,
    masked: Boolean = false,
    onChange: (String) -> Unit,
) {
    TextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label, color = Seal.InkDim, fontFamily = FontFamily.Monospace, fontSize = 12.sp) },
        singleLine = true,
        visualTransformation = if (masked) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        // Material's default text field shape is not part of this design
        // system, so it gets overridden alongside the colors, not just the
        // colors alone.
        shape = RoundedCornerShape(SealRadius.Button),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = Seal.CardBg,
            unfocusedContainerColor = Seal.CardBg,
            focusedTextColor = Seal.Ink,
            unfocusedTextColor = Seal.Ink,
            cursorColor = Seal.Open,
            focusedIndicatorColor = Seal.Open,
            unfocusedIndicatorColor = Color.Transparent,
        ),
        modifier = Modifier.fillMaxWidth().testTag(tag).padding(bottom = 8.dp),
    )
}

// ---- Hand-drawn glyphs -----------------------------------------------
// No icon library is present in this module's dependencies. Each glyph is a
// few Canvas primitives rather than a pulled-in icon set — consistent with
// SettingsScreen's ChevronGlyph and ActivityScreen's TriangleAlertGlyph.

@Composable
private fun ArchiveGlyph(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(19.dp)) {
        drawRoundRect(color, cornerRadius = CornerRadius(2.dp.toPx()), style = Stroke(width = 1.6.dp.toPx()))
        val lineY = size.height * 0.4f
        drawLine(color, androidx.compose.ui.geometry.Offset(0f, lineY), androidx.compose.ui.geometry.Offset(size.width, lineY), strokeWidth = 1.6.dp.toPx())
    }
}

@Composable
private fun FileGlyph(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(17.dp)) {
        val dogEar = size.width * 0.42f
        val path = Path().apply {
            moveTo(0f, 0f)
            lineTo(dogEar, 0f)
            lineTo(size.width, size.height * 0.28f)
            lineTo(size.width, size.height)
            lineTo(0f, size.height)
            close()
        }
        drawPath(path, color = color, style = Stroke(width = 1.5.dp.toPx()))
        val foldPath = Path().apply {
            moveTo(dogEar, 0f)
            lineTo(dogEar, size.height * 0.28f)
            lineTo(size.width, size.height * 0.28f)
        }
        drawPath(foldPath, color = color, style = Stroke(width = 1.5.dp.toPx()))
    }
}

@Composable
private fun ClockGlyph(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(19.dp)) {
        drawCircle(color, radius = size.minDimension / 2f - 1.dp.toPx(), style = Stroke(width = 1.6.dp.toPx()))
        val center = androidx.compose.ui.geometry.Offset(size.width / 2f, size.height / 2f)
        drawLine(color, center, androidx.compose.ui.geometry.Offset(center.x, center.y - size.height * 0.28f), strokeWidth = 1.6.dp.toPx())
        drawLine(color, center, androidx.compose.ui.geometry.Offset(center.x + size.width * 0.18f, center.y), strokeWidth = 1.6.dp.toPx())
    }
}

@Composable
private fun GearGlyph(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(19.dp)) {
        drawCircle(color, radius = size.minDimension / 2f - 4.dp.toPx(), style = Stroke(width = 1.6.dp.toPx()))
        drawCircle(color, radius = 2.dp.toPx())
    }
}

@Composable
private fun SearchGlyph(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(18.dp)) {
        val r = size.minDimension * 0.32f
        val center = androidx.compose.ui.geometry.Offset(size.width * 0.42f, size.height * 0.42f)
        drawCircle(color, radius = r, center = center, style = Stroke(width = 1.6.dp.toPx()))
        drawLine(
            color,
            androidx.compose.ui.geometry.Offset(center.x + r * 0.75f, center.y + r * 0.75f),
            androidx.compose.ui.geometry.Offset(size.width, size.height),
            strokeWidth = 1.6.dp.toPx(),
        )
    }
}

@Composable
private fun PlusGlyph(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(18.dp)) {
        drawLine(color, androidx.compose.ui.geometry.Offset(size.width / 2f, 0f), androidx.compose.ui.geometry.Offset(size.width / 2f, size.height), strokeWidth = 1.8.dp.toPx())
        drawLine(color, androidx.compose.ui.geometry.Offset(0f, size.height / 2f), androidx.compose.ui.geometry.Offset(size.width, size.height / 2f), strokeWidth = 1.8.dp.toPx())
    }
}

@Composable
private fun ArrowLeftGlyph(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(20.dp)) {
        val midY = size.height / 2f
        drawLine(color, androidx.compose.ui.geometry.Offset(size.width, midY), androidx.compose.ui.geometry.Offset(0f, midY), strokeWidth = 1.8.dp.toPx())
        val path = Path().apply {
            moveTo(size.width * 0.4f, midY - size.height * 0.28f)
            lineTo(0f, midY)
            lineTo(size.width * 0.4f, midY + size.height * 0.28f)
        }
        drawPath(path, color = color, style = Stroke(width = 1.8.dp.toPx()))
    }
}

@Composable
private fun PencilGlyph(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(18.dp)) {
        val path = Path().apply {
            moveTo(size.width * 0.15f, size.height * 0.85f)
            lineTo(size.width * 0.7f, size.height * 0.3f)
            lineTo(size.width * 0.9f, size.height * 0.5f)
            lineTo(size.width * 0.35f, size.height)
            close()
        }
        drawPath(path, color = color, style = Stroke(width = 1.6.dp.toPx()))
    }
}

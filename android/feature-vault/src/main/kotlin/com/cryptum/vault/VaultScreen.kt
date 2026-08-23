package com.cryptum.vault

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.graphics.Color
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

/** The one thing on screen at a time. Replaces two nullable/boolean flags so
 * the seal/open transition below has a single, stable key to animate on. */
private sealed interface Screen {
    data object List : Screen
    data object Compose : Screen
    data class Open(val id: UUID, val title: String, val payload: SecretPayload) : Screen
    /**
     * Editing an existing Secret. There is no `VaultRepository.update()` yet
     * (see .scratch/cryptum/issues/24-vault-repository-missing-update.md), so
     * [VaultScreen] wires this to a no-op save for now — the UI is real, the
     * persistence is not.
     */
    data class Edit(val id: UUID, val title: String, val payload: SecretPayload) : Screen
    /** The read failed — no plaintext ever existed on this device. */
    data class Failed(val title: String) : Screen
}

/**
 * The Vault: a list of sealed things, one of which can be open at a time.
 *
 * State is held here rather than in a ViewModel because there is exactly one
 * decision to make — which Secret is open — and a ViewModel would add a
 * lifecycle that outlives the screen. In a vault, a component that survives the
 * screen is a component that can hold a plaintext past the point the user
 * thought they closed it.
 */
@Composable
fun VaultScreen(repository: VaultRepository, modifier: Modifier = Modifier) {
    var summaries by remember { mutableStateOf(emptyList<SecretSummary>()) }
    var screen by remember { mutableStateOf<Screen>(Screen.List) }
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
                    onCancel = { screen = Screen.List },
                    onSave = { title, payload ->
                        scope.launch {
                            repository.create(title, payload)
                            screen = Screen.List
                            refresh()
                        }
                    },
                    onSaveEdit = { _, _ -> },
                )

                is Screen.Edit -> ComposeSecret(
                    editing = Triple(state.id, state.title, state.payload),
                    onCancel = { screen = Screen.List },
                    onSave = { _, _ -> },
                    onSaveEdit = { id, _ ->
                        // No VaultRepository.update() exists yet — see
                        // .scratch/cryptum/issues/24-vault-repository-missing-update.md.
                        // Returning to the *original* payload, not the locally-edited
                        // one: showing the edit as if it were saved would be a false
                        // "saved" state the next real read silently contradicts.
                        screen = Screen.Open(id, state.title, state.payload)
                    },
                )

                is Screen.Open -> OpenedSecret(
                    title = state.title,
                    payload = state.payload,
                    onEdit = { screen = Screen.Edit(state.id, state.title, state.payload) },
                ) {
                    // Dropping the reference is the closing action. There is
                    // no "keep it around in case they come back" cache:
                    // coming back costs one unwrap, and holding a plaintext
                    // costs a heap dump.
                    screen = Screen.List
                }

                is Screen.Failed -> FailedSecret(title = state.title, onClose = { screen = Screen.List })

                Screen.List -> SecretList(
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
            }
        }
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

        // The hold's only visible progress: the accent grows in from the
        // leading edge and reaches full width exactly as the hold fires.
        if (progress > 0f) {
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .fillMaxHeight()
                    .fillMaxWidth(progress)
                    .background(Seal.Open.copy(alpha = 0.12f)),
            )
            Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth(progress)
                    .height(2.dp)
                    .background(Seal.Open),
            )
        }
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

        Text("V A U L T", color = Seal.InkDim, fontSize = 13.sp, letterSpacing = 0.32.sp)

        Spacer(Modifier.height(24.dp))

        // No chevrons, no cards, no dividers. Each row is a block of the same
        // sealed material as the lock screen, so "closed" is one visual idea
        // across the whole app rather than a different metaphor per screen.
        LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.weight(1f)) {
            items(summaries, key = { it.id }) { summary ->
                HoldToOpen(
                    modifier = Modifier.fillMaxWidth().height(64.dp),
                    onActivated = { onOpen(summary) },
                ) {
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
                        Column {
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
                    }
                }
            }
        }

        Box(
            Modifier
                .fillMaxWidth()
                .height(56.dp)
                .testTag(TAG_NEW_SECRET)
                .background(Seal.Grain)
                .pointerInput(Unit) { detectTapGestures(onTap = { onNew() }) },
            contentAlignment = Alignment.Center,
        ) {
            Text("seal something new", color = Seal.Ink, fontFamily = FontFamily.Monospace, fontSize = 14.sp)
        }

        Spacer(Modifier.height(28.dp))
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
        Spacer(Modifier.height(56.dp))
        Header(if (editing != null) "E D I T" else "N E W", onBack = onCancel)
        Spacer(Modifier.height(16.dp))

        if (editing != null) {
            Text(
                "This replaces the stored value. The old one isn't recoverable.",
                color = Seal.InkDim,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                modifier = Modifier.padding(bottom = 16.dp),
            )
        }

        VaultField("title", title, TAG_FIELD_TITLE) { title = it }
        Text(
            "Titles aren't encrypted — they're how your vault gets listed. Keep them recognisable, not revealing.",
            color = Seal.InkDim,
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
            Text("seal", color = Seal.Ink, fontFamily = FontFamily.Monospace, fontSize = 15.sp)
        }
    }
}

/**
 * One [SealState] for the whole Secret, not one per field: the prototype
 * reveals username, password and url together, on the same hold gesture, and
 * design-language's "nothing auto-opens" rule applies to the Secret, not to
 * the password alone. Notes are the sole exception — captions, not credentials.
 */
@Composable
private fun OpenedSecret(
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

    Column(Modifier.fillMaxSize().padding(horizontal = Seal.Gutter)) {
        Spacer(Modifier.height(56.dp))
        Header(title, onBack = onClose)
        Spacer(Modifier.height(16.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            SealChip(id = title, state = sealState, size = 40.dp)
            Spacer(Modifier.width(12.dp))
            if (sealState == SealState.Open) {
                Text(
                    "reseals in ${countdown.secondsRemaining}s",
                    color = Seal.InkDim,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        if (sealState == SealState.Open) {
            // Reuses HoldToOpen's progress-bar visual language: a thin accent
            // line that drains as the window closes.
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(2.dp)
                    .background(Seal.Divider),
            ) {
                Box(
                    Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(countdownProgress)
                        .background(Seal.Open),
                )
            }
            Spacer(Modifier.height(16.dp))
        }

        if (sealState == SealState.Open) {
            payload.username?.let { OpenField("username", it) }
            payload.password?.let { OpenField("password", it) }
            payload.url?.let { OpenField("url", it) }
        } else {
            // Opening the envelope is one act, one hold gesture: someone
            // standing behind the user sees the Secret exists, not what it
            // is, and cannot trigger a reveal with a stray tap.
            HoldToOpen(
                modifier = Modifier.fillMaxWidth().height(56.dp),
                onActivated = { sealState = SealState.Open },
            ) {
                Box(
                    Modifier.fillMaxSize().background(Seal.Mass),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Text(
                        "hold to reveal",
                        color = Seal.InkDim,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(horizontal = 18.dp).testTag(TAG_REVEAL),
                    )
                }
            }
        }

        payload.notes?.let { OpenField("notes", it) }

        if (sealState == SealState.Open) {
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    Modifier
                        .weight(1f)
                        .height(44.dp)
                        .testTag(TAG_RESEAL_NOW)
                        .background(Seal.Grain)
                        .pointerInput(Unit) { detectTapGestures(onTap = { reseal() }) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text("reseal now", color = Seal.InkDim, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                }
                Box(
                    Modifier
                        .weight(1f)
                        .height(44.dp)
                        .testTag(TAG_EDIT)
                        .background(Seal.Grain)
                        .pointerInput(Unit) { detectTapGestures(onTap = { onEdit() }) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text("edit", color = Seal.InkDim, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                }
            }
        }
    }
}

@Composable
private fun FailedSecret(title: String, onClose: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(horizontal = Seal.Gutter)) {
        Spacer(Modifier.height(56.dp))
        Header(title, onBack = onClose)
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
private fun Header(text: String, onBack: () -> Unit) {
    Column {
        Box(
            Modifier
                .testTag(TAG_BACK)
                .pointerInput(Unit) { detectTapGestures(onTap = { onBack() }) },
        ) {
            Text("← close", color = Seal.InkDim, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
        }
        Spacer(Modifier.height(12.dp))
        Text(text, color = Seal.Ink, fontSize = 20.sp)
    }
}

@Composable
private fun OpenField(label: String, value: String) {
    Column(Modifier.padding(bottom = 18.dp)) {
        Text(label, color = Seal.InkDim, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
        Spacer(Modifier.height(4.dp))
        Text(value, color = Seal.Ink, fontSize = 16.sp)
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

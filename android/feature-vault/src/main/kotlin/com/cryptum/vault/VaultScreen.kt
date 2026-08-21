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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cryptum.lock.Seal
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
const val TAG_BACK = "back"

/** The one thing on screen at a time. Replaces two nullable/boolean flags so
 * the seal/open transition below has a single, stable key to animate on. */
private sealed interface Screen {
    data object List : Screen
    data object Compose : Screen
    data class Open(val title: String, val payload: SecretPayload) : Screen
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
                )

                is Screen.Open -> OpenedSecret(title = state.title, payload = state.payload) {
                    // Dropping the reference is the closing action. There is
                    // no "keep it around in case they come back" cache:
                    // coming back costs one unwrap, and holding a plaintext
                    // costs a heap dump.
                    screen = Screen.List
                }

                Screen.List -> SecretList(
                    summaries = summaries,
                    onNew = { screen = Screen.Compose },
                    onOpen = { summary ->
                        scope.launch { screen = Screen.Open(summary.title, repository.read(summary.id)) }
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
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(Seal.Mass)
                            .padding(horizontal = 18.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Text(summary.title, color = Seal.Ink, fontSize = 16.sp)
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
private fun ComposeSecret(onCancel: () -> Unit, onSave: (String, SecretPayload) -> Unit) {
    var title by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize().padding(horizontal = Seal.Gutter)) {
        Spacer(Modifier.height(56.dp))
        Header("N E W", onBack = onCancel)
        Spacer(Modifier.height(16.dp))

        VaultField("title", title, TAG_FIELD_TITLE) { title = it }
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
                .testTag(TAG_SAVE)
                .background(Seal.Grain)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = {
                            // Blank is not the same as absent. An empty box means the
                            // user left the field alone, so it does not go into the
                            // payload at all.
                            onSave(
                                title,
                                SecretPayload(
                                    username = username.ifBlank { null },
                                    password = password.ifBlank { null },
                                    url = url.ifBlank { null },
                                    notes = notes.ifBlank { null },
                                ),
                            )
                        },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            Text("seal", color = Seal.Ink, fontFamily = FontFamily.Monospace, fontSize = 15.sp)
        }
    }
}

@Composable
private fun OpenedSecret(title: String, payload: SecretPayload, onClose: () -> Unit) {
    var revealed by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().padding(horizontal = Seal.Gutter)) {
        Spacer(Modifier.height(56.dp))
        Header(title, onBack = onClose)
        Spacer(Modifier.height(24.dp))

        payload.username?.let { OpenField("username", it) }

        payload.password?.let { secret ->
            if (revealed) {
                OpenField("password", secret)
            } else {
                // Opening the envelope and revealing the password are two
                // separate acts, and both take the same hold gesture: someone
                // standing behind the user sees the Secret exists, not what
                // it is, and cannot trigger a reveal with a stray tap.
                HoldToOpen(
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    onActivated = { revealed = true },
                ) {
                    Box(
                        Modifier.fillMaxSize().background(Seal.Mass),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Text(
                            "password  ·  hold to reveal",
                            color = Seal.InkDim,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 14.sp,
                            modifier = Modifier.padding(horizontal = 18.dp).testTag(TAG_REVEAL),
                        )
                    }
                }
            }
        }

        payload.url?.let { OpenField("url", it) }
        payload.notes?.let { OpenField("notes", it) }
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
        colors = TextFieldDefaults.colors(
            focusedContainerColor = Seal.Mass,
            unfocusedContainerColor = Seal.Mass,
            focusedTextColor = Seal.Ink,
            unfocusedTextColor = Seal.Ink,
            cursorColor = Seal.Open,
            focusedIndicatorColor = Seal.Open,
            unfocusedIndicatorColor = Color.Transparent,
        ),
        modifier = Modifier.fillMaxWidth().testTag(tag).padding(bottom = 8.dp),
    )
}

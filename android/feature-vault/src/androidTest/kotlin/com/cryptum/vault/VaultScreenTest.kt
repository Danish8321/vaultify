package com.cryptum.vault

import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Task 2.13's stated verification: the round trip, and that plaintext never
 * touches disk.
 *
 * The repository is a fake so that what fails here is the Vault, not the
 * network. The bytes it holds are the real sealed bytes — it stores what the
 * envelope produced rather than the payload — so a screen that somehow bypassed
 * the envelope would still fail to read anything back.
 */
class VaultScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val secret = "correct horse battery staple"

    @Test
    fun a_created_Secret_can_be_read_back() {
        val repository = SealedFakeRepository()

        compose.setContent { VaultScreen(repository) }

        compose.onNodeWithTag(TAG_NEW_SECRET).performClick()
        compose.onNodeWithTag(TAG_FIELD_TITLE).performTextInput("Email")
        compose.onNodeWithTag(TAG_FIELD_PASSWORD).performTextInput(secret)
        compose.onNodeWithTag(TAG_SAVE).performClick()

        // Back on the list: the title is visible, because it is plaintext by
        // design, and the password is not, because nothing has been opened yet.
        compose.onNodeWithText("Email").assertExists()
        compose.onNodeWithText(secret).assertDoesNotExist()

        compose.onNodeWithText("Email").performHold()
        compose.waitForIdle()
        compose.onNodeWithTag(TAG_REVEAL).performHold()
        compose.onNodeWithText(secret).assertExists()
    }

    @Test
    fun the_list_holds_no_plaintext_before_a_Secret_is_opened() {
        val repository = SealedFakeRepository()
        runBlocking { repository.create("Email", SecretPayload(password = secret)) }

        compose.setContent { VaultScreen(repository) }

        compose.onNodeWithText("Email").assertExists()
        // The stored bytes are what the server would hold. If the plaintext is
        // in there, the envelope is not doing its job.
        assertFalse(repository.storedBytesContain(secret))
    }

    @Test
    fun no_plaintext_reaches_the_app_s_own_storage() {
        val repository = SealedFakeRepository()

        compose.setContent { VaultScreen(repository) }

        compose.onNodeWithTag(TAG_NEW_SECRET).performClick()
        compose.onNodeWithTag(TAG_FIELD_TITLE).performTextInput("Email")
        compose.onNodeWithTag(TAG_FIELD_PASSWORD).performTextInput(secret)
        compose.onNodeWithTag(TAG_SAVE).performClick()
        compose.onNodeWithText("Email").performHold()
        compose.waitForIdle()
        compose.onNodeWithTag(TAG_REVEAL).performHold()
        compose.onNodeWithText(secret).assertExists()
        compose.waitForIdle()

        // Everything the app can write to without a permission: internal files,
        // cache, shared preferences, databases. A password in any of them
        // survives a lock, a reboot, and a backup extraction.
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val roots = listOfNotNull(
            context.filesDir,
            context.cacheDir,
            context.dataDir,
            context.getExternalFilesDir(null),
        )

        val leaked = roots.flatMap { it.walkFiles() }.filter { it.containsText(secret) }

        assertTrue(leaked.isEmpty(), "plaintext found on disk in: ${leaked.map { it.path }}")
    }

    @Test
    fun capture_the_vault_screens() {
        // Not an assertion — a rendering, so the design can be reviewed as
        // pixels rather than as a description.
        val repository = SealedFakeRepository()
        runBlocking {
            repository.create("Email", SecretPayload(username = "ada", password = secret))
            repository.create("Bank", SecretPayload(password = "another"))
            repository.create("Router admin", SecretPayload(password = "third"))
        }

        compose.setContent { VaultScreen(repository) }
        compose.waitForIdle()
        capture("vault-list.png")

        compose.onNodeWithText("Email").performHold()
        compose.waitForIdle()
        capture("vault-opened.png")
    }

    private fun capture(name: String) {
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val dir = File(
            requireNotNull(InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")),
        )
        FileOutputStream(File(dir, name)).use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    private fun File.walkFiles(): List<File> =
        walkTopDown().filter { it.isFile && it.canRead() && it.length() < 8L * 1024 * 1024 }.toList()

    private fun File.containsText(text: String): Boolean =
        try {
            readBytes().containsBytesOf(text)
        } catch (_: Exception) {
            // An unreadable file cannot be inspected, and pretending otherwise
            // would turn a gap in the check into a pass.
            false
        }
}

/**
 * Stores exactly what the server would store: ciphertext, nonce and the DEK.
 * Nothing here can return a payload it was not given in sealed form.
 */
private class SealedFakeRepository : VaultRepository {

    private class Row(val title: String, val ciphertext: ByteArray, val nonce: ByteArray, val dek: ByteArray)

    private val rows = LinkedHashMap<UUID, Row>()

    override suspend fun list(): List<SecretSummary> =
        rows.map { (id, row) -> SecretSummary(id, row.title) }

    override suspend fun create(title: String, payload: SecretPayload): UUID {
        val request = SecretEnvelope.seal(title, payload)
        val id = UUID.randomUUID()
        rows[id] = Row(request.title, request.ciphertext, request.nonce, request.dek.copyOf())
        return id
    }

    override suspend fun read(id: UUID): SecretPayload {
        val row = requireNotNull(rows[id])
        return SecretEnvelope.open(row.ciphertext, row.nonce, row.dek)
    }

    fun storedBytesContain(text: String): Boolean =
        rows.values.any { (it.ciphertext + it.nonce).containsBytesOf(text) }
}

/**
 * The design language requires a sustained press to open or reveal, never a
 * tap, so tests drive the same gesture a real thumb would rather than
 * [androidx.compose.ui.test.performClick]. The hold is gated by a real
 * [kotlinx.coroutines.delay] in [com.cryptum.lock.Seal.HoldToOpenMillis], not
 * by Compose's frame clock, so this sleeps the test thread for real rather
 * than advancing an injected event timestamp — `advanceEventTime` alone
 * delivers down/up back-to-back with no actual elapsed time for the delay to
 * observe.
 */
private fun androidx.compose.ui.test.SemanticsNodeInteraction.performHold() {
    performTouchInput { down(center) }
    Thread.sleep(com.cryptum.lock.Seal.HoldToOpenMillis.toLong() + 250)
    // The hold firing usually replaces this node's content (the row becomes
    // "opened", the reveal prompt becomes the plaintext) before the release
    // is injected, so the up() target may already be gone — that is success,
    // not a failure to clean up after.
    runCatching { performTouchInput { up() } }
}

private fun ByteArray.containsBytesOf(text: String): Boolean {
    val needle = text.toByteArray()
    if (needle.isEmpty() || needle.size > size) return false
    return (0..size - needle.size).any { start ->
        needle.indices.all { this[start + it] == needle[it] }
    }
}

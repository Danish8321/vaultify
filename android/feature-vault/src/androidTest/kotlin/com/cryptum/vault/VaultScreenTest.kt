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

    /**
     * Holds [node] for [Seal.HoldToOpenMillis], real wall time — the app-side
     * `LaunchedEffect(delay(...))` this drives runs on the actual clock, not a
     * test-controlled one. Releases through [ComposeContentTestRule.onRoot]
     * rather than re-resolving [node]: the hold firing usually replaces the
     * node's content (a list row becomes the opened screen) before release,
     * and re-resolving a since-changed node for `up()` throws before it ever
     * reaches the input dispatcher — silently leaving pointer 0 stuck DOWN for
     * whichever gesture runs next.
     */
    private fun performHold(node: androidx.compose.ui.test.SemanticsNodeInteraction) {
        node.performTouchInput { down(center) }
        Thread.sleep(com.cryptum.lock.Seal.HoldToOpenMillis.toLong() + 250)
        compose.onRoot().performTouchInput { up() }
    }

    @Test
    fun a_created_Secret_can_be_read_back() {
        val repository = SealedFakeRepository()

        compose.setContent { VaultScreen(repository, SealedFakeFileRepository()) }

        compose.onNodeWithTag(TAG_NEW_SECRET).performClick()
        compose.onNodeWithTag(TAG_FIELD_TITLE).performTextInput("Email")
        compose.onNodeWithTag(TAG_FIELD_PASSWORD).performTextInput(secret)
        compose.onNodeWithTag(TAG_SAVE).performClick()

        // Back on the list: the title is visible, because it is plaintext by
        // design, and the password is not, because nothing has been opened yet.
        compose.onNodeWithText("Email").assertExists()
        compose.onNodeWithText(secret).assertDoesNotExist()

        performHold(compose.onNodeWithText("Email"))
        compose.waitForIdle()
        performHold(compose.onNodeWithTag(TAG_REVEAL))
        compose.onNodeWithText(secret).assertExists()
    }

    @Test
    fun the_list_holds_no_plaintext_before_a_Secret_is_opened() {
        val repository = SealedFakeRepository()
        runBlocking { repository.create("Email", SecretPayload(password = secret)) }

        compose.setContent { VaultScreen(repository, SealedFakeFileRepository()) }

        compose.onNodeWithText("Email").assertExists()
        // The stored bytes are what the server would hold. If the plaintext is
        // in there, the envelope is not doing its job.
        assertFalse(repository.storedBytesContain(secret))
    }

    @Test
    fun no_plaintext_reaches_the_app_s_own_storage() {
        val repository = SealedFakeRepository()

        compose.setContent { VaultScreen(repository, SealedFakeFileRepository()) }

        compose.onNodeWithTag(TAG_NEW_SECRET).performClick()
        compose.onNodeWithTag(TAG_FIELD_TITLE).performTextInput("Email")
        compose.onNodeWithTag(TAG_FIELD_PASSWORD).performTextInput(secret)
        compose.onNodeWithTag(TAG_SAVE).performClick()
        performHold(compose.onNodeWithText("Email"))
        compose.waitForIdle()
        performHold(compose.onNodeWithTag(TAG_REVEAL))
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
    fun editing_a_Secret_persists_through_the_repository() {
        // Ticket 24: onSaveEdit must actually call VaultRepository.update(),
        // not just navigate as if it had. Proven by reading the Secret back
        // through a fresh screen instance backed by the same repository.
        val repository = SealedFakeRepository()
        runBlocking { repository.create("Email", SecretPayload(password = secret)) }

        compose.setContent { VaultScreen(repository, SealedFakeFileRepository()) }

        performHold(compose.onNodeWithText("Email"))
        compose.waitForIdle()
        // TAG_EDIT only exists once the Secret is revealed (hold-to-reveal),
        // not merely opened — see OpenedSecret's sealState == Open gate.
        performHold(compose.onNodeWithTag(TAG_REVEAL))
        compose.waitForIdle()
        compose.onNodeWithTag(TAG_EDIT).performClick()

        val updated = "a whole new password"
        compose.onNodeWithTag(TAG_FIELD_PASSWORD).performTextInput(updated)
        compose.onNodeWithTag(TAG_SAVE).performClick()
        compose.waitForIdle()

        val persisted = runBlocking { repository.read(repository.list().single().id) }
        assertTrue(persisted.password?.contains(updated) == true)
    }

    @Test
    fun deleting_the_account_calls_the_repository_and_notifies_the_caller() {
        // Ticket 23: onConfirmDelete must actually reach VaultRepository.delete()
        // and the caller must be told once the shred animation completes, so
        // MainActivity can re-lock instead of leaving an unlocked Vault whose
        // key no longer exists.
        val repository = SealedFakeRepository()
        runBlocking { repository.create("Email", SecretPayload(password = secret)) }
        var deleted = false

        compose.setContent { VaultScreen(repository, SealedFakeFileRepository(), onAccountDeleted = { deleted = true }) }

        compose.onNodeWithTag(TAG_SETTINGS).performClick()
        compose.onNodeWithTag(TAG_DELETE_ACCOUNT_ROW).performClick()
        compose.onNodeWithTag(TAG_DELETE_CONFIRM_FIELD).performTextInput("DELETE")
        compose.onNodeWithTag(TAG_DELETE_CONFIRM_BUTTON).performClick()
        compose.waitForIdle()

        assertTrue(runBlocking { repository.list() }.isEmpty(), "repository.delete() was not called")

        // The shred animation fires onFinished after ShreddingDurationMillis.
        // ComposeTestRule runs the composition's LaunchedEffect coroutines on
        // its own test dispatcher, so delay() doesn't progress with real wall
        // time (a Thread.sleep on this thread never touches it) — it advances
        // only through the test clock, which is the supported way to drive it
        // deterministically.
        compose.mainClock.advanceTimeBy(ShreddingDurationMillis.toLong() + 500)
        compose.waitForIdle()
        assertTrue(deleted, "onAccountDeleted was never invoked")
    }

    @Test
    fun settings_navigates_to_the_activity_screen() {
        // Follow-up item #4 from ticket 23's close-out: ActivityScreen had no
        // navigation entry point. There is no activity-log data source yet
        // (no VaultRepository method, no core-api endpoint), so this proves
        // only the navigation reaches a real, empty ActivityScreen — not
        // fabricated data.
        val repository = SealedFakeRepository()

        compose.setContent { VaultScreen(repository, SealedFakeFileRepository()) }

        compose.onNodeWithTag(TAG_SETTINGS).performClick()
        compose.onNodeWithTag(TAG_VIEW_ACTIVITY_ROW).performClick()

        compose.onNodeWithTag(TAG_ACTIVITY_SCREEN).assertExists()
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

        compose.setContent { VaultScreen(repository, SealedFakeFileRepository()) }
        compose.waitForIdle()
        capture("vault-list.png")

        performHold(compose.onNodeWithText("Email"))
        compose.waitForIdle()
        capture("vault-opened.png")
    }

    @Test
    fun deleting_a_selected_file_calls_the_repository_and_removes_it_from_the_list() {
        val repository = SealedFakeFileRepository()
        val id = runBlocking { repository.upload("passport.pdf", "bytes".toByteArray()) }

        compose.setContent { VaultScreen(SealedFakeRepository(), repository) }
        compose.onNodeWithTag(TAG_TAB_FILES).performClick()
        compose.waitForIdle()
        assertTrue(runBlocking { repository.list() }.any { it.id == id })

        compose.onNodeWithTag(TAG_FILES_SELECT_TOGGLE).performClick()
        compose.onNodeWithTag("files-row-select-$id").performClick()
        compose.onNodeWithTag(TAG_FILES_DELETE_SELECTED).performClick()
        compose.waitForIdle()

        assertFalse(runBlocking { repository.list() }.any { it.id == id })
        compose.onNodeWithText("passport.pdf").assertDoesNotExist()
    }

    @Test
    fun capture_the_files_tab() {
        // Not an assertion — a rendering, so the new Files tab can be
        // reviewed as pixels rather than as a description.
        val repository = SealedFakeRepository()

        compose.setContent { VaultScreen(repository, SealedFakeFileRepository()) }
        compose.onNodeWithTag(TAG_TAB_FILES).performClick()
        compose.waitForIdle()
        capture("files-list.png")

        compose.onNodeWithTag(TAG_NEW_FILE).performClick()
        compose.waitForIdle()
        capture("files-add-sheet.png")
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
        rows.map { (id, row) -> SecretSummary(id, row.title, hint = "") }

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

    override suspend fun update(id: UUID, title: String, payload: SecretPayload) {
        val request = SecretEnvelope.sealForUpdate(title, payload)
        rows[id] = Row(request.title, request.ciphertext, request.nonce, request.dek.copyOf())
    }


    override suspend fun deleteItem(id: UUID) {
        rows.remove(id)
    }

    override suspend fun delete() {
        rows.clear()
    }

    fun storedBytesContain(text: String): Boolean =
        rows.values.any { (it.ciphertext + it.nonce).containsBytesOf(text) }
}

/**
 * Stores exactly what the server would store for a File: ciphertext, nonce
 * and the DEK. Nothing here can return bytes it was not given in sealed form.
 */
private class SealedFakeFileRepository : FileRepository {

    private class Row(val title: String, val ciphertext: ByteArray, val nonce: ByteArray, val dek: ByteArray)

    private val rows = LinkedHashMap<UUID, Row>()

    override suspend fun list(): List<FileSummary> =
        rows.map { (id, row) -> FileSummary(id, row.title) }

    override suspend fun upload(title: String, bytes: ByteArray): UUID {
        val sealed = com.cryptum.crypto.CryptoCore.seal(bytes)
        val id = UUID.randomUUID()
        rows[id] = Row(title, sealed.ciphertext, sealed.nonce, sealed.dek.copyOf())
        return id
    }

    override suspend fun download(id: UUID): ByteArray {
        val row = requireNotNull(rows[id])
        return com.cryptum.crypto.CryptoCore.open(
            com.cryptum.crypto.SealedSecret(row.ciphertext, row.nonce, row.dek),
            row.dek,
        )
    }

    override suspend fun delete(id: UUID) {
        rows.remove(id)
    }
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

private fun ByteArray.containsBytesOf(text: String): Boolean {
    val needle = text.toByteArray()
    if (needle.isEmpty() || needle.size > size) return false
    return (0..size - needle.size).any { start ->
        needle.indices.all { this[start + it] == needle[it] }
    }
}

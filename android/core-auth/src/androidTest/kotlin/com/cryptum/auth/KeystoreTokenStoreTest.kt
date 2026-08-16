package com.cryptum.auth

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class KeystoreTokenStoreTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var store: KeystoreTokenStore

    private val token = "rt-8f3a-not-a-real-token-but-distinctive"

    @Before
    fun setUp() {
        store = KeystoreTokenStore(context)
        store.clear()
    }

    @After
    fun tearDown() {
        store.clear()
    }

    @Test
    fun stores_and_returns_the_refresh_token() {
        store.saveRefreshToken(token)

        assertEquals(token, store.refreshToken())
    }

    @Test
    fun the_token_never_appears_in_the_clear_on_disk() {
        // The plan's stated verification for 2.10, and the reason this module
        // exists. Searches every file the app owns, not just the preferences
        // file it is supposed to use — a token leaked into a cache, a WAL or a
        // journal is leaked just the same.
        store.saveRefreshToken(token)

        val needle = token.toByteArray()
        val offenders = context.dataDir.walkTopDown()
            .filter { it.isFile }
            .filter { it.readBytesOrEmpty().containsSequence(needle) }
            .map { it.absolutePath }
            .toList()

        assertEquals(emptyList<String>(), offenders)
    }

    @Test
    fun clear_removes_the_token() {
        store.saveRefreshToken(token)

        store.clear()

        assertNull(store.refreshToken())
    }

    @Test
    fun clear_destroys_the_key_not_just_the_ciphertext() {
        // Separate from the test above on purpose. Clearing only the preferences
        // would satisfy that one, while leaving a key that can still decrypt a
        // ciphertext recovered from a backup. Destroying the key makes every
        // copy inert at once — ADR-0003's argument, applied on the device.
        store.saveRefreshToken(token)

        store.clear()

        assertNull(KeystoreTokenStore.loadSecretKey())
    }

    @Test
    fun reading_before_anything_is_stored_returns_null() {
        assertNull(store.refreshToken())
    }

    @Test
    fun a_second_instance_reads_what_the_first_one_wrote() {
        // Survives process death: the key is in the Keystore, not in memory.
        store.saveRefreshToken(token)

        assertEquals(token, KeystoreTokenStore(context).refreshToken())
    }

    @Test
    fun the_key_lives_in_the_android_keystore_and_is_not_exportable() {
        // The whole guarantee. If the key material could be read out, encrypting
        // with it would be theatre — an attacker with file access would have
        // both halves.
        store.saveRefreshToken(token)

        val key = KeystoreTokenStore.loadSecretKey()

        assertNotNull(key)
        assertNull("key material must not be exportable", key!!.encoded)
    }

    @Test
    fun tampering_with_the_stored_ciphertext_is_detected() {
        // GCM authenticates. A token that decrypts to garbage would be sent to
        // the identity provider and fail confusingly; a detected tamper lets the
        // caller re-authenticate cleanly.
        store.saveRefreshToken(token)
        store.corruptStoredCiphertextForTest()

        assertNull(store.refreshToken())
    }

    private fun File.readBytesOrEmpty(): ByteArray =
        try {
            readBytes()
        } catch (_: Exception) {
            ByteArray(0)
        }

    private fun ByteArray.containsSequence(needle: ByteArray): Boolean {
        if (needle.isEmpty() || size < needle.size) return false
        outer@ for (i in 0..size - needle.size) {
            for (j in needle.indices) if (this[i + j] != needle[j]) continue@outer
            return true
        }
        return false
    }

    @Test
    fun does_not_require_user_authentication_to_read() {
        // Deliberate, and the one place this module trades security for
        // function: silent token refresh has to work while the app is locked or
        // backgrounded, so the key is not gated on biometrics. The app lock in
        // task 2.12 gates the UI instead. Asserted so the trade cannot be
        // reversed by accident — a key that suddenly required auth would break
        // background refresh in a way that looks like a server fault.
        store.saveRefreshToken(token)

        assertFalse(KeystoreTokenStore.keyRequiresUserAuthentication())
    }
}

package com.cryptum.lock

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.assertIsDisplayed
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.io.FileOutputStream

class LockGateTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun locked_content_is_not_rendered_at_all() {
        // The plan's stated verification: Vault content is unreachable until the
        // gate passes. "Not rendered" rather than "not visible" — a covered
        // composable still reaches the recents thumbnail and accessibility.
        compose.setContent {
            LockGate(isLocked = true, onUnlockRequested = {}) {
                Text("hunter2", Modifier.fillMaxSize())
            }
        }

        compose.onNodeWithTag(TAG_SEAL).assertIsDisplayed()
        compose.onNodeWithTag(TAG_VAULT_CONTENT).assertDoesNotExist()
        compose.onNodeWithText("hunter2").assertDoesNotExist()
    }

    @Test
    fun unlocked_content_is_rendered() {
        compose.setContent {
            LockGate(isLocked = false, onUnlockRequested = {}) {
                Text("hunter2")
            }
        }

        compose.onNodeWithTag(TAG_VAULT_CONTENT).assertIsDisplayed()
        compose.onNodeWithTag(TAG_SEAL).assertDoesNotExist()
    }

    @Test
    fun capture_the_seal_screen() {
        // Not an assertion — a rendering, written to the device so the design
        // can be reviewed as pixels rather than as a description.
        compose.setContent {
            LockGate(isLocked = true, onUnlockRequested = {}) { Text("vault") }
        }
        compose.waitForIdle()

        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()

        // additionalTestOutputDir is the one location AGP copies back to the host
        // (build/outputs/connected_android_test_additional_output) before it
        // uninstalls the test APK. Anything written elsewhere on the device is
        // deleted with the app before it can be pulled.
        val dir = File(
            requireNotNull(InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")),
        )
        FileOutputStream(File(dir, "seal-screen.png")).use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}

private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onNodeWithText(text: String) =
    onNode(androidx.compose.ui.test.hasText(text))

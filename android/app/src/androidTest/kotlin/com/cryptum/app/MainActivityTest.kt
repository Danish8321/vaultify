package com.cryptum.app

import android.view.WindowManager
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.platform.app.InstrumentationRegistry
import com.cryptum.lock.TAG_SEAL
import com.cryptum.lock.TAG_VAULT_CONTENT
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertTrue

/**
 * The app as a user meets it, on a real Activity.
 *
 * This is the first test in the project that runs against something
 * installable. Everything before it composed a library directly, which cannot
 * falsify anything that depends on there being an Activity at all — and
 * FLAG_SECURE is exactly that kind of claim.
 */
class MainActivityTest {

    init {
        // These tests exercise the lock/vault flow, not first-run onboarding
        // (ticket 29 covers that separately) — seed the already-onboarded
        // state before the compose rule launches the Activity (its launch
        // happens during rule application, ahead of any @Before), matching
        // a returning user.
        OnboardingPrefs.setOnboarded(InstrumentationRegistry.getInstrumentation().targetContext)
    }

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun the_app_opens_sealed() {
        compose.onNodeWithTag(TAG_SEAL).assertExists()
        compose.onNodeWithTag(TAG_VAULT_CONTENT).assertDoesNotExist()
    }

    @Test
    fun the_window_blocks_screenshots_and_the_recents_thumbnail() {
        // Asserted on the real window rather than on a composable, because
        // FLAG_SECURE only exists at the window. A test that checked for the
        // presence of a "secure" composable would prove nothing about whether
        // the flag was ever actually set.
        val flags = compose.activity.window.attributes.flags

        assertTrue(
            flags and WindowManager.LayoutParams.FLAG_SECURE != 0,
            "FLAG_SECURE is not set, so the Vault appears in screenshots and in recents",
        )
    }
}

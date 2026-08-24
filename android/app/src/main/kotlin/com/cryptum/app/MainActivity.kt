package com.cryptum.app

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.core.content.ContextCompat
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.fragment.app.FragmentActivity
import com.cryptum.lock.AppLock
import com.cryptum.lock.LockGate
import com.cryptum.lock.OnboardingScreen
import com.cryptum.lock.ReLockOnBackground
import com.cryptum.lock.Seal
import com.cryptum.lock.promptToUnlock
import com.cryptum.vault.VaultScreen

/**
 * The colour scheme exists only so stock Material components (ripple,
 * text-field chrome) stop falling back to M3's own light defaults — every
 * value here is a Seal colour, never a new one. Shape and typography are left
 * at M3 default because nothing in this app uses either.
 */
private val CryptumColorScheme = darkColorScheme(
    primary = Seal.Open,
    onPrimary = Seal.Ground,
    background = Seal.Ground,
    onBackground = Seal.Ink,
    surface = Seal.Mass,
    onSurface = Seal.Ink,
    surfaceVariant = Seal.Grain,
    onSurfaceVariant = Seal.InkDim,
    outline = Seal.InkDim,
)

/**
 * The whole app. One Activity, one window, two states.
 *
 * A [FragmentActivity] because `BiometricPrompt` requires one — it hosts its
 * dialog as a fragment. That is the only reason; nothing here uses fragments.
 */
class MainActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Set on the window for the whole lifetime of the app, rather than by
        // whichever screen happens to show plaintext.
        //
        // Two reasons. The flag only exists at the window, so per-screen
        // toggling means a window that is briefly insecure during every
        // transition — and the recents thumbnail is captured exactly at a
        // transition. And a rule of "remember to protect the screens that show
        // secrets" is a rule someone eventually forgets on a new screen.
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)

        setContent {
            MaterialTheme(colorScheme = CryptumColorScheme) {
                val lock = remember { AppLock() }
                var locked by remember { mutableStateOf(true) }
                // In-memory only — no settings-persistence layer exists yet (the
                // same gap SettingsScreen already lives with), so onboarding
                // replays every fresh process rather than only on a real first run.
                var onboarded by remember { mutableStateOf(false) }

                SideEffect { lock.onLockStateChanged { locked = it } }

                ReLockOnBackground(lock)

                if (!onboarded) {
                    OnboardingScreen(onFinished = { onboarded = true })
                } else {
                    LockGate(
                        isLocked = locked,
                        onUnlockRequested = { promptToUnlock(this, lock, ContextCompat.getMainExecutor(this)) },
                    ) {
                        // Constructed inside the unlocked branch on purpose: while the
                        // Vault is sealed there is no repository, so there is nothing
                        // holding a connection or a token in memory behind the seal.
                        val repository = remember { Repositories.forSignedInUser() }
                        VaultScreen(repository, onAccountDeleted = { lock.onAccountDeleted() })
                    }
                }
            }
        }
    }
}

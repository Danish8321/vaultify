package com.cryptum.app

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.fragment.app.FragmentActivity
import com.cryptum.lock.AppLock
import com.cryptum.lock.LockGate
import com.cryptum.lock.ReLockOnBackground
import com.cryptum.lock.promptToUnlock
import com.cryptum.vault.VaultScreen

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
            val lock = remember { AppLock() }
            var locked by remember { mutableStateOf(true) }

            remember(lock) { lock.onLockStateChanged { locked = it } }

            ReLockOnBackground(lock)

            LockGate(
                isLocked = locked,
                onUnlockRequested = { promptToUnlock(this, lock, mainExecutor) },
            ) {
                // Constructed inside the unlocked branch on purpose: while the
                // Vault is sealed there is no repository, so there is nothing
                // holding a connection or a token in memory behind the seal.
                val repository = remember { Repositories.forSignedInUser() }
                VaultScreen(repository)
            }
        }
    }
}

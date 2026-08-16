package com.cryptum.lock

import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.util.concurrent.Executor

/**
 * Authenticators accepted to open the Vault.
 *
 * `BIOMETRIC_STRONG` only — `BIOMETRIC_WEAK` includes face unlock
 * implementations that are defeatable by a photograph on some devices, which is
 * not an acceptable gate on a credential store.
 *
 * `DEVICE_CREDENTIAL` is included as the fallback the plan requires. It also
 * covers the user who has enrolled no biometric at all; without it they would
 * be locked out of their own vault.
 */
private const val ALLOWED =
    BiometricManager.Authenticators.BIOMETRIC_STRONG or
        BiometricManager.Authenticators.DEVICE_CREDENTIAL

/**
 * Shows the platform authentication prompt and reports the outcome to [lock].
 *
 * Deliberately thin. The decision of what an outcome *means* belongs to
 * [AppLock], which is tested without a device; this function only translates
 * platform callbacks into those calls.
 */
fun promptToUnlock(activity: FragmentActivity, lock: AppLock, executor: Executor) {
    val prompt = BiometricPrompt(
        activity,
        executor,
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                lock.onAuthenticationSucceeded()
            }

            // Not overriding onAuthenticationFailed for a single bad read: the
            // platform prompt stays up and lets the user retry, and re-locking
            // there would dismiss a prompt the user is still using. Only a
            // terminal error counts as a failure.
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                lock.onAuthenticationFailed()
            }
        },
    )

    prompt.authenticate(
        BiometricPrompt.PromptInfo.Builder()
            .setTitle("Open Cryptum")
            .setSubtitle("Your vault is sealed")
            .setAllowedAuthenticators(ALLOWED)
            .build(),
    )
}

/**
 * Re-locks [lock] whenever the app leaves the foreground.
 *
 * Bound to ON_STOP rather than ON_PAUSE: ON_PAUSE also fires for a permission
 * dialog or the biometric prompt itself, which would re-lock the vault in the
 * middle of unlocking it.
 */
@Composable
fun ReLockOnBackground(lock: AppLock) {
    val owner = LocalLifecycleOwner.current

    DisposableEffect(owner) {
        val observer = object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) = lock.onBackgrounded()
            override fun onResume(owner: LifecycleOwner) = lock.onResumed()
        }

        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
}

/**
 * Blocks screenshots and the recents-screen thumbnail for as long as this is
 * composed (security-requirements, task 2.13).
 *
 * Applied at the window, because that is the only level `FLAG_SECURE` exists
 * at — there is no per-composable equivalent, and anything claiming to be one
 * is hiding pixels rather than preventing capture.
 */
@Composable
fun SecureScreen() {
    val context = LocalContext.current

    DisposableEffect(Unit) {
        val window = (context as? ComponentActivity)?.window
        window?.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
}

package com.cryptum.app

import android.content.Context

/**
 * Whether onboarding has completed once on this device. Plain
 * [android.content.SharedPreferences] rather than a new DataStore
 * dependency — one boolean does not need a database.
 *
 * Deliberately not a server-tracked [com.cryptum.vault] concept: onboarding
 * is "has this install seen the intro," not an account fact, so it carries
 * no auth requirement and survives no reinstall. See
 * `.scratch/cryptum/issues/29-onboarding-not-persisted.md`.
 */
object OnboardingPrefs {
    private const val PrefsName = "onboarding"
    private const val KeyOnboarded = "onboarded"

    fun isOnboarded(context: Context): Boolean =
        context.getSharedPreferences(PrefsName, Context.MODE_PRIVATE).getBoolean(KeyOnboarded, false)

    fun setOnboarded(context: Context) {
        context.getSharedPreferences(PrefsName, Context.MODE_PRIVATE).edit()
            .putBoolean(KeyOnboarded, true)
            .apply()
    }
}

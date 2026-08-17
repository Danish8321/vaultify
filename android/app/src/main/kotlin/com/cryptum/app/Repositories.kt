package com.cryptum.app

import com.cryptum.vault.VaultRepository

/**
 * Where the Vault's data comes from.
 *
 * Nothing real yet, and deliberately not faked. Two things are missing and both
 * are tracked: there is no acquired access token (ticket 14 — the MSAL half of
 * task 2.10 is deferred) and no deployed environment to call (ticket 06 — Azure
 * infrastructure is unauthorised to deploy).
 *
 * The tempting move here is an in-memory repository so the app "works" when you
 * run it. That would be the fourth-and-fifth-gate mistake again in a new place:
 * a build that looks finished while proving nothing, where the failure only
 * surfaces once someone trusts it. Failing loudly, naming what is missing, is
 * the honest state of this wiring.
 */
object Repositories {

    fun forSignedInUser(): VaultRepository =
        error(
            "No Vault backend is wired up yet: sign-in is deferred (ticket 14) and " +
                "no environment is deployed (ticket 06). The Vault screens are proven " +
                "against a fake in :feature-vault's instrumented tests.",
        )
}

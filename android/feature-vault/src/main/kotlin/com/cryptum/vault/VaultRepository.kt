package com.cryptum.vault

import com.cryptum.api.ItemsApi
import com.cryptum.api.model.CreateSecretRequest
import java.util.UUID

/**
 * One row of the list. Title (plus a non-secret [hint] line, e.g. the item's
 * kind) only — reading it costs no DEK unwrap (ADR-0002).
 */
data class SecretSummary(val id: UUID, val title: String, val hint: String)

/**
 * What the screens are allowed to know about the server.
 *
 * An interface rather than the generated `ItemsApi` directly, for one reason
 * that matters: the screens must be testable without a network, and the thing
 * being tested is that plaintext appears on screen and nowhere else. A fake
 * that speaks this interface makes that test possible; a fake HTTP transport
 * would prove the same thing far more expensively.
 */
interface VaultRepository {
    suspend fun list(): List<SecretSummary>
    suspend fun create(title: String, payload: SecretPayload): UUID
    suspend fun read(id: UUID): SecretPayload
}

/**
 * The real one. Deliberately thin: it seals, calls, and opens.
 *
 * Not covered by unit tests. Everything here that could be wrong is either the
 * envelope — proven in `SecretEnvelopeTest` — or the HTTP call itself, which
 * only a real server can falsify. That is task 2.14's job, and claiming this
 * class is verified before then would be a claim without evidence.
 */
class ApiVaultRepository(private val api: ItemsApi) : VaultRepository {

    override suspend fun list(): List<SecretSummary> =
        api.listItems().body().map { SecretSummary(id = it.id, title = it.title, hint = it.kind) }

    override suspend fun create(title: String, payload: SecretPayload): UUID {
        val request: CreateSecretRequest = SecretEnvelope.seal(title, payload)
        return try {
            api.createSecret(request).body().id
        } finally {
            // The DEK travelled in the request body and has no further use on
            // this device. Zeroing it here rather than trusting the caller is
            // the difference between a key that lives for a millisecond and one
            // that lives until the next garbage collection.
            request.dek.fill(0)
        }
    }

    override suspend fun read(id: UUID): SecretPayload {
        val item = api.readItem(id).body()
        return try {
            SecretEnvelope.open(item)
        } finally {
            item.dek.fill(0)
        }
    }
}

package com.cryptum.vault

import com.cryptum.api.ItemsApi
import com.cryptum.api.model.CreateFileRequest
import com.cryptum.crypto.CryptoCore
import com.cryptum.crypto.SealedSecret
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.readBytes
import java.util.UUID

/** One row of the Files list. No hint field: a File carries no non-secret metadata worth showing. */
data class FileSummary(val id: UUID, val title: String)

/**
 * What the Files screen is allowed to know about the server.
 *
 * There is no list-files-only endpoint on the backend — [list] reuses the
 * generic Item list and filters by kind.
 */
interface FileRepository {
    suspend fun list(): List<FileSummary>

    /** Encrypts [bytes] client-side and uploads the ciphertext directly to blob storage. */
    suspend fun upload(title: String, bytes: ByteArray): UUID

    /** Downloads and decrypts a File's ciphertext. */
    suspend fun download(id: UUID): ByteArray

    /** Soft-deletes this File and its blob (`DELETE /items/{id}`, shared with Secrets). */
    suspend fun delete(id: UUID)
}

/**
 * The real one. Registers metadata through [api], then PUTs the ciphertext
 * straight to the blob SAS the registration returns using [blobClient] — the
 * app server never sees file bytes (docs/IMPLEMENTATION-PLAN.md 3.1/3.3).
 *
 * Not covered by unit tests, same reasoning as [ApiVaultRepository]: what
 * could be wrong here is either the envelope (proven elsewhere) or the HTTP
 * calls themselves, which only a real server and a real blob account can
 * falsify.
 */
class ApiFileRepository(
    private val api: ItemsApi,
    private val blobClient: HttpClient,
) : FileRepository {

    override suspend fun list(): List<FileSummary> =
        api.listItems().body()
            .filter { it.kind == "File" }
            .map { FileSummary(id = it.id, title = it.title) }

    override suspend fun upload(title: String, bytes: ByteArray): UUID {
        val sealed: SealedSecret = CryptoCore.seal(bytes)
        val request = CreateFileRequest(
            title = title,
            sizeBytes = sealed.ciphertext.size,
            nonce = sealed.nonce,
            dek = sealed.dek,
        )
        return try {
            val created = api.createFile(request).body()
            // Azure Blob Storage requires this header on a block-blob PUT; the
            // SAS itself carries no content-type opinion.
            blobClient.put(created.uploadUri.toString()) {
                header("x-ms-blob-type", "BlockBlob")
                setBody(sealed.ciphertext)
            }
            created.id
        } finally {
            request.dek.fill(0)
        }
    }

    override suspend fun download(id: UUID): ByteArray {
        val file = api.readFile(id).body()
        return try {
            val ciphertext = blobClient.get(file.downloadUri.toString()).readBytes()
            CryptoCore.open(SealedSecret(ciphertext, file.nonce, file.dek), file.dek)
        } finally {
            file.dek.fill(0)
        }
    }

    override suspend fun delete(id: UUID) {
        api.deleteItem(id)
    }
}

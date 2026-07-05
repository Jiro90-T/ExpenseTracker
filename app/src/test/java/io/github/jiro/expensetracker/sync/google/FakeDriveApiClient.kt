package io.github.jiro.expensetracker.sync.google

internal class FakeDriveApiClient : DriveApiClient {
    /** When non-null, every upload throws this. */
    var uploadError: DriveApiException? = null

    /** When non-null, every download throws this. */
    var downloadError: DriveApiException? = null

    /** Body returned by the next download call. Default "fake-file-id". */
    var downloadBody: String? = null

    /** Recorded uploads: (fileId, body, mimeType). */
    val uploads = mutableListOf<Triple<String?, String, String>>()

    /** Recorded downloads. */
    val downloads = mutableListOf<String>()

    /** ID returned by the next upload call. Default "fake-file-id". */
    var nextUploadId: String = "fake-file-id"

    override suspend fun upload(fileId: String?, body: String, mimeType: String): String {
        uploads.add(Triple(fileId, body, mimeType))
        uploadError?.let { throw it }
        return nextUploadId
    }

    override suspend fun download(fileId: String): String? {
        downloads.add(fileId)
        downloadError?.let { throw it }
        return downloadBody
    }
}
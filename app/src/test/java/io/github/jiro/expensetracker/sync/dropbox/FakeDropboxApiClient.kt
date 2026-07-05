package io.github.jiro.expensetracker.sync.dropbox

/**
 * Test fake for [DropboxApiClient]. Mirrors 4b's [io.github.jiro.expensetracker.sync.google.FakeDriveApiClient].
 *
 * Configure [uploadError]/[downloadError] to make the next call throw;
 * otherwise [nextUploadId] is returned from upload and [downloadBody] from
 * download. Use [uploads]/[downloads]/[revLookups] to assert on what was
 * called and with what arguments.
 */
internal class FakeDropboxApiClient : DropboxApiClient {
    var uploadError: DropboxApiException? = null
    var downloadError: DropboxApiException? = null
    var revLookupError: DropboxApiException? = null

    var downloadBody: String? = null
    var nextUploadId: String = "fake-rev"
    var nextRevLookupResult: String? = null

    val uploads: MutableList<Pair<String?, String>> = mutableListOf()
    val downloads: MutableList<Unit> = mutableListOf()
    val revLookups: MutableList<Unit> = mutableListOf()

    override suspend fun upload(existingRev: String?, body: String): String {
        uploads.add(existingRev to body)
        uploadError?.let { throw it }
        return nextUploadId
    }

    override suspend fun download(): String? {
        downloads.add(Unit)
        downloadError?.let { throw it }
        return downloadBody
    }

    override suspend fun getRev(): String? {
        revLookups.add(Unit)
        revLookupError?.let { throw it }
        return nextRevLookupResult
    }
}
package io.github.jiro.expensetracker.sync.dropbox

internal class FakeDropboxSyncTokensRepository(
    initial: DropboxSyncTokens? = null,
) : DropboxSyncTokensRepository {
    var stored: DropboxSyncTokens? = initial
    var loadCount = 0
    var saveCount = 0
    var clearCount = 0
    override suspend fun load(): DropboxSyncTokens? { loadCount++; return stored }
    override suspend fun save(tokens: DropboxSyncTokens) { saveCount++; stored = tokens }
    override suspend fun clear() { clearCount++; stored = null }
}

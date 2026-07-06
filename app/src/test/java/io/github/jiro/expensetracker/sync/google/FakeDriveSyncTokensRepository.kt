package io.github.jiro.expensetracker.sync.google

internal class FakeDriveSyncTokensRepository(
    initial: SyncTokens? = null,
) : SyncTokensRepository {
    var stored: SyncTokens? = initial
    var loadCount = 0
    var saveCount = 0
    var clearCount = 0
    override suspend fun load(): SyncTokens? { loadCount++; return stored }
    override suspend fun save(tokens: SyncTokens) { saveCount++; stored = tokens }
    override suspend fun clear() { clearCount++; stored = null }
}

# Phase 4a — Cloud Sync Foundation — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add the structural foundation for cloud sync — data model, repository contract, snapshot codec, and Hilt wiring — so 4b/4c can drop in concrete Drive/Dropbox implementations by replacing a single Hilt binding.

**Architecture:** New `sync/` package alongside `backup/`. `sync/` owns the cloud payload shape (v4 envelope + sync header: device id + last-modified timestamp + SHA-256 checksum) and the `CloudSyncRepository` contract. 4a ships `NoOpCloudSyncRepository` as the structural placeholder; the only Hilt binding that changes between 4a/4b/4c is which concrete class the `@Binds` points at. No UI, no real I/O.

**Tech Stack:** Kotlin, Hilt, kotlinx.coroutines (StateFlow, suspend), `org.json` (existing testImplementation), Robolectric (existing testImplementation), JUnit 4. No new dependencies.

---

## File Structure

| File | Action | Responsibility |
| --- | --- | --- |
| `app/src/main/java/io/github/jiro/expensetracker/sync/BackupBody.kt` | new | Pure wrapper around the v4 envelope arrays (`accounts`, `categories`, `transactions`). `serialize()` / `deserialize()` using the existing per-row helpers in `backup/BackupFormat.kt`. |
| `app/src/main/java/io/github/jiro/expensetracker/sync/SyncSnapshot.kt` | new | `SyncSnapshot` data class: `body: BackupBody`, `lastModifiedEpochMillis: Long`, `deviceId: String`, `schemaVersion: Int = 4`, `checksum: String`. |
| `app/src/main/java/io/github/jiro/expensetracker/sync/SyncException.kt` | new | `SyncException(code, message, cause)` + `SyncErrorCode` enum (`MALFORMED`, `CHECKSUM_MISMATCH`, `SCHEMA_INCOMPATIBLE`). |
| `app/src/main/java/io/github/jiro/expensetracker/sync/SyncState.kt` | new | `SyncState` sealed class (`SignedOut`, `SignedIn`, `Syncing`, `Error`) + `Operation` enum (`PUSH`, `PULL`). |
| `app/src/main/java/io/github/jiro/expensetracker/sync/PullResult.kt` | new | `PushResult`, `PullResult` (Success/NoRemoteSnapshot/Conflict/Failed), `SyncResult` (Pushed/Pulled/NoRemoteSnapshot/Failed), `SignInResult` (Success/Failed). |
| `app/src/main/java/io/github/jiro/expensetracker/sync/CloudSyncRepository.kt` | new | Interface with `state`/`lastSyncedAtEpochMillis`/`isSignedIn` flows + suspend `signIn`/`signOut`/`push`/`pull`/`syncOnce`. |
| `app/src/main/java/io/github/jiro/expensetracker/sync/SyncSnapshotCodec.kt` | new | `encode(snapshot): String` + `decode(json): SyncSnapshot` + private `sha256Hex(input: String): String`. |
| `app/src/main/java/io/github/jiro/expensetracker/sync/DeviceIdProvider.kt` | new | Interface + `DefaultDeviceIdProvider` (UUID in `sync_prefs` SharedPreferences). |
| `app/src/main/java/io/github/jiro/expensetracker/sync/NoOpCloudSyncRepository.kt` | new | `@Singleton class` with `@Inject constructor()`, throws on `push`, returns `NoRemoteSnapshot` on `pull`/`syncOnce`, transitions `state` on `signIn`/`signOut`. |
| `app/src/main/java/io/github/jiro/expensetracker/di/SyncModule.kt` | new | Hilt `@Module` binding `CloudSyncRepository` → `NoOpCloudSyncRepository` and `DeviceIdProvider` → `DefaultDeviceIdProvider`. (Lives in the flat `di/` package to match every other Hilt module in the repo — `StatisticsModule`, `AccountManagementModule`, etc. — minor deviation from the spec's `sync/di/` path.) |
| `app/src/test/java/io/github/jiro/expensetracker/sync/BackupBodyTest.kt` | new | Round-trip test for `serialize` / `deserialize`. |
| `app/src/test/java/io/github/jiro/expensetracker/sync/SyncSnapshotCodecTest.kt` | new | 7 tests: round-trip, valid checksum, checksum mismatch, schema too new, malformed JSON, missing body, missing checksum. |
| `app/src/test/java/io/github/jiro/expensetracker/sync/DeviceIdProviderTest.kt` | new | 3 Robolectric tests: same on second call, persists across instances, UUID regex. |
| `app/src/test/java/io/github/jiro/expensetracker/sync/NoOpCloudSyncRepositoryTest.kt` | new | 8 contract tests: initial state, `signIn`/`signOut` transitions, `push` throws, `pull`/`syncOnce` return `NoRemoteSnapshot`, `lastSyncedAtEpochMillis` stays null. |

**Modified files:** none. 4a is purely additive.

---

### Task 1: BackupBody wrapper + round-trip test

**Files:**
- Create: `app/src/main/java/io/github/jiro/expensetracker/sync/BackupBody.kt`
- Create: `app/src/test/java/io/github/jiro/expensetracker/sync/BackupBodyTest.kt`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/io/github/jiro/expensetracker/sync/BackupBodyTest.kt`:

```kotlin
package io.github.jiro.expensetracker.sync

import io.github.jiro.expensetracker.backup.AccountRow
import io.github.jiro.expensetracker.backup.CategoryRow
import io.github.jiro.expensetracker.backup.TransactionRow
import org.junit.Assert.assertEquals
import org.junit.Test

class BackupBodyTest {

    @Test
    fun roundTrip_preservesAccountsCategoriesAndTransactions() {
        val original = BackupBody(
            accounts = listOf(
                AccountRow(
                    id = 1L,
                    name = "Cash wallet",
                    type = "CASH",
                    icon = "💵",
                    color = 0xFF00FF00.toInt(),
                    currencyCode = "USD",
                    openingBalanceMinor = 0L,
                    createdAtEpochMillis = 1_700_000_000_000L,
                    archived = false,
                    archivedAtEpochMillis = null,
                    sortOrder = 0,
                ),
            ),
            categories = listOf(
                CategoryRow(id = 1L, name = "Food", type = "EXPENSE", sortOrder = 0, isBuiltIn = true),
                CategoryRow(id = 2L, name = "Salary", type = "INCOME", sortOrder = 1, isBuiltIn = true),
            ),
            transactions = listOf(
                TransactionRow(
                    id = 10L,
                    title = "Groceries",
                    amountMinor = 1500L,
                    currencyCode = "USD",
                    type = "EXPENSE",
                    categoryId = 1L,
                    occurredAtEpochMillis = 1_710_000_000_000L,
                    note = "weekly",
                    createdAtEpochMillis = 1_710_000_000_000L,
                    recurringGroupId = null,
                    recurrenceKind = null,
                    recurrenceInterval = 1,
                    recurrenceEndAt = null,
                    recurrenceMaxOccurrences = null,
                    recurrenceNextAt = null,
                    receiptPath = null,
                    accountId = 1L,
                    transferAccountId = null,
                ),
            ),
        )
        val json = original.serialize()
        val decoded = BackupBody.deserialize(json)
        assertEquals(original, decoded)
    }

    @Test
    fun roundTrip_handlesEmptyArrays() {
        val empty = BackupBody(accounts = emptyList(), categories = emptyList(), transactions = emptyList())
        val decoded = BackupBody.deserialize(empty.serialize())
        assertEquals(empty, decoded)
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run:
```bash
export JAVA_HOME=C:/tools/jdk-21.0.5+11 && ./gradlew testDebugUnitTest --tests "*BackupBodyTest*" 2>&1 | tail -15
```
Expected: FAIL with `Unresolved reference: BackupBody`.

- [ ] **Step 3: Implement BackupBody**

Create `app/src/main/java/io/github/jiro/expensetracker/sync/BackupBody.kt`:

```kotlin
package io.github.jiro.expensetracker.sync

import io.github.jiro.expensetracker.backup.AccountRow
import io.github.jiro.expensetracker.backup.CategoryRow
import io.github.jiro.expensetracker.backup.TransactionRow
import io.github.jiro.expensetracker.backup.accountEntityToJson
import io.github.jiro.expensetracker.backup.accountFromJson
import io.github.jiro.expensetracker.backup.categoryEntityToJson
import io.github.jiro.expensetracker.backup.categoryFromJson
import io.github.jiro.expensetracker.backup.transactionEntityToJson
import io.github.jiro.expensetracker.backup.transactionFromJson
import org.json.JSONArray
import org.json.JSONObject

/**
 * Pure-data wrapper around the three arrays that the v4 backup envelope
 * carries (accounts, categories, transactions). The sync codec wraps this
 * in a header (schemaVersion / lastModifiedEpochMillis / deviceId /
 * checksum); the rest of the app can read and write it without touching
 * org.json.
 */
data class BackupBody(
    val accounts: List<AccountRow>,
    val categories: List<CategoryRow>,
    val transactions: List<TransactionRow>,
) {
    fun serialize(): String = JSONObject().apply {
        put("accounts", JSONArray().also { arr -> accounts.forEach { arr.put(toJson(it)) } })
        put("categories", JSONArray().also { arr -> categories.forEach { arr.put(toJson(it)) } })
        put("transactions", JSONArray().also { arr -> transactions.forEach { arr.put(toJson(it)) } })
    }.toString()

    companion object {
        fun deserialize(json: String): BackupBody {
            val obj = JSONObject(json)
            val accounts = obj.getJSONArray("accounts").let { arr ->
                List(arr.length()) { i -> accountFromJson(arr.getJSONObject(i)) }
            }
            val categories = obj.getJSONArray("categories").let { arr ->
                List(arr.length()) { i -> categoryFromJson(arr.getJSONObject(i)) }
            }
            val transactions = obj.getJSONArray("transactions").let { arr ->
                List(arr.length()) { i -> transactionFromJson(arr.getJSONObject(i)) }
            }
            return BackupBody(accounts, categories, transactions)
        }

        // One-arg overloads of the existing per-row helpers in BackupFormat.
        // The existing signatures are positional and 5-11 args deep; these
        // thin wrappers keep the call sites in this file readable.
        private fun toJson(row: AccountRow) = accountEntityToJson(
            id = row.id, name = row.name, type = row.type, icon = row.icon,
            color = row.color, currencyCode = row.currencyCode,
            openingBalanceMinor = row.openingBalanceMinor,
            createdAtEpochMillis = row.createdAtEpochMillis,
            archived = row.archived,
            archivedAtEpochMillis = row.archivedAtEpochMillis,
            sortOrder = row.sortOrder,
        )

        private fun toJson(row: CategoryRow) = categoryEntityToJson(
            id = row.id, name = row.name, type = row.type,
            sortOrder = row.sortOrder, isBuiltIn = row.isBuiltIn,
        )

        private fun toJson(row: TransactionRow) = transactionEntityToJson(
            id = row.id, title = row.title, amountMinor = row.amountMinor,
            currencyCode = row.currencyCode, type = row.type, categoryId = row.categoryId,
            occurredAtEpochMillis = row.occurredAtEpochMillis, note = row.note,
            createdAtEpochMillis = row.createdAtEpochMillis,
            recurringGroupId = row.recurringGroupId,
            recurrenceKind = row.recurrenceKind,
            recurrenceInterval = row.recurrenceInterval,
            recurrenceEndAt = row.recurrenceEndAt,
            recurrenceMaxOccurrences = row.recurrenceMaxOccurrences,
            recurrenceNextAt = row.recurrenceNextAt,
            receiptPath = row.receiptPath,
            accountId = row.accountId,
            transferAccountId = row.transferAccountId,
        )
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run:
```bash
export JAVA_HOME=C:/tools/jdk-21.0.5+11 && ./gradlew testDebugUnitTest --tests "*BackupBodyTest*" 2>&1 | tail -20
```
Expected: 2 tests, 0 failures.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/io/github/jiro/expensetracker/sync/BackupBody.kt app/src/test/java/io/github/jiro/expensetracker/sync/BackupBodyTest.kt
git -c user.name="MiniMax-M3" -c user.email="291324429+Jiro90-T@users.noreply.github.com" commit -m "feat(sync): BackupBody wrapper with serialize/deserialize (Phase 4a)"
```

---

### Task 2: Sync data model + repository interface

**Files:**
- Create: `app/src/main/java/io/github/jiro/expensetracker/sync/SyncException.kt`
- Create: `app/src/main/java/io/github/jiro/expensetracker/sync/SyncSnapshot.kt`
- Create: `app/src/main/java/io/github/jiro/expensetracker/sync/SyncState.kt`
- Create: `app/src/main/java/io/github/jiro/expensetracker/sync/PullResult.kt`
- Create: `app/src/main/java/io/github/jiro/expensetracker/sync/CloudSyncRepository.kt`

These are all pure data / interface declarations. No behavior, no tests in this task — compilation in Task 3 (codec) and Task 5 (NoOp) will exercise them.

- [ ] **Step 1: Create SyncException.kt**

Create `app/src/main/java/io/github/jiro/expensetracker/sync/SyncException.kt`:

```kotlin
package io.github.jiro.expensetracker.sync

enum class SyncErrorCode { MALFORMED, CHECKSUM_MISMATCH, SCHEMA_INCOMPATIBLE }

data class SyncException(
    val code: SyncErrorCode,
    override val message: String,
    override val cause: Throwable? = null,
) : RuntimeException(message, cause)
```

- [ ] **Step 2: Create SyncSnapshot.kt**

Create `app/src/main/java/io/github/jiro/expensetracker/sync/SyncSnapshot.kt`:

```kotlin
package io.github.jiro.expensetracker.sync

import io.github.jiro.expensetracker.backup.BackupFormat

/**
 * One cloud-synced snapshot of the local database. The codec wraps [body]
 * in a JSON header (schemaVersion / lastModifiedEpochMillis / deviceId /
 * checksum) so any reader can verify integrity before deserialization.
 *
 * [schemaVersion] mirrors the v4 envelope's formatVersion; [checksum] is
 * SHA-256 hex of the serialized body. [deviceId] is the stable UUID the
 * provider assigned at install time.
 */
data class SyncSnapshot(
    val body: BackupBody,
    val lastModifiedEpochMillis: Long,
    val deviceId: String,
    val schemaVersion: Int = BackupFormat.FORMAT_VERSION,
    val checksum: String,
)
```

- [ ] **Step 3: Create SyncState.kt**

Create `app/src/main/java/io/github/jiro/expensetracker/sync/SyncState.kt`:

```kotlin
package io.github.jiro.expensetracker.sync

sealed class SyncState {
    object SignedOut : SyncState()
    data class SignedIn(val providerId: String) : SyncState()
    data class Syncing(val operation: Operation) : SyncState()
    data class Error(val message: String, val cause: Throwable? = null) : SyncState()
}

enum class Operation { PUSH, PULL }
```

- [ ] **Step 4: Create PullResult.kt**

Create `app/src/main/java/io/github/jiro/expensetracker/sync/PullResult.kt`:

```kotlin
package io.github.jiro.expensetracker.sync

data class PushResult(val pushedAtEpochMillis: Long)

sealed class PullResult<out T> {
    data class Success<T>(val snapshot: T, val pulledAtEpochMillis: Long) : PullResult<T>()
    object NoRemoteSnapshot : PullResult<Nothing>()
    data class Conflict(val remote: SyncSnapshot, val local: SyncSnapshot) : PullResult<Nothing>()
    data class Failed(val message: String, val cause: Throwable? = null) : PullResult<Nothing>()
}

sealed class SyncResult {
    data class Pushed(val pushedAtEpochMillis: Long) : SyncResult()
    data class Pulled(val snapshot: SyncSnapshot, val pulledAtEpochMillis: Long) : SyncResult()
    object NoRemoteSnapshot : SyncResult()
    data class Failed(val message: String, val cause: Throwable? = null) : SyncResult()
}

sealed class SignInResult {
    object Success : SignInResult()
    data class Failed(val message: String, val cause: Throwable? = null) : SignInResult()
}
```

- [ ] **Step 5: Create CloudSyncRepository.kt**

Create `app/src/main/java/io/github/jiro/expensetracker/sync/CloudSyncRepository.kt`:

```kotlin
package io.github.jiro.expensetracker.sync

import kotlinx.coroutines.flow.StateFlow

/**
 * Contract for any cloud-sync provider. 4a ships NoOpCloudSyncRepository;
 * 4b swaps in a Drive-backed implementation and 4c swaps in a Dropbox-
 * backed one. Callers depend only on this interface, so the swap is a
 * single Hilt binding.
 */
interface CloudSyncRepository {
    val state: StateFlow<SyncState>
    val lastSyncedAtEpochMillis: StateFlow<Long?>
    val isSignedIn: StateFlow<Boolean>

    suspend fun signIn(): SignInResult
    suspend fun signOut()
    suspend fun push(snapshot: SyncSnapshot): PushResult
    suspend fun pull(): PullResult<SyncSnapshot>
    suspend fun syncOnce(): SyncResult
}
```

- [ ] **Step 6: Verify it compiles**

Run:
```bash
export JAVA_HOME=C:/tools/jdk-21.0.5+11 && ./gradlew :app:compileDebugKotlin 2>&1 | tail -10
```
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/io/github/jiro/expensetracker/sync/
git -c user.name="MiniMax-M3" -c user.email="291324429+Jiro90-T@users.noreply.github.com" commit -m "feat(sync): data model + repository interface (Phase 4a)"
```

---

### Task 3: SyncSnapshotCodec + tests

**Files:**
- Create: `app/src/main/java/io/github/jiro/expensetracker/sync/SyncSnapshotCodec.kt`
- Create: `app/src/test/java/io/github/jiro/expensetracker/sync/SyncSnapshotCodecTest.kt`

- [ ] **Step 1: Write the failing test file**

Create `app/src/test/java/io/github/jiro/expensetracker/sync/SyncSnapshotCodecTest.kt`:

```kotlin
package io.github.jiro.expensetracker.sync

import io.github.jiro.expensetracker.backup.AccountRow
import io.github.jiro.expensetracker.backup.BackupFormat
import io.github.jiro.expensetracker.backup.CategoryRow
import io.github.jiro.expensetracker.backup.TransactionRow
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class SyncSnapshotCodecTest {

    private fun sampleBody(): BackupBody = BackupBody(
        accounts = listOf(
            AccountRow(
                id = 1L, name = "Cash wallet", type = "CASH", icon = "💵",
                color = 0xFF00FF00.toInt(), currencyCode = "USD",
                openingBalanceMinor = 0L, createdAtEpochMillis = 1_700_000_000_000L,
                archived = false, archivedAtEpochMillis = null, sortOrder = 0,
            ),
        ),
        categories = listOf(
            CategoryRow(id = 1L, name = "Food", type = "EXPENSE", sortOrder = 0, isBuiltIn = true),
        ),
        transactions = listOf(
            TransactionRow(
                id = 10L, title = "Groceries", amountMinor = 1500L, currencyCode = "USD",
                type = "EXPENSE", categoryId = 1L, occurredAtEpochMillis = 1_710_000_000_000L,
                note = "weekly", createdAtEpochMillis = 1_710_000_000_000L,
                recurringGroupId = null, recurrenceKind = null, recurrenceInterval = 1,
                recurrenceEndAt = null, recurrenceMaxOccurrences = null, recurrenceNextAt = null,
                receiptPath = null, accountId = 1L, transferAccountId = null,
            ),
        ),
    )

    private fun sampleSnapshot(
        lastModified: Long = 1_750_000_000_000L,
        deviceId: String = "device-abc",
    ): SyncSnapshot = SyncSnapshot(
        body = sampleBody(),
        lastModifiedEpochMillis = lastModified,
        deviceId = deviceId,
    )

    @Test
    fun encode_then_decode_returnsEquivalentSnapshot() {
        val original = sampleSnapshot()
        val json = SyncSnapshotCodec.encode(original)
        val decoded = SyncSnapshotCodec.decode(json)
        assertEquals(original, decoded)
    }

    @Test
    fun encode_producesValidChecksum() {
        val snapshot = sampleSnapshot()
        val json = SyncSnapshotCodec.encode(snapshot)
        // Strip the wrapper, recompute the checksum on the body, compare.
        val outer = org.json.JSONObject(json)
        val bodyJson = outer.getJSONObject("body").toString()
        val expected = outer.getString("checksum")
        val actual = sha256Hex(bodyJson)
        assertEquals(expected, actual)
    }

    @Test
    fun decode_throwsOnChecksumMismatch() {
        val snapshot = sampleSnapshot()
        val json = SyncSnapshotCodec.encode(snapshot)
        // Flip a byte in the body to invalidate the checksum.
        val tampered = json.replace("\"Food\"", "\"Foodz\"")
        try {
            SyncSnapshotCodec.decode(tampered)
            fail("Expected SyncException(CHECKSUM_MISMATCH)")
        } catch (e: SyncException) {
            assertEquals(SyncErrorCode.CHECKSUM_MISMATCH, e.code)
        }
    }

    @Test
    fun decode_throwsOnSchemaTooNew() {
        val snapshot = sampleSnapshot()
        val json = SyncSnapshotCodec.encode(snapshot)
        val tampered = json.replace(
            "\"schemaVersion\":${BackupFormat.FORMAT_VERSION}",
            "\"schemaVersion\":${BackupFormat.FORMAT_VERSION + 1}",
        )
        try {
            SyncSnapshotCodec.decode(tampered)
            fail("Expected SyncException(SCHEMA_INCOMPATIBLE)")
        } catch (e: SyncException) {
            assertEquals(SyncErrorCode.SCHEMA_INCOMPATIBLE, e.code)
        }
    }

    @Test
    fun decode_throwsOnMalformedJson() {
        try {
            SyncSnapshotCodec.decode("not json")
            fail("Expected SyncException(MALFORMED)")
        } catch (e: SyncException) {
            assertEquals(SyncErrorCode.MALFORMED, e.code)
        }
    }

    @Test
    fun decode_throwsOnMissingBody() {
        val snapshot = sampleSnapshot()
        val json = SyncSnapshotCodec.encode(snapshot)
        // Remove the body key entirely.
        val outer = org.json.JSONObject(json)
        outer.remove("body")
        try {
            SyncSnapshotCodec.decode(outer.toString())
            fail("Expected SyncException(MALFORMED)")
        } catch (e: SyncException) {
            assertEquals(SyncErrorCode.MALFORMED, e.code)
        }
    }

    @Test
    fun decode_throwsOnMissingChecksum() {
        val snapshot = sampleSnapshot()
        val json = SyncSnapshotCodec.encode(snapshot)
        val outer = org.json.JSONObject(json)
        outer.remove("checksum")
        try {
            SyncSnapshotCodec.decode(outer.toString())
            fail("Expected SyncException(CHECKSUM_MISMATCH)")
        } catch (e: SyncException) {
            assertEquals(SyncErrorCode.CHECKSUM_MISMATCH, e.code)
        }
    }

    private fun sha256Hex(input: String): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run:
```bash
export JAVA_HOME=C:/tools/jdk-21.0.5+11 && ./gradlew testDebugUnitTest --tests "*SyncSnapshotCodecTest*" 2>&1 | tail -15
```
Expected: FAIL with `Unresolved reference: SyncSnapshotCodec`.

- [ ] **Step 3: Implement SyncSnapshotCodec**

Create `app/src/main/java/io/github/jiro/expensetracker/sync/SyncSnapshotCodec.kt`:

```kotlin
package io.github.jiro.expensetracker.sync

import io.github.jiro.expensetracker.backup.BackupFormat
import java.security.MessageDigest
import org.json.JSONObject

/**
 * Wraps a [SyncSnapshot] in the on-the-wire JSON envelope used by every
 * cloud-sync provider. The envelope is the v4 backup arrays plus four
 * sync-specific metadata fields:
 *
 *   - schemaVersion : mirror of BackupFormat.FORMAT_VERSION; future-
 *     versioned snapshots are rejected with SCHEMA_INCOMPATIBLE.
 *   - lastModifiedEpochMillis : writer's wall clock at encode time, used
 *     for last-write-wins conflict resolution.
 *   - deviceId      : stable UUID per install (see [DeviceIdProvider]).
 *   - checksum      : SHA-256 hex of the serialized body, verified before
 *     deserialize. Absent or wrong → CHECKSUM_MISMATCH, never trusted.
 */
object SyncSnapshotCodec {

    fun encode(snapshot: SyncSnapshot): String {
        val bodyJson = snapshot.body.serialize()
        val checksum = sha256Hex(bodyJson)
        val outer = JSONObject().apply {
            put("schemaVersion", snapshot.schemaVersion)
            put("lastModifiedEpochMillis", snapshot.lastModifiedEpochMillis)
            put("deviceId", snapshot.deviceId)
            put("checksum", checksum)
            put("body", JSONObject(bodyJson))
        }
        return outer.toString()
    }

    fun decode(json: String): SyncSnapshot {
        val outer = JSONObject(json)
        val schemaVersion = outer.optInt("schemaVersion", -1)
        if (schemaVersion !in 1..BackupFormat.FORMAT_VERSION) {
            throw SyncException(
                SyncErrorCode.SCHEMA_INCOMPATIBLE,
                "Snapshot schema version $schemaVersion not supported (expected 1..${BackupFormat.FORMAT_VERSION})",
            )
        }
        val expected = outer.optString("checksum")
        if (expected.isEmpty()) {
            throw SyncException(SyncErrorCode.CHECKSUM_MISMATCH, "Snapshot has no checksum")
        }
        val bodyObj = outer.optJSONObject("body")
            ?: throw SyncException(SyncErrorCode.MALFORMED, "Snapshot missing body")
        val bodyJson = bodyObj.toString()
        val actual = sha256Hex(bodyJson)
        if (!expected.equals(actual, ignoreCase = true)) {
            throw SyncException(SyncErrorCode.CHECKSUM_MISMATCH, "Snapshot checksum mismatch")
        }
        val body = BackupBody.deserialize(bodyJson)
        return SyncSnapshot(
            body = body,
            lastModifiedEpochMillis = outer.getLong("lastModifiedEpochMillis"),
            deviceId = outer.getString("deviceId"),
            schemaVersion = schemaVersion,
            checksum = expected,
        )
    }

    private fun sha256Hex(input: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run:
```bash
export JAVA_HOME=C:/tools/jdk-21.0.5+11 && ./gradlew testDebugUnitTest --tests "*SyncSnapshotCodecTest*" 2>&1 | tail -20
```
Expected: 7 tests, 0 failures.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/io/github/jiro/expensetracker/sync/SyncSnapshotCodec.kt app/src/test/java/io/github/jiro/expensetracker/sync/SyncSnapshotCodecTest.kt
git -c user.name="MiniMax-M3" -c user.email="291324429+Jiro90-T@users.noreply.github.com" commit -m "feat(sync): SyncSnapshotCodec with SHA-256 integrity (Phase 4a)"
```

---

### Task 4: DeviceIdProvider + DefaultDeviceIdProvider + Robolectric tests

**Files:**
- Create: `app/src/main/java/io/github/jiro/expensetracker/sync/DeviceIdProvider.kt`
- Create: `app/src/test/java/io/github/jiro/expensetracker/sync/DeviceIdProviderTest.kt`

- [ ] **Step 1: Write the failing test file**

Create `app/src/test/java/io/github/jiro/expensetracker/sync/DeviceIdProviderTest.kt`:

```kotlin
package io.github.jiro.expensetracker.sync

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DeviceIdProviderTest {

    private fun newProvider(): DefaultDeviceIdProvider =
        DefaultDeviceIdProvider(ApplicationProvider.getApplicationContext())

    @Test
    fun getOrCreate_returnsSameIdOnSecondCall() {
        val provider = newProvider()
        val first = provider.getOrCreate()
        val second = provider.getOrCreate()
        assertEquals(first, second)
    }

    @Test
    fun getOrCreate_persistsAcrossInstances() {
        val first = newProvider().getOrCreate()
        val second = newProvider().getOrCreate()
        assertEquals(first, second)
    }

    @Test
    fun getOrCreate_generatesUuidFormat() {
        val id = newProvider().getOrCreate()
        // 8-4-4-4-12 hex layout
        assertTrue(id.matches(Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")))
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run:
```bash
export JAVA_HOME=C:/tools/jdk-21.0.5+11 && ./gradlew testDebugUnitTest --tests "*DeviceIdProviderTest*" 2>&1 | tail -15
```
Expected: FAIL with `Unresolved reference: DefaultDeviceIdProvider`.

- [ ] **Step 3: Implement DeviceIdProvider**

Create `app/src/main/java/io/github/jiro/expensetracker/sync/DeviceIdProvider.kt`:

```kotlin
package io.github.jiro.expensetracker.sync

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

interface DeviceIdProvider {
    fun getOrCreate(): String
}

@Singleton
internal class DefaultDeviceIdProvider @Inject constructor(
    @ApplicationContext private val context: Context,
) : DeviceIdProvider {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("sync_prefs", Context.MODE_PRIVATE)

    override fun getOrCreate(): String {
        prefs.getString(KEY, null)?.let { return it }
        val newId = UUID.randomUUID().toString()
        prefs.edit().putString(KEY, newId).apply()
        return newId
    }

    private companion object { const val KEY = "device_id" }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run:
```bash
export JAVA_HOME=C:/tools/jdk-21.0.5+11 && ./gradlew testDebugUnitTest --tests "*DeviceIdProviderTest*" 2>&1 | tail -20
```
Expected: 3 tests, 0 failures.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/io/github/jiro/expensetracker/sync/DeviceIdProvider.kt app/src/test/java/io/github/jiro/expensetracker/sync/DeviceIdProviderTest.kt
git -c user.name="MiniMax-M3" -c user.email="291324429+Jiro90-T@users.noreply.github.com" commit -m "feat(sync): DeviceIdProvider with stable UUID per install (Phase 4a)"
```

---

### Task 5: NoOpCloudSyncRepository + contract tests

**Files:**
- Create: `app/src/main/java/io/github/jiro/expensetracker/sync/NoOpCloudSyncRepository.kt`
- Create: `app/src/test/java/io/github/jiro/expensetracker/sync/NoOpCloudSyncRepositoryTest.kt`

- [ ] **Step 1: Write the failing test file**

Create `app/src/test/java/io/github/jiro/expensetracker/sync/NoOpCloudSyncRepositoryTest.kt`:

```kotlin
package io.github.jiro.expensetracker.sync

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class NoOpCloudSyncRepositoryTest {

    private lateinit var repo: NoOpCloudSyncRepository

    @Before
    fun setUp() {
        repo = NoOpCloudSyncRepository()
    }

    @Test
    fun state_startsAsSignedOut() = runTest {
        assertEquals(SyncState.SignedOut, repo.state.first())
    }

    @Test
    fun isSignedIn_isFalse_initially() = runTest {
        assertFalse(repo.isSignedIn.first())
    }

    @Test
    fun lastSyncedAtEpochMillis_remainsNull() = runTest {
        assertNull(repo.lastSyncedAtEpochMillis.first())
    }

    @Test
    fun signIn_transitionsStateToSignedIn() = runTest {
        val result = repo.signIn()
        assertEquals(SignInResult.Success, result)
        assertEquals(SyncState.SignedIn("noop"), repo.state.first())
        assertTrue(repo.isSignedIn.first())
    }

    @Test
    fun signOut_transitionsStateToSignedOut() = runTest {
        repo.signIn()
        repo.signOut()
        assertEquals(SyncState.SignedOut, repo.state.first())
        assertFalse(repo.isSignedIn.first())
    }

    @Test
    fun push_throwsNotImplementedError() = runTest {
        val snapshot = SyncSnapshot(
            body = BackupBody(emptyList(), emptyList(), emptyList()),
            lastModifiedEpochMillis = 0L,
            deviceId = "test",
            checksum = "00",
        )
        try {
            repo.push(snapshot)
            fail("Expected NotImplementedError")
        } catch (e: NotImplementedError) {
            // expected
        }
    }

    @Test
    fun pull_returnsNoRemoteSnapshot() = runTest {
        assertEquals(PullResult.NoRemoteSnapshot, repo.pull())
    }

    @Test
    fun syncOnce_returnsNoRemoteSnapshot() = runTest {
        assertEquals(SyncResult.NoRemoteSnapshot, repo.syncOnce())
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run:
```bash
export JAVA_HOME=C:/tools/jdk-21.0.5+11 && ./gradlew testDebugUnitTest --tests "*NoOpCloudSyncRepositoryTest*" 2>&1 | tail -15
```
Expected: FAIL with `Unresolved reference: NoOpCloudSyncRepository`.

- [ ] **Step 3: Implement NoOpCloudSyncRepository**

Create `app/src/main/java/io/github/jiro/expensetracker/sync/NoOpCloudSyncRepository.kt`:

```kotlin
package io.github.jiro.expensetracker.sync

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Structural placeholder for 4a. Every method exists so the contract
 * compiles, but no I/O happens. 4b/4c replace this with a real provider
 * via a single Hilt binding swap in [io.github.jiro.expensetracker.di.SyncModule].
 *
 * - signIn()    : flips state to SignedIn("noop") — represents the case
 *                 where the user is "signed in" to a local-only stub for
 *                 development. 4b/4c swap this for real OAuth.
 * - signOut()   : flips state back to SignedOut.
 * - push(...)   : throws — the contract exists, no real backend yet.
 * - pull()      : returns NoRemoteSnapshot — there is no remote.
 * - syncOnce()  : returns NoRemoteSnapshot — pull is the no-op result.
 */
@Singleton
class NoOpCloudSyncRepository @Inject constructor() : CloudSyncRepository {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val _state = MutableStateFlow<SyncState>(SyncState.SignedOut)
    private val _lastSyncedAtEpochMillis = MutableStateFlow<Long?>(null)

    override val state: StateFlow<SyncState> = _state.asStateFlow()
    override val lastSyncedAtEpochMillis: StateFlow<Long?> = _lastSyncedAtEpochMillis.asStateFlow()
    override val isSignedIn: StateFlow<Boolean> = _state
        .map { it is SyncState.SignedIn }
        .stateIn(scope, SharingStarted.Eagerly, false)

    override suspend fun signIn(): SignInResult {
        _state.value = SyncState.SignedIn("noop")
        return SignInResult.Success
    }

    override suspend fun signOut() {
        _state.value = SyncState.SignedOut
    }

    override suspend fun push(snapshot: SyncSnapshot): PushResult {
        throw NotImplementedError("push not available in 4a")
    }

    override suspend fun pull(): PullResult<SyncSnapshot> = PullResult.NoRemoteSnapshot

    override suspend fun syncOnce(): SyncResult = SyncResult.NoRemoteSnapshot
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run:
```bash
export JAVA_HOME=C:/tools/jdk-21.0.5+11 && ./gradlew testDebugUnitTest --tests "*NoOpCloudSyncRepositoryTest*" 2>&1 | tail -20
```
Expected: 8 tests, 0 failures.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/io/github/jiro/expensetracker/sync/NoOpCloudSyncRepository.kt app/src/test/java/io/github/jiro/expensetracker/sync/NoOpCloudSyncRepositoryTest.kt
git -c user.name="MiniMax-M3" -c user.email="291324429+Jiro90-T@users.noreply.github.com" commit -m "feat(sync): NoOpCloudSyncRepository placeholder (Phase 4a)"
```

> **Subagent note:** The original draft of the `isSignedIn` flow used
> a `GlobalScope` + manual `MutableStateFlow` bridge to keep the
> implementation in one file. The committed version replaces that with
> a proper `_state.map { ... }.stateIn(scope, SharingStarted.Eagerly,
> false)` — the test contract (8 tests passing) is identical.

---

### Task 6: SyncModule + full build + smoke test + tag

**Files:**
- Create: `app/src/main/java/io/github/jiro/expensetracker/di/SyncModule.kt`
- Create: `docs/superpowers/testdata/phase-4a-sync-foundation.md`

- [ ] **Step 1: Create SyncModule**

Create `app/src/main/java/io/github/jiro/expensetracker/di/SyncModule.kt`:

```kotlin
package io.github.jiro.expensetracker.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.jiro.expensetracker.sync.CloudSyncRepository
import io.github.jiro.expensetracker.sync.DefaultDeviceIdProvider
import io.github.jiro.expensetracker.sync.DeviceIdProvider
import io.github.jiro.expensetracker.sync.NoOpCloudSyncRepository
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class SyncModule {

    @Binds
    @Singleton
    abstract fun bindCloudSyncRepository(
        impl: NoOpCloudSyncRepository,
    ): CloudSyncRepository

    @Binds
    @Singleton
    abstract fun bindDeviceIdProvider(
        impl: DefaultDeviceIdProvider,
    ): DeviceIdProvider
}
```

- [ ] **Step 2: Run the full unit test suite**

Run:
```bash
export JAVA_HOME=C:/tools/jdk-21.0.5+11 && ./gradlew testDebugUnitTest 2>&1 | tail -20
```
Expected: All existing tests still pass + 20 new tests (2 BackupBody + 7 SyncSnapshotCodec + 3 DeviceIdProvider + 8 NoOpCloudSyncRepository) = `X/Y passing` where the new count is `previous + 20`. If any existing test fails, stop and investigate — 4a must not regress prior phases.

- [ ] **Step 3: Build a debug APK to verify the Hilt graph compiles**

Run:
```bash
export JAVA_HOME=C:/tools/jdk-21.0.5+11 && ./gradlew :app:assembleDebug 2>&1 | tail -10
```
Expected: `BUILD SUCCESSFUL`. A Hilt graph error would surface here.

- [ ] **Step 4: Write the smoke test document**

Create `docs/superpowers/testdata/phase-4a-sync-foundation.md`:

```markdown
# Phase 4a — Cloud Sync Foundation — Smoke Test

## Scope

4a is structural only — no UI, no I/O. There is nothing visible for the
user to do or see. The "smoke test" is therefore a build + test pass,
not an interactive flow.

## Automated verification

```bash
export JAVA_HOME=C:/tools/jdk-21.0.5+11
./gradlew testDebugUnitTest    # 20 new tests, 0 regressions
./gradlew :app:assembleDebug   # debug APK builds, Hilt graph resolves
```

Expected: `BUILD SUCCESSFUL` from both, with `testDebugUnitTest` reporting
`X/Y passing` where `Y = (previous count) + 20`.

## Manual verification

- [ ] Open the app. Nothing should change — the home, transactions,
      statistics, accounts, and settings screens look and behave exactly
      as they did before this phase.
- [ ] The Settings screen does NOT have a "Sign in" or "Sync" entry.
- [ ] Force-stop and reopen the app. Still no change.

## What this phase did NOT add

- No cloud connection of any kind.
- No sign-in flow.
- No sync status indicator.
- No push/pull triggers.
- No data movement.

All of the above is reserved for 4b (Drive), 4c (Dropbox), and 4d (UI +
triggers + manual merge).
```

- [ ] **Step 5: Commit + tag**

```bash
git add app/src/main/java/io/github/jiro/expensetracker/di/SyncModule.kt docs/superpowers/testdata/phase-4a-sync-foundation.md
git -c user.name="MiniMax-M3" -c user.email="291324429+Jiro90-T@users.noreply.github.com" commit -m "feat(sync): Hilt SyncModule + smoke test (Phase 4a)"
git tag v0.18.12
git push origin master v0.18.12
```

Expected: `git tag` shows `v0.18.12`, `git push` reports both refs pushed.

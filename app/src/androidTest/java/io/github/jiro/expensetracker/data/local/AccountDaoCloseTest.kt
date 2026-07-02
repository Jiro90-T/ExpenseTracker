package io.github.jiro.expensetracker.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AccountDaoCloseTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: AccountDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.accountDao()
    }

    @After fun tearDown() { db.close() }

    private fun account(
        id: Long = 0,
        name: String = "Test",
        archived: Boolean = false,
        archivedAt: Long? = null,
    ) = AccountEntity(
        id = id,
        name = name,
        type = "CASH",
        icon = "💵",
        color = 0xFFFFFFFF.toInt(),
        currencyCode = "USD",
        openingBalanceMinor = 0L,
        createdAtEpochMillis = 0L,
        archived = archived,
        archivedAtEpochMillis = archivedAt,
    )

    private suspend fun insert(name: String): Long = dao.insert(account(name = name))

    // ---- close / reopen writes ----

    @Test fun close_setsArchivedAndTimestamp() = runTest {
        val id = insert("Checking")
        dao.close(id, now = 1_700_000_000_000L)
        val row = dao.findById(id)!!
        assertTrue(row.archived)
        assertEquals(1_700_000_000_000L, row.archivedAtEpochMillis)
    }

    @Test fun reopen_clearsArchivedAndTimestamp() = runTest {
        val id = insert("Savings")
        dao.close(id, now = 1_700_000_000_000L)
        dao.reopen(id)
        val row = dao.findById(id)!!
        assertFalse(row.archived)
        assertNull(row.archivedAtEpochMillis)
    }

    @Test fun close_isIdempotent_secondCallOverwritesTimestamp() = runTest {
        val id = insert("Idem")
        dao.close(id, now = 1_000L)
        dao.close(id, now = 2_000L)
        val row = dao.findById(id)!!
        assertTrue(row.archived)
        assertEquals(2_000L, row.archivedAtEpochMillis)
    }

    // ---- observeAllEntities / observeAllBalances ----

    @Test fun observeAllEntities_includesArchivedRows() = runTest {
        val a = insert("Active")
        val c = insert("Closed")
        dao.close(c, now = 1_000L)

        val all = dao.observeAllEntities().first()
        val byName = all.associateBy { it.name }
        assertTrue("active row should be present", "Active" in byName)
        assertTrue("closed row should be present", "Closed" in byName)
        assertTrue(byName["Closed"]!!.archived)
        // Sanity: the active row's archived flag is still false.
        assertFalse(byName["Active"]!!.archived)
        // The first row is the one we inserted first.
        assertEquals(a, byName["Active"]!!.id)
    }

    @Test fun observeAllBalances_returnsRowForEveryAccount() = runTest {
        insert("Active")
        val c = insert("Closed")
        dao.close(c, now = 1_000L)

        val balances = dao.observeAllBalances().first()
        // One row per account (active + closed).
        assertEquals(2, balances.size)
    }

    // ---- listAllOnce ----

    @Test fun listAllOnce_returnsEveryRow() = runTest {
        insert("A")
        insert("B")
        val c = insert("C")
        dao.close(c, now = 1_000L)
        val all = dao.listAllOnce()
        assertEquals(3, all.size)
    }

    // ---- findActiveDefault ----

    @Test fun findActiveDefault_returnsLowestIdActive() = runTest {
        val first = insert("First")
        insert("Second")
        val third = insert("Third")
        // First is the lowest-id active row.
        val def = dao.findActiveDefault()
        assertNotNull(def)
        assertEquals(first, def!!.id)
    }

    @Test fun findActiveDefault_skipsArchivedRows() = runTest {
        val first = insert("First")
        insert("Second")
        dao.close(first, now = 1_000L)
        val def = dao.findActiveDefault()
        assertNotNull(def)
        assertEquals("Second", def!!.name)
    }

    @Test fun findActiveDefault_returnsNullWhenAllArchived() = runTest {
        val a = insert("A")
        dao.close(a, now = 1_000L)
        assertNull(dao.findActiveDefault())
    }
}
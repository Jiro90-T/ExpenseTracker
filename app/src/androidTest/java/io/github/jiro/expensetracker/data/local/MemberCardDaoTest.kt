package io.github.jiro.expensetracker.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MemberCardDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: MemberCardDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.memberCardDao()
    }

    @After
    fun tearDown() { db.close() }

    private fun card(
        name: String,
        id: Long = 0,
        createdAt: Long = 0L,
    ) = MemberCardEntity(
        id = id,
        name = name,
        imagePath = "$name.jpg",
        createdAtEpochMillis = createdAt,
    )

    @Test fun insert_thenFindById_returnsRow() = runTest {
        val id = dao.insert(card("Cashback Card"))
        val found = dao.findById(id)
        assertNotNull(found)
        assertEquals("Cashback Card", found!!.name)
        assertEquals("Cashback Card.jpg", found.imagePath)
    }

    @Test fun insert_duplicateName_allowed() = runTest {
        dao.insert(card("Same Name"))
        val second = dao.insert(card("Same Name"))
        assertNotNull(dao.findById(second))
    }

    @Test fun observeAll_ordersByNameAscending_caseInsensitive() = runTest {
        dao.insert(card("banana", createdAt = 1))
        dao.insert(card("Apple", createdAt = 2))
        dao.insert(card("cherry", createdAt = 3))
        val all = dao.observeAll().first()
        assertEquals(listOf("Apple", "banana", "cherry"), all.map { it.name })
    }

    @Test fun searchByName_matchesSubstring_caseInsensitive() = runTest {
        dao.insert(card("BPI Rewards"))
        dao.insert(card("SM Advantage"))
        dao.insert(card("Shell Fleet"))
        dao.insert(card("BPI Savings"))

        val bpis = dao.searchByName("bpi").first()
        assertEquals(setOf("BPI Rewards", "BPI Savings"), bpis.map { it.name }.toSet())

        val adv = dao.searchByName("ADV").first()
        assertEquals(listOf("SM Advantage"), adv.map { it.name })
    }

    @Test fun deleteById_removesRow() = runTest {
        val id = dao.insert(card("BPI Rewards"))
        dao.deleteById(id)
        assertNull(dao.findById(id))
    }

    @Test fun update_changesAllFields() = runTest {
        val id = dao.insert(
            MemberCardEntity(
                name = "Old Name",
                imagePath = "old.jpg",
                memberIdText = "OLD-1",
                colorHex = 0xFF112233.toInt(),
                icon = "💳",
                expiresAtEpochMillis = 1_000_000L,
                notes = "old notes",
                createdAtEpochMillis = 0L,
            )
        )
        dao.update(
            MemberCardEntity(
                id = id,
                name = "New Name",
                imagePath = "new.jpg",
                memberIdText = "NEW-1",
                colorHex = 0xFFAABBCC.toInt(),
                icon = "🏦",
                expiresAtEpochMillis = 2_000_000L,
                notes = "new notes",
                createdAtEpochMillis = 0L,
                sortOrder = 7,
            )
        )
        val found = dao.findById(id)
        assertNotNull(found)
        assertEquals("New Name", found!!.name)
        assertEquals("new.jpg", found.imagePath)
        assertEquals("NEW-1", found.memberIdText)
        assertEquals(0xFFAABBCC.toInt(), found.colorHex)
        assertEquals("🏦", found.icon)
        assertEquals(2_000_000L, found.expiresAtEpochMillis)
        assertEquals("new notes", found.notes)
        assertEquals(7, found.sortOrder)
    }
}
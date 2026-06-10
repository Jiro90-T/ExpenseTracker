package io.github.jiro.expensetracker.data.repository

import io.github.jiro.expensetracker.data.local.BudgetDao
import io.github.jiro.expensetracker.data.local.BudgetEntity
import java.util.Calendar
import java.util.TimeZone
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

/**
 * Repository for monthly budgets. `open` so a test fake can subclass it (the
 * BudgetViewModel test is a follow-up; the production code paths don't rely
 * on it being subclassable).
 */
@Singleton
open class BudgetRepository @Inject constructor(
    private val dao: BudgetDao,
) {
    fun observeByMonth(monthStart: Long): Flow<List<BudgetEntity>> = dao.observeByMonth(monthStart)

    suspend fun upsert(budget: BudgetEntity) = dao.upsert(budget)

    suspend fun deleteByKey(categoryId: Long, monthStart: Long): Int =
        dao.deleteByKey(categoryId, monthStart)

    companion object {
        /**
         * Local-time midnight on the 1st of [now]'s month. This is the bucket
         * key used to read/write budget rows; everywhere in the app that
         * needs a "month" should call this.
         *
         * `now` defaults to wall-clock now in production. The time-zone
         * defaults to the device's current default — production callers
         * should pass nothing.
         */
        fun currentMonthStart(
            now: Long = System.currentTimeMillis(),
            timeZone: TimeZone = TimeZone.getDefault(),
        ): Long {
            val cal = Calendar.getInstance(timeZone)
            cal.timeInMillis = now
            cal.set(Calendar.DAY_OF_MONTH, 1)
            cal.set(Calendar.HOUR_OF_DAY, 0)
            cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            return cal.timeInMillis
        }

        /**
         * The first instant of the month that follows [monthStart]. Use this
         * to derive a [LongRange] for the month:
         * `monthStart until BudgetRepository.nextMonthStart(monthStart)`.
         */
        fun nextMonthStart(
            monthStart: Long,
            timeZone: TimeZone = TimeZone.getDefault(),
        ): Long {
            val cal = Calendar.getInstance(timeZone)
            cal.timeInMillis = monthStart
            cal.add(Calendar.MONTH, 1)
            return cal.timeInMillis
        }
    }
}

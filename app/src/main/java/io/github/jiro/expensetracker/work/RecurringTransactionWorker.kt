package io.github.jiro.expensetracker.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import io.github.jiro.expensetracker.data.RecurrenceKind
import io.github.jiro.expensetracker.data.isSeriesExhausted
import io.github.jiro.expensetracker.data.local.AppDatabase
import io.github.jiro.expensetracker.data.local.TransactionEntity
import io.github.jiro.expensetracker.data.nextOccurrence
import io.github.jiro.expensetracker.data.repository.TransactionRepository

/**
 * Materialises due recurring-transaction instances.
 *
 * Runs daily (scheduled from [io.github.jiro.expensetracker.ExpenseTrackerApp]).
 * For every parent row whose `recurrenceNextAt <= now`, clones it as a
 * new instance and advances the parent's `recurrenceNextAt` — or nulls it
 * if the end condition (date or max-occurrences) is met.
 *
 * The worker keeps no in-memory state; it queries the DB each run, so it's
 * safe for WorkManager to re-run us on retries or after device reboots.
 */
@HiltWorker
class RecurringTransactionWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val database: AppDatabase,
    private val transactionRepository: TransactionRepository,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val nowMs = System.currentTimeMillis()
        val parents = database.transactionDao().dueRecurringParents(nowMs)
        if (parents.isEmpty()) return Result.success()

        for (parent in parents) {
            materialiseOne(parent, nowMs)
        }
        return Result.success()
    }

    private suspend fun materialiseOne(parent: TransactionEntity, nowMs: Long) {
        val kind = RecurrenceKind.fromStorage(parent.recurrenceKind) ?: return
        val interval = parent.recurrenceInterval.coerceAtLeast(1)
        val seriesId = parent.recurringGroupId ?: return

        // 1. Insert a new instance. Same data as the parent at the time of
        //    materialisation; recurrenceNextAt is null because the instance
        //    is not itself a parent.
        val instance = parent.copy(
            id = 0L,  // Room auto-generates
            occurredAtEpochMillis = parent.recurrenceNextAt ?: nowMs,
            recurrenceNextAt = null,
        )
        database.transactionDao().insert(instance)

        // 2. Decide whether the series is exhausted, or advance the parent.
        val materialisedSoFar = database.transactionDao().countByRecurringGroup(seriesId)
        val candidateNext = nextOccurrence(kind, interval, parent.recurrenceNextAt ?: nowMs)
        val exhausted = isSeriesExhausted(
            nextMs = candidateNext,
            endAtMs = parent.recurrenceEndAt,
            maxOccurrences = parent.recurrenceMaxOccurrences,
            materialisedSoFar = materialisedSoFar,
        )

        val updatedParent = parent.copy(
            recurrenceNextAt = if (exhausted) null else candidateNext,
        )
        database.transactionDao().update(updatedParent)
    }
}

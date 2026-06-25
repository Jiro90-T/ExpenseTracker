package io.github.jiro.expensetracker.data.repository

import io.github.jiro.expensetracker.data.local.TransactionDao
import io.github.jiro.expensetracker.data.local.TransactionEntity
import io.github.jiro.expensetracker.data.local.TransactionWithCategory
import io.github.jiro.expensetracker.data.repository.ReceiptRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * Boundary between ViewModels and the local DB. Future sync / backup layers plug in here,
 * not in the ViewModels.
 */
@Singleton
open class TransactionRepository @Inject constructor(
    private val dao: TransactionDao,
    private val receiptRepository: ReceiptRepository,
) {
    fun observeAll(): Flow<List<TransactionWithCategory>> = dao.observeAllWithCategory()

    fun observeInRange(startMs: Long, endMs: Long): Flow<List<TransactionWithCategory>> =
        dao.observeInRangeWithCategory(startMs, endMs)

    suspend fun findById(id: Long): TransactionEntity? = dao.findById(id)

    suspend fun countForAccount(accountId: Long): Int =
        dao.countForAccount(accountId)

    open suspend fun add(transaction: TransactionEntity): Long = dao.insert(transaction)

    suspend fun update(transaction: TransactionEntity) = dao.update(transaction)

    suspend fun delete(transaction: TransactionEntity) = withContext(Dispatchers.IO) {
        val receiptPath = transaction.receiptPath
        dao.delete(transaction)
        if (!receiptPath.isNullOrBlank()) {
            receiptRepository.delete(receiptPath)
        }
    }

    /**
     * Re-insert a previously deleted transaction, preserving its original id.
     * Used by the swipe-to-delete undo flow.
     */
    suspend fun restore(transaction: TransactionEntity): Long = dao.restore(transaction)

    /** All rows in a recurring series (parent + materialised instances). */
    fun observeGroup(groupId: String): Flow<List<TransactionWithCategory>> =
        dao.observeByRecurringGroup(groupId)

    fun observeByAccount(accountId: Long): Flow<List<TransactionWithCategory>> =
        dao.observeByAccount(accountId)
}

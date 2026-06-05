package io.github.yourusername.expensetracker.data.repository

import io.github.yourusername.expensetracker.data.local.TransactionDao
import io.github.yourusername.expensetracker.data.local.TransactionEntity
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

/**
 * Boundary between ViewModels and the local DB. Future sync / backup layers plug in here,
 * not in the ViewModels.
 */
@Singleton
class TransactionRepository @Inject constructor(
    private val dao: TransactionDao,
) {
    fun observeAll(): Flow<List<TransactionEntity>> = dao.observeAll()

    suspend fun add(transaction: TransactionEntity): Long = dao.insert(transaction)

    suspend fun delete(transaction: TransactionEntity) = dao.delete(transaction)
}

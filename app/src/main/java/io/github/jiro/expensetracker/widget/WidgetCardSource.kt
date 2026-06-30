package io.github.jiro.expensetracker.widget

import android.content.Context
import io.github.jiro.expensetracker.data.repository.MemberCardRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

/**
 * Loads the widget's card list on demand. Wraps the existing repository so
 * the Glance composable doesn't need a flow/subscriber.
 */
@Singleton
class WidgetCardSource @Inject constructor(
    private val repository: MemberCardRepository,
) {
    suspend fun loadAll(context: Context): List<WidgetCard> {
        val cardsDir = java.io.File(context.filesDir, "cards")
        return repository.observeAll().first().map { entity ->
            val exists = entity.imagePath.isNotBlank() &&
                java.io.File(cardsDir, entity.imagePath).isFile
            WidgetCard.from(entity, imageExists = exists)
        }
    }
}

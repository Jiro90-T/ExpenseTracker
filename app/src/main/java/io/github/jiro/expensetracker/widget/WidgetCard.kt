package io.github.jiro.expensetracker.widget

import io.github.jiro.expensetracker.data.local.MemberCardEntity
import java.time.LocalDate
import java.time.ZoneId

/**
 * Subset of [MemberCardEntity] the widget renders. Strips [notes]; flags
 * a missing image file so the Glance composable can render a placeholder
 * instead of trying to decode a non-existent file.
 */
data class WidgetCard(
    val id: Long,
    val name: String,
    val imagePath: String?,          // null iff imageMissing == true
    val imageMissing: Boolean,
    val expiresAtEpochMillis: Long?,
    val memberIdText: String?,
    val isExpired: Boolean,
    val notes: String? = null,
) {
    companion object {
        fun from(entity: MemberCardEntity, imageExists: Boolean): WidgetCard {
            val missing = entity.imagePath.isBlank() || !imageExists
            return WidgetCard(
                id = entity.id,
                name = entity.name,
                imagePath = if (missing) null else entity.imagePath,
                imageMissing = missing,
                expiresAtEpochMillis = entity.expiresAtEpochMillis,
                memberIdText = entity.memberIdText,
                isExpired = entity.expiresAtEpochMillis?.let { ms ->
                    ms < LocalDate.now().atStartOfDay(ZoneId.systemDefault())
                        .toInstant().toEpochMilli()
                } ?: false,
                notes = null,
            )
        }
    }
}

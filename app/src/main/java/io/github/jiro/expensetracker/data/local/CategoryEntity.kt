package io.github.jiro.expensetracker.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A user- or built-in category that transactions are bucketed into.
 *
 * The (name, type) unique index lets the same name exist for both EXPENSE
 * and INCOME (e.g. "Other" / "Other") without colliding.
 */
@Entity(
    tableName = "categories",
    indices = [Index(value = ["name", "type"], unique = true)],
)
data class CategoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val name: String,
    val type: String,
    val sortOrder: Int = 0,
    val isBuiltIn: Boolean = false,
)

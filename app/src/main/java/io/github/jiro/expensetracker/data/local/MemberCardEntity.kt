package io.github.jiro.expensetracker.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One loyalty / membership card. The photo file lives at
 * `<filesDir>/cards/$imagePath`; everything else is metadata.
 *
 * Fields mirror the spec at docs/superpowers/specs/2026-06-28-member-cards-design.md.
 */
@Entity(tableName = "member_cards")
data class MemberCardEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** Filename under `<filesDir>/cards/` (e.g. `${UUID}.jpg`). */
    val imagePath: String,
    val memberIdText: String? = null,
    /** ARGB color (e.g. `0xFF1976D2.toInt()`); null when "Auto". */
    val colorHex: Int? = null,
    /** Emoji glyph (e.g. "💳"); null when "Auto". */
    val icon: String? = null,
    val expiresAtEpochMillis: Long? = null,
    val notes: String? = null,
    val createdAtEpochMillis: Long,
    /** Reserved for Phase B manual reorder; not exposed in UI yet. */
    val sortOrder: Int = 0,
)

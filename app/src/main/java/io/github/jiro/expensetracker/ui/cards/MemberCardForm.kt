package io.github.jiro.expensetracker.ui.cards

/**
 * Form-state for a single member card. Used by [MemberCardEditViewModel]
 * to track edits and to capture a baseline snapshot for the discard-changes
 * detection. Also consumed by [MemberCardRepository] as the persistence
 * input shape.
 *
 * The image is **not** on this form — the source URI (or new-file path) is
 * passed separately to the repository, to avoid two fields tracking the
 * same value.
 */
data class MemberCardForm(
    val name: String,
    val memberIdText: String? = null,
    val colorHex: Int? = null,
    val icon: String? = null,
    val expiresAtEpochMillis: Long? = null,
    val notes: String? = null,
) {
    /** Compare two forms ignoring name-case differences (callers may want
     *  case-insensitive dirty detection — left to the VM, not enforced here). */
    fun contentEquals(other: MemberCardForm): Boolean =
        name == other.name &&
            memberIdText == other.memberIdText &&
            colorHex == other.colorHex &&
            icon == other.icon &&
            expiresAtEpochMillis == other.expiresAtEpochMillis &&
            notes == other.notes
}
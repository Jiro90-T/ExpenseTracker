package io.github.jiro.expensetracker.sync

enum class SyncProviderId(val displayKey: String) {
    DROPBOX("dropbox"),
    GOOGLE_DRIVE("google_drive"),
    ;

    companion object {
        fun fromKey(key: String?): SyncProviderId =
            entries.firstOrNull { it.displayKey == key } ?: DROPBOX
    }
}
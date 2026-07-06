package io.github.jiro.expensetracker.ui.settings

import android.app.Application

/**
 * Robolectric-only Application that bypasses the real Hilt-backed
 * [io.github.jiro.expensetracker.ExpenseTrackerApp] (whose graph eagerly
 * constructs the DropboxCloudSyncRepository, which then fails at
 * construction time when DROPBOX_CLIENT_ID is empty). Compose tests only
 * need a Context with resources loaded, so a plain Application is fine.
 */
class TestApp : Application()
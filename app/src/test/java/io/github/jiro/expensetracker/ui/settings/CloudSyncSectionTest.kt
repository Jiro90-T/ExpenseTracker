package io.github.jiro.expensetracker.ui.settings

import android.app.Application
import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import io.github.jiro.expensetracker.sync.CloudSyncSessionState
import io.github.jiro.expensetracker.sync.SyncProviderId
import io.github.jiro.expensetracker.sync.SyncState
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.Shadows

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = TestApp::class)
class CloudSyncSectionTest {

    @get:Rule(order = 1)
    val addActivityRule = object : TestWatcher() {
        override fun starting(description: Description) {
            super.starting(description)
            val app: Application = ApplicationProvider.getApplicationContext()
            Shadows.shadowOf(app.packageManager).addActivityIfNotPresent(
                ComponentName(app.packageName, ComponentActivity::class.java.name),
            )
        }
    }

    @get:Rule(order = 2) val composeTestRule = createComposeRule()

    @Test
    fun signedOut_showsSignInButton() {
        val session = mutableStateOf(CloudSyncSessionState(
            providerId = SyncProviderId.DROPBOX,
            state = SyncState.SignedOut,
            lastSyncedAtEpochMillis = null,
            accountEmail = null,
            conflictPending = false,
        ))
        var signInCalled = false
        composeTestRule.setContent {
            MaterialTheme {
                CloudSyncSection(
                    session = session.value,
                    dropboxConfigured = true,
                    googleDriveConfigured = true,
                    onProviderSelected = {},
                    onSignInClick = { signInCalled = true },
                    onSignOutClick = {},
                    onSyncNowClick = {},
                    onConflictClick = {},
                )
            }
        }
        composeTestRule.onNodeWithText("Sign in", useUnmergedTree = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("Sign in", useUnmergedTree = true).performClick()
        org.junit.Assert.assertTrue(signInCalled)
    }

    @Test
    fun conflictPending_showsBanner() {
        composeTestRule.setContent {
            MaterialTheme {
                CloudSyncSection(
                    session = CloudSyncSessionState(
                        providerId = SyncProviderId.DROPBOX,
                        state = SyncState.SignedIn("dropbox"),
                        lastSyncedAtEpochMillis = 1L,
                        accountEmail = "user@example.com",
                        conflictPending = true,
                    ),
                    dropboxConfigured = true,
                    googleDriveConfigured = true,
                    onProviderSelected = {},
                    onSignInClick = {},
                    onSignOutClick = {},
                    onSyncNowClick = {},
                    onConflictClick = {},
                )
            }
        }
        composeTestRule.onNodeWithText(
            "Conflict — sync tie. Tap to resolve.",
            useUnmergedTree = true,
        ).assertIsDisplayed()
    }
}
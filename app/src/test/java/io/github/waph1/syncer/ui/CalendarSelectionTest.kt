package io.github.waph1.syncer.ui

import android.Manifest
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.waph1.syncer.MainActivity
import io.github.waph1.syncer.appContainer
import io.github.waph1.syncer.settings.AppSettings
import io.github.waph1.syncer.settings.FolderTarget
import io.github.waph1.syncer.testing.FakeCalendarProvider
import io.github.waph1.syncer.testing.FakeDocumentsProvider
import io.github.waph1.syncer.testing.TestSyncerApp
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = TestSyncerApp::class, qualifiers = "w411dp-h891dp")
class CalendarSelectionTest {
    @get:Rule(order = 0)
    val tmp = TemporaryFolder()

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<MainActivity>()

    private val context get() = compose.activity.applicationContext

    @Before
    fun setUp() {
        FakeDocumentsProvider.root = tmp.newFolder("storage")
        Robolectric.setupContentProvider(FakeDocumentsProvider::class.java, FakeDocumentsProvider.AUTHORITY)
        Robolectric.setupContentProvider(FakeCalendarProvider::class.java, FakeCalendarProvider.AUTHORITY)
        shadowOf(compose.activity.application).grantPermissions(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
        FakeCalendarProvider.tables.clear()
        FakeCalendarProvider.tables["calendars"] = listOf(
            mapOf("_id" to 1L, "calendar_displayName" to "Lavoro", "account_name" to "me@gmail.com", "sync_events" to 1, "_sync_id" to "work@group", "visible" to 1),
            mapOf("_id" to 2L, "calendar_displayName" to "Festivita", "account_name" to "me@gmail.com", "sync_events" to 0, "_sync_id" to "holidays@group", "visible" to 0),
        )
        FakeCalendarProvider.tables["events"] = listOf(
            mapOf("_id" to 10L, "calendar_id" to 1L, "dtstart" to 0L, "deleted" to 0),
            mapOf("_id" to 11L, "calendar_id" to 1L, "dtstart" to 0L, "deleted" to 0),
        )
    }

    @Test
    fun calendarsShowTheirDeviceStateAndCanBeExcludedOrSynced() {
        val container = context.appContainer
        container.settings.replace(
            AppSettings(
                setupCompleted = true,
                accountName = "me@gmail.com",
                calendar = FolderTarget(true, FakeDocumentsProvider.treeUri("cal").toString()),
            ),
        )
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Impostazioni").performClick()

        compose.onNodeWithText("Calendari da esportare").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Sincronizzato · 2 eventi sul telefono").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Sincronizzazione Android disattivata · 0 eventi sul telefono · nascosto").performScrollTo().assertIsDisplayed()

        // Unticking a calendar excludes it from the export (saved by its Google calendar id).
        compose.onNodeWithText("Lavoro").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(setOf("work@group"), container.settings.current.excludedCalendars)

        // "Attiva sincronizzazione" turns on Android's sync for that calendar.
        compose.onNodeWithText("Attiva sincronizzazione").performScrollTo().performClick()
        compose.waitUntil(5_000) { FakeCalendarProvider.tables["calendars"]!!.any { it["_id"] == 2L && it["sync_events"] == 1 } }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Sincronizzato · 0 eventi sul telefono · nascosto").fetchSemanticsNodes().size == 1
        }
    }
}

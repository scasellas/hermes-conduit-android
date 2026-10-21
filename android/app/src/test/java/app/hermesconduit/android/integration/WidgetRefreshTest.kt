package app.hermesconduit.android.integration

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.work.Configuration
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.hermesconduit.android.MainActivity
import app.hermesconduit.android.integration.Integration.app
import app.hermesconduit.android.integration.Integration.pump
import app.hermesconduit.android.widget.CharacterWidgetReceiver
import app.hermesconduit.android.widget.NativeWidgetReceiver
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/**
 * The Glance launcher widgets: placed, they render through WorkManager into
 * real RemoteViews; loading the chat list (as opening Conduit signed in does)
 * refreshes both to the latest chat, even while their first session still runs.
 */
@RunWith(AndroidJUnit4::class)
class WidgetRefreshTest {
    private val fake = FakeHermes()
    private val manager: AppWidgetManager get() = AppWidgetManager.getInstance(app)

    @Before fun setUp() {
        fake.start()
        // Glance renders in a WorkManager session worker; the test WorkManager runs it at once
        // (Robolectric has no JobScheduler to run the real one's work).
        WorkManagerTestInitHelper.initializeTestWorkManager(app, Configuration.Builder().setExecutor(SynchronousExecutor()).build())
        Integration.signIn(fake)
    }

    @After fun tearDown() {
        Integration.tearDown()
        fake.close()
    }

    private fun texts(view: View?): List<String> = when (view) {
        null -> emptyList()
        is TextView -> listOf(view.text.toString())
        is ViewGroup -> (0 until view.childCount).flatMap { texts(view.getChildAt(it)) }
        else -> emptyList()
    }

    private fun widgetTexts(id: Int) = runCatching { texts(shadowOf(manager).getViewFor(id)) }.getOrDefault(emptyList())

    /** Adds a widget the way the launcher does: bind its id (with res/xml's provider info), then the system's APPWIDGET_UPDATE to its receiver. */
    private fun place(id: Int, receiver: Class<*>) {
        val provider = ComponentName(app, receiver)
        val density = app.resources.displayMetrics.density
        shadowOf(manager).addBoundWidget(
            id,
            AppWidgetProviderInfo().apply {
                this.provider = provider
                minWidth = (250 * density).toInt()
                minHeight = (110 * density).toInt()
                resizeMode = AppWidgetProviderInfo.RESIZE_BOTH
                widgetCategory = AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN
            },
        )
        app.sendBroadcast(
            Intent(AppWidgetManager.ACTION_APPWIDGET_UPDATE).setComponent(provider)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, intArrayOf(id)),
        )
    }

    @Test
    fun refreshRendersBothWidgetsWithTheLatestChat() {
        place(41, NativeWidgetReceiver::class.java)
        place(42, CharacterWidgetReceiver::class.java)
        // Placed before any chat is known: the widgets offer to open Conduit.
        pump(timeoutMs = 20_000, message = "placed widgets", diagnostics = { "widgets show ${widgetTexts(41)} and ${widgetTexts(42)}" }) {
            "Open Conduit" in widgetTexts(41) && "Open Conduit" in widgetTexts(42)
        }

        // Loading the chat list after sign-in caches it and refreshes the widgets.
        ActivityScenario.launch(MainActivity::class.java).use {
            pump(timeoutMs = 20_000, message = "native widget", diagnostics = { "widgets show ${widgetTexts(41)} and ${widgetTexts(42)}" }) { "First chat" in widgetTexts(41) }
            pump(timeoutMs = 20_000, message = "character widget", diagnostics = { "widget shows ${widgetTexts(42)}" }) { "First chat" in widgetTexts(42) }
        }
    }
}

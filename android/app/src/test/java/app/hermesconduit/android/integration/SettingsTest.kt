package app.hermesconduit.android.integration

import android.content.ComponentName
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.printToString
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.hermesconduit.android.MainActivity
import app.hermesconduit.android.data.AppSettings
import app.hermesconduit.android.integration.Integration.app
import app.hermesconduit.android.integration.Integration.exists
import app.hermesconduit.android.integration.Integration.pump
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Settings > Appearance on the real framework: language, theme and the launcher icon alias. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w900dp-h1000dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val fake = FakeHermes()

    @Before fun setUp() {
        fake.start()
        Integration.signIn(fake)
    }

    @After fun tearDown() {
        Integration.tearDown()
        fake.close()
    }

    private fun waitForText(text: String) =
        pump(message = "\"$text\" on screen", diagnostics = { compose.onAllNodes(isRoot()).printToString(Int.MAX_VALUE) }) {
            compose.onAllNodes(hasText(text)).exists()
        }

    private fun openSettings() {
        waitForText("First chat")
        compose.onNodeWithText("Settings").performScrollTo().performClick()
        waitForText("Language")
    }

    private fun pick(row: String, choice: String) {
        compose.onNodeWithText(row).performScrollTo().performClick()
        val option = hasText(choice) and hasAnyAncestor(isDialog())
        pump(message = "\"$choice\" offered") { compose.onAllNodes(option).exists() }
        compose.onNode(option).performClick()
    }

    /** How light the screen's background is (0 black … 1 white): the window drawn in software, sampled at a corner. */
    private fun backgroundLightness(scenario: ActivityScenario<MainActivity>): Float {
        compose.waitForIdle()
        var lightness = -1f
        scenario.onActivity { activity ->
            val view = activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            val pixel = bitmap.getPixel(bitmap.width - 4, bitmap.height - 4)
            lightness = (Color.red(pixel) + Color.green(pixel) + Color.blue(pixel)) / (3f * 255f)
        }
        return lightness
    }

    @Test
    fun languageSwitchChangesVisibleStrings() {
        ActivityScenario.launch(MainActivity::class.java).use {
            openSettings()
            pick("Language", "Deutsch")
            waitForText("Sprache")
            assertTrue(compose.onAllNodes(hasText("ERSCHEINUNGSBILD")).exists())
            assertTrue(compose.onAllNodes(hasText("Einstellungen")).exists())
            assertEquals("de", app.settings.getString(AppSettings.LANGUAGE, "system"))
        }
    }

    @Test
    fun themeSwitchRecolorsTheApp() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            openSettings()
            pick("Theme", "Dark")
            pump(message = "dark theme", diagnostics = { "lightness ${backgroundLightness(scenario)}" }) { backgroundLightness(scenario) < 0.3f }
            pick("Theme", "Light")
            pump(message = "light theme", diagnostics = { "lightness ${backgroundLightness(scenario)}" }) { backgroundLightness(scenario) > 0.7f }
            assertEquals("light", app.settings.getString(AppSettings.THEME, "system"))
        }
    }

    @Test
    fun appIconSwitchTogglesTheLauncherAlias() {
        val pm = app.packageManager
        val dark = ComponentName(app, "${app.packageName}.DarkIcon")
        val light = ComponentName(app, "${app.packageName}.LightIcon")
        fun state(component: ComponentName) = pm.getComponentEnabledSetting(component)
        ActivityScenario.launch(MainActivity::class.java).use {
            openSettings()
            pick("App icon", "Light")
            pump(message = "light icon enabled") { state(light) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED }
            assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DISABLED, state(dark))

            pick("App icon", "Dark")
            pump(message = "dark icon enabled") { state(dark) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED }
            assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DISABLED, state(light))
            assertEquals("dark", app.settings.getString(AppSettings.APP_ICON, "dark"))
        }
    }
}

package app.hermesconduit.android

import app.hermesconduit.android.model.ConduitJson
import app.hermesconduit.android.model.ProfileConfig
import app.hermesconduit.android.model.SettingValue
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProfileConfigTest {
    private val config = ConduitJson.parseToJsonElement(
        """{"display":{"show_reasoning":false,"tool_progress":"all"},"compression":{"threshold":0.8},"timezone":"UTC"}""",
    ) as JsonObject

    @Test
    fun readsDottedKeys() {
        assertEquals(SettingValue.Bool(false), ProfileConfig.valueAt(config, "display.show_reasoning"))
        assertEquals(SettingValue.Text("all"), ProfileConfig.valueAt(config, "display.tool_progress"))
        assertEquals(SettingValue.Number(0.8), ProfileConfig.valueAt(config, "compression.threshold"))
        assertNull(ProfileConfig.valueAt(config, "memory.provider"))
    }

    @Test
    fun writesKeepSiblingsAndCreateParents() {
        val updated = ProfileConfig.withValue(ProfileConfig.withValue(config, "display.expand_tools", SettingValue.Bool(true)), "memory.provider", SettingValue.Text("honcho"))
        assertEquals(SettingValue.Text("all"), ProfileConfig.valueAt(updated, "display.tool_progress"))
        assertEquals(SettingValue.Bool(true), ProfileConfig.valueAt(updated, "display.expand_tools"))
        assertEquals(SettingValue.Text("honcho"), ProfileConfig.valueAt(updated, "memory.provider"))
        assertEquals("UTC", ProfileConfig.valueAt(updated, "timezone")?.text)
    }
}

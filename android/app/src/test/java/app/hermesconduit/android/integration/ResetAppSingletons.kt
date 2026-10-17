package app.hermesconduit.android.integration

import org.robolectric.pluginapi.TestEnvironmentLifecyclePlugin

/**
 * Robolectric reuses one class loader across tests, so the app's per-process
 * singletons would outlive the Application they were built for. This forgets
 * them before each test's Application is created.
 */
class ResetAppSingletons : TestEnvironmentLifecyclePlugin {
    override fun onSetupApplicationState() {
        val loader = Thread.currentThread().contextClassLoader ?: javaClass.classLoader
        SINGLETONS.forEach { (name, field) ->
            val type = runCatching { Class.forName("app.hermesconduit.android.$name", false, loader) }.getOrNull() ?: return@forEach
            runCatching {
                val f = type.getDeclaredField(field).apply { isAccessible = true }
                if (f.type == Boolean::class.javaPrimitiveType) f.setBoolean(null, false) else f.set(null, null)
            }
        }
        runCatching {
            Class.forName("app.hermesconduit.android.ConduitApplicationKt", false, loader)
                .getDeclaredField("fallbackSettings").apply { isAccessible = true }.set(null, null)
        }
    }

    private companion object {
        val SINGLETONS = listOf(
            "watch.WatchPhoneCallLog" to "shared",
            "watch.WatchBridge" to "shared",
            "watch.WatchDirectBroker" to "shared",
            "ui.gateway.GatewayRestartController" to "instance",
            "ui.voice.VoiceHub" to "instance",
            "ui.location.LocationSharing" to "instance",
            "ui.plugin.PluginStatusRepository" to "instance",
            "ui.health.HealthSync" to "instance",
            "ui.kanban.KanbanRuntime" to "instance",
            "ui.device.VoiceHostTools" to "instance",
            "ui.phone.PhoneSync" to "instance",
            "calls.HermesCalls" to "instance",
            "wake.WakePhrase" to "instance",
            "core.ProfileAppearance" to "instance",
            "ui.device.DeviceSharing" to "started",
        )
    }
}

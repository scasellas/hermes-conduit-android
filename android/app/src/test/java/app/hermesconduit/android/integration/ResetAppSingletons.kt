package app.hermesconduit.android.integration

import org.robolectric.pluginapi.TestEnvironmentLifecyclePlugin

/**
 * Robolectric reuses one class loader across a class's tests, so per-process
 * singletons would outlive the Application they were built for (a second
 * MainActivity would get a view model bound to the first test's connection).
 * This forgets them before each test's Application is created.
 */
class ResetAppSingletons : TestEnvironmentLifecyclePlugin {
    override fun onSetupApplicationState() {
        val loader = Thread.currentThread().contextClassLoader ?: javaClass.classLoader
        SINGLETONS.forEach { (name, field) ->
            val type = runCatching { Class.forName(name, false, loader) }.getOrNull() ?: return@forEach
            runCatching {
                val f = type.getDeclaredField(field).apply { isAccessible = true }
                if (f.type == Boolean::class.javaPrimitiveType) f.setBoolean(null, false) else f.set(null, null)
            }
        }
    }

    private companion object {
        const val APP = "app.hermesconduit.android"
        val SINGLETONS = listOf(
            "$APP.ConduitApplicationKt" to "fallbackSettings",
            "$APP.watch.WatchPhoneCallLog" to "shared",
            "$APP.watch.WatchBridge" to "shared",
            "$APP.watch.WatchDirectBroker" to "shared",
            "$APP.ui.gateway.GatewayRestartController" to "instance",
            "$APP.ui.voice.VoiceHub" to "instance",
            "$APP.ui.location.LocationSharing" to "instance",
            "$APP.ui.plugin.PluginStatusRepository" to "instance",
            "$APP.ui.health.HealthSync" to "instance",
            "$APP.ui.kanban.KanbanRuntime" to "instance",
            "$APP.ui.device.VoiceHostTools" to "instance",
            "$APP.ui.phone.PhoneSync" to "instance",
            "$APP.ui.phone.ConduitAlarms" to "instance",
            "$APP.calls.HermesCalls" to "instance",
            "$APP.wake.WakePhrase" to "instance",
            "$APP.core.ProfileAppearance" to "instance",
            "$APP.ui.device.DeviceSharing" to "started",
            // The default view model factory keeps the first Application it saw.
            "androidx.lifecycle.ViewModelProvider\$AndroidViewModelFactory" to "_instance",
        )
    }
}

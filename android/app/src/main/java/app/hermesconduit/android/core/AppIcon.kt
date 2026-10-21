package app.hermesconduit.android.core

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/** Switches the launcher icon between the dark (default) and light artwork. */
object AppIcon {
    fun apply(context: Context, choice: String) {
        val pm = context.packageManager
        val dark = ComponentName(context, "${context.packageName}.DarkIcon")
        val light = ComponentName(context, "${context.packageName}.LightIcon")
        val (on, off) = if (choice == "light") light to dark else dark to light
        pm.setComponentEnabledSetting(on, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
        pm.setComponentEnabledSetting(off, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
    }
}

package app.hermesconduit.android.ui

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.service.quicksettings.TileService
import app.hermesconduit.android.MainActivity

/** Quick Settings tile: open Conduit to a new chat. */
class AskHermesTileService : TileService() {
    override fun onClick() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("conduit://new"), this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
            startActivityAndCollapse(intent)
        }
    }
}

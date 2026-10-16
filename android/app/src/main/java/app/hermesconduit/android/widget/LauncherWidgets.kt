package app.hermesconduit.android.widget

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.ActionParameters
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import app.hermesconduit.android.MainActivity
import app.hermesconduit.android.R
import app.hermesconduit.android.core.L10n
import app.hermesconduit.android.data.ConnectionStore
import app.hermesconduit.android.data.OfflineCache

/**
 * The iOS launcher widgets ("Native" and "Conduit-chan"): the workspace,
 * the chosen conversation (resume), New Chat and Talk.
 */
data class WidgetModel(
    val dashboardLabel: String,
    val profileLabel: String,
    val title: String,
    val resumeUri: String,
)

object WidgetData {
    private fun prefs(context: Context) = context.getSharedPreferences("widgets", Context.MODE_PRIVATE)

    fun saveConfig(context: Context, widgetId: Int, dashboardId: String?, profile: String, sessionId: String?, hideTitle: Boolean) {
        prefs(context).edit()
            .putString("$widgetId.dashboard", dashboardId)
            .putString("$widgetId.profile", profile)
            .putString("$widgetId.session", sessionId)
            .putBoolean("$widgetId.hide", hideTitle)
            .apply()
    }

    fun model(context: Context, widgetId: Int): WidgetModel {
        val p = prefs(context)
        val store = runCatching { ConnectionStore(context) }.getOrNull()
        val dashboards = store?.all().orEmpty()
        val dashboardId = p.getString("$widgetId.dashboard", null) ?: store?.activeId ?: dashboards.firstOrNull()?.id
        val dashboard = dashboards.firstOrNull { it.id == dashboardId }
        val profile = p.getString("$widgetId.profile", null) ?: dashboard?.profile ?: "default"
        val sessions = OfflineCache(context).sessions(dashboardId, profile).orEmpty()
        val chosen = p.getString("$widgetId.session", null)?.let { id -> sessions.firstOrNull { it.matches(id) } } ?: sessions.firstOrNull()
        val hide = p.getBoolean("$widgetId.hide", false)
        val title = when {
            chosen == null -> L10n.tr("Open Conduit")
            hide -> L10n.tr("Continue your conversation")
            else -> chosen.title
        }
        val resume = chosen?.let { "conduit://session/${Uri.encode(it.id)}?profile=${Uri.encode(profile)}" } ?: "conduit://open"
        return WidgetModel(dashboard?.displayName.orEmpty(), if (profile == "default") "" else profile, title, resume)
    }

    suspend fun refresh(context: Context) {
        runCatching { NativeWidget().updateAll(context) }
        runCatching { CharacterWidget().updateAll(context) }
    }
}

private fun open(context: Context, uri: String) =
    actionStartActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri), context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

@Composable
private fun LauncherContent(context: Context, model: WidgetModel, character: Boolean) {
    val accent = ColorProvider(Color(0xFFE0AB47))
    val muted = ColorProvider(Color(0xFFA0A0A8))
    Column(
        GlanceModifier.fillMaxSize().background(ColorProvider(Color(0xFF121214))).cornerRadius(20.dp).padding(14.dp),
    ) {
        Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(GlanceModifier.defaultWeight()) {
                if (model.dashboardLabel.isNotEmpty()) Text(model.dashboardLabel, style = TextStyle(color = muted, fontSize = 12.sp, fontWeight = FontWeight.Medium), maxLines = 1)
                if (model.profileLabel.isNotEmpty()) Text(model.profileLabel, style = TextStyle(color = muted, fontSize = 11.sp), maxLines = 1)
                Text(
                    model.title,
                    style = TextStyle(color = ColorProvider(Color.White), fontSize = 18.sp, fontWeight = FontWeight.Bold),
                    maxLines = 2,
                    modifier = GlanceModifier.clickable(open(context, model.resumeUri)),
                )
            }
            Image(
                ImageProvider(if (character) R.drawable.widget_character else R.drawable.widget_mark),
                contentDescription = null,
                modifier = GlanceModifier.size(if (character) 72.dp else 38.dp),
            )
        }
        Spacer(GlanceModifier.defaultWeight())
        Row(GlanceModifier.fillMaxWidth()) {
            Text(
                "＋ " + L10n.tr("New Chat"),
                style = TextStyle(color = accent, fontSize = 14.sp, fontWeight = FontWeight.Medium),
                modifier = GlanceModifier.background(ColorProvider(Color(0xFF26262A))).cornerRadius(16.dp).padding(horizontal = 10.dp, vertical = 8.dp).clickable(open(context, "conduit://new")),
            )
            Spacer(GlanceModifier.defaultWeight())
            Text(
                "◉ " + L10n.tr("Talk"),
                style = TextStyle(color = accent, fontSize = 14.sp, fontWeight = FontWeight.Medium),
                modifier = GlanceModifier.background(ColorProvider(Color(0xFF26262A))).cornerRadius(16.dp).padding(horizontal = 10.dp, vertical = 8.dp).clickable(open(context, "conduit://voice")),
            )
        }
    }
}

class NativeWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val widgetId = GlanceAppWidgetManager(context).getAppWidgetId(id)
        val model = WidgetData.model(context, widgetId)
        provideContent { GlanceTheme { LauncherContent(context, model, character = false) } }
    }
}

class CharacterWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val widgetId = GlanceAppWidgetManager(context).getAppWidgetId(id)
        val model = WidgetData.model(context, widgetId)
        provideContent { GlanceTheme { LauncherContent(context, model, character = true) } }
    }
}

class NativeWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = NativeWidget()
}

class CharacterWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = CharacterWidget()
}


package app.hermesconduit.android.ui.bots

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import app.hermesconduit.android.core.HermesConnection
import app.hermesconduit.android.core.L10n
import app.hermesconduit.android.model.BotAvatarImage
import app.hermesconduit.android.model.BotMode
import app.hermesconduit.android.model.BotModePhase
import app.hermesconduit.android.model.BotProfile
import app.hermesconduit.android.model.BotRosterDecoder
import app.hermesconduit.android.model.DesktopGroupChat
import app.hermesconduit.android.model.GatewayErrors
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream

/**
 * The app-wide Bot Mode roster (`profiles.list`, unscoped). Hermes stays
 * authoritative; nothing here is persisted. Shared by the Bots screen, the
 * chat composer's @mentions and group-room avatars.
 */
object BotRoster {
    private val _bots = MutableStateFlow<List<BotProfile>>(emptyList())
    /** The FULL roster, meta-hidden bots included (hygiene and mentions need them). */
    val bots: StateFlow<List<BotProfile>> = _bots

    private val _phase = MutableStateFlow<BotModePhase>(BotModePhase.Idle)
    val phase: StateFlow<BotModePhase> = _phase

    private val _desktopGroups = MutableStateFlow<List<DesktopGroupChat>>(emptyList())
    val desktopGroups: StateFlow<List<DesktopGroupChat>> = _desktopGroups

    private val _avatars = MutableStateFlow<Map<String, ImageBitmap>>(emptyMap())
    val avatars: StateFlow<Map<String, ImageBitmap>> = _avatars

    @Volatile var supportsProtocol: Boolean = false
        private set

    private var dashboardId: String? = null
    private var inflight: Deferred<Unit>? = null
    private val fetchedAvatars = mutableSetOf<String>()

    /** Clears everything when the dashboard changed since the last read. */
    private fun fence(hermes: HermesConnection) {
        val id = hermes.connection.value?.id
        if (id != dashboardId) {
            dashboardId = id
            _bots.value = emptyList()
            _desktopGroups.value = emptyList()
            _avatars.value = emptyMap()
            fetchedAvatars.clear()
            _phase.value = BotModePhase.Idle
            supportsProtocol = false
        }
    }

    /** Single-flight refresh; concurrent callers join the running one. */
    suspend fun refresh(hermes: HermesConnection) {
        fence(hermes)
        inflight?.takeIf { it.isActive }?.let { return it.await() }
        val job = hermes.scope.async { performRefresh(hermes) }
        inflight = job
        job.await()
    }

    /** Reloads the roster and every avatar (pull-to-refresh). */
    suspend fun reload(hermes: HermesConnection) {
        fetchedAvatars.clear()
        refresh(hermes)
    }

    private suspend fun performRefresh(hermes: HermesConnection) {
        val dashboard = dashboardId
        if (_bots.value.isEmpty()) _phase.value = BotModePhase.Loading
        try {
            val snapshot = BotRosterDecoder.decode(hermes.rpc("profiles.list", buildJsonObject {}))
                ?: error(L10n.tr("Could not load bots."))
            if (dashboard != hermes.connection.value?.id) return
            _bots.value = BotProfile.displayOrder(snapshot.bots)
            _desktopGroups.value = snapshot.desktopGroups
            supportsProtocol = snapshot.supportsBotProtocol
            _phase.value = BotModePhase.Available
            // A bot without a picture drops any cached one.
            val withAvatar = snapshot.bots.filter { it.hasAvatar }.map { it.name }.toSet()
            _avatars.update { it.filterKeys { name -> name in withAvatar } }
        } catch (e: Exception) {
            if (dashboard != hermes.connection.value?.id) return
            _phase.value = when {
                GatewayErrors.isMissingMethod(e) -> BotModePhase.GatewayUnsupported
                e is IllegalStateException && e.message == "Not connected to the gateway." && _bots.value.isEmpty() ->
                    BotModePhase.Failed(L10n.tr("Connect to Hermes to see your bots."))
                _bots.value.isEmpty() -> BotModePhase.Failed(L10n.tr("Could not load bots."))
                else -> BotModePhase.Failed(L10n.tr("Could not refresh bots."))
            }
        }
    }

    fun bot(named: String?): BotProfile? = named?.let { n -> _bots.value.firstOrNull { it.name == n } }

    /** Fetches a bot's picture from its profile's asset store once per reload. */
    suspend fun loadAvatar(hermes: HermesConnection, bot: BotProfile) {
        if (!bot.hasAvatar || !fetchedAvatars.add(bot.name)) return
        try {
            val result = hermes.rpc("profiles.get_asset", buildJsonObject { put("name", bot.name); put("asset", BotMode.AVATAR_ASSET) })
            val bytes = BotAvatarImage.imageData(result)
            val image = bytes?.let { withContext(Dispatchers.Default) { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() } }
            if (image == null) {
                fetchedAvatars.remove(bot.name)
                return
            }
            _avatars.update { it + (bot.name to image) }
        } catch (_: Exception) {
            fetchedAvatars.remove(bot.name)
        }
    }

    fun setAvatar(name: String, png: ByteArray?) {
        val image = png?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }
        _avatars.update { if (image == null) it - name else it + (name to image) }
        if (image != null) fetchedAvatars.add(name) else fetchedAvatars.remove(name)
    }

    /** Center-crops to a square and downsizes to 256 px PNG (`profiles.set_asset` caps at 2 MB). */
    fun normalizedPng(bytes: ByteArray, edge: Int = BotMode.AVATAR_EDGE): ByteArray? =
        squareImage(bytes, edge, Bitmap.CompressFormat.PNG, 100, background = null)

    /** A center-cropped square, encoded. A `background` fills transparent pixels first. */
    fun squareImage(bytes: ByteArray, edge: Int, format: Bitmap.CompressFormat, quality: Int, background: Int?): ByteArray? {
        val source = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        if (source.width <= 0 || source.height <= 0) return null
        val out = Bitmap.createBitmap(edge, edge, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        if (background != null) canvas.drawColor(background) else canvas.drawColor(Color.TRANSPARENT)
        val side = minOf(source.width, source.height)
        val left = (source.width - side) / 2
        val top = (source.height - side) / 2
        canvas.drawBitmap(source, Rect(left, top, left + side, top + side), RectF(0f, 0f, edge.toFloat(), edge.toFloat()), Paint(Paint.FILTER_BITMAP_FLAG))
        return ByteArrayOutputStream().use { stream ->
            if (!out.compress(format, quality, stream)) return null
            stream.toByteArray()
        }
    }
}

package app.hermesconduit.android.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.util.Base64
import app.hermesconduit.android.appSettings
import app.hermesconduit.android.hermes
import app.hermesconduit.android.model.bool
import app.hermesconduit.android.model.obj
import app.hermesconduit.android.model.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Per-dashboard profile order, photos and background glow (iOS
 * ProfileAppearanceStore + ProfileGlowColors). Photos are saved to Hermes
 * with `profiles.set_asset` when the gateway supports it, else kept on the device.
 */
class ProfileAppearance private constructor(private val context: Context) {
    private val hermes = context.hermes
    private val settings = context.appSettings
    private val _version = MutableStateFlow(0)
    /** Bumped whenever a photo, order or glow changes. */
    val version: StateFlow<Int> = _version

    private fun dashboard() = hermes.connection.value?.id ?: "none"
    private fun dir() = File(context.filesDir, "avatars/${dashboard()}").apply { mkdirs() }
    private fun file(profile: String) = File(dir(), profile.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".png")

    fun order(): List<String> = settings.getString("profiles.order.${dashboard()}", "").split('\n').filter { it.isNotEmpty() }

    fun setOrder(order: List<String>) {
        settings.set("profiles.order.${dashboard()}", order.joinToString("\n"))
        _version.value++
    }

    fun sorted(profiles: List<String>): List<String> {
        val order = order()
        return profiles.sortedBy { p -> order.indexOf(p).let { if (it < 0) Int.MAX_VALUE else it } }
    }

    fun photo(profile: String): Bitmap? = file(profile).takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path) }

    /** Fetches the photo Hermes holds (`profiles.get_asset`), caching it on disk. */
    suspend fun loadPhoto(profile: String) {
        val result = runCatching {
            hermes.rpc("profiles.get_asset", buildJsonObject { put("name", profile); put("asset", "avatar") }).obj
        }.getOrNull() ?: return
        if (result["found"].bool != true) return
        val data = result["data"].str ?: return
        val payload = if (data.startsWith("data:")) data.substringAfter(",", "") else data
        val bytes = runCatching { Base64.decode(payload, Base64.DEFAULT) }.getOrNull() ?: return
        withContext(Dispatchers.IO) { file(profile).writeBytes(bytes) }
        _version.value++
    }

    /** Saves (or with null, removes) a photo, on Hermes when it can and always on the device. */
    suspend fun setPhoto(profile: String, bitmap: Bitmap?) {
        if (bitmap == null) {
            file(profile).delete()
            runCatching { hermes.rpc("profiles.set_asset", buildJsonObject { put("name", profile); put("asset", "avatar"); put("clear", true) }) }
        } else {
            val png = withContext(Dispatchers.Default) {
                val side = 512
                val scaled = Bitmap.createScaledBitmap(bitmap, side, side * bitmap.height / bitmap.width.coerceAtLeast(1), true)
                ByteArrayOutputStream().also { scaled.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
            }
            withContext(Dispatchers.IO) { file(profile).writeBytes(png) }
            runCatching {
                hermes.rpc("profiles.set_asset", buildJsonObject {
                    put("name", profile); put("asset", "avatar")
                    put("data", "data:image/png;base64," + Base64.encodeToString(png, Base64.NO_WRAP))
                })
            }
        }
        _version.value++
    }

    /** `#rrggbb` picked for the profile, or null for Automatic. */
    fun pickedGlow(profile: String): String? = settings.getString("profiles.glow.${dashboard()}.$profile", "").ifEmpty { null }

    fun setGlow(profile: String, hex: String?) {
        settings.set("profiles.glow.${dashboard()}.$profile", hex.orEmpty())
        _version.value++
    }

    /** The glow: the picked color, else the photo's main color lifted to glow strength, else null. */
    fun glow(profile: String): Int? {
        pickedGlow(profile)?.let { return runCatching { Color.parseColor(it) }.getOrNull() }
        val photo = photo(profile) ?: return null
        return dominantColor(photo)
    }

    companion object {
        @Volatile private var instance: ProfileAppearance? = null
        fun of(context: Context) = instance ?: synchronized(this) { instance ?: ProfileAppearance(context.applicationContext).also { instance = it } }

        val swatches = listOf("#E0AB47", "#F5A63D", "#E5534B", "#E05FA0", "#AB8FFA", "#6194FA", "#3FB7D9", "#3CC59A", "#61CC80", "#A5C94A", "#9E9E9E", "#C9A27E")

        /** Mean of the saturated pixels' hue, lifted (iOS ProfileGlowPalette.glowable). */
        fun dominantColor(bitmap: Bitmap): Int? {
            val small = Bitmap.createScaledBitmap(bitmap, 32, 32, true)
            val bins = Array(12) { FloatArray(4) }
            val hsv = FloatArray(3)
            for (x in 0 until 32) for (y in 0 until 32) {
                Color.colorToHSV(small.getPixel(x, y), hsv)
                if (hsv[1] < 0.25f || hsv[2] < 0.25f) continue
                val bin = ((hsv[0] / 30f).toInt()).coerceIn(0, 11)
                val weight = hsv[1] * hsv[2]
                bins[bin][0] += hsv[0] * weight; bins[bin][1] += hsv[1] * weight; bins[bin][2] += hsv[2] * weight; bins[bin][3] += weight
            }
            val best = bins.maxByOrNull { it[3] } ?: return null
            if (best[3] < 1f) return null
            val hue = best[0] / best[3]
            return Color.HSVToColor(floatArrayOf(hue, (best[1] / best[3]).coerceAtLeast(0.55f), (best[2] / best[3]).coerceAtLeast(0.85f)))
        }
    }
}

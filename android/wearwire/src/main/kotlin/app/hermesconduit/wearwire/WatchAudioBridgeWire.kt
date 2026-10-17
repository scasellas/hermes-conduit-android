package app.hermesconduit.wearwire

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * A watch call to GPT-Live (or Grok) through the Hermes host's audio bridge
 * (port of the iOS `WatchAudioBridgeWire`, designs/apple-watch-gpt-live.md).
 * GPT-Live on a ChatGPT subscription speaks WebRTC only, so the conduit_push
 * plugin holds the call and the watch streams to it over one WebSocket
 * through the push relay: /v1/watch-audio/{grant}/watch, with the grant's
 * relay key as the bearer.
 *
 * Each connection opens with a hello in clear, [4][stream id 16][version],
 * then everything is sealed: [type][counter 8 BE][ChaCha20-Poly1305 ct],
 * type 1 audio (PCM16LE mono), 2 engine event (the engine's own JSON text),
 * 3 control (JSON). The keys come from the grant's 32-byte root by
 * HKDF-SHA256, one per direction and stream; the nonce is 4 zero bytes and
 * the counter, which starts at 0 and must rise. The relay's own notices are
 * [0, n] in clear. Mirrors the plugin's dashboard/plugin_api.py ("Watch
 * audio"), checked by its test vectors.
 */
object WatchAudioBridgeWire {
    const val VERSION: Int = 1
    const val HELLO_TYPE: Int = 4
    /** The engines the bridge runs. Gemini stays direct. */
    const val GPT_LIVE = "gpt_live"
    const val GROK = "grok"
    /** The relay's bound on one message. */
    const val MAX_MESSAGE_BYTES = 64 * 1024
    /** What fits in one sealed message: the type, counter and tag go around it. */
    const val MAX_PLAIN_BYTES = MAX_MESSAGE_BYTES - 1 - 8 - 16
    const val SALT = "conduit-watch-audio-v1"
    const val ASSOCIATED_DATA_TAG = "conduit-watch-audio/1"

    enum class Kind(val raw: Int) {
        AUDIO(1), EVENT(2), CONTROL(3);

        companion object {
            fun of(raw: Int) = entries.firstOrNull { it.raw == raw }
        }
    }

    enum class Direction(val raw: String) { WATCH_TO_HOST("watch-to-host"), HOST_TO_WATCH("host-to-watch") }

    /** What the relay says about the other side, in clear. */
    enum class Notice(val raw: Int) { WATCH_CONNECTED(1), WATCH_GONE(2) }

    class Failure(val kind: Kind) : Exception(kind.name) {
        enum class Kind { MALFORMED, DID_NOT_VERIFY, TOO_LARGE, REPLAYED }
    }

    /** One stream's keys: one per direction, from the grant's root. */
    class Keys private constructor(val watchToHost: ByteArray, val hostToWatch: ByteArray) {
        fun key(direction: Direction) = if (direction == Direction.WATCH_TO_HOST) watchToHost else hostToWatch

        companion object {
            /** Null unless [root] is 32 bytes and [streamId] 16. */
            fun of(root: ByteArray, streamId: ByteArray): Keys? {
                if (root.size != 32 || streamId.size != 16) return null
                val stream = WatchToolSeal.base64Url(streamId)
                fun derive(direction: Direction) = WatchToolSeal.hkdf(root, SALT, "conduit-watch-audio-v1 ${direction.raw} stream=$stream")
                return Keys(derive(Direction.WATCH_TO_HOST), derive(Direction.HOST_TO_WATCH))
            }
        }
    }

    fun associatedData(direction: Direction, grantId: String, streamId: ByteArray, kind: Kind): ByteArray =
        listOf(ASSOCIATED_DATA_TAG, direction.raw, "grant=$grantId", "sid=${WatchToolSeal.base64Url(streamId)}", "type=${kind.raw}")
            .joinToString("\n").toByteArray(Charsets.UTF_8)

    fun nonce(counter: Long): ByteArray = ByteArray(12).also { bytes ->
        for (i in 0 until 8) bytes[4 + i] = (counter ushr (56 - 8 * i)).toByte()
    }

    fun seal(plain: ByteArray, kind: Kind, counter: Long, keys: Keys, direction: Direction, grantId: String, streamId: ByteArray): ByteArray {
        if (plain.size > MAX_PLAIN_BYTES) throw Failure(Failure.Kind.TOO_LARGE)
        val box = WatchToolSeal.chachaSeal(keys.key(direction), nonce(counter), plain, associatedData(direction, grantId, streamId, kind))
        val message = ByteArray(1 + 8 + box.size)
        message[0] = kind.raw.toByte()
        nonce(counter).copyInto(message, 1, 4, 12)
        box.copyInto(message, 9)
        return message
    }

    data class Opened(val kind: Kind, val counter: Long, val plain: ByteArray)

    fun open(message: ByteArray, keys: Keys, direction: Direction, grantId: String, streamId: ByteArray): Opened {
        if (message.size < 1 + 8 + 16 || message.size > MAX_MESSAGE_BYTES) throw Failure(Failure.Kind.MALFORMED)
        val kind = Kind.of(message[0].toInt() and 0xff) ?: throw Failure(Failure.Kind.MALFORMED)
        var counter = 0L
        for (i in 1..8) counter = (counter shl 8) or (message[i].toLong() and 0xff)
        val plain = try {
            WatchToolSeal.chachaOpen(keys.key(direction), nonce(counter), message.copyOfRange(9, message.size), associatedData(direction, grantId, streamId, kind))
        } catch (_: Exception) {
            throw Failure(Failure.Kind.DID_NOT_VERIFY)
        }
        return Opened(kind, counter, plain)
    }

    /** The first message on each connection, in clear. */
    fun hello(streamId: ByteArray): ByteArray = byteArrayOf(HELLO_TYPE.toByte()) + streamId + byteArrayOf(VERSION.toByte())

    /** The relay's notice, if [message] is one. */
    fun notice(message: ByteArray): Notice? {
        if (message.size != 2 || message[0].toInt() != 0) return null
        return Notice.entries.firstOrNull { it.raw == message[1].toInt() }
    }

    /** A new stream id: 16 random bytes. Taken once per grant. */
    fun newStreamId(): ByteArray = WatchToolSeal.randomBytes(16)

    // region Control

    /**
     * Starts the engine. [briefing] is Conduit's rules, persona and memory;
     * [greeting] asks for a first line; [callOpening] makes the model open a
     * call Hermes placed; [history] seeds a rejoined call's conversation.
     */
    fun start(engine: String, voice: String?, briefing: String?, greeting: String?, callOpening: Boolean = false, history: List<JsonObject>): ByteArray {
        val message = buildJsonObject {
            put("type", "start")
            put("engine", engine)
            if (!voice.isNullOrEmpty()) put("voice", voice)
            if (!briefing.isNullOrEmpty()) put("briefing", briefing)
            if (greeting != null) put("greeting", greeting)
            if (callOpening) put("call_opening", true)
            if (history.isNotEmpty()) put("history", JsonArray(history))
        }
        return WatchToolSeal.json(message)
    }

    val end: ByteArray get() = "{\"type\":\"end\"}".toByteArray(Charsets.UTF_8)

    data class Started(
        val engine: String,
        val voice: String?,
        val inputRate: Int,
        val outputRate: Int,
        val briefingApplied: Boolean,
        /** The host made the model speak first: a greeting or a call Hermes placed. */
        val greetingApplied: Boolean,
    )

    /** Whether the watch sends a call Hermes placed its own opening turn: once, never on a rejoin, and only when the host didn't already. */
    fun sendsOpeningTurn(rejoining: Boolean, started: Started) = !rejoining && !started.greetingApplied

    sealed class Control {
        data class StartedControl(val started: Started) : Control()
        /** The engine's session ended: "ended" when asked, "error" after an error message, otherwise the engine's or the host's reason. */
        data class Ended(val reason: String?) : Control()
        /** A start the host refused or a session that failed, with a message for the user. */
        data class Error(val code: String?, val message: String) : Control()
    }

    fun control(plain: ByteArray): Control? {
        val o = runCatching { WearWire.json.parseToJsonElement(plain.toString(Charsets.UTF_8)) as? JsonObject }.getOrNull() ?: return null
        return when (o.text("type")) {
            "started" -> Control.StartedControl(Started(
                o.text("engine").orEmpty(), o.text("voice"),
                o.int("input_rate") ?: 16_000, o.int("output_rate") ?: 24_000,
                (o["briefing_applied"] as? JsonPrimitive)?.booleanOrNull ?: false,
                (o["greeting_applied"] as? JsonPrimitive)?.booleanOrNull ?: false,
            ))
            "ended" -> Control.Ended(o.text("reason"))
            "error" -> Control.Error(o.text("code"), o.text("message").orEmpty())
            else -> null
        }
    }

    /** PCM16LE mono, as the bridge carries audio. */
    fun pcm(samples: ShortArray): ByteArray = ByteArray(samples.size * 2).also { out ->
        samples.forEachIndexed { i, s ->
            out[2 * i] = s.toInt().toByte()
            out[2 * i + 1] = (s.toInt() shr 8).toByte()
        }
    }

    fun samples(pcm: ByteArray): ShortArray = ShortArray(pcm.size / 2) { i ->
        ((pcm[2 * i].toInt() and 0xff) or (pcm[2 * i + 1].toInt() shl 8)).toShort()
    }

    // endregion
}

/** One connection's sealing state: its stream id and keys, the counter it sends with and the newest it accepted. */
class WatchAudioBridgeStream private constructor(val grantId: String, val streamId: ByteArray, val keys: WatchAudioBridgeWire.Keys) {
    var sent: Long = 0
        private set
    var received: Long? = null
        private set

    @Synchronized
    fun seal(plain: ByteArray, kind: WatchAudioBridgeWire.Kind): ByteArray {
        val message = WatchAudioBridgeWire.seal(plain, kind, sent, keys, WatchAudioBridgeWire.Direction.WATCH_TO_HOST, grantId, streamId)
        sent += 1
        return message
    }

    /** Checked once it verifies, so a forged counter can't move the window. */
    @Synchronized
    fun open(message: ByteArray): Pair<WatchAudioBridgeWire.Kind, ByteArray> {
        val opened = WatchAudioBridgeWire.open(message, keys, WatchAudioBridgeWire.Direction.HOST_TO_WATCH, grantId, streamId)
        received?.let { if (opened.counter <= it) throw WatchAudioBridgeWire.Failure(WatchAudioBridgeWire.Failure.Kind.REPLAYED) }
        received = opened.counter
        return opened.kind to opened.plain
    }

    companion object {
        fun of(grantId: String, root: ByteArray, streamId: ByteArray = WatchAudioBridgeWire.newStreamId()): WatchAudioBridgeStream? {
            val keys = WatchAudioBridgeWire.Keys.of(root, streamId) ?: return null
            return WatchAudioBridgeStream(grantId, streamId, keys)
        }
    }
}

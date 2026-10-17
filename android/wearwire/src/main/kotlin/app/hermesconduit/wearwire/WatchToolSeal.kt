package app.hermesconduit.wearwire

import app.hermesconduit.android.core.live.GeminiLiveProtocol
import app.hermesconduit.android.core.live.VoiceChatToolText
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * A watch call's sealing with its grant (port of the iOS `WatchToolSeal`):
 * HKDF-SHA256 from the grant's 32-byte root to one ChaCha20-Poly1305 key per
 * direction, with the grant and the request id bound in as associated data,
 * so an answer can't be replayed to another request or sent back the other
 * way. Mirrors conduit_push's dashboard/plugin_api.py, checked by the
 * plugin's own test vectors.
 */
object WatchToolSeal {
    enum class Direction(val raw: String) {
        /** What the watch asks Hermes. */
        CALL("call"),
        /** What Hermes answers. */
        RESULT("result"),
    }

    class Failure(val kind: Kind) : Exception(kind.name) {
        enum class Kind { MALFORMED, DID_NOT_VERIFY, TOO_LARGE }
    }

    /** A sealed message as the relay carries it: the nonce and the ciphertext with its tag, base64url. */
    data class Sealed(val n: String, val ct: String)

    class Keys private constructor(val call: ByteArray, val result: ByteArray) {
        fun key(direction: Direction) = if (direction == Direction.CALL) call else result

        companion object {
            /** Null unless [root] is 32 bytes. */
            fun of(root: ByteArray): Keys? {
                if (root.size != 32) return null
                return Keys(hkdf(root, SALT, info(Direction.CALL)), hkdf(root, SALT, info(Direction.RESULT)))
            }
        }
    }

    const val SALT = "conduit-watch-tools-v1"
    /** The most a sealed call carries: a tool name and a short query. */
    const val MAX_CALL_BYTES = 4 * 1024

    fun info(direction: Direction) = when (direction) {
        Direction.CALL -> "conduit-watch-tools-v1 call watch-to-host"
        Direction.RESULT -> "conduit-watch-tools-v1 result host-to-watch"
    }

    fun associatedData(direction: Direction, grantId: String, rid: String): ByteArray =
        listOf("conduit-watch-tools/1", direction.raw, "grant=$grantId", "rid=$rid").joinToString("\n").toByteArray(Charsets.UTF_8)

    /** A fixed nonce is for test vectors only. */
    fun seal(plaintext: ByteArray, keys: Keys, direction: Direction, grantId: String, rid: String, nonce: ByteArray = randomBytes(12)): Sealed {
        val combined = chachaSeal(keys.key(direction), nonce, plaintext, associatedData(direction, grantId, rid))
        return Sealed(base64Url(nonce), base64Url(combined))
    }

    fun open(sealed: Sealed, keys: Keys, direction: Direction, grantId: String, rid: String): ByteArray {
        val nonce = data(sealed.n)?.takeIf { it.size == 12 } ?: throw Failure(Failure.Kind.MALFORMED)
        val combined = data(sealed.ct)?.takeIf { it.size >= 16 } ?: throw Failure(Failure.Kind.MALFORMED)
        return try {
            chachaOpen(keys.key(direction), nonce, combined, associatedData(direction, grantId, rid))
        } catch (_: Exception) {
            throw Failure(Failure.Kind.DID_NOT_VERIFY)
        }
    }

    /** A JSON object as the host reads it: sorted keys (all the way down), no escaped slashes. */
    fun json(element: JsonElement): ByteArray = sorted(element).toString().toByteArray(Charsets.UTF_8)

    fun sorted(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> JsonObject(element.toSortedMap().mapValues { sorted(it.value) })
        is JsonArray -> JsonArray(element.map(::sorted))
        else -> element
    }

    /** A new request id: 16 random bytes, base64url. */
    fun newRequestId(): String = base64Url(randomBytes(16))

    fun base64Url(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    /** Only the base64url alphabet, unpadded. */
    fun data(base64Url: String): ByteArray? {
        if (!base64Url.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '-' || it == '_' } || base64Url.length % 4 == 1) return null
        return runCatching { Base64.getUrlDecoder().decode(base64Url) }.getOrNull()
    }

    // region Primitives

    private val random = SecureRandom()

    fun randomBytes(count: Int) = ByteArray(count).also(random::nextBytes)

    /** HKDF-SHA256 (RFC 5869) to 32 bytes: one expand block. */
    fun hkdf(ikm: ByteArray, salt: String, info: String): ByteArray {
        val extract = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(salt.toByteArray(Charsets.UTF_8), "HmacSHA256")) }
        val prk = extract.doFinal(ikm)
        val expand = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(prk, "HmacSHA256")) }
        expand.update(info.toByteArray(Charsets.UTF_8))
        expand.update(1.toByte())
        return expand.doFinal()
    }

    /** ChaCha20-Poly1305: the ciphertext followed by its 16-byte tag. */
    fun chachaSeal(key: ByteArray, nonce: ByteArray, plain: ByteArray, aad: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("ChaCha20-Poly1305")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "ChaCha20"), IvParameterSpec(nonce))
        cipher.updateAAD(aad)
        return cipher.doFinal(plain)
    }

    fun chachaOpen(key: ByteArray, nonce: ByteArray, combined: ByteArray, aad: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("ChaCha20-Poly1305")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "ChaCha20"), IvParameterSpec(nonce))
        cipher.updateAAD(aad)
        return cipher.doFinal(combined)
    }

    // endregion
}

/**
 * What a relayed lookup hands the model (port of the iOS `WatchToolAnswer`):
 * the result the phone's tool bridge builds for the same host response, with
 * the scheduling and fallback the phone's broker sends the watch. The chat
 * tools come back from the host as the model's text, numbered and fenced as
 * the phone's are. Written for the model, not shown, so not localized.
 */
object WatchToolAnswer {
    const val WEB_SEARCH = "web_search"
    const val RECALL_MEMORY = "recall_memory"
    const val FIND_CHAT = VoiceChatToolText.FIND_CHAT
    const val READ_CHAT = VoiceChatToolText.READ_CHAT
    val chatTools = setOf(FIND_CHAT, READ_CHAT)
    val tools = chatTools + setOf(WEB_SEARCH, RECALL_MEMORY)
    /** The plugin's capability for the chat tools. */
    const val CHAT_CAPABILITY = "watch-chats"

    const val WEB_SEARCH_LIMIT = 3
    const val MEMORY_RECALL_LIMIT = 4000
    const val LOOKUP_ANSWER_NOTE = "Answer the user now from this result; don't say again that you're checking or looking it up."
    /** A lookup that outlasted its wait, as the phone's broker answers it. */
    val tookTooLong = mapOf("error" to "That took too long. Tell the user in a few words.")

    /** The trimmed query (read_chat's match number); null when there is none. */
    fun query(name: String, arguments: Map<String, String>): String? =
        arguments[if (name == READ_CHAT) "number" else "query"]?.trim()?.takeIf { it.isNotEmpty() }

    /** What the host runs: the tool and the arguments the phone's client would send its route. */
    fun request(name: String, query: String): JsonObject = buildJsonObject {
        put("tool", name)
        put("args", buildJsonObject {
            when (name) {
                WEB_SEARCH -> { put("query", query); put("limit", WEB_SEARCH_LIMIT) }
                READ_CHAT -> put("number", query)
                else -> put("query", query)
            }
        })
    }

    fun missingQuery(name: String): Map<String, String> = noted(mapOf("error" to if (name == READ_CHAT) "number is required" else "query is required"), name)

    /** The bridge's answer for the host's response to [name]. */
    fun result(name: String, body: JsonObject): Map<String, String> {
        if (name in chatTools) return chatResult(name, body)
        if (name == WEB_SEARCH) {
            val items = body["results"] as? JsonArray
            if (!body.ok || items == null) return noted(mapOf("error" to (reason(body) ?: "The Hermes web search returned no results")), name)
            val lines = items.mapNotNull { it as? JsonObject }.mapNotNull { item ->
                val url = item.text("url")?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                "${item.text("title").orEmpty()}: ${item.text("snippet").orEmpty()} ($url)"
            }
            if (lines.isEmpty()) return noted(mapOf("results" to "No results."), name)
            return noted(mapOf("results" to lines.mapIndexed { i, line -> "${i + 1}. $line" }.joinToString("\n")), name)
        }
        val results = body.text("results")
        if (!body.ok || (body["available"] as? JsonPrimitive)?.booleanOrNull == false || results == null) {
            return mapOf("error" to (reason(body) ?: "Hermes memory is not available"))
        }
        val text = results.trim().take(MEMORY_RECALL_LIMIT)
        return mapOf("results" to text.ifEmpty { "Nothing in memory about that." })
    }

    private fun chatMatches(body: JsonObject): Pair<String, Int> {
        val chats = body.text("chats").orEmpty()
        return chats to if (chats.isEmpty()) 0 else maxOf(body.int("count") ?: 0, 1)
    }

    private fun chatFailure(name: String) = if (name == FIND_CHAT) "Searching chats failed on Hermes." else "Reading the chat failed on Hermes."

    private fun chatResult(name: String, body: JsonObject): Map<String, String> {
        if (!body.ok) return mapOf("error" to VoiceChatToolText.failed(reason(body) ?: chatFailure(name)))
        if (name == FIND_CHAT) {
            val (chats, count) = chatMatches(body)
            return VoiceChatToolText.searchResult(chats, count, opens = false)
        }
        if ((body["unknown"] as? JsonPrimitive)?.booleanOrNull == true) return mapOf("error" to VoiceChatToolText.UNKNOWN_FOR_READ)
        val chat = body.text("chat").orEmpty()
        return if (chat.isEmpty()) VoiceChatToolText.emptyReadResult(body.text("title").orEmpty()) else VoiceChatToolText.readResult(chat)
    }

    /** What a GPT-Live call's "Find chat:" or "Read chat:" delegation is answered with, from the host's answer. */
    fun delegatedChatReply(name: String, body: JsonObject): String {
        if (!body.ok) {
            val why = reason(body) ?: chatFailure(name)
            return if (name == FIND_CHAT) VoiceChatToolText.delegatedSearchFailed(why) else VoiceChatToolText.delegatedReadFailed(why)
        }
        if (name == FIND_CHAT) {
            val (chats, count) = chatMatches(body)
            return if (chats.isEmpty()) VoiceChatToolText.DELEGATED_NO_MATCHES else VoiceChatToolText.delegatedSearchFound(chats, count, opens = false)
        }
        if ((body["unknown"] as? JsonPrimitive)?.booleanOrNull == true) return VoiceChatToolText.DELEGATED_UNKNOWN_CHAT
        val chat = body.text("chat").orEmpty()
        return if (chat.isEmpty()) VoiceChatToolText.delegatedEmptyChat(body.text("title").orEmpty()) else VoiceChatToolText.delegatedRead(chat)
    }

    /** A watch call can't open a chat: what an "Open chat:" delegation hears. */
    const val DELEGATED_CANT_OPEN = "[This call can't open a chat on the phone. Tell the user to open it in Conduit on their phone, or delegate \"Read chat:\" with its number to talk about it here.]"

    /** The answer as the phone's broker would send it to the watch. */
    fun outgoing(id: String, name: String, result: Map<String, String>) = WatchVoiceWire.DirectOutgoing.ToolResponse(
        id, name, result, GeminiLiveProtocol.Scheduling.WHEN_IDLE.raw,
        if (name in chatTools) VoiceChatToolText.fallbackText(result, name) else fallbackText(result),
    )

    /** Said instead when the call's connection is gone. */
    fun fallbackText(result: Map<String, String>): String? {
        result["results"]?.takeIf { it.isNotEmpty() }?.let {
            return "[The lookup you just made returned this. Answer the user's question from it now, without another lookup; don't say again that you're checking or looking it up:\n$it]"
        }
        result["error"]?.takeIf { it.isNotEmpty() }?.let { return "[The lookup you just made failed ($it). Tell the user in one short sentence.]" }
        return null
    }

    /** Every web_search answer carries the note, a failure included. */
    private fun noted(result: Map<String, String>, name: String): Map<String, String> = if (name != WEB_SEARCH) result else result + ("note" to LOOKUP_ANSWER_NOTE)

    internal fun reason(body: JsonObject): String? = listOf("detail", "error", "reason").firstNotNullOfOrNull { body.text(it) }?.takeIf { it.isNotEmpty() }
}

internal val JsonObject.ok: Boolean get() = (this["ok"] as? JsonPrimitive)?.booleanOrNull == true
internal fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull && it.isString }?.content
internal fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull && !it.isString }?.intOrNull

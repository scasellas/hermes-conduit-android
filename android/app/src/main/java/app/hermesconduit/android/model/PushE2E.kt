package app.hermesconduit.android.model

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.util.zip.Inflater

/**
 * End-to-end sealed push content (iOS Shared/NotificationE2E.swift, #431),
 * for pushes that reach Android through UnifiedPush.
 *
 * Each registration gets a 32-byte root secret made on this phone and handed
 * to the Hermes notifier plugin over the signed-in dashboard connection, so
 * it never passes through the push server or the distributor. Both sides
 * derive separate keys from it with HKDF-SHA256 (gateway → device content,
 * device → gateway answers) and seal with ChaCha20-Poly1305; the associated
 * data binds every envelope to its pairing and to the fields the push server
 * can see. Wire format, derivation and associated data are the iOS ones
 * byte for byte (checked against the plugin's vectors in PushE2ETest), so the
 * plugin seals for both platforms with the same `e2e.py`.
 */
object PushE2E {
    const val ENVELOPE_KEY = "conduit_e2e"
    const val ROUTING_KEY = "conduit"
    const val ANSWER_PREFIX = "e2e1."
    const val MAX_AGE_MS = 24L * 60 * 60 * 1000
    const val MAX_CLOCK_SKEW_MS = 60L * 60 * 1000
    /** The most a sealed body may inflate to (the push itself is at most 4 KB). */
    const val MAX_INFLATED_BYTES = 64 * 1024

    private const val SALT = "conduit-e2e-v1"
    private const val PUSH_INFO = "conduit-e2e-v1 push gateway-to-device"
    private const val ANSWER_INFO = "conduit-e2e-v1 answer device-to-gateway"
    private const val AAD_TAG = "conduit-e2e/1"

    private val kidPattern = Regex("^[0-9a-f]{32}$")
    private val tokPattern = Regex("^[0-9a-f]{16}$")

    class Keys(val push: ByteArray, val answer: ByteArray)

    fun keys(secret: ByteArray): Keys = Keys(
        SealCrypto.hkdf(secret, SALT.toByteArray(), PUSH_INFO.toByteArray()),
        SealCrypto.hkdf(secret, SALT.toByteArray(), ANSWER_INFO.toByteArray()),
    )

    /** A new pairing key: a random secret and an unrelated random key id, not yet confirmed by the plugin. */
    fun newSecret(): Pair<String, ByteArray> = SealCrypto.randomHex(16) to SealCrypto.randomBytes(32)

    // region Envelope

    /** The `conduit_e2e` object exactly as the push server forwarded it. */
    class Envelope(
        val kid: String,
        val msg: String,
        val iat: Long,
        val tok: String,
        val z: Int,
        val req: String,
        val nonce: ByteArray,
        val ciphertext: ByteArray,
    ) {
        /** Identifies one sealed message across deliveries (replay checks). */
        val replayKey: String get() = "$kid:$msg"

        companion object {
            fun parse(value: JsonElement?): Envelope? {
                val o = value as? JsonObject ?: return null
                if (number(o["v"]) != 1L) return null
                val kid = string(o["kid"])?.takeIf(kidPattern::matches) ?: return null
                val msg = string(o["msg"])?.takeIf { it.isNotEmpty() } ?: return null
                val iat = number(o["iat"])?.takeIf { it > 0 } ?: return null
                val tok = string(o["tok"])?.takeIf(tokPattern::matches) ?: return null
                val z = number(o["z"])?.takeIf { it == 0L || it == 1L }?.toInt() ?: return null
                val req = string(o["req"]) ?: return null
                val nonce = string(o["n"])?.let(SealCrypto::base64UrlDecoded)?.takeIf { it.size == 12 } ?: return null
                val ciphertext = string(o["ct"])?.let(SealCrypto::base64UrlDecoded)?.takeIf { it.size >= 16 } ?: return null
                return Envelope(kid, msg, iat, tok, z, req, nonce, ciphertext)
            }
        }
    }

    fun pushAAD(kid: String, installationID: String, gatewayID: String, msg: String, type: String, iat: Long, tok: String, z: Int, requestID: String): ByteArray =
        SealCrypto.aad(AAD_TAG, "push", "kid=$kid", "inst=$installationID", "gw=$gatewayID", "msg=$msg", "type=$type", "iat=$iat", "tok=$tok", "z=$z", "req=$requestID")

    fun answerAAD(kid: String, installationID: String, gatewayID: String, requestID: String, questionID: String): ByteArray =
        SealCrypto.aad(AAD_TAG, "answer", "kid=$kid", "inst=$installationID", "gw=$gatewayID", "req=$requestID", "qid=$questionID")

    // endregion

    // region Trust

    sealed class Evaluation {
        /**
         * Nothing this phone can vouch for: no envelope (a plain wake-up, or a
         * plugin without a key), or one that doesn't verify, is stale or
         * names an unknown key. It shows nothing and routes nowhere; Conduit
         * only reconnects and looks for itself.
         */
        data class Untrusted(val type: String?) : Evaluation()

        class Verified(val record: PushKeyRecord, val envelope: Envelope, val type: String, val content: JsonObject) : Evaluation() {
            /**
             * The routing payload, built only from authenticated data: the
             * decrypted content, the type bound in the associated data, and
             * the gateway, dashboard and profile this key was provisioned for
             * (never the push's routing stub).
             */
            val routingPayload: JsonObject
                get() = JsonObject(buildMap {
                    record.profile?.let { put("profile", JsonPrimitive(it)) }
                    putAll(content - "title" - "body")
                    put("type", JsonPrimitive(type))
                    put("gateway_id", JsonPrimitive(record.gatewayID))
                    record.dashboardID?.let { put("dashboard_id", JsonPrimitive(it)) }
                })
        }
    }

    /** The routing stub (top-level `conduit`, else `body.conduit`, as iOS reads it). */
    fun routingStub(payload: JsonObject): JsonObject? =
        payload[ROUTING_KEY] as? JsonObject ?: (payload["body"] as? JsonObject)?.get(ROUTING_KEY) as? JsonObject

    /** A UnifiedPush message body: the same JSON layout an iOS push's userInfo has (without `aps`). */
    fun parseMessage(bytes: ByteArray): JsonObject? =
        runCatching { ConduitJson.parseToJsonElement(bytes.decodeToString()) as? JsonObject }.getOrNull()

    fun evaluate(payload: JsonObject, records: List<PushKeyRecord>, nowMs: Long = System.currentTimeMillis()): Evaluation {
        val type = string(routingStub(payload)?.get("type"))?.trim()
        val envelope = Envelope.parse(payload[ENVELOPE_KEY]) ?: return Evaluation.Untrusted(type)
        if (type.isNullOrEmpty()) return Evaluation.Untrusted(type)
        val record = records.firstOrNull { it.kid == envelope.kid } ?: return Evaluation.Untrusted(type)
        val issuedMs = envelope.iat * 1000
        if (issuedMs < nowMs - MAX_AGE_MS || issuedMs > nowMs + MAX_CLOCK_SKEW_MS) return Evaluation.Untrusted(type)
        val aad = pushAAD(record.kid, record.installationID, record.gatewayID, envelope.msg, type, envelope.iat, envelope.tok, envelope.z, envelope.req)
        val opened = runCatching {
            SealCrypto.open(SealCrypto.base64Url(envelope.nonce), SealCrypto.base64Url(envelope.ciphertext), keys(record.secret).push, aad)
        }.getOrNull() ?: return Evaluation.Untrusted(type)
        val content = inflate(opened, compressed = envelope.z == 1) ?: return Evaluation.Untrusted(type)
        // A decision answers to the request the ciphertext is bound to.
        val decisionRequest = (content["decision"] as? JsonObject)?.get("request_id")
        if (decisionRequest != null && decisionRequest !is JsonNull && string(decisionRequest) != envelope.req) return Evaluation.Untrusted(type)
        return Evaluation.Verified(record, envelope, type, content)
    }

    private fun inflate(data: ByteArray, compressed: Boolean): JsonObject? {
        val raw = if (compressed) {
            // Raw DEFLATE (what Apple's Compression framework calls zlib).
            val inflater = Inflater(true)
            try {
                inflater.setInput(data)
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                while (!inflater.finished()) {
                    val count = inflater.inflate(buffer)
                    if (count == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
                    out.write(buffer, 0, count)
                    if (out.size() > MAX_INFLATED_BYTES) return null
                }
                if (!inflater.finished()) return null
                out.toByteArray()
            } catch (_: Exception) {
                return null
            } finally {
                inflater.end()
            }
        } else data
        return runCatching { ConduitJson.parseToJsonElement(raw.decodeToString()) as? JsonObject }.getOrNull()
    }

    /** A clarify answer sealed for the gateway that asked, bound to its request and question (iOS `sealAnswer`). */
    fun sealAnswer(answer: String, record: PushKeyRecord, requestID: String, questionID: String?, nonce: ByteArray = SealCrypto.randomBytes(12)): String {
        val aad = answerAAD(record.kid, record.installationID, record.gatewayID, requestID, questionID.orEmpty())
        val sealed = SealCrypto.seal(answer.toByteArray(), keys(record.secret).answer, aad, nonce)
        return "$ANSWER_PREFIX${record.kid}.${sealed.nonce}.${sealed.ct}"
    }

    // endregion

    /** The generic copy for a push Conduit can't show (iOS `genericCopy`, word for word). */
    fun genericCopy(type: String?): Pair<String, String> = when (type) {
        "approval.needed" -> "Approval needed" to "Hermes is waiting for your approval."
        "input.needed" -> "Input needed" to "Hermes needs your response before it can continue."
        "turn.failed" -> "Turn failed" to "A Hermes turn could not be completed."
        "background_task.finished" -> "Background task finished" to "A delegated task has finished."
        "call.requested" -> "Hermes wants to talk" to "Tap to talk to Hermes."
        else -> "Response ready" to "Hermes has finished responding."
    }

    private fun string(element: JsonElement?): String? = (element as? JsonPrimitive)?.takeIf { it !is JsonNull && it.isString }?.content

    /** A JSON number (never a string or a bool), as an integer. */
    private fun number(element: JsonElement?): Long? {
        val p = element as? JsonPrimitive ?: return null
        if (p is JsonNull || p.isString || p.content == "true" || p.content == "false") return null
        p.longOrNull?.let { return it }
        val d = p.content.toDoubleOrNull() ?: return null
        return if (d.isFinite() && d == Math.floor(d)) d.toLong() else null
    }
}

/**
 * One registration's key as this phone stores it (iOS E2EKeyRecord).
 * [installationID] and [gatewayID] are the pairing the plugin accepted it
 * for; [dashboardID] and [profile] are where its pushes belong.
 */
data class PushKeyRecord(
    val kid: String,
    val secret: ByteArray,
    val installationID: String,
    val gatewayID: String,
    val dashboardID: String?,
    val profile: String?,
    val createdAtMs: Long,
) {
    fun json(): JsonObject = buildJsonObject {
        put("kid", kid)
        put("secret", SealCrypto.base64Url(secret))
        put("installationID", installationID)
        put("gatewayID", gatewayID)
        dashboardID?.let { put("dashboardID", it) }
        profile?.let { put("profile", it) }
        put("createdAt", createdAtMs)
    }

    override fun equals(other: Any?): Boolean = other is PushKeyRecord && json() == other.json()
    override fun hashCode(): Int = json().hashCode()

    companion object {
        fun parse(json: JsonObject): PushKeyRecord? {
            return PushKeyRecord(
                kid = json["kid"].str ?: return null,
                secret = json["secret"].str?.let(SealCrypto::base64UrlDecoded)?.takeIf { it.size == 32 } ?: return null,
                installationID = json["installationID"].str ?: return null,
                gatewayID = json["gatewayID"].str ?: return null,
                dashboardID = json["dashboardID"].str,
                profile = json["profile"].str,
                createdAtMs = json["createdAt"].long ?: 0,
            )
        }

        fun encodeAll(records: List<PushKeyRecord>): String = kotlinx.serialization.json.JsonArray(records.map { it.json() }).toString()

        fun decodeAll(text: String?): List<PushKeyRecord> =
            text?.let { runCatching { ConduitJson.parseToJsonElement(it) as? kotlinx.serialization.json.JsonArray }.getOrNull() }
                ?.mapNotNull { (it as? JsonObject)?.let(::parse) }.orEmpty()
    }
}

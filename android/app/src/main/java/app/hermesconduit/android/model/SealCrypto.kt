package app.hermesconduit.android.model

import com.google.crypto.tink.aead.internal.InsecureNonceChaCha20Poly1305
import com.google.crypto.tink.subtle.Hkdf
import com.google.crypto.tink.subtle.X25519
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * The end-to-end sealing shared by health, calendar & phone and location
 * uploads (iOS HealthSyncCrypto, PhoneSyncCrypto, LocationCrypto,
 * VoiceReadSeal): an X25519 agreement, HKDF-SHA256 keys by purpose and
 * ChaCha20-Poly1305 boxes whose associated data binds each to its key,
 * device and batch or call. Byte-compatible with CryptoKit and the
 * notifier's stores (checked against their vectors in the unit tests).
 */
object SealCrypto {
    private val random = SecureRandom()

    class Failure(val reason: String) : Exception(reason)

    fun base64Url(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    /** Nil for anything but unpadded base64url (as iOS's `base64URLDecoded`). */
    fun base64UrlDecoded(value: String): ByteArray? {
        if (!Regex("^[A-Za-z0-9_-]*$").matches(value) || value.length % 4 == 1) return null
        return runCatching { Base64.getUrlDecoder().decode(value) }.getOrNull()
    }

    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

    fun randomBytes(count: Int): ByteArray = ByteArray(count).also(random::nextBytes)

    fun randomHex(bytes: Int): String = hex(randomBytes(bytes))

    /** A UUID's hex without dashes, lowercased (iOS batch and device ids). */
    fun compactUuid(): String = java.util.UUID.randomUUID().toString().replace("-", "").lowercase()

    // region X25519 and HKDF

    class KeyPair(val privateKey: ByteArray) {
        val publicKey: ByteArray = X25519.publicFromPrivate(privateKey)
    }

    fun newKeyPair(): KeyPair = KeyPair(X25519.generatePrivateKey())

    fun sharedSecret(privateKey: ByteArray, peerPublic: ByteArray): ByteArray = X25519.computeSharedSecret(privateKey, peerPublic)

    fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int = 32): ByteArray =
        Hkdf.computeHkdf("HMACSHA256", ikm, salt, info, length)

    /** `sha256(label + devicePublic + hostPublic)` in hex, first 32 characters. */
    fun keyID(label: String, devicePublic: ByteArray, hostPublic: ByteArray): String =
        hex(sha256(label.toByteArray() + devicePublic + hostPublic)).take(32)

    /**
     * The agreement every store makes: checks the host's key and id, then
     * derives one 32-byte key per purpose with info
     * `purpose + "\n" + deviceID + "\n" + devicePublic + hostPublic`.
     */
    fun agree(
        devicePrivate: ByteArray,
        deviceID: String,
        hostPublicKey: String,
        kid: String,
        kidLabel: String,
        salt: String,
        purposes: List<String>,
    ): List<ByteArray> {
        val hostPublic = base64UrlDecoded(hostPublicKey)?.takeIf { it.size == 32 } ?: throw Failure("malformedHostKey")
        val devicePublic = X25519.publicFromPrivate(devicePrivate)
        if (keyID(kidLabel, devicePublic, hostPublic) != kid) throw Failure("keyIDMismatch")
        val shared = try {
            sharedSecret(devicePrivate, hostPublic)
        } catch (e: Exception) {
            throw Failure("malformedHostKey")
        }
        return purposes.map { purpose ->
            val info = purpose.toByteArray() + "\n$deviceID\n".toByteArray() + devicePublic + hostPublic
            hkdf(shared, salt.toByteArray(), info)
        }
    }

    // endregion

    // region ChaCha20-Poly1305

    class Sealed(val nonce: String, val ct: String)

    fun seal(plaintext: ByteArray, key: ByteArray, aad: ByteArray, nonce: ByteArray = randomBytes(12)): Sealed {
        val box = InsecureNonceChaCha20Poly1305(key).encrypt(nonce, plaintext, aad)
        return Sealed(base64Url(nonce), base64Url(box))
    }

    /** Opens `{nonce, ct}` (ct is ciphertext + 16-byte tag). */
    fun open(nonceText: String?, ctText: String?, key: ByteArray, aad: ByteArray): ByteArray {
        val nonce = nonceText?.let(::base64UrlDecoded)?.takeIf { it.size == 12 } ?: throw Failure("malformedAnswer")
        val ct = ctText?.let(::base64UrlDecoded)?.takeIf { it.size >= 16 } ?: throw Failure("malformedAnswer")
        return try {
            InsecureNonceChaCha20Poly1305(key).decrypt(nonce, ct, aad)
        } catch (e: Exception) {
            throw Failure("authenticationFailure")
        }
    }

    fun open(response: JsonObject, key: ByteArray, aad: ByteArray): ByteArray =
        open(response["nonce"].str, response["ct"].str, key, aad)

    /** Associated data: lines joined with "\n". */
    fun aad(vararg lines: String): ByteArray = lines.joinToString("\n").toByteArray()

    // endregion
}

/**
 * The sealing a live voice call's read of the host uses (POST
 * /voice-tools/call), shared by health and location (iOS `VoiceReadSeal`).
 */
object VoiceReadSeal {
    fun aad(tag: String, direction: String, tool: String, kid: String, deviceID: String, callID: String): ByteArray =
        SealCrypto.aad(tag, "voice-$direction", "tool=$tool", "kid=$kid", "dev=$deviceID", "call=$callID")

    fun newCallID(): String = SealCrypto.base64Url(SealCrypto.randomBytes(16))

    fun request(
        tag: String,
        tool: String,
        plaintext: ByteArray,
        requestKey: ByteArray,
        kid: String,
        deviceID: String,
        callID: String,
        nonce: ByteArray = SealCrypto.randomBytes(12),
    ): JsonObject {
        if (!VoiceHostTool.isToolName(tool)) throw SealCrypto.Failure("badToolName")
        val sealed = SealCrypto.seal(plaintext, requestKey, aad(tag, "request", tool, kid, deviceID, callID), nonce)
        return buildJsonObject {
            put("v", 1)
            put("tool", tool)
            put("device_id", deviceID)
            put("kid", kid)
            put("call_id", callID)
            put("nonce", sealed.nonce)
            put("ct", sealed.ct)
        }
    }

    /** The opened answer's `result`, checked against the tool and call it answers. */
    fun openAnswer(
        response: JsonObject,
        tag: String,
        tool: String,
        answerKey: ByteArray,
        kid: String,
        deviceID: String,
        callID: String,
    ): JsonElement {
        if (!VoiceHostTool.isToolName(tool)) throw SealCrypto.Failure("badToolName")
        val plaintext = SealCrypto.open(response, answerKey, aad(tag, "answer", tool, kid, deviceID, callID))
        val obj = runCatching { ConduitJson.parseToJsonElement(plaintext.decodeToString()).obj }.getOrNull()
            ?: throw SealCrypto.Failure("malformedAnswer")
        if (obj["call_id"].str != callID) throw SealCrypto.Failure("malformedAnswer")
        return obj["result"] ?: throw SealCrypto.Failure("malformedAnswer")
    }
}

/** JSON with sorted keys, as iOS serializes what it seals or hashes. */
object CanonicalJson {
    fun encode(element: JsonElement): String = StringBuilder().also { write(element, it) }.toString()

    private fun write(element: JsonElement, out: StringBuilder) {
        when (element) {
            is JsonObject -> {
                out.append('{')
                element.keys.sorted().forEachIndexed { index, key ->
                    if (index > 0) out.append(',')
                    out.append(JsonPrimitive(key).toString()).append(':')
                    write(element.getValue(key), out)
                }
                out.append('}')
            }
            is JsonArray -> {
                out.append('[')
                element.forEachIndexed { index, item ->
                    if (index > 0) out.append(',')
                    write(item, out)
                }
                out.append(']')
            }
            is JsonNull -> out.append("null")
            is JsonPrimitive -> out.append(number(element) ?: element.toString())
        }
    }

    /** Whole doubles as integers ("12", not "12.0"), as JSONSerialization writes them. */
    private fun number(primitive: JsonPrimitive): String? {
        if (primitive.isString) return null
        val text = primitive.content
        if (text == "true" || text == "false" || text == "null") return null
        val value = text.toDoubleOrNull() ?: return null
        if (text.contains('.') || text.contains('e') || text.contains('E')) {
            if (value.isFinite() && value == Math.rint(value) && kotlin.math.abs(value) < 1e15) return value.toLong().toString()
        }
        return null
    }

    fun bytes(element: JsonElement): ByteArray = encode(element).toByteArray()
}

/** A JSON-able value from a plain Kotlin value (maps, lists, strings, numbers, booleans). */
fun jsonOf(value: Any?): JsonElement = when (value) {
    null -> JsonNull
    is JsonElement -> value
    is String -> JsonPrimitive(value)
    is Boolean -> JsonPrimitive(value)
    is Int -> JsonPrimitive(value)
    is Long -> JsonPrimitive(value)
    is Double -> JsonPrimitive(value)
    is Float -> JsonPrimitive(value.toDouble())
    is Number -> JsonPrimitive(value)
    is Map<*, *> -> JsonObject(value.entries.associate { (k, v) -> k.toString() to jsonOf(v) })
    is Iterable<*> -> JsonArray(value.map(::jsonOf))
    is Array<*> -> JsonArray(value.map(::jsonOf))
    else -> JsonPrimitive(value.toString())
}

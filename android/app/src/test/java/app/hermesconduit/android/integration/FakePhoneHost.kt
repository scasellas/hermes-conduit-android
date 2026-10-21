package app.hermesconduit.android.integration

import app.hermesconduit.android.model.ConduitJson
import app.hermesconduit.android.model.PhoneSessionKey
import app.hermesconduit.android.model.PhoneSyncCrypto
import app.hermesconduit.android.model.SealCrypto
import app.hermesconduit.android.model.arr
import app.hermesconduit.android.model.obj
import app.hermesconduit.android.model.str
import app.hermesconduit.android.net.DashboardHttpException
import app.hermesconduit.android.ui.phone.PhoneSyncEngine
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The Conduit plugin's phone endpoints (status, key, ingest, exchange), with
 * real key agreement and sealing, so a pass runs end to end as with a host.
 */
class FakePhoneHost {
    private val hostKey = SealCrypto.newKeyPair()
    private var keys: PhoneSessionKey? = null
    val batches = mutableListOf<JsonObject>()
    val reported = mutableListOf<JsonObject>()
    var changes: List<JsonObject> = emptyList()

    @Synchronized
    fun handle(method: String, path: String, query: Map<String, String?>, body: JsonElement?): JsonObject {
        val base = PhoneSyncEngine.BASE
        val o = body?.obj
        if (path == "$base/status") return buildJsonObject {
            put("generation", "g1")
            keys?.let { k -> put("device", buildJsonObject { put("kid", k.kid) }) }
        }
        if (path == "$base/key") {
            val deviceID = o!!["device_id"].str!!
            val devicePublic = SealCrypto.base64UrlDecoded(o["public_key"].str!!)!!
            val kid = PhoneSyncCrypto.keyID(devicePublic, hostKey.publicKey)
            val shared = SealCrypto.sharedSecret(hostKey.privateKey, devicePublic)
            fun derive(purpose: String) = SealCrypto.base64Url(
                SealCrypto.hkdf(shared, PhoneSyncCrypto.SALT.toByteArray(), purpose.toByteArray() + "\n$deviceID\n".toByteArray() + devicePublic + hostKey.publicKey),
            )
            keys = PhoneSessionKey(kid, derive(PhoneSyncCrypto.INGEST_INFO), derive(PhoneSyncCrypto.EXCHANGE_REQUEST_INFO), derive(PhoneSyncCrypto.EXCHANGE_ANSWER_INFO))
            return buildJsonObject { put("host_public_key", SealCrypto.base64Url(hostKey.publicKey)); put("kid", kid); put("generation", "g1") }
        }
        val k = keys
        if (k == null || o!!["kid"].str != k.kid) throw DashboardHttpException(409, "unknown_key")
        val deviceID = o["device_id"].str!!
        when (path) {
            "$base/ingest" -> {
                val plaintext = SealCrypto.open(o, k.ingestBytes, PhoneSyncCrypto.ingestAAD(k.kid, deviceID, o["batch_id"].str!!))
                batches += ConduitJson.parseToJsonElement(plaintext.decodeToString()).obj!!
                return buildJsonObject { put("ok", true); put("pending", changes.size) }
            }
            "$base/exchange" -> {
                val op = o["op"].str!!
                val callID = o["call_id"].str!!
                val request = ConduitJson.parseToJsonElement(
                    SealCrypto.open(o, k.requestBytes, PhoneSyncCrypto.exchangeAAD("request", op, k.kid, deviceID, callID)).decodeToString(),
                ).obj!!
                reported += request["results"].arr.orEmpty().mapNotNull { it.obj }
                val answered = reported.mapNotNull { it["id"].str }.toSet()
                val waiting = changes.filter { it["id"].str !in answered }
                val answer = buildJsonObject { put("call_id", callID); put("changes", JsonArray(waiting)) }
                val sealed = SealCrypto.seal(answer.toString().toByteArray(), k.answerBytes, PhoneSyncCrypto.exchangeAAD("answer", op, k.kid, deviceID, callID))
                return buildJsonObject { put("ok", true); put("v", 1); put("nonce", sealed.nonce); put("ct", sealed.ct) }
            }
        }
        throw DashboardHttpException(404, path)
    }
}

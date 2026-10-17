package app.hermesconduit.android

import app.hermesconduit.android.model.PushE2E
import app.hermesconduit.android.model.PushKeyRecord
import app.hermesconduit.android.model.SealCrypto
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.util.zip.Deflater

/**
 * Seals a push the way the notifier plugin's `e2e.seal_event` does
 * (docs/ANDROID_PUSH.md section 5), for tests: the UnifiedPush body with the
 * routing stub and the `conduit_e2e` envelope.
 */
object PushTestSeal {
    fun body(
        record: PushKeyRecord,
        type: String,
        content: JsonObject,
        iat: Long,
        msg: String = "m-" + SealCrypto.randomHex(8),
        tok: String = SealCrypto.randomHex(8),
        req: String = "",
        compress: Boolean = false,
    ): ByteArray = message(record, type, content, iat, msg, tok, req, compress).toString().toByteArray()

    fun message(
        record: PushKeyRecord,
        type: String,
        content: JsonObject,
        iat: Long,
        msg: String = "m-" + SealCrypto.randomHex(8),
        tok: String = SealCrypto.randomHex(8),
        req: String = "",
        compress: Boolean = false,
    ): JsonObject {
        val z = if (compress) 1 else 0
        val plaintext = content.toString().toByteArray().let { if (compress) deflate(it) else it }
        val aad = PushE2E.pushAAD(record.kid, record.installationID, record.gatewayID, msg, type, iat, tok, z, req)
        val sealed = SealCrypto.seal(plaintext, PushE2E.keys(record.secret).push, aad)
        return buildJsonObject {
            put("conduit", buildJsonObject { put("type", type); put("e2e", 1) })
            put("conduit_e2e", buildJsonObject {
                put("v", 1); put("kid", record.kid); put("msg", msg); put("iat", iat); put("tok", tok)
                put("z", z); put("req", req); put("n", sealed.nonce); put("ct", sealed.ct)
            })
        }
    }

    private fun deflate(data: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, true)
        deflater.setInput(data)
        deflater.finish()
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(1024)
        while (!deflater.finished()) out.write(buffer, 0, deflater.deflate(buffer))
        deflater.end()
        return out.toByteArray()
    }
}

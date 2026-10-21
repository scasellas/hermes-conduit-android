package app.hermesconduit.android.integration

import org.robolectric.pluginapi.TestEnvironmentLifecyclePlugin
import java.io.InputStream
import java.io.OutputStream
import java.security.Key
import java.security.KeyStore
import java.security.KeyStoreSpi
import java.security.Provider
import java.security.SecureRandom
import java.security.Security
import java.security.cert.Certificate
import java.security.spec.AlgorithmParameterSpec
import java.util.Collections
import java.util.Date
import java.util.Enumeration
import javax.crypto.KeyGenerator
import javax.crypto.KeyGeneratorSpi
import javax.crypto.SecretKey

/**
 * Robolectric has no Android Keystore, which EncryptedSharedPreferences (the
 * ConnectionStore) needs before the first screen draws. This stands in an
 * in-memory "AndroidKeyStore" provider whose AES keys are ordinary JCA keys,
 * installed before each test's Application is created.
 */
class FakeAndroidKeyStore : TestEnvironmentLifecyclePlugin {
    override fun onSetupApplicationState() {
        if (Security.getProvider(NAME) == null) Security.addProvider(FakeProvider())
        keys.clear()
    }

    class FakeProvider : Provider(NAME, 1.0, "In-memory Android Keystore for Robolectric") {
        init {
            put("KeyStore.$NAME", Store::class.java.name)
            put("KeyGenerator.AES", AesGenerator::class.java.name)
            put("KeyGenerator.HmacSHA256", HmacGenerator::class.java.name)
        }
    }

    class Store : KeyStoreSpi() {
        override fun engineGetKey(alias: String, password: CharArray?): Key? = keys[alias]
        override fun engineGetCertificateChain(alias: String): Array<Certificate>? = null
        override fun engineGetCertificate(alias: String): Certificate? = null
        override fun engineGetCreationDate(alias: String): Date = Date()
        override fun engineSetKeyEntry(alias: String, key: Key, password: CharArray?, chain: Array<out Certificate>?) {
            keys[alias] = key as SecretKey
        }
        override fun engineSetKeyEntry(alias: String, key: ByteArray, chain: Array<out Certificate>?) = throw UnsupportedOperationException()
        override fun engineSetCertificateEntry(alias: String, cert: Certificate) = throw UnsupportedOperationException()
        override fun engineDeleteEntry(alias: String) {
            keys.remove(alias)
        }
        override fun engineAliases(): Enumeration<String> = Collections.enumeration(keys.keys.toList())
        override fun engineContainsAlias(alias: String) = keys.containsKey(alias)
        override fun engineSize() = keys.size
        override fun engineIsKeyEntry(alias: String) = keys.containsKey(alias)
        override fun engineIsCertificateEntry(alias: String) = false
        override fun engineGetCertificateAlias(cert: Certificate): String? = null
        override fun engineStore(stream: OutputStream?, password: CharArray?) = Unit
        override fun engineLoad(stream: InputStream?, password: CharArray?) = Unit
        override fun engineLoad(param: KeyStore.LoadStoreParameter?) = Unit
    }

    /** Generates a plain key and files it under the KeyGenParameterSpec's alias. */
    abstract class AliasedGenerator(private val algorithm: String, private val bits: Int) : KeyGeneratorSpi() {
        private var alias: String? = null

        override fun engineInit(random: SecureRandom?) = Unit
        override fun engineInit(keysize: Int, random: SecureRandom?) = Unit
        override fun engineInit(params: AlgorithmParameterSpec?, random: SecureRandom?) {
            // android.security.keystore.KeyGenParameterSpec#getKeystoreAlias, without linking the Android class.
            alias = params?.javaClass?.getMethod("getKeystoreAlias")?.invoke(params) as? String
        }

        override fun engineGenerateKey(): SecretKey {
            val key = KeyGenerator.getInstance(algorithm, "SunJCE").apply { init(bits) }.generateKey()
            alias?.let { keys[it] = key }
            return key
        }
    }

    class AesGenerator : AliasedGenerator("AES", 256)
    class HmacGenerator : AliasedGenerator("HmacSHA256", 256)

    companion object {
        const val NAME = "AndroidKeyStore"
        val keys: MutableMap<String, SecretKey> = Collections.synchronizedMap(mutableMapOf())
    }
}

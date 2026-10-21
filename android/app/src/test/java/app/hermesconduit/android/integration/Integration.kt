package app.hermesconduit.android.integration

import android.os.Looper
import androidx.compose.ui.test.SemanticsNodeInteractionCollection
import androidx.test.core.app.ApplicationProvider
import app.hermesconduit.android.ConduitApplication
import app.hermesconduit.android.core.LinkState
import app.hermesconduit.android.data.SavedConnection
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import androidx.startup.InitializationProvider
import org.junit.Assert.fail
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import java.time.Duration

/** Shared plumbing for the Robolectric tests that drive the real app against [FakeHermes]. */
object Integration {
    val app: ConduitApplication get() = ApplicationProvider.getApplicationContext()

    /**
     * Runs the main looper (advancing its clock, so coroutine delays on Main
     * fire) until [condition] holds, while OkHttp works on its own threads.
     */
    fun pump(timeoutMs: Long = 10_000, message: String = "condition", diagnostics: () -> String = { "" }, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        val looper = shadowOf(Looper.getMainLooper())
        while (true) {
            looper.idleFor(Duration.ofMillis(10))
            if (runCatching(condition).getOrDefault(false)) return
            if (System.currentTimeMillis() > deadline) fail("Timed out waiting for $message\n" + runCatching(diagnostics).getOrElse { it.toString() })
            Thread.sleep(5)
        }
    }

    /** Keeps the main looper running for [ms] of wall time (letting sockets deliver), without a condition. */
    fun settle(ms: Long = 300) {
        val end = System.currentTimeMillis() + ms
        val looper = shadowOf(Looper.getMainLooper())
        while (System.currentTimeMillis() < end) {
            looper.idleFor(Duration.ofMillis(10))
            Thread.sleep(5)
        }
    }

    /**
     * Robolectric creates no manifest ContentProviders, so androidx.startup's
     * initializers (ProcessLifecycleOwner, WorkManager) wait for this, as the
     * system runs them at process start.
     */
    fun runStartupInitializers() {
        Robolectric.setupContentProvider(InitializationProvider::class.java, "${app.packageName}.androidx-startup")
    }

    /** Signs in with a password through the real connection, as the login screen does. */
    fun signIn(fake: FakeHermes) = runBlocking {
        app.connection.signInWithPassword(SavedConnection(baseUrl = fake.baseUrl, username = FakeHermes.USER, password = FakeHermes.PASSWORD))
    }

    /** Waits for the gateway socket, and for the app to have said hello on it. */
    fun awaitGateway(fake: FakeHermes) = pump(message = "gateway socket") {
        fake.connected && fake.calls("client.capabilities").isNotEmpty() && app.connection.link.value == LinkState.CONNECTED
    }

    fun awaitRpc(fake: FakeHermes, method: String, predicate: (FakeHermes.Rpc) -> Boolean = { true }): FakeHermes.Rpc {
        var found: FakeHermes.Rpc? = null
        pump(message = "RPC $method") { fake.calls(method).firstOrNull(predicate)?.also { found = it } != null }
        return found!!
    }

    /** Stops the app's long-lived coroutines so nothing outlives the test. */
    fun tearDown() {
        runCatching { app.connection.scope.cancel() }
    }

    fun SemanticsNodeInteractionCollection.exists() = fetchSemanticsNodes().isNotEmpty()
}

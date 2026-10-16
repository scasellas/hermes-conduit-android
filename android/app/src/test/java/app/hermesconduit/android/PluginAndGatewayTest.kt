package app.hermesconduit.android

import app.hermesconduit.android.model.ConduitJson
import app.hermesconduit.android.model.GatewayParser
import app.hermesconduit.android.model.GatewayRestartAction
import app.hermesconduit.android.model.GatewayRestartMonitor
import app.hermesconduit.android.model.GatewayRestartStage
import app.hermesconduit.android.model.GatewayRestartState
import app.hermesconduit.android.model.GatewayRunStatus
import app.hermesconduit.android.model.HeaderIssue
import app.hermesconduit.android.model.HeaderRow
import app.hermesconduit.android.model.HeaderRules
import app.hermesconduit.android.model.PluginStatus
import app.hermesconduit.android.model.PluginUpdateRules
import app.hermesconduit.android.model.PluginUpdateState
import app.hermesconduit.android.net.DashboardHttpException
import app.hermesconduit.android.ui.gateway.GatewayRestarter
import app.hermesconduit.android.ui.plugin.PluginUpdater
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginAndGatewayTest {
    private fun obj(json: String) = ConduitJson.parseToJsonElement(json) as JsonObject

    // region Plugin status

    @Test
    fun versionsCompareNumerically() {
        assertTrue(PluginStatus.isOlder("0.9.1", "0.10.0"))
        assertFalse(PluginStatus.isOlder("0.41.0", "0.41"))
        assertFalse(PluginStatus.isOlder("1.0.0-beta", "0.41.0"))
        assertTrue(PluginStatus.isOlder(null, "0.1"))
        assertTrue(PluginStatus.isOlder("garbage", "0.1"))
    }

    @Test
    fun reportedPluginParsesCapabilitiesAndRuntime() {
        val status = PluginStatus.parse(
            obj("""{"ok":true,"version":"0.41.2","capabilities":["artifacts","cron-notifications"],"loaded_version":"0.40.0","restart_needed":true,"boot_id":"b1"}"""),
        )
        assertEquals(setOf("artifacts", "cron-notifications"), status.capabilities)
        assertTrue(status.reports("artifacts"))
        assertFalse(status.mightSupport("pairing"))
        assertEquals("0.40.0", status.runningVersion)
        assertTrue(status.restartPending)
        // New enough on disk: only the restart is left, no update offer.
        assertFalse(status.updateAvailable)
        assertFalse(status.predatesOneTapUpdate)
    }

    @Test
    fun missingRouteOrMalformedAnswer() {
        assertEquals(PluginStatus.State.PredatesCapabilities, PluginStatus.stateForHttpError(404))
        assertEquals(PluginStatus.State.Unknown, PluginStatus.stateForHttpError(500))
        assertEquals(PluginStatus.State.Unknown, PluginStatus.state(obj("""{"ok":false}""")))
        val predates = PluginStatus(PluginStatus.State.PredatesCapabilities)
        assertTrue(predates.updateAvailable)
        assertTrue(predates.predatesOneTapUpdate)
        assertTrue(predates.mightSupport("anything"))
    }

    @Test
    fun oldPluginWithoutRuntimeNeedsUpdate() {
        val status = PluginStatus.parse(obj("""{"ok":true,"version":"0.20.0","capabilities":["pairing"]}"""))
        assertNull(status.runtime)
        assertFalse(status.restartPending)
        assertTrue(status.predatesOneTapUpdate)
        assertTrue(status.updateAvailable)
        assertEquals("0.20.0", status.runningVersion)
    }

    @Test
    fun updateRules() {
        assertTrue(PluginUpdateRules.isNotInstalled(400, "Plugin 'conduit_push' was not found in plugins"))
        assertTrue(PluginUpdateRules.needsReinstall(400, "conduit_push has no owned Git checkout; reinstall"))
        assertFalse(PluginUpdateRules.isNotInstalled(404, "not found"))
        assertEquals(2, PluginUpdateRules.runningChats("2 chat turns still running"))
        assertEquals(1, PluginUpdateRules.runningChats("busy"))
        assertEquals("1:05", PluginUpdateRules.elapsed(65))
    }

    // endregion

    // region Gateway

    @Test
    fun gatewayStatusParsing() {
        val status = GatewayRunStatus.parse(
            obj("""{"gateway_running":true,"gateway_state":"degraded","gateway_pid":42,"gateway_shared_with":["work","default","alpha","work"]}"""),
        )!!
        assertTrue(status.isServing)
        assertEquals(42L, status.pid)
        assertEquals(listOf("default", "alpha", "work"), status.sharedProfiles)
        assertFalse(GatewayRunStatus(true, "draining").isServing)
        assertNull(GatewayRunStatus.parse(obj("""{"version":"1"}""")))
        assertEquals(emptyList<String>(), GatewayRunStatus.sharedProfiles(listOf("default")))
    }

    @Test
    fun actionLatestLineSkipsBanners() {
        val action = GatewayRestartAction.parse(obj("""{"running":false,"exit_code":0,"lines":["Stopping","Started gateway","=== gateway-restart started ===","  "]}"""))!!
        assertEquals("Started gateway", action.latestLine)
        assertEquals(0L, action.exitCode)
    }

    @Test
    fun monitorVerdicts() {
        val before = GatewayRunStatus(running = true, state = "running", pid = 1)
        val monitor = GatewayRestartMonitor(before)
        assertEquals(
            GatewayRestartMonitor.Verdict.Waiting(GatewayRestartStage.DRAINING),
            monitor.observe(GatewayRestartAction(running = true), GatewayRunStatus(true, "draining", 1), 2.0),
        )
        assertEquals(
            GatewayRestartMonitor.Verdict.Waiting(GatewayRestartStage.STARTING),
            monitor.observe(GatewayRestartAction(running = true), GatewayRunStatus(false, "stopped"), 4.0),
        )
        val back = monitor.observe(null, GatewayRunStatus(true, "running", 2), 6.0)
        assertTrue(back is GatewayRestartMonitor.Verdict.Back)

        // A new PID alone counts as the restart having happened.
        assertTrue(GatewayRestartMonitor(before).observe(null, GatewayRunStatus(true, "running", 9), 1.0) is GatewayRestartMonitor.Verdict.Back)
        // Same PID and still serving: wait until the grace period.
        assertEquals(
            GatewayRestartMonitor.Verdict.Waiting(GatewayRestartStage.STOPPING),
            GatewayRestartMonitor(before).observe(null, GatewayRunStatus(true, "running", 1), 1.0),
        )
        assertTrue(GatewayRestartMonitor(before).observe(null, GatewayRunStatus(true, "running", 1), 31.0) is GatewayRestartMonitor.Verdict.Back)
        // A failing child.
        assertEquals(
            GatewayRestartMonitor.Verdict.Failed("bad"),
            GatewayRestartMonitor(before).observe(GatewayRestartAction(false, 1, listOf("bad")), null, 1.0),
        )
        // Out of time.
        assertEquals(
            GatewayRestartMonitor.Verdict.FailedToStart,
            GatewayRestartMonitor(before).observe(null, GatewayRunStatus(false, "startup_failed"), 601.0),
        )
    }

    @Test
    fun restarterFollowsTheGatewayBack() = runTest {
        var clock = 0L
        var statusCalls = 0
        val states = mutableListOf<GatewayRestartState>()
        val restarter = GatewayRestarter(
            request = { method, path, _, _ ->
                when {
                    method == "POST" && path == GatewayRestarter.RESTART_PATH -> obj("""{"ok":true}""")
                    path == GatewayRestarter.ACTION_STATUS_PATH -> throw DashboardHttpException(404, "Not Found")
                    path == GatewayRestarter.STATUS_PATH -> {
                        statusCalls++
                        when (statusCalls) {
                            1 -> obj("""{"gateway_running":true,"gateway_state":"running","gateway_pid":10}""")
                            2 -> obj("""{"gateway_running":false,"gateway_state":"stopped"}""")
                            else -> obj("""{"gateway_running":true,"gateway_state":"running","gateway_pid":11,"gateway_shared_with":["default","work"]}""")
                        }
                    }
                    else -> error("unexpected $method $path")
                }
            },
            sleep = { clock += it },
            now = { clock },
        )
        val outcome = restarter.run { states += it }
        assertEquals(GatewayRestartState.Restarted(listOf("default", "work")), outcome)
        assertEquals(GatewayRestartState.Requesting, states.first())
        assertTrue(states.any { it == GatewayRestartState.Restarting(GatewayRestartStage.STARTING, null) })
    }

    @Test
    fun restarterReportsAnOldDashboard() = runTest {
        val restarter = GatewayRestarter(request = { method, _, _, _ ->
            if (method == "POST") throw DashboardHttpException(405, "Method Not Allowed") else obj("{}")
        })
        assertEquals(GatewayRestartState.Unsupported, restarter.run {})
    }

    @Test
    fun updaterInstallsWhenThePluginIsMissingAndFinishesWithoutRestart() = runTest {
        var clock = 0L
        val calls = mutableListOf<String>()
        var statusCalls = 0
        val updater = PluginUpdater(
            request = { method, path, _, body ->
                calls += "$method $path"
                when (path) {
                    PluginUpdateRules.UPDATE_PATH -> throw DashboardHttpException(400, "Plugin 'conduit_push' was not found")
                    PluginUpdateRules.INSTALL_PATH -> {
                        assertEquals(PluginStatus.REPOSITORY, (body as JsonObject)["identifier"].toString().trim('"'))
                        obj("""{"ok":true}""")
                    }
                    GatewayRestarter.RESTART_PATH -> obj("{}")
                    GatewayRestarter.ACTION_STATUS_PATH -> obj("""{"running":false,"exit_code":0,"lines":[]}""")
                    GatewayRestarter.STATUS_PATH -> {
                        statusCalls++
                        obj("""{"gateway_running":true,"gateway_state":"running"}""")
                    }
                    PluginStatus.PATH -> obj("""{"ok":true,"version":"0.41.0","capabilities":[],"loaded_version":"0.41.0","restart_needed":false}""")
                    else -> error(path)
                }
            },
            sleep = { clock += it },
            now = { clock },
        )
        val outcome = updater.run {}
        assertEquals(PluginUpdateState.Updated("0.41.0"), outcome)
        assertTrue("POST ${PluginUpdateRules.INSTALL_PATH}" in calls)
    }

    @Test
    fun diagnosticsParsing() {
        val d = GatewayParser.diagnostics(
            obj("""{"gateway_running":false,"gateway_state":"stopped","version":"0.9","gateway_pid":7}"""),
            obj("""{"platforms":[{"name":"Telegram","state":"connected","configured":true},{"platform":"discord","status":"error","error":"bad token"}]}"""),
            obj("""{"lines":["a","b"]}"""),
            connected = true,
        )
        assertFalse(d.gatewayRunning)
        assertEquals(7L, d.pid)
        assertEquals(listOf("Telegram", "discord"), d.connectors.map { it.name })
        assertEquals("error", d.connectors[1].state)
        assertEquals(listOf("a", "b"), d.logs)
        assertNull(d.error)
        assertEquals("Could not refresh dashboard status.", GatewayParser.diagnostics(null, null, null, true).error)
        assertEquals(0, GatewayParser.connectorHealth("Online"))
        assertEquals(2, GatewayParser.connectorHealth("error"))
    }

    // endregion

    // region Extra headers

    @Test
    fun headerValidation() {
        assertEquals(HeaderIssue.EMPTY_NAME, HeaderRules.issue(" ", "v"))
        assertEquals(HeaderIssue.INVALID_NAME, HeaderRules.issue("X Bad", "v"))
        assertEquals(HeaderIssue.RESERVED_NAME, HeaderRules.issue("Cookie", "v"))
        assertEquals(HeaderIssue.RESERVED_NAME, HeaderRules.issue("Sec-Fetch-Mode", "v"))
        assertEquals(HeaderIssue.INVALID_VALUE, HeaderRules.issue("X-Token", "a\nb"))
        assertNull(HeaderRules.issue("X-Token", "a\tb"))
        assertNull(HeaderRules.issue(HeaderRow(1, "", "")))
    }

    @Test
    fun headerSendableAndDuplicates() {
        val rows = listOf(HeaderRow(1, " X-A ", "1"), HeaderRow(2, "x-a", "2"), HeaderRow(3, "Host", "h"), HeaderRow(4, "X-B", "3"))
        assertEquals(mapOf("X-A" to "1", "X-B" to "3"), HeaderRules.sendable(rows))
        assertEquals(setOf("x-a"), HeaderRules.duplicateNames(rows))
    }

    @Test
    fun headerOrigins() {
        assertEquals("https://example.com", HeaderRules.origin("https://example.com:443/hermes"))
        assertEquals("https://example.com:8443", HeaderRules.origin("https://Example.com:8443"))
        assertNull(HeaderRules.origin("http://192.168.1.2:9119"))
    }

    // endregion
}

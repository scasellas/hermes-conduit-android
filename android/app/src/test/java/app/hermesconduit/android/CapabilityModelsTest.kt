package app.hermesconduit.android

import app.hermesconduit.android.model.AgentPluginRow
import app.hermesconduit.android.model.CapabilityFormat
import app.hermesconduit.android.model.CapabilityParsers
import app.hermesconduit.android.model.CapabilityRestartMonitor
import app.hermesconduit.android.model.CapabilitySkill
import app.hermesconduit.android.model.CapabilitySkillOrdering
import app.hermesconduit.android.model.CapabilityToolset
import app.hermesconduit.android.model.CapabilityToolsetOrdering
import app.hermesconduit.android.model.ConnectorCatalogEntry
import app.hermesconduit.android.model.ConnectorGrouping
import app.hermesconduit.android.model.ConnectorProbe
import app.hermesconduit.android.model.ConnectorServer
import app.hermesconduit.android.model.ConnectorState
import app.hermesconduit.android.model.CapabilityRestartState
import app.hermesconduit.android.model.HermesActionStatus
import app.hermesconduit.android.model.OfficialSkill
import app.hermesconduit.android.model.PluginCatalogEntry
import app.hermesconduit.android.model.PluginResponses
import app.hermesconduit.android.model.PluginUpdateOutcome
import app.hermesconduit.android.model.SkillFrontmatter
import app.hermesconduit.android.model.TerminalBackendOption
import app.hermesconduit.android.model.ToolsetConfig
import app.hermesconduit.android.net.DashboardApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CapabilityModelsTest {
    private fun json(text: String) = DashboardApi.normalize(text)

    // Skills

    @Test
    fun installedSkillsAndToolsetsParseFromTopLevelArrays() {
        val skills = CapabilityParsers.skills(
            json("""[{"name":"b","category":"z","enabled":true,"usage":3,"provenance":"hub"},{"name":"a","category":"z"},{"nope":1},"x"]"""),
        )
        assertEquals(listOf("a", "b"), skills.map { it.name })
        assertEquals(CapabilitySkill("b", category = "z", enabled = true, provenance = "hub", usage = 3), skills[1])
        assertFalse(skills[0].enabled)

        val toolsets = CapabilityParsers.toolsets(
            json("""[{"name":"web","label":"🌐 Web","enabled":true,"configured":false,"tools":["web_search","web_extract",7]}]"""),
        )
        assertEquals(listOf("web_search", "web_extract"), toolsets.single().tools)
        assertEquals(false, toolsets.single().configured)
        assertTrue(CapabilityParsers.skills(json("""{"detail":"x"}""")).isEmpty())
    }

    @Test
    fun officialSkillsFallBackOnIdentifierAndCategory() {
        val list = OfficialSkill.list(
            json("""{"skills":[{"name":"pdf","identifier":"","tags":["docs"]},{"name":"","identifier":"x"},{"name":"git","identifier":"official/git","category":"dev","installed":true}]}"""),
        )
        assertEquals(2, list.size)
        assertEquals("pdf", list[0].identifier)
        assertEquals("general", list[0].category)
        assertEquals("official/git", list[1].identifier)
        assertTrue(list[1].installed)
    }

    @Test
    fun availableSkillsSkipInstalledAndMatchTags() {
        val catalog = listOf(
            OfficialSkill("zeta", "zeta", tags = listOf("Documents")),
            OfficialSkill("alpha", "alpha"),
            OfficialSkill("done", "done", installed = true),
            OfficialSkill("mine", "mine"),
        )
        assertEquals(listOf("alpha", "zeta"), CapabilitySkillOrdering.available(catalog, setOf("mine"), "").map { it.name })
        assertEquals(listOf("zeta"), CapabilitySkillOrdering.available(catalog, emptySet(), "docu").map { it.name })
    }

    @Test
    fun skillsSortByUsageThenName() {
        val skills = listOf(CapabilitySkill("b", usage = 1), CapabilitySkill("A"), CapabilitySkill("c", usage = 5), CapabilitySkill("a2"))
        assertEquals(listOf("c", "b", "A", "a2"), CapabilitySkillOrdering.sorted(skills, mostUsedFirst = true).map { it.name })
        assertEquals(listOf("A", "a2", "b", "c"), CapabilitySkillOrdering.sorted(skills, mostUsedFirst = false).map { it.name })
        assertTrue(CapabilitySkillOrdering.matches(CapabilitySkill("x", category = "Software"), "soft"))
    }

    @Test
    fun prettyCategory() {
        assertEquals("Software Development", CapabilitySkillOrdering.prettyCategory("software_development"))
        assertEquals("Data Science Tools", CapabilitySkillOrdering.prettyCategory("data-science_tools"))
        assertEquals("General", CapabilitySkillOrdering.prettyCategory(null))
        assertEquals("General", CapabilitySkillOrdering.prettyCategory("  "))
    }

    @Test
    fun frontmatterReadsTopLevelFieldsOnly() {
        val doc = SkillFrontmatter.parse(
            "---\r\nname: pdf\ndescription: \"Reads PDFs\"\nmetadata:\n  nested: no\n# comment\n- item\nempty:\nversion: '1.2'\n---\n\n# Body\nText\n",
        )
        assertEquals(
            listOf(SkillFrontmatter.Field("name", "pdf"), SkillFrontmatter.Field("description", "Reads PDFs"), SkillFrontmatter.Field("version", "1.2")),
            doc.fields,
        )
        assertEquals("# Body\nText", doc.body)

        val plain = SkillFrontmatter.parse("  just text \n")
        assertTrue(plain.fields.isEmpty())
        assertEquals("just text", plain.body)
        assertEquals("---\nunclosed: yes", SkillFrontmatter.parse("---\nunclosed: yes").body)
    }

    // Tools

    @Test
    fun toolsetLabelsDropLeadingEmoji() {
        assertEquals("Web Search", CapabilityToolsetOrdering.label(CapabilityToolset("web", label = "🔍 Web Search")))
        assertEquals("Terminal", CapabilityToolsetOrdering.label(CapabilityToolset("t", label = "⚙️ Terminal")))
        assertEquals("Image Gen", CapabilityToolsetOrdering.label(CapabilityToolset("image_gen")))
        assertEquals("🎨", CapabilityToolsetOrdering.label(CapabilityToolset("x", label = "🎨")))
        assertFalse(CapabilityToolsetOrdering.isVisible(CapabilityToolset("moa")))
    }

    @Test
    fun toolCallsCountAndOrderToolsets() {
        val counts = CapabilityToolsetOrdering.toolCallCounts(
            json("""{"tools":[{"tool":"a","count":2},{"tool":"a","count":3},{"tool":"b","count":1},{"tool":"c"},{"count":4}]}"""),
        )
        assertEquals(mapOf("a" to 5, "b" to 1), counts)
        val sets = listOf(
            CapabilityToolset("one", tools = listOf("b")),
            CapabilityToolset("two", tools = listOf("a", "b")),
            CapabilityToolset("zero"),
        )
        assertEquals(6, CapabilityToolsetOrdering.calls(sets[1], counts))
        assertEquals(listOf("two", "one", "zero"), CapabilityToolsetOrdering.sorted(sets, counts, true).map { it.name })
        assertEquals(listOf("zero", "one", "two"), CapabilityToolsetOrdering.sorted(sets, counts, false).map { it.name })
        assertTrue(CapabilityToolsetOrdering.matches(sets[1], "A"))
    }

    @Test
    fun toolsetConfigAndWebBackends() {
        val config = ToolsetConfig.parse(
            json(
                """{"has_category":true,"active_search_backend":"firecrawl","active_extract_backend":"tavily","search_via_nous":true,
                "providers":[
                  {"name":"Nous","web_backend":"firecrawl","managed_nous_feature":"web","capabilities":["search","extract"],"status":"needs_auth"},
                  {"name":"Firecrawl","web_backend":"firecrawl","env_vars":[{"key":"FIRECRAWL_API_KEY","prompt":"","is_set":true}]},
                  {"name":"Tavily","web_backend":"tavily","is_active":true,"badge":"free","env_vars":[{"key":"TAVILY"},{"prompt":"x"}]},
                  {"badge":"no name"}
                ]}""",
            ),
        )
        assertTrue(config.hasCategory)
        assertEquals(3, config.providers.size)
        val (nous, firecrawl, tavily) = config.providers
        assertTrue(config.serves("search", nous))
        assertFalse(config.serves("search", firecrawl))
        assertTrue(config.serves("extract", tavily))
        assertFalse(config.serves("extract", nous))
        assertFalse(config.serves("other", nous))
        assertEquals(ToolsetConfig.EnvVar("FIRECRAWL_API_KEY", "FIRECRAWL_API_KEY", null, true), firecrawl.envVars.single())
        assertEquals(1, tavily.envVars.size)
        assertTrue(tavily.isActive)
        assertEquals("needs_auth", nous.status)
    }

    @Test
    fun terminalBackends() {
        val list = TerminalBackendOption.list(
            json("""{"backends":[{"name":"local","active":true,"status":"ready","detail":""},{"name":"docker","label":"Docker","status":"needs_setup","detail":"Install Docker"}]}"""),
        )
        assertEquals("local", list[0].label)
        assertNull(list[0].detail)
        assertEquals("Install Docker", list[1].detail)
    }

    // Connectors

    @Test
    fun connectorServersAndCatalog() {
        val servers = ConnectorServer.list(
            json(
                """{"servers":[
                {"name":"linear","transport":"http","url":"https://mcp.linear.app","auth":"oauth"},
                {"name":"fs","transport":"stdio","command":"npx","args":["-y","fs"],"enabled":false},
                {"name":"plug","source":"plugin","transport":"http","url":"https://x"},
                {"name":"hdr","transport":"http","url":"https://y","auth":"header"}
                ]}""",
            ),
        )
        assertEquals(4, servers.size)
        assertTrue(servers[0].canSignIn)
        assertTrue(servers[0].enabled)
        assertFalse(servers[1].enabled)
        assertEquals(listOf("-y", "fs"), servers[1].args)
        assertEquals("", servers[2].plugin)
        assertFalse(servers[2].canSignIn)
        assertFalse(servers[3].canSignIn)

        val catalog = ConnectorCatalogEntry.list(
            json("""{"entries":[{"name":"github","auth_type":"api_key","required_env":[{"name":"GITHUB_TOKEN","prompt":"Token"},{"name":"ORG","required":false}]},{"name":"x"}]}"""),
        )
        assertEquals("api_key", catalog[0].authType)
        assertEquals(ConnectorCatalogEntry.EnvField("ORG", "ORG", false), catalog[0].requiredEnv[1])
        assertTrue(catalog[0].requiredEnv[0].required)
        assertEquals("none", catalog[1].authType)
    }

    @Test
    fun connectorStatesReadAuthErrorsAsSignIn() {
        val on = ConnectorServer("a")
        val probe = ConnectorProbe.parse(json("""{"ok":true,"tools":[{"name":"t1"},{"name":"t2","description":"d"}]}"""))
        assertEquals(ConnectorState.On(2), ConnectorState.of(on, probe, probing = false))
        assertEquals(ConnectorState.Checking, ConnectorState.of(on, probe, probing = true))
        assertEquals(ConnectorState.Off, ConnectorState.of(on.copy(enabled = false), probe, probing = true))
        assertEquals(ConnectorState.Unknown, ConnectorState.of(on, null, probing = false))
        assertEquals(ConnectorState.NeedsSignIn, ConnectorState.of(on, ConnectorProbe.failure("HTTP 401 Unauthorized"), false))
        assertEquals(ConnectorState.NeedsSignIn, ConnectorState.of(on, ConnectorProbe.failure("OAuth required"), false))
        assertEquals(ConnectorState.Failed("spawn ENOENT"), ConnectorState.of(on, ConnectorProbe.failure("spawn ENOENT"), false))
    }

    @Test
    fun connectorGroupsPutBrokenFirstAndHideInstalledCatalog() {
        val servers = listOf(ConnectorServer("ok"), ConnectorServer("broken"), ConnectorServer("Asleep", enabled = false), ConnectorServer("auth"))
        val states = mapOf(
            "ok" to ConnectorState.On(1),
            "broken" to ConnectorState.Failed("x"),
            "auth" to ConnectorState.NeedsSignIn,
            "Asleep" to ConnectorState.Off,
        )
        val catalog = listOf(ConnectorCatalogEntry("ok"), ConnectorCatalogEntry("zed"), ConnectorCatalogEntry("done", installed = true), ConnectorCatalogEntry("Beta", description = "find me"))
        val groups = ConnectorGrouping.groups(servers, catalog, states, "")
        assertEquals(listOf("broken", "auth", "ok"), groups.connected.map { it.name })
        assertEquals(listOf("Beta", "zed"), groups.available.map { it.name })
        assertEquals(listOf("Asleep"), groups.off.map { it.name })
        assertEquals(listOf("Beta"), ConnectorGrouping.groups(servers, catalog, states, "FIND").available.map { it.name })
    }

    // Plugins

    @Test
    fun pluginsFilterBundledAndPlumbing() {
        val plugins = AgentPluginRow.list(
            json(
                """{"plugins":[
                {"name":"zeta","source":"git","runtime_status":"enabled","path":"/home/h/.hermes/plugins/zeta-repo/","can_update_git":true},
                {"name":"disk-cleanup","source":"bundled"},
                {"name":"memory","source":"bundled"},
                {"name":"model-providers/openai","source":"user"},
                {"name":"Alpha","source":"user","can_remove":true,"version":""}
                ]}""",
            ),
        )
        assertEquals(listOf("Alpha", "disk-cleanup", "zeta"), plugins.map { it.name })
        val zeta = plugins[2]
        assertEquals("zeta-repo", zeta.folderName)
        assertTrue(zeta.isEnabled)
        assertTrue(zeta.canUpdate)
        assertEquals("inactive", plugins[1].runtimeStatus)
        assertFalse(plugins[1].canUpdate)
        assertNull(plugins[0].version)
        assertEquals("Alpha", plugins[0].folderName)
    }

    @Test
    fun pluginCatalogMatchesByFolderAndOrdersOfficialFirst() {
        val catalog = PluginCatalogEntry.list(
            json(
                """{"entries":[
                {"name":"zeta-repo","installed":true,"update_available":true,"tier":"official"},
                {"name":"b","title":"Beta"},
                {"name":"a","tier":"official"},
                {"name":"c","title":""}
                ]}""",
            ),
        )
        val plugin = AgentPluginRow("zeta", path = "plugins/zeta-repo")
        assertEquals("zeta-repo", PluginCatalogEntry.entryFor(plugin, catalog)?.name)
        assertNull(PluginCatalogEntry.entryFor(AgentPluginRow("other"), catalog))
        assertEquals(listOf("a", "Beta", "c"), PluginCatalogEntry.available(catalog, "").map { it.displayName })
        assertEquals("community", catalog[1].tier)
    }

    @Test
    fun pluginUpdateAndInstallResponses() {
        assertEquals(
            PluginUpdateOutcome.NeedsConsent(listOf("+ network")),
            PluginResponses.update(json("""{"consent_required":true,"delta_lines":["+ network"]}""")),
        )
        assertEquals(
            PluginUpdateOutcome.NeedsConsent(listOf("asks for more")),
            PluginResponses.update(json("""{"consent_required":true,"error":"asks for more"}""")),
        )
        assertEquals(PluginUpdateOutcome.UpToDate, PluginResponses.update(json("""{"unchanged":true}""")))
        assertEquals(PluginUpdateOutcome.Updated(true), PluginResponses.update(json("""{"ok":true}""")))
        assertEquals(PluginUpdateOutcome.Updated(false), PluginResponses.update(json("""{"restart_required":false}""")))

        val install = PluginResponses.install(json("""{"plugin_name":"Weather","missing_env":["KEY",{"name":"OTHER"},3],"restart_required":true}"""), "fallback")
        assertEquals("Weather", install.name)
        assertEquals(listOf("KEY", "OTHER"), install.missingEnv)
        assertTrue(install.restartRequired)
        assertEquals("fallback", PluginResponses.install(json("{}"), "fallback").name)
    }

    // Actions and formatting

    @Test
    fun actionStatusFailureReasonSkipsBanners() {
        val status = HermesActionStatus.parse(json("""{"running":false,"exit_code":1,"lines":["installing","Error: scan failed  ","","=== done ==="]}"""))
        assertFalse(status.succeeded)
        assertEquals("Error: scan failed", status.failureReason)
        assertTrue(HermesActionStatus.parse(json("""{"running":false,"exit_code":0}""")).succeeded)
        assertFalse(HermesActionStatus.parse(json("""{"running":true,"exit_code":0}""")).succeeded)
        assertNull(HermesActionStatus.parse(json("""{"lines":["=== x ==="]}""")).failureReason)
    }

    @Test
    fun usesFormatCompactly() {
        assertEquals("×7", CapabilityFormat.uses(7))
        assertEquals("×999", CapabilityFormat.uses(999))
        assertEquals("×1.2K", CapabilityFormat.uses(1_234))
        assertEquals("×1K", CapabilityFormat.uses(1_000))
        assertEquals("×12K", CapabilityFormat.uses(12_345))
        assertEquals("×3.5M", CapabilityFormat.uses(3_456_789))
    }

    @Test
    fun restartMonitorWaitsForServingGateway() {
        val before = CapabilityRestartMonitor.GatewayStatus(true, "running", 10, emptyList())
        val monitor = CapabilityRestartMonitor(before)
        val running = HermesActionStatus(running = true, exitCode = null, lines = emptyList())
        assertEquals(
            CapabilityRestartState.Restarting(CapabilityRestartState.Stage.STOPPING, null),
            monitor.observe(running, before, 1_000),
        )
        assertEquals(
            CapabilityRestartState.Restarting(CapabilityRestartState.Stage.STARTING, null),
            monitor.observe(running, before.copy(running = false), 3_000),
        )
        assertEquals(CapabilityRestartState.Restarted(emptyList()), monitor.observe(running, before, 5_000))

        val newPid = CapabilityRestartMonitor(before).observe(null, before.copy(pid = 11), 2_000)
        assertTrue(newPid is CapabilityRestartState.Restarted)

        val failed = CapabilityRestartMonitor(before).observe(HermesActionStatus(false, 2, listOf("boom")), before, 2_000)
        assertEquals(CapabilityRestartState.Failed("boom"), failed)

        val stuck = CapabilityRestartMonitor(before).observe(null, before.copy(running = false, state = "startup_failed"), CapabilityRestartMonitor.WATCH_LIMIT_MS)
        assertEquals(CapabilityRestartState.FailedToStart, stuck)

        val parsed = CapabilityRestartMonitor.GatewayStatus.parse(
            json("""{"gateway_running":true,"gateway_state":"draining","gateway_shared_with":["work","default","work","alpha"]}"""),
        )
        assertEquals(listOf("default", "alpha", "work"), parsed?.sharedProfiles)
        assertFalse(parsed!!.isServing)
        assertNull(CapabilityRestartMonitor.GatewayStatus.parse(json("""{"version":"1"}""")))
    }
}

package app.hermesconduit.android.model

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.Locale

/*
 * Settings > Capabilities (iOS `CapabilityCatalogModels.swift`, the
 * capability structs of `Models.swift`): installed skills and toolsets, the
 * official skills catalog, tool call counts, a toolset's providers, MCP
 * servers and their catalog (Connectors), agent plugins and their catalog,
 * and Hermes' background actions. Shapes follow Hermes' dashboard routes.
 * Every parser is lenient: a malformed row is skipped, never thrown.
 */

private val JsonElement?.strings: List<String> get() = arr?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }.orEmpty()

/** A non-empty string, or null. */
private fun JsonObject.text(key: String): String? = this[key].str?.takeIf { it.isNotEmpty() }

/** Strict JSON booleans only, as the Swift `as? Bool` casts. */
private fun JsonObject.flag(key: String): Boolean? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.bool

private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.long?.toInt()

private fun JsonObject.rows(key: String): List<JsonObject> = this[key].arr?.mapNotNull { it.obj }.orEmpty()

private fun String.containsCI(query: String) = contains(query, ignoreCase = true)

private val byNameCI: Comparator<String> = String.CASE_INSENSITIVE_ORDER

// region Installed skills and toolsets (`GET /api/skills`, `GET /api/tools/toolsets`)

data class CapabilitySkill(
    val name: String,
    val description: String? = null,
    val category: String? = null,
    val enabled: Boolean = false,
    val provenance: String? = null,
    val usage: Int? = null,
)

data class CapabilityToolset(
    val name: String,
    val description: String? = null,
    val enabled: Boolean = false,
    val configured: Boolean? = null,
    val label: String? = null,
    val tools: List<String>? = null,
)

object CapabilityParsers {
    /** `GET /api/skills` (a top-level array): by category, then name. */
    fun skills(response: JsonObject): List<CapabilitySkill> =
        (response["_array"].arr ?: response["skills"].arr ?: JsonArray(emptyList()))
            .mapNotNull { it.obj }
            .mapNotNull { row ->
                val name = row["name"].str ?: return@mapNotNull null
                CapabilitySkill(
                    name = name,
                    description = row["description"].str,
                    category = row["category"].str,
                    enabled = row.flag("enabled") ?: false,
                    provenance = row["provenance"].str,
                    usage = row.int("usage"),
                )
            }
            .sortedWith(compareBy<CapabilitySkill> { it.category.orEmpty() }.thenBy { it.name })

    /** `GET /api/tools/toolsets` (a top-level array): by label. */
    fun toolsets(response: JsonObject): List<CapabilityToolset> =
        (response["_array"].arr ?: response["toolsets"].arr ?: JsonArray(emptyList()))
            .mapNotNull { it.obj }
            .mapNotNull { row ->
                val name = row["name"].str ?: return@mapNotNull null
                CapabilityToolset(
                    name = name,
                    description = row["description"].str,
                    enabled = row.flag("enabled") ?: false,
                    configured = row.flag("configured"),
                    label = row["label"].str,
                    tools = row["tools"].arr?.let { tools -> tools.strings },
                )
            }
            .sortedBy { it.label ?: it.name }
}

// endregion

// region Skills catalog

/** A skill in Hermes' official optional-skills catalog (`GET /api/skills/hub/official`). */
data class OfficialSkill(
    val name: String,
    val identifier: String,
    val description: String = "",
    val category: String = "general",
    val tags: List<String> = emptyList(),
    val installed: Boolean = false,
) {
    companion object {
        fun parse(value: JsonElement?): OfficialSkill? {
            val row = value.obj ?: return null
            val name = row.text("name") ?: return null
            return OfficialSkill(
                name = name,
                identifier = row.text("identifier") ?: name,
                description = row["description"].str.orEmpty(),
                category = row.text("category") ?: "general",
                tags = row["tags"].strings,
                installed = row.flag("installed") ?: false,
            )
        }

        fun list(response: JsonObject): List<OfficialSkill> = response["skills"].arr?.mapNotNull(::parse).orEmpty()
    }
}

object CapabilitySkillOrdering {
    /** The most used first (or least, flipped), then by name. */
    fun sorted(skills: List<CapabilitySkill>, mostUsedFirst: Boolean): List<CapabilitySkill> =
        skills.sortedWith { lhs, rhs ->
            val l = lhs.usage ?: 0
            val r = rhs.usage ?: 0
            if (l != r) {
                if (mostUsedFirst) r.compareTo(l) else l.compareTo(r)
            } else {
                byNameCI.compare(lhs.name, rhs.name)
            }
        }

    fun matches(skill: CapabilitySkill, query: String): Boolean {
        if (query.isEmpty()) return true
        return skill.name.containsCI(query) ||
            skill.description?.containsCI(query) == true ||
            skill.category?.containsCI(query) == true
    }

    /** Catalog rows not installed yet, A-Z; a name already installed from another source is left out too. */
    fun available(catalog: List<OfficialSkill>, installedNames: Set<String>, query: String): List<OfficialSkill> =
        catalog
            .filter { !it.installed && it.name !in installedNames }
            .filter { entry ->
                query.isEmpty() ||
                    entry.name.containsCI(query) ||
                    entry.description.containsCI(query) ||
                    entry.category.containsCI(query) ||
                    entry.tags.any { it.containsCI(query) }
            }
            .sortedWith(compareBy(byNameCI) { it.name })

    /** `software_development` reads "Software Development". */
    fun prettyCategory(category: String?): String {
        val raw = category.orEmpty().trim()
        return (raw.ifEmpty { "general" })
            .replace('_', ' ')
            .replace('-', ' ')
            .split(' ')
            .filter { it.isNotEmpty() }
            .joinToString(" ") { word -> word.substring(0, 1).uppercase() + word.substring(1) }
    }
}

/** A SKILL.md split into its frontmatter fields and its body. */
data class SkillFrontmatter(val fields: List<Field>, val body: String) {
    data class Field(val key: String, val value: String)

    companion object {
        /** Top-level `key: value` lines between the opening `---` fences; nested lines stay out. */
        fun parse(text: String): SkillFrontmatter {
            val lines = text.replace("\r\n", "\n").split("\n")
            if (lines.firstOrNull()?.trim() != "---") return SkillFrontmatter(emptyList(), text.trim())
            val close = (1 until lines.size).firstOrNull { lines[it].trim() == "---" }
                ?: return SkillFrontmatter(emptyList(), text.trim())
            val fields = mutableListOf<Field>()
            for (line in lines.subList(1, close)) {
                val first = line.firstOrNull() ?: continue
                if (first.isWhitespace() || first == '#' || first == '-') continue
                val colon = line.indexOf(':')
                if (colon < 0) continue
                val key = line.substring(0, colon).trim()
                var value = line.substring(colon + 1).trim()
                if (value.length >= 2 && (value.first() == '"' || value.first() == '\'') && value.last() == value.first()) {
                    value = value.substring(1, value.length - 1)
                }
                if (key.isEmpty() || value.isEmpty()) continue
                fields += Field(key, value)
            }
            val body = lines.subList(close + 1, lines.size).joinToString("\n").trim()
            return SkillFrontmatter(fields, body)
        }
    }
}

// endregion

// region Tools

object CapabilityToolsetOrdering {
    /** Platform plumbing Hermes Desktop leaves off its Tools tab. */
    val hidden = setOf("discord", "discord_admin", "yuanbao", "context_engine", "moa")

    fun isVisible(toolset: CapabilityToolset) = toolset.name !in hidden

    /** The label without a leading emoji, or the name when Hermes sends none. */
    fun label(toolset: CapabilityToolset): String {
        val raw = toolset.label?.trim().orEmpty()
        if (raw.isEmpty()) {
            return toolset.name.replace('_', ' ').split(' ').joinToString(" ") { word ->
                word.lowercase(Locale.ROOT).replaceFirstChar { it.titlecase(Locale.ROOT) }
            }
        }
        var index = 0
        while (index < raw.length) {
            val cp = raw.codePointAt(index)
            if (!isDecoration(cp)) break
            index += Character.charCount(cp)
        }
        val stripped = raw.substring(index)
        return stripped.ifEmpty { raw }
    }

    private fun isDecoration(cp: Int): Boolean =
        Character.isWhitespace(cp) ||
            Character.getType(cp) == Character.OTHER_SYMBOL.toInt() ||
            cp in 0x1F000..0x1FAFF ||
            cp in 0x2600..0x27BF ||
            cp in 0xFE00..0xFE0F ||
            cp == 0x200D || cp == 0x20E3 ||
            cp in 0xE0020..0xE007F

    /** Calls of every tool in the set. */
    fun calls(toolset: CapabilityToolset, counts: Map<String, Int>): Int =
        toolset.tools.orEmpty().sumOf { counts[it] ?: 0 }

    fun matches(toolset: CapabilityToolset, query: String): Boolean {
        if (query.isEmpty()) return true
        return toolset.name.containsCI(query) ||
            label(toolset).containsCI(query) ||
            toolset.description?.containsCI(query) == true ||
            toolset.tools.orEmpty().any { it.containsCI(query) }
    }

    /** The most called first (or least, flipped), then by label. */
    fun sorted(toolsets: List<CapabilityToolset>, counts: Map<String, Int>, mostUsedFirst: Boolean): List<CapabilityToolset> =
        toolsets.sortedWith { lhs, rhs ->
            val l = calls(lhs, counts)
            val r = calls(rhs, counts)
            if (l != r) {
                if (mostUsedFirst) r.compareTo(l) else l.compareTo(r)
            } else {
                byNameCI.compare(label(lhs), label(rhs))
            }
        }

    /** Per-tool call counts from `GET /api/analytics/usage` (`tools`). */
    fun toolCallCounts(response: JsonObject): Map<String, Int> {
        val counts = mutableMapOf<String, Int>()
        for (row in response.rows("tools")) {
            val tool = row["tool"].str ?: continue
            val count = row.int("count") ?: continue
            counts[tool] = (counts[tool] ?: 0) + count
        }
        return counts
    }
}

/** A toolset's backends and the keys each needs (`GET /api/tools/toolsets/{name}/config`). */
data class ToolsetConfig(
    val hasCategory: Boolean,
    val providers: List<Provider>,
    val activeProvider: String?,
    val activeSearchBackend: String?,
    val activeExtractBackend: String?,
    val searchViaNous: Boolean,
    val extractViaNous: Boolean,
) {
    data class EnvVar(val key: String, val prompt: String, val url: String?, val isSet: Boolean)

    data class Provider(
        val name: String,
        val badge: String,
        val tag: String,
        val envVars: List<EnvVar>,
        val managedNousFeature: String?,
        val isActive: Boolean,
        /** ready, needs_setup, needs_auth or needs_keys. */
        val status: String,
        /** The web toolset's backend key. */
        val webBackend: String?,
        /** What a web backend can serve: search, extract. */
        val capabilities: List<String>,
    )

    /**
     * Whether [provider] is the one a web search (or extract) call uses now.
     * Managed Nous and own-key Firecrawl share a backend name, so the Nous
     * flag tells them apart.
     */
    fun serves(capability: String, provider: Provider): Boolean {
        val backend = provider.webBackend ?: return false
        return when (capability) {
            "search" -> backend == activeSearchBackend && (provider.managedNousFeature != null) == searchViaNous
            "extract" -> backend == activeExtractBackend && (provider.managedNousFeature != null) == extractViaNous
            else -> false
        }
    }

    companion object {
        fun parse(dict: JsonObject): ToolsetConfig = ToolsetConfig(
            hasCategory = dict.flag("has_category") ?: false,
            activeProvider = dict["active_provider"].str,
            activeSearchBackend = dict["active_search_backend"].str,
            activeExtractBackend = dict["active_extract_backend"].str,
            searchViaNous = dict.flag("search_via_nous") ?: false,
            extractViaNous = dict.flag("extract_via_nous") ?: false,
            providers = dict.rows("providers").mapNotNull { row ->
                val name = row["name"].str ?: return@mapNotNull null
                Provider(
                    name = name,
                    badge = row["badge"].str.orEmpty(),
                    tag = row["tag"].str.orEmpty(),
                    envVars = row.rows("env_vars").mapNotNull { env ->
                        val key = env["key"].str ?: return@mapNotNull null
                        EnvVar(key, env.text("prompt") ?: key, env["url"].str, env.flag("is_set") ?: false)
                    },
                    managedNousFeature = row["managed_nous_feature"].str,
                    isActive = row.flag("is_active") ?: false,
                    status = row["status"].str.orEmpty(),
                    webBackend = row["web_backend"].str,
                    capabilities = row["capabilities"].strings,
                )
            },
        )
    }
}

/** Where the terminal tool runs (`GET /api/tools/terminal/backends`). */
data class TerminalBackendOption(
    val name: String,
    val label: String,
    val description: String,
    val active: Boolean,
    /** ready, needs_setup or unavailable. */
    val status: String,
    val detail: String?,
) {
    companion object {
        fun list(response: JsonObject): List<TerminalBackendOption> = response.rows("backends").mapNotNull { row ->
            val name = row["name"].str ?: return@mapNotNull null
            TerminalBackendOption(
                name = name,
                label = row.text("label") ?: name,
                description = row["description"].str.orEmpty(),
                active = row.flag("active") ?: false,
                status = row["status"].str.orEmpty(),
                detail = row.text("detail"),
            )
        }
    }
}

// endregion

// region Connectors (MCP servers)

/** An MCP server in the profile's config (`GET /api/mcp/servers`). */
data class ConnectorServer(
    val name: String,
    /** http, stdio or unknown. */
    val transport: String = "unknown",
    val url: String? = null,
    val command: String? = null,
    val args: List<String> = emptyList(),
    /** oauth, header, or null. */
    val auth: String? = null,
    val enabled: Boolean = true,
    /** A plugin's server: shown, never changed from here (Hermes answers 409). */
    val plugin: String? = null,
) {
    val isFromPlugin: Boolean get() = plugin != null

    /** OAuth sign-in applies to a URL server that doesn't send its own Authorization header. */
    val canSignIn: Boolean get() = transport == "http" && url != null && auth != "header" && !isFromPlugin

    companion object {
        fun list(response: JsonObject): List<ConnectorServer> = response.rows("servers").mapNotNull { row ->
            val name = row["name"].str ?: return@mapNotNull null
            ConnectorServer(
                name = name,
                transport = row["transport"].str ?: "unknown",
                url = row.text("url"),
                command = row.text("command"),
                args = row["args"].strings,
                auth = row.text("auth"),
                enabled = row.flag("enabled") ?: true,
                plugin = if (row["source"].str == "plugin") row["plugin"].str.orEmpty() else null,
            )
        }
    }
}

/** An entry of Hermes' MCP catalog (`GET /api/mcp/catalog`). */
data class ConnectorCatalogEntry(
    val name: String,
    val description: String = "",
    val transport: String = "",
    /** none, api_key or oauth. */
    val authType: String = "none",
    val requiredEnv: List<EnvField> = emptyList(),
    val url: String? = null,
    val command: String? = null,
    val args: List<String> = emptyList(),
    val needsInstall: Boolean = false,
    val installed: Boolean = false,
) {
    data class EnvField(val name: String, val prompt: String, val required: Boolean)

    companion object {
        fun list(response: JsonObject): List<ConnectorCatalogEntry> = response.rows("entries").mapNotNull { row ->
            val name = row["name"].str ?: return@mapNotNull null
            ConnectorCatalogEntry(
                name = name,
                description = row["description"].str.orEmpty(),
                transport = row["transport"].str.orEmpty(),
                authType = row["auth_type"].str ?: "none",
                requiredEnv = row.rows("required_env").mapNotNull { field ->
                    val fieldName = field["name"].str ?: return@mapNotNull null
                    EnvField(fieldName, field.text("prompt") ?: fieldName, field.flag("required") ?: true)
                },
                url = row.text("url"),
                command = row.text("command"),
                args = row["args"].strings,
                needsInstall = row.flag("needs_install") ?: false,
                installed = row.flag("installed") ?: false,
            )
        }
    }
}

/** What a test connection to a server found (`POST /api/mcp/servers/{name}/test`). */
data class ConnectorProbe(
    val ok: Boolean,
    val error: String?,
    val tools: List<Tool>,
    val checkedAt: Long = System.currentTimeMillis(),
) {
    data class Tool(val name: String, val description: String)

    companion object {
        fun parse(dict: JsonObject, checkedAt: Long = System.currentTimeMillis()) = ConnectorProbe(
            ok = dict.flag("ok") ?: false,
            error = dict.text("error"),
            tools = dict.rows("tools").mapNotNull { row ->
                val name = row["name"].str ?: return@mapNotNull null
                Tool(name, row["description"].str.orEmpty())
            },
            checkedAt = checkedAt,
        )

        fun failure(message: String, checkedAt: Long = System.currentTimeMillis()) =
            ConnectorProbe(ok = false, error = message, tools = emptyList(), checkedAt = checkedAt)
    }
}

/** A server's state on the Connectors list, as Hermes Desktop words it. */
sealed interface ConnectorState {
    data object Off : ConnectorState
    data object Checking : ConnectorState
    data object Unknown : ConnectorState
    data class On(val tools: Int) : ConnectorState
    data object NeedsSignIn : ConnectorState
    data class Failed(val error: String) : ConnectorState

    companion object {
        private val authError = Regex("401|unauthori[sz]ed|forbidden|invalid token|authentication|oauth", RegexOption.IGNORE_CASE)

        /** An auth-shaped probe error means a sign-in, not a broken server. */
        fun of(server: ConnectorServer, probe: ConnectorProbe?, probing: Boolean): ConnectorState {
            if (!server.enabled) return Off
            if (probing) return Checking
            if (probe == null) return Unknown
            if (probe.ok) return On(probe.tools.size)
            val error = probe.error.orEmpty()
            if (authError.containsMatchIn(error)) return NeedsSignIn
            return Failed(error)
        }
    }
}

object ConnectorGrouping {
    data class Groups(
        val connected: List<ConnectorServer>,
        val available: List<ConnectorCatalogEntry>,
        val off: List<ConnectorServer>,
    )

    private fun rank(state: ConnectorState?): Int = when (state ?: ConnectorState.Unknown) {
        is ConnectorState.Failed -> 0
        ConnectorState.NeedsSignIn -> 1
        ConnectorState.Unknown -> 2
        ConnectorState.Checking -> 3
        is ConnectorState.On -> 4
        ConnectorState.Off -> 5
    }

    /**
     * Connected (any server that's on, broken ones first), Available
     * (catalog entries this profile hasn't installed), Turned off.
     */
    fun groups(
        servers: List<ConnectorServer>,
        catalog: List<ConnectorCatalogEntry>,
        states: Map<String, ConnectorState>,
        query: String,
    ): Groups {
        fun matches(vararg text: String) = query.isEmpty() || text.any { it.containsCI(query) }
        val visible = servers.filter { matches(it.name, it.url.orEmpty(), it.command.orEmpty(), it.plugin.orEmpty()) }
        val configured = servers.map { it.name }.toSet()
        return Groups(
            connected = visible.filter { it.enabled }
                .sortedWith(compareBy<ConnectorServer> { rank(states[it.name]) }.thenBy(byNameCI) { it.name }),
            available = catalog
                .filter { !it.installed && it.name !in configured && matches(it.name, it.description) }
                .sortedWith(compareBy(byNameCI) { it.name }),
            off = visible.filter { !it.enabled }.sortedWith(compareBy(byNameCI) { it.name }),
        )
    }
}

// endregion

// region Plugins

/** An agent plugin installed for the profile (`GET /api/dashboard/plugins/hub`). */
data class AgentPluginRow(
    val name: String,
    val version: String? = null,
    val description: String = "",
    /** bundled, user, git, project or entrypoint. */
    val source: String = "",
    /** enabled, disabled or inactive. */
    val runtimeStatus: String = "inactive",
    val canRemove: Boolean = false,
    val canUpdateGit: Boolean = false,
    /** The plugin's folder; its last component is the name the update and remove routes take. */
    val path: String? = null,
    val authRequired: Boolean = false,
    val authCommand: String? = null,
) {
    val isEnabled: Boolean get() = runtimeStatus == "enabled"

    val folderName: String
        get() = path?.split('/')?.lastOrNull { it.isNotEmpty() } ?: name

    /** User-installed plugins (from Git or the catalog) can be updated. */
    val canUpdate: Boolean get() = canUpdateGit || canRemove

    /** Bundled plugins stay out except the two a person turns on; provider/platform plumbing stays out. */
    val isListed: Boolean
        get() {
            if (hiddenPrefixes.any { name.startsWith(it) }) return false
            return source != "bundled" || name in listedBundled
        }

    companion object {
        val listedBundled = setOf("disk-cleanup", "security-guidance")
        val hiddenPrefixes = listOf("dashboard_auth/", "model-providers/", "platforms/")

        fun list(response: JsonObject): List<AgentPluginRow> = response.rows("plugins").mapNotNull { row ->
            val name = row["name"].str ?: return@mapNotNull null
            AgentPluginRow(
                name = name,
                version = row.text("version"),
                description = row["description"].str.orEmpty(),
                source = row["source"].str.orEmpty(),
                runtimeStatus = row["runtime_status"].str ?: "inactive",
                canRemove = row.flag("can_remove") ?: false,
                canUpdateGit = row.flag("can_update_git") ?: false,
                path = row["path"].str,
                authRequired = row.flag("auth_required") ?: false,
                authCommand = row.text("auth_command"),
            )
        }
            .filter { it.isListed }
            .sortedWith(compareBy(byNameCI) { it.name })
    }
}

/** An entry of Hermes' curated plugin catalog (`GET /api/dashboard/plugins/catalog`). */
data class PluginCatalogEntry(
    val name: String,
    val title: String? = null,
    val description: String = "",
    /** official or community. */
    val tier: String = "community",
    val capabilitySummary: String = "",
    val installed: Boolean = false,
    val updateAvailable: Boolean = false,
) {
    val displayName: String get() = title ?: name

    companion object {
        fun list(response: JsonObject): List<PluginCatalogEntry> = response.rows("entries").mapNotNull { row ->
            val name = row["name"].str ?: return@mapNotNull null
            PluginCatalogEntry(
                name = name,
                title = row.text("title"),
                description = row["description"].str.orEmpty(),
                tier = row["tier"].str ?: "community",
                capabilitySummary = row["capability_summary"].str.orEmpty(),
                installed = row.flag("installed") ?: false,
                updateAvailable = row.flag("update_available") ?: false,
            )
        }

        /** The catalog entry an installed plugin came from, matched by name or folder. */
        fun entryFor(plugin: AgentPluginRow, catalog: List<PluginCatalogEntry>): PluginCatalogEntry? =
            catalog.firstOrNull { it.installed && (it.name == plugin.name || it.name == plugin.folderName) }

        /** Not installed yet; official entries first, then A-Z. */
        fun available(catalog: List<PluginCatalogEntry>, query: String): List<PluginCatalogEntry> =
            catalog
                .filter { !it.installed }
                .filter { query.isEmpty() || it.displayName.containsCI(query) || it.description.containsCI(query) }
                .sortedWith(compareBy<PluginCatalogEntry> { it.tier != "official" }.thenBy(byNameCI) { it.displayName })
    }
}

/** How a plugin update went. */
sealed interface PluginUpdateOutcome {
    data class Updated(val restartRequired: Boolean) : PluginUpdateOutcome
    data object UpToDate : PluginUpdateOutcome
    /** The new pin asks for more than the installed one; nothing changed until these lines are accepted. */
    data class NeedsConsent(val lines: List<String>) : PluginUpdateOutcome
}

/** What a plugin install left to do. */
data class PluginInstallOutcome(val name: String, val missingEnv: List<String>, val restartRequired: Boolean)

object PluginResponses {
    /** `POST …/update`: consent, unchanged, or updated (a git pull reports no activation, so restart by default). */
    fun update(response: JsonObject): PluginUpdateOutcome {
        if (response.flag("consent_required") == true) {
            val lines = response["delta_lines"].strings
            return PluginUpdateOutcome.NeedsConsent(lines.ifEmpty { listOfNotNull(response.text("error")) })
        }
        if (response.flag("unchanged") == true) return PluginUpdateOutcome.UpToDate
        return PluginUpdateOutcome.Updated(response.flag("restart_required") ?: true)
    }

    /** `POST …/install`: `missing_env` holds names or `{name}` rows. */
    fun install(response: JsonObject, fallbackName: String): PluginInstallOutcome = PluginInstallOutcome(
        name = response["plugin_name"].str ?: fallbackName,
        missingEnv = response["missing_env"].arr?.mapNotNull { value ->
            (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: value.obj?.get("name").str
        }.orEmpty(),
        restartRequired = response.flag("restart_required") ?: false,
    )
}

// endregion

// region Background actions

/** Hermes runs installs as background CLI actions, polled at `GET /api/actions/{name}/status`. */
data class HermesActionStatus(val running: Boolean, val exitCode: Int?, val lines: List<String>) {
    val succeeded: Boolean get() = !running && exitCode == 0

    /** The last meaningful line of the log, shown when the action failed. */
    val failureReason: String?
        get() = lines.asReversed().map { it.trim() }.firstOrNull { it.isNotEmpty() && !it.startsWith("===") }

    companion object {
        fun parse(dict: JsonObject) = HermesActionStatus(
            running = dict.flag("running") ?: false,
            exitCode = dict.int("exit_code"),
            lines = dict["lines"].strings,
        )
    }
}

sealed interface CapabilityInstallState {
    data object Running : CapabilityInstallState
    data class Failed(val reason: String) : CapabilityInstallState
}

// endregion

// region Gateway restart (offered after a plugin change)

/** Where Restart Gateway stands. */
sealed interface CapabilityRestartState {
    enum class Stage { DRAINING, STOPPING, STARTING }

    data object Idle : CapabilityRestartState
    data object Requesting : CapabilityRestartState
    data class Restarting(val stage: Stage, val detail: String?) : CapabilityRestartState
    data class Restarted(val sharedProfiles: List<String>) : CapabilityRestartState
    data class Failed(val detail: String?) : CapabilityRestartState
    data object FailedToStart : CapabilityRestartState
    data object NotBackYet : CapabilityRestartState
    data object Unsupported : CapabilityRestartState

    val isInProgress: Boolean get() = this is Requesting || this is Restarting
}

/**
 * Decides, poll by poll, whether a restarted gateway is back (iOS
 * `GatewayRestartMonitor`). The restart child exiting 0 is success, and so is
 * one still running after a grace period (with no service manager it becomes
 * the gateway). The gateway must also be serving in `/api/status`; it going
 * down, or a new PID, shows the restart happened.
 */
class CapabilityRestartMonitor(private val before: GatewayStatus?) {
    data class GatewayStatus(val running: Boolean, val state: String?, val pid: Long?, val sharedProfiles: List<String>) {
        /** Running, and neither starting, failed, nor draining. A degraded gateway is up. */
        val isServing: Boolean
            get() = running && state?.lowercase() !in setOf("starting", "stopped", "startup_failed", "draining")

        companion object {
            fun parse(json: JsonObject): GatewayStatus? {
                val running = json.flag("gateway_running") ?: return null
                return GatewayStatus(
                    running = running,
                    state = json["gateway_state"].str,
                    pid = json["gateway_pid"].long,
                    sharedProfiles = sharedProfiles(json["gateway_shared_with"]),
                )
            }

            /** Unique names, default first, the rest sorted; empty unless two or more share it. */
            fun sharedProfiles(value: JsonElement?): List<String> {
                val names = value.arr?.mapNotNull { it.str?.trim() }?.filter { it.isNotEmpty() }?.distinct().orEmpty()
                if (names.size < 2) return emptyList()
                return names.sortedWith(compareBy<String> { it != "default" }.thenBy(String.CASE_INSENSITIVE_ORDER) { it })
            }
        }
    }

    private var sawDown = false
    private var childFinishedAt: Long? = null

    /** [action] and [gateway] are null when that read failed; [elapsedMs] counts from when Hermes took the request. */
    fun observe(action: HermesActionStatus?, gateway: GatewayStatus?, elapsedMs: Long): CapabilityRestartState {
        if (action != null && !action.running && action.exitCode != null) {
            if (action.exitCode != 0) return CapabilityRestartState.Failed(latestLine(action))
            if (childFinishedAt == null) childFinishedAt = elapsedMs
        }
        if (before?.running == false || gateway?.running == false) sawDown = true
        if (gateway != null && gateway.isServing) {
            val newProcess = before?.pid != null && gateway.pid != null && before.pid != gateway.pid
            if (childFinishedAt != null || sawDown || newProcess || elapsedMs >= RUNNING_CHILD_GRACE_MS) {
                return CapabilityRestartState.Restarted(gateway.sharedProfiles)
            }
        }
        val outOfTime = elapsedMs >= WATCH_LIMIT_MS || childFinishedAt?.let { elapsedMs - it >= COME_BACK_WINDOW_MS } == true
        if (outOfTime) {
            return if (gateway?.state?.lowercase() == "startup_failed") CapabilityRestartState.FailedToStart else CapabilityRestartState.NotBackYet
        }
        if (sawDown || childFinishedAt != null) return CapabilityRestartState.Restarting(CapabilityRestartState.Stage.STARTING, null)
        val stage = if (gateway?.state?.lowercase() == "draining") CapabilityRestartState.Stage.DRAINING else CapabilityRestartState.Stage.STOPPING
        return CapabilityRestartState.Restarting(stage, null)
    }

    companion object {
        const val RUNNING_CHILD_GRACE_MS = 30_000L
        const val COME_BACK_WINDOW_MS = 90_000L
        const val WATCH_LIMIT_MS = 600_000L

        /** The newest log line worth showing, skipping blanks and `===` banners. */
        fun latestLine(action: HermesActionStatus): String? {
            val line = action.failureReason ?: return null
            return if (line.length > 240) line.take(240) + "…" else line
        }
    }
}

// endregion

object CapabilityFormat {
    /** `×1.2K`: how often a skill or tool was used. */
    fun uses(count: Int): String = "×" + compact(count)

    fun compact(count: Int): String {
        val abs = kotlin.math.abs(count.toLong())
        fun fmt(value: Double, suffix: String): String {
            val rounded = if (value < 10) Math.round(value * 10) / 10.0 else Math.round(value).toDouble()
            val text = if (rounded % 1.0 == 0.0) rounded.toLong().toString() else String.format(Locale.ROOT, "%.1f", rounded)
            return (if (count < 0) "-" else "") + text + suffix
        }
        return when {
            abs < 1_000 -> count.toString()
            abs < 1_000_000 -> fmt(abs / 1_000.0, "K")
            abs < 1_000_000_000 -> fmt(abs / 1_000_000.0, "M")
            else -> fmt(abs / 1_000_000_000.0, "B")
        }
    }
}

package app.hermesconduit.android.ui

import app.hermesconduit.android.core.L10n
import app.hermesconduit.android.model.SlashCommand

/** The iOS built-in slash list, so common commands show even on gateways whose catalog lists only skills. */
object SlashCommands {
    fun builtIns(): List<SlashCommand> {
        val session = L10n.tr("Session")
        fun c(name: String, description: String, category: String, vararg aliases: String) =
            SlashCommand(name, L10n.tr(description), aliases.toList(), category)
        return listOf(
            c("new", "Start a new conversation", session, "reset"),
            c("branch", "Branch this conversation into a new chat", session, "fork"),
            c("model", "Open the model and run settings", session),
            c("yolo", "Toggle automatic tool approval", session),
            c("help", "Show available slash commands", session, "commands"),
            c("approvals", "Show or set approval mode", "Hermes"),
            c("agents", "Show active sessions and tasks", "Hermes", "tasks"),
            c("background", "Run a prompt in the background", "Hermes", "bg", "btw"),
            c("compress", "Compress this conversation context", "Hermes", "compact"),
            c("debug", "Create a debug report", "Hermes"),
            c("goal", "Manage this session's standing goal", "Hermes"),
            c("personality", "Switch the session personality", "Hermes"),
            c("queue", "Queue a prompt for the next turn", "Hermes", "q"),
            c("retry", "Retry the last user message", "Hermes"),
            c("rollback", "List or restore filesystem checkpoints", "Hermes"),
            c("save", "Save the current transcript", "Hermes"),
            c("status", "Show current session status", "Hermes"),
            c("steer", "Steer the current run", "Hermes"),
            c("stop", "Stop running background processes", "Hermes"),
            c("tools", "List or toggle agent tools", "Hermes"),
            c("undo", "Remove the last user and assistant exchange", "Hermes"),
            c("usage", "Show this session's token usage", "Hermes"),
            c("version", "Show the Hermes Agent version", "Hermes"),
        )
    }

    fun help(commands: List<SlashCommand>): String = buildString {
        append("**").append(L10n.tr("Slash commands")).append("**\n\n")
        commands.groupBy { it.category ?: "Hermes" }.forEach { (category, list) ->
            append("_").append(category).append("_\n")
            list.forEach { c ->
                append("- `/").append(c.name).append('`')
                if (c.aliases.isNotEmpty()) append(" (").append(c.aliases.joinToString { "/$it" }).append(')')
                append(" — ").append(c.description).append('\n')
            }
            append('\n')
        }
    }

    /** Suggestions for a draft that is still typing the command name. */
    fun suggestions(draft: String, commands: List<SlashCommand>): List<SlashCommand> {
        if (!draft.startsWith("/") || ' ' in draft || '\n' in draft) return emptyList()
        val prefix = draft.drop(1).lowercase()
        return commands.filter { c -> c.name.startsWith(prefix) || c.aliases.any { it.startsWith(prefix) } }.take(12)
    }
}

package app.hermesconduit.android.ui.cron

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.hermesconduit.android.core.t
import app.hermesconduit.android.model.CronJob
import app.hermesconduit.android.model.CronJobAction
import app.hermesconduit.android.model.CronJobFilter
import app.hermesconduit.android.model.CronParser
import app.hermesconduit.android.ui.LocalRouteArg
import app.hermesconduit.android.ui.components.EmptyState
import app.hermesconduit.android.ui.components.LoadingBox
import app.hermesconduit.android.ui.components.ScreenScaffold
import app.hermesconduit.android.ui.components.SettingsColumn
import app.hermesconduit.android.ui.components.SettingsRow
import app.hermesconduit.android.ui.components.SettingsSection
import app.hermesconduit.android.ui.nav.activityAppViewModel
import app.hermesconduit.android.ui.nav.openChatById
import java.text.DateFormat
import java.util.Date

private val ActiveGreen = Color(0xFF34C759)

/** Route `cron` (the list) and `cron/<jobId>` (one job). */
@Composable
fun CronScreen(onBack: () -> Unit, navigate: (String) -> Unit) {
    val jobId = LocalRouteArg.current
    val viewModel: CronViewModel = viewModel()
    if (jobId != null) CronJobDetailScreen(viewModel, jobId, onBack, navigate) else CronListScreen(viewModel, onBack, navigate)
}

@Composable
private fun ErrorSnackbar(error: String?, onShown: () -> Unit, host: SnackbarHostState) {
    LaunchedEffect(error) {
        if (error != null) {
            host.showSnackbar(error)
            onShown()
        }
    }
}

@Composable
private fun CronListScreen(viewModel: CronViewModel, onBack: () -> Unit, navigate: (String) -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val app = activityAppViewModel()
    ErrorSnackbar(state.error, viewModel::clearError, snackbar)
    val isSearching = state.query.isNotBlank()
    val jobs = CronParser.visibleJobs(state.jobs, state.filter, state.query)

    ScreenScaffold(
        t("Scheduled jobs"),
        onBack,
        actions = {
            if (state.loading) CircularProgressIndicator(Modifier.size(20.dp).padding(end = 4.dp), strokeWidth = 2.dp)
            IconButton(onClick = viewModel::refresh) { Icon(Icons.Default.Refresh, t("Refresh")) }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            if (!state.loaded && state.loading) {
                LoadingBox()
            } else {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item {
                        val active = state.jobs.count { it.isActive }
                        Text(
                            if (state.jobs.isEmpty()) t("%d configured", 0)
                            else t("%d active", active) + " · " + t("%d inactive", state.jobs.size - active),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    item {
                        OutlinedTextField(
                            value = state.query,
                            onValueChange = viewModel::setQuery,
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            leadingIcon = { Icon(Icons.Default.Search, null) },
                            placeholder = { Text(t("Search scheduled jobs")) },
                        )
                    }
                    if (state.jobs.isNotEmpty()) {
                        item {
                            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                                CronJobFilter.entries.forEachIndexed { index, filter ->
                                    SegmentedButton(
                                        selected = state.filter == filter,
                                        onClick = { viewModel.setFilter(filter) },
                                        shape = SegmentedButtonDefaults.itemShape(index, CronJobFilter.entries.size),
                                    ) { Text(t(filter.title)) }
                                }
                            }
                        }
                    }
                    if (jobs.isEmpty() && !state.loading) {
                        item {
                            val (title, description) = CronParser.emptyState(state.filter, state.jobs.isNotEmpty(), isSearching)
                            Column(Modifier.fillMaxWidth().padding(vertical = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.Default.Schedule, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(t(title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
                                Text(t(description), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    if (jobs.isNotEmpty()) {
                        val shown = CronParser.shownJobs(jobs, state.showAll, isSearching)
                        item {
                            Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                                Text(t("Jobs"), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                                Text("${jobs.size}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        items(shown, key = { it.id }) { job ->
                            CronJobRow(job, notifies = viewModel.notifies(job)) { navigate("cron/${Uri.encode(job.id)}") }
                        }
                        if (!isSearching && jobs.size > CronJobFilter.COLLAPSED_ROW_COUNT) {
                            item {
                                TextButton(onClick = viewModel::toggleShowAll) {
                                    Icon(if (state.showAll) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null)
                                    Text(if (state.showAll) t("Show fewer") else t("Show %d more", jobs.size - shown.size))
                                }
                            }
                        }
                    }
                    if (state.recentRuns.isNotEmpty()) {
                        item {
                            Text(t("Recent runs"), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
                        }
                        items(state.recentRuns, key = { "run:" + it.id }) { session ->
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceContainer,
                                shape = MaterialTheme.shapes.large,
                                modifier = Modifier.fillMaxWidth().clickable { app?.openChatById(session.id, session.title, navigate) },
                            ) {
                                Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                                    Text(session.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
                                    val detail = listOfNotNull(session.model, session.lastActiveMs?.let(::formatTime)).joinToString(" · ")
                                    if (detail.isNotEmpty()) {
                                        Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
        }
    }
}

@Composable
private fun statusLabel(job: CronJob): String = when {
    job.isFinished -> t("Finished")
    job.isActive -> t("Active")
    else -> t("Paused")
}

@Composable
private fun CronJobRow(job: CronJob, notifies: Boolean, onClick: () -> Unit) {
    val tint = if (job.isActive) ActiveGreen else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(30.dp).background(tint.copy(alpha = 0.13f), CircleShape), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Schedule, null, tint = tint, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                Text(job.displayName, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    job.scheduleText ?: t("No schedule"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (!notifies) {
                Icon(Icons.Outlined.NotificationsOff, t("Notifications off"), Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(6.dp))
            }
            Text(statusLabel(job), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, color = tint)
        }
    }
}

@Composable
private fun CronJobDetailScreen(viewModel: CronViewModel, jobId: String, onBack: () -> Unit, navigate: (String) -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val app = activityAppViewModel()
    ErrorSnackbar(state.error, viewModel::clearError, snackbar)
    val job = state.jobs.firstOrNull { it.id == jobId }
    LaunchedEffect(jobId, job != null) { if (job != null && state.runs.isEmpty()) viewModel.loadRuns(job) }

    ScreenScaffold(t("Scheduled job"), onBack) { padding ->
        Box(Modifier.fillMaxSize()) {
            when {
                job == null && (state.loading || !state.loaded) -> LoadingBox(Modifier.padding(padding))
                job == null -> EmptyState(t("No Scheduled Jobs"), Modifier.padding(padding))
                else -> SettingsColumn(padding) {
                    SettingsSection(job.displayName) {
                        SettingsRow(t("Status"), value = statusLabel(job))
                        SettingsRow(t("Schedule"), value = job.scheduleText ?: "—")
                        SettingsRow(t("Next run"), value = if (job.isFinished) "—" else job.nextRunAt ?: "—")
                        SettingsRow(t("Last run"), value = job.lastRunAt ?: "—")
                        SettingsRow(t("Delivery"), value = job.deliver ?: t("Local"))
                        job.lastError?.takeIf { it.isNotBlank() }?.let {
                            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(16.dp))
                        }
                    }
                    if (state.supportsNotifications) {
                        SettingsSection(t("Notifications")) {
                            SettingsRow(
                                t("Notify me when it runs"),
                                subtitle = t("Runs that answer [SILENT] never notify."),
                            ) {
                                Switch(
                                    checked = viewModel.notifies(job),
                                    onCheckedChange = { viewModel.setNotifies(it, job) },
                                    enabled = state.notificationsChangingJobId == null,
                                )
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        // A finished one-shot job has nothing left to pause or resume.
                        if (!job.isFinished) {
                            OutlinedButton(
                                onClick = { viewModel.perform(if (job.enabled) CronJobAction.PAUSE else CronJobAction.RESUME, job) },
                                enabled = state.actionJobId == null,
                                modifier = Modifier.weight(1f),
                            ) { Text(if (job.enabled) t("Pause") else t("Resume")) }
                        }
                        Button(
                            onClick = { viewModel.perform(CronJobAction.TRIGGER, job) { viewModel.loadRuns(job) } },
                            enabled = state.actionJobId == null,
                            modifier = Modifier.weight(1f),
                        ) { Text(if (state.actionJobId == job.id) t("Working…") else t("Run now")) }
                    }
                    job.prompt?.takeIf { it.isNotEmpty() }?.let { prompt ->
                        SettingsSection(t("Prompt")) {
                            SelectionContainer { Text(prompt, Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium) }
                        }
                    }
                    SettingsSection(t("Run history")) {
                        if (state.runsLoading && state.runs.isEmpty()) {
                            Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(24.dp)) }
                        } else if (state.runs.isEmpty()) {
                            Text(t("This job has not run yet."), Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        state.runs.forEach { run ->
                            SettingsRow(
                                run.title ?: run.preview ?: run.id,
                                subtitle = run.model ?: "Hermes",
                                value = (run.lastActive ?: run.startedAt)?.let(::formatTime),
                                onClick = { app?.openChatById(run.id, run.title, navigate) },
                            )
                        }
                    }
                    if (job.script != null) {
                        SettingsSection(t("Script")) {
                            Text(job.script, Modifier.padding(16.dp), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
        }
    }
}

private fun formatTime(ms: Long): String = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(ms))

package dev.quietinbox.feature.health

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.PauseCircle
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.Timeline
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.quietinbox.core.designsystem.R
import dev.quietinbox.core.designsystem.components.QualityTag
import dev.quietinbox.core.designsystem.components.SectionHeader
import dev.quietinbox.core.designsystem.components.SourceBadge
import dev.quietinbox.core.designsystem.components.StatTile
import dev.quietinbox.core.designsystem.components.StatusHero
import dev.quietinbox.core.designsystem.components.TimeFormat
import dev.quietinbox.core.designsystem.components.currentLocale
import dev.quietinbox.core.designsystem.components.diagnosticLabel
import dev.quietinbox.core.designsystem.components.gapReasonLabel
import dev.quietinbox.core.designsystem.components.listenerStateLabel
import dev.quietinbox.core.designsystem.components.relativeTime
import dev.quietinbox.core.designsystem.theme.QualityColors
import dev.quietinbox.core.model.ListenerState
import dev.quietinbox.core.model.SourceConfiguration
import dev.quietinbox.platform.crypto.KeyFailure
import kotlinx.coroutines.launch

@Composable
fun HealthScreen(
    modifier: Modifier = Modifier,
    viewModel: HealthViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var settingsMissing by remember { mutableStateOf(false) }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var addSheet by remember { mutableStateOf(false) }
    var removeTarget by remember { mutableStateOf<SourceConfiguration?>(null) }
    var resetDialog by remember { mutableStateOf(false) }

    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose { }
    }

    val testSentText = stringResource(R.string.health_test_sent)
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            viewModel.sendTest()
            scope.launch { snackbar.showSnackbar(testSentText) }
        }
    }
    val sendTest: () -> Unit = {
        if (Build.VERSION.SDK_INT >= 33 && !viewModel.canPostNotifications()) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            viewModel.sendTest()
            scope.launch { snackbar.showSnackbar(testSentText) }
        }
    }

    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.health_title)) },
                subtitle = { Text(listenerStateLabel(state.capture.listenerState), style = MaterialTheme.typography.bodySmall) },
                scrollBehavior = scrollBehavior,
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = padding.calculateTopPadding() + 4.dp, bottom = padding.calculateBottomPadding() + 96.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "hero") {
                HeroCard(state, onGrant = { settingsMissing = !viewModel.openListenerSettings(context) }, onPause = viewModel::setPaused)
                if (settingsMissing) {
                    Text(
                        stringResource(R.string.listener_settings_manual),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            }
            if (state.vaultFailure != null) {
                item(key = "vault") {
                    Card(
                        modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer),
                        shape = MaterialTheme.shapes.large,
                    ) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Icon(Icons.Outlined.Lock, null)
                                Text(stringResource(R.string.vault_locked_title), style = MaterialTheme.typography.titleMedium)
                            }
                            Text(stringResource(R.string.vault_locked_body, failureText(state.vaultFailure)))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilledTonalButton(onClick = viewModel::retryVault) { Text(stringResource(R.string.vault_locked_retry)) }
                                TextButton(onClick = { resetDialog = true }) { Text(stringResource(R.string.vault_locked_reset)) }
                            }
                        }
                    }
                }
            }
            if (!state.listenerGranted && Build.VERSION.SDK_INT >= 33) {
                item(key = "restricted") {
                    Card(modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth(), shape = MaterialTheme.shapes.large, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Icon(Icons.Outlined.Info, null, tint = MaterialTheme.colorScheme.primary)
                                Text(stringResource(R.string.health_restricted_hint), style = MaterialTheme.typography.bodyMedium)
                            }
                            TextButton(onClick = { context.startActivity(viewModel.appInfoIntent()) }) { Text(stringResource(R.string.health_app_info)) }
                        }
                    }
                }
            }
            item(key = "pipeline") {
                SectionHeader(stringResource(R.string.health_pipeline_title))
                Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatTile(state.capture.acceptedCount.toString(), stringResource(R.string.health_accepted), Modifier.weight(1f))
                    StatTile(state.capture.queueDepth.toString(), stringResource(R.string.health_queue), Modifier.weight(1f))
                    StatTile(state.pendingJournal.toString(), stringResource(R.string.health_pending_journal), Modifier.weight(1f))
                }
                Spacer(Modifier.height(8.dp))
                Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatTile(
                        state.capture.overflowCount.toString(),
                        stringResource(R.string.health_overflow),
                        Modifier.weight(1f),
                        container = if (state.capture.overflowCount > 0) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainer,
                    )
                    StatTile(state.capture.droppedAfterRevoke.toString(), stringResource(R.string.health_dropped), Modifier.weight(1f))
                }
                Spacer(Modifier.height(8.dp))
                // `admitted` counts events that reached the journal, before parsing; this is the
                // only line on the page that means a copy was actually written (H5).
                Text(
                    state.capture.lastCommittedAtEpochMs
                        ?.let { stringResource(R.string.health_last_saved, relativeTime(it)) }
                        ?: stringResource(R.string.health_last_saved_never),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                Spacer(Modifier.height(8.dp))
                Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = sendTest, enabled = state.listenerGranted) {
                        Icon(Icons.Outlined.Send, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.health_send_test))
                    }
                }
            }
            item(key = "sources-header") {
                SectionHeader(stringResource(R.string.health_sources_title)) {
                    TextButton(onClick = { addSheet = true }) {
                        Icon(Icons.Outlined.Add, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.health_add_source))
                    }
                }
            }
            items(state.sources, key = { it.packageName }) { s ->
                SourceRow(
                    source = s,
                    onEnabled = { viewModel.setSourceEnabled(s.packageName, it) },
                    onPaused = { viewModel.setSourcePaused(s.packageName, it) },
                    onRemove = { removeTarget = s },
                )
            }
            item(key = "preview-hint") {
                // The app can detect a placeholder body but never why it is one, and the fix is
                // never in QuietInbox — it is in the source app, or in Android's own sensitive
                // notification setting (H9, and COMPATIBILITY.md "Hidden previews").
                Text(
                    stringResource(R.string.health_preview_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
            }
            item(key = "gaps") {
                SectionHeader(stringResource(R.string.health_gaps_title))
                // Shown whether or not there are gaps: with gaps listed the page still never said
                // what a gap is not, and the empty state read as "nothing was missed" — the
                // opposite of what the app can honestly claim (H8).
                Text(stringResource(R.string.health_gaps_caveat), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp))
                if (state.gaps.isEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.health_no_gaps), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp))
                }
            }
            items(state.gaps, key = { "gap-${it.id}" }) { gap ->
                val start = gap.startEpochMs?.let { TimeFormat.dateTime(it, locale = currentLocale()) } ?: stringResource(R.string.health_gap_unknown_time)
                val end = gap.endEpochMs?.let { TimeFormat.time(it, locale = currentLocale()) } ?: stringResource(R.string.health_gap_open)
                ListItem(
                    leadingContent = { Icon(Icons.Outlined.Timeline, null, tint = QualityColors.uncertain) },
                    headlineContent = { Text("$start → $end") },
                    supportingContent = { Text(gapReasonLabel(gap.reason)) },
                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
                )
            }
            item(key = "diag") {
                SectionHeader(stringResource(R.string.health_diagnostics_title)) {
                    val copied = stringResource(R.string.health_diagnostics_copied)
                    TextButton(onClick = {
                        val cm = context.getSystemService(ClipboardManager::class.java)
                        cm.setPrimaryClip(ClipData.newPlainText("QuietInbox diagnostics", viewModel.diagnosticsSummary()))
                        scope.launch { snackbar.showSnackbar(copied) }
                    }) {
                        Icon(Icons.Outlined.ContentCopy, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.health_copy_diagnostics))
                    }
                }
                if (state.diagnostics.isEmpty()) {
                    Text(stringResource(R.string.health_diagnostics_empty), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp))
                }
            }
            items(state.diagnostics, key = { "diag-${it.code}" }) { d ->
                ListItem(
                    headlineContent = { Text(diagnosticLabel(d.code), style = MaterialTheme.typography.bodyMedium) },
                    supportingContent = { Text(d.code, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) },
                    trailingContent = { Text(d.n.toString(), style = MaterialTheme.typography.labelLarge) },
                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
                )
            }
        }
    }

    if (addSheet) {
        AddSourceSheet(
            onDismiss = { addSheet = false },
            search = viewModel::installedApps,
            onAdd = { viewModel.addSource(it); addSheet = false },
        )
    }
    removeTarget?.let { s ->
        AlertDialog(
            onDismissRequest = { removeTarget = null },
            title = { Text(stringResource(R.string.health_remove_title, s.displayName)) },
            text = { Text(stringResource(R.string.health_remove_body)) },
            confirmButton = {
                Column(horizontalAlignment = Alignment.End) {
                    TextButton(onClick = { viewModel.removeSource(s.packageName, deleteData = false); removeTarget = null }) { Text(stringResource(R.string.health_remove_keep_data)) }
                    TextButton(onClick = { viewModel.removeSource(s.packageName, deleteData = true); removeTarget = null }) { Text(stringResource(R.string.health_remove_delete_data), color = MaterialTheme.colorScheme.error) }
                }
            },
            dismissButton = { TextButton(onClick = { removeTarget = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    if (resetDialog) {
        AlertDialog(
            onDismissRequest = { resetDialog = false },
            title = { Text(stringResource(R.string.vault_reset_confirm_title)) },
            text = { Text(stringResource(R.string.vault_reset_confirm_body)) },
            confirmButton = { TextButton(onClick = { resetDialog = false; viewModel.resetVault() }) { Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { resetDialog = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun failureText(f: KeyFailure?): String = when (f) {
    KeyFailure.Invalidated -> stringResource(R.string.vault_failure_invalidated)
    KeyFailure.Tampered -> stringResource(R.string.vault_failure_tampered)
    is KeyFailure.Unavailable -> stringResource(R.string.vault_failure_unavailable, f.cause)
    null -> ""
}

@Composable
private fun HeroCard(state: HealthUiState, onGrant: () -> Unit, onPause: (Boolean) -> Unit) {
    val ls = state.capture.listenerState
    val (container, content) = when (ls) {
        ListenerState.CONNECTED -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
        ListenerState.PAUSED, ListenerState.RECONNECTING, ListenerState.GRANTED_DISCONNECTED -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
        ListenerState.DEGRADED -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
        ListenerState.NOT_GRANTED -> MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
    }
    val icon = when (ls) {
        ListenerState.CONNECTED -> Icons.Outlined.CheckCircle
        ListenerState.PAUSED -> Icons.Outlined.PauseCircle
        ListenerState.DEGRADED -> Icons.Outlined.ErrorOutline
        ListenerState.NOT_GRANTED -> Icons.Outlined.NotificationsOff
        else -> Icons.Outlined.Sync
    }
    val body = when (ls) {
        // One sentence per locale rather than a body plus an appended fragment: the concatenation
        // produced "…for every message. since 11:18 PM" in English and a stray space in Chinese.
        ListenerState.CONNECTED -> state.capture.connectedSinceEpochMs?.let {
            stringResource(R.string.health_connected_body_since, TimeFormat.time(it, locale = currentLocale()))
        } ?: stringResource(R.string.health_connected_body)
        ListenerState.NOT_GRANTED -> stringResource(R.string.health_not_granted_body)
        ListenerState.DEGRADED -> if (state.vaultFailure != null) stringResource(R.string.health_vault_locked) else stringResource(R.string.gap_reason_overflow)
        else -> ""
    }
    // Rendered in every state, not only the fall-through one. A listener that looks connected and
    // has silently stopped receiving callbacks — the work-profile / DPC case in COMPATIBILITY.md —
    // is exactly the state where this timestamp is the whole diagnosis, and it was the state that
    // never showed it (H4).
    val lastEvent = state.capture.lastEventAtEpochMs
        ?.let { stringResource(R.string.health_last_event, relativeTime(it)) }
        ?: stringResource(R.string.health_last_event_never)
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        StatusHero(title = listenerStateLabel(ls), body = body, icon = icon, container = container, content = content)
        Text(lastEvent, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!state.listenerGranted) {
                Button(onClick = onGrant) { Text(stringResource(R.string.health_grant)) }
            } else {
                OutlinedButton(onClick = { onPause(!state.capture.pausedByUser) }) {
                    Text(stringResource(if (state.capture.pausedByUser) R.string.health_resume else R.string.health_pause))
                }
            }
        }
    }
}

@Composable
private fun SourceRow(source: SourceConfiguration, onEnabled: (Boolean) -> Unit, onPaused: (Boolean) -> Unit, onRemove: () -> Unit) {
    val context = LocalContext.current
    ListItem(
        leadingContent = { SourceBadge(source.packageName, size = 40.dp) },
        headlineContent = { Text(source.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.health_adapter, source.adapterId ?: stringResource(R.string.health_adapter_standard)), style = MaterialTheme.typography.bodySmall)
                if (source.paused) QualityTag(stringResource(R.string.health_source_paused), Icons.Outlined.PauseCircle, QualityColors.uncertain)
            }
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Opens the system's notification settings for the source app — a Settings screen,
                // never the source's own UI and never a notification. It is the only place the user
                // can turn message previews back on, and the app had no way of saying so (H9).
                IconButton(onClick = { openSourceNotificationSettings(context, source.packageName) }) {
                    Icon(Icons.Outlined.Tune, stringResource(R.string.health_source_notification_settings), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = { onPaused(!source.paused) }) {
                    Icon(Icons.Outlined.PauseCircle, stringResource(if (source.paused) R.string.health_resume else R.string.health_pause), tint = if (source.paused) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = source.enabled, onCheckedChange = onEnabled)
                IconButton(onClick = onRemove) { Icon(Icons.Outlined.ErrorOutline, stringResource(R.string.health_remove_source), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
    )
}

@Composable
private fun AddSourceSheet(onDismiss: () -> Unit, search: suspend (String) -> List<InstalledApp>, onAdd: (InstalledApp) -> Unit) {
    var query by remember { mutableStateOf("") }
    var apps by remember { mutableStateOf<List<InstalledApp>>(emptyList()) }
    LaunchedEffect(query) { apps = search(query) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 16.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.health_add_source_title), style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                leadingIcon = { Icon(Icons.Outlined.Search, null) },
                placeholder = { Text(stringResource(R.string.health_add_source_search)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            if (apps.isEmpty()) {
                Text(stringResource(R.string.health_add_source_empty), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 24.dp))
            }
            LazyColumn(contentPadding = PaddingValues(bottom = 32.dp)) {
                items(apps, key = { it.packageName }) { app ->
                    ListItem(
                        leadingContent = { SourceBadge(app.packageName, size = 36.dp) },
                        headlineContent = { Text(app.label) },
                        supportingContent = {
                            when {
                                app.manual -> Text(stringResource(R.string.health_add_by_package), style = MaterialTheme.typography.bodySmall)
                                app.hasAdapter -> QualityTag(stringResource(R.string.health_known_source), Icons.Outlined.CheckCircle, QualityColors.verified)
                                else -> Text(app.packageName, style = MaterialTheme.typography.bodySmall)
                            }
                        },
                        trailingContent = { TextButton(onClick = { onAdd(app) }) { Text(stringResource(R.string.action_add)) } },
                        colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                    )
                }
            }
        }
    }
}

/**
 * The system's notification settings for [packageName]. QuietInbox never acts on a source
 * notification; this opens a Settings screen, and falls back to the app's details page on devices
 * whose OEM Settings does not answer the per-app notification intent.
 */
private fun openSourceNotificationSettings(context: Context, packageName: String) {
    val notifications = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    val details = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    for (intent in listOf(notifications, details)) {
        try {
            context.startActivity(intent)
            return
        } catch (_: ActivityNotFoundException) {
            continue
        }
    }
}

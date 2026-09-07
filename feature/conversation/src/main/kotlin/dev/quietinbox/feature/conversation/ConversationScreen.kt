package dev.quietinbox.feature.conversation

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import android.graphics.BitmapFactory
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.SyncProblem
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material.icons.outlined.Unarchive
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material3.FloatingToolbarDefaults.ScreenOffset
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.quietinbox.core.designsystem.R
import dev.quietinbox.core.designsystem.components.EmptyState
import dev.quietinbox.core.designsystem.components.LoadingScreen
import dev.quietinbox.core.designsystem.components.MonogramAvatar
import dev.quietinbox.core.designsystem.components.QualityTag
import dev.quietinbox.core.designsystem.components.SourceBadge
import dev.quietinbox.core.designsystem.components.TimeFormat
import dev.quietinbox.core.designsystem.components.currentLocale
import dev.quietinbox.core.designsystem.components.dayLabel
import dev.quietinbox.core.designsystem.components.identityLabel
import dev.quietinbox.core.designsystem.components.mediaLabel
import dev.quietinbox.core.designsystem.components.originLabel
import dev.quietinbox.core.designsystem.components.truncationLabel
import dev.quietinbox.core.designsystem.theme.QualityColors
import dev.quietinbox.core.model.ContentStatus
import dev.quietinbox.core.model.DedupState
import dev.quietinbox.core.model.MediaState
import dev.quietinbox.core.model.Message
import dev.quietinbox.core.model.TimestampQuality
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ConversationScreen(
    conversationId: Long,
    /** A search hit to land on. Null means land on the newest message, as every other entry does. */
    messageId: Long? = null,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    showBackButton: Boolean = true,
    viewModel: ConversationViewModel = hiltViewModel<ConversationViewModel, ConversationViewModel.Factory>(
        key = "conversation-$conversationId",
        creationCallback = { it.create(conversationId) },
    ),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val listState = rememberLazyListState()
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    var openDialog by remember { mutableStateOf<OpenSourceResult?>(null) }
    var deleteDialog by remember { mutableStateOf(false) }
    var deleteConversationDialog by remember { mutableStateOf(false) }

    // Three separate defects met on this one effect. It re-ran on every size change and jumped to
    // the newest message unconditionally, so (a) a search hit could not be landed on, (b) a restored
    // scroll position was overwritten on the next composition, and (c) the index was off by one —
    // the `info` item precedes the messages, and only the clamp hid it.
    var landed by rememberSaveable(conversationId, messageId) { mutableStateOf(false) }
    var highlighted by remember(conversationId, messageId) { mutableStateOf(messageId) }
    val lastIndex = state.messages.size
    LaunchedEffect(state.messages.size, messageId) {
        if (state.messages.isEmpty()) return@LaunchedEffect
        if (!landed) {
            val anchor = messageId?.let { id -> state.messages.indexOfFirst { it.id == id } } ?: -1
            when {
                anchor >= 0 -> listState.scrollToItem(anchor + 1)
                // The hit is not in the list — deleted or expired between the search and the tap.
                // Landing on the newest message would be a silent lie about where the match was.
                messageId != null -> Unit
                else -> listState.scrollToItem(lastIndex)
            }
            landed = true
            return@LaunchedEffect
        }
        // Afterwards follow new messages only when the reader is already at the end.
        // No layout yet (a rotation or a fold rebuilt the composition) means we do not know where
        // the reader was — and guessing "at the bottom" is what overwrites a restored position.
        val visibleEnd = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: return@LaunchedEffect
        if (visibleEnd >= lastIndex - 1) listState.animateScrollToItem(lastIndex)
    }
    LaunchedEffect(highlighted) {
        if (highlighted != null) {
            delay(HIGHLIGHT_MS)
            highlighted = null
        }
    }

    val conversation = state.conversation
    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                navigationIcon = {
                    if (showBackButton) {
                        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back)) }
                    }
                },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (conversation != null) MonogramAvatar(conversation.title, conversation.identityKey, size = 36.dp)
                        Column {
                            Text(
                                conversation?.title ?: stringResource(R.string.analytics_unknown_conversation),
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                stringResource(R.string.conv_source, state.sourceLabel),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                actions = {
                    if (conversation != null) {
                        IconButton(onClick = { viewModel.setPinned(!conversation.pinned) }) {
                            Icon(Icons.Outlined.PushPin, stringResource(if (conversation.pinned) R.string.action_unpin else R.string.action_pin), tint = if (conversation.pinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = { viewModel.setArchived(!conversation.archived) }) {
                            Icon(if (conversation.archived) Icons.Outlined.Unarchive else Icons.Outlined.Archive, stringResource(if (conversation.archived) R.string.action_unarchive else R.string.action_archive))
                        }
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        if (state.vaultLocked) {
            EmptyState(
                title = stringResource(R.string.vault_locked_title),
                body = stringResource(R.string.health_vault_locked),
                icon = Icons.Outlined.SyncProblem,
                modifier = Modifier.padding(padding),
                actions = { TextButton(onClick = viewModel::retryVault) { Text(stringResource(R.string.action_retry)) } },
            )
            return@Scaffold
        }
        if (state.loading) {
            LoadingScreen(Modifier.padding(padding))
            return@Scaffold
        }
        Box(Modifier.fillMaxSize().padding(padding)) {
            val selecting = state.selection.isNotEmpty()
            HorizontalFloatingToolbar(
                expanded = true,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .offset(y = -ScreenOffset)
                    .zIndex(1f),
                colors = if (selecting) FloatingToolbarDefaults.vibrantFloatingToolbarColors() else FloatingToolbarDefaults.standardFloatingToolbarColors(),
                content = {
                    if (selecting) {
                        Text(
                            stringResource(R.string.conv_selected_count, state.selection.size),
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(horizontal = 12.dp),
                        )
                        val selectedText = remember(state.selection, state.messages) {
                            state.messages.filter { it.id in state.selection }
                                .map { it.body }.filter { it.isNotBlank() }.joinToString("\n\n")
                        }
                        IconButton(
                            onClick = { copyToClipboard(context, selectedText) },
                            // A selection of media-only messages has nothing to copy; the button
                            // used to accept the tap and do nothing at all.
                            enabled = selectedText.isNotBlank(),
                        ) { Icon(Icons.Outlined.ContentCopy, stringResource(R.string.action_copy)) }
                        IconButton(onClick = { deleteDialog = true }) { Icon(Icons.Outlined.Delete, stringResource(R.string.action_delete)) }
                        IconButton(onClick = viewModel::clearSelection) { Icon(Icons.Outlined.CheckCircle, stringResource(R.string.action_close)) }
                    } else {
                        IconButton(onClick = { openDialog = viewModel.openSourceIntent() }, enabled = state.sourceInstalled) {
                            Icon(Icons.AutoMirrored.Outlined.OpenInNew, stringResource(R.string.conv_open_source))
                        }
                        IconButton(onClick = { deleteConversationDialog = true }) { Icon(Icons.Outlined.Delete, stringResource(R.string.action_delete)) }
                    }
                },
            )

            if (state.messages.isEmpty()) {
                EmptyState(
                    title = stringResource(R.string.conv_empty),
                    body = "",
                    icon = Icons.Outlined.Forum,
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 120.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    item(key = "info") {
                        conversation?.let { c ->
                            val identity = identityLabel(c.identityConfidence)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                                SuggestionChip(onClick = {}, label = { Text(identity.text) }, icon = { Icon(identity.icon, null, tint = identity.tint, modifier = Modifier.size(16.dp)) })
                                SuggestionChip(onClick = {}, label = { Text(stringResource(R.string.conv_saved_count, c.messageCount)) })
                                if (c.ambiguousCount > 0) {
                                    SuggestionChip(onClick = {}, label = { Text(stringResource(R.string.ambiguous_count, c.ambiguousCount)) }, icon = { Icon(Icons.Outlined.ContentCopy, null, tint = QualityColors.uncertain, modifier = Modifier.size(16.dp)) })
                                }
                            }
                        }
                    }
                    itemsIndexed(state.messages, key = { _, m -> m.id }) { index, m ->
                        val previous = state.messages.getOrNull(index - 1)
                        val newDay = previous == null || TimeFormat.localDate(previous.sortKey) != TimeFormat.localDate(m.sortKey)
                        if (newDay) {
                            Text(
                                dayLabel(m.sortKey),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            )
                        }
                        MessageBubble(
                            message = m,
                            showSender = m.senderName != null && (previous == null || previous.senderName != m.senderName || newDay),
                            selected = m.id in state.selection,
                            highlighted = m.id == highlighted,
                            selecting = selecting,
                            onToggleSelect = { viewModel.toggleSelect(m.id) },
                            onCopy = { copyToClipboard(context, m.body) },
                            onDeleteOnly = {
                                viewModel.clearSelection()
                                viewModel.toggleSelect(m.id)
                                deleteDialog = true
                            },
                            loadThumbnail = viewModel::loadThumbnail,
                            modifier = Modifier.animateItem(),
                        )
                    }
                }
            }
        }
    }

    when (val d = openDialog) {
        is OpenSourceResult.Launch -> AlertDialog(
            onDismissRequest = { openDialog = null },
            title = { Text(stringResource(R.string.conv_open_source_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.conv_open_source_body, state.sourceLabel))
                    Text(stringResource(R.string.conv_open_source_fallback), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            // A launch can still fail: the package may have been disabled or removed since the
            // button was enabled. Saying nothing would leave the user believing the app opened.
            confirmButton = {
                TextButton(onClick = {
                    openDialog = try {
                        context.startActivity(d.intent)
                        null
                    } catch (_: ActivityNotFoundException) {
                        OpenSourceResult.LaunchFailed
                    } catch (_: SecurityException) {
                        OpenSourceResult.LaunchFailed
                    }
                }) { Text(stringResource(R.string.action_open)) }
            },
            dismissButton = { TextButton(onClick = { openDialog = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
        OpenSourceResult.NotInstalled -> AlertDialog(
            onDismissRequest = { openDialog = null },
            text = { Text(stringResource(R.string.conv_open_source_unavailable)) },
            confirmButton = { TextButton(onClick = { openDialog = null }) { Text(stringResource(R.string.action_ok)) } },
        )
        OpenSourceResult.LaunchFailed -> AlertDialog(
            onDismissRequest = { openDialog = null },
            text = { Text(stringResource(R.string.conv_open_source_failed, state.sourceLabel)) },
            confirmButton = { TextButton(onClick = { openDialog = null }) { Text(stringResource(R.string.action_ok)) } },
        )
        null -> Unit
    }
    if (deleteDialog) {
        AlertDialog(
            onDismissRequest = { deleteDialog = false },
            title = { Text(stringResource(R.string.conv_delete_messages_title, state.selection.size)) },
            text = { Text(stringResource(R.string.conv_delete_messages_body)) },
            confirmButton = { TextButton(onClick = { deleteDialog = false; viewModel.deleteSelected() }) { Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { deleteDialog = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    if (deleteConversationDialog) {
        AlertDialog(
            onDismissRequest = { deleteConversationDialog = false },
            title = { Text(stringResource(R.string.inbox_delete_title)) },
            text = { Text(stringResource(R.string.inbox_delete_body)) },
            confirmButton = { TextButton(onClick = { deleteConversationDialog = false; viewModel.deleteConversation(onBack) }) { Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { deleteConversationDialog = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun MessageBubble(
    message: Message,
    showSender: Boolean,
    selected: Boolean,
    /** Briefly tinted after landing here from a search hit, so the match is findable by eye. */
    highlighted: Boolean,
    selecting: Boolean,
    onToggleSelect: () -> Unit,
    onCopy: () -> Unit,
    onDeleteOnly: () -> Unit,
    loadThumbnail: suspend (Long) -> ByteArray?,
    modifier: Modifier = Modifier,
) {
    val self = message.isSelf
    val ambiguous = message.dedupState == DedupState.AMBIGUOUS_REPEAT
    val container = when {
        selected -> MaterialTheme.colorScheme.tertiaryContainer
        highlighted -> MaterialTheme.colorScheme.secondaryContainer
        self -> MaterialTheme.colorScheme.primaryContainer
        ambiguous -> MaterialTheme.colorScheme.surfaceContainerHigh
        else -> MaterialTheme.colorScheme.surfaceContainerLow
    }
    val content = when {
        selected -> MaterialTheme.colorScheme.onTertiaryContainer
        highlighted -> MaterialTheme.colorScheme.onSecondaryContainer
        self -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.onSurface
    }
    val shape = RoundedCornerShape(
        topStart = 20.dp,
        topEnd = 20.dp,
        bottomStart = if (self) 20.dp else 6.dp,
        bottomEnd = if (self) 6.dp else 20.dp,
    )
    val selectedLabel = stringResource(R.string.a11y_selected)
    val notSelectedLabel = stringResource(R.string.a11y_not_selected)
    val copyLabel = stringResource(R.string.action_copy)
    val deleteLabel = stringResource(R.string.action_delete)
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = if (self) Alignment.End else Alignment.Start,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 340.dp)
                .clip(shape)
                .background(container)
                .combinedClickable(
                    onClick = { if (selecting) onToggleSelect() },
                    onLongClick = onToggleSelect,
                )
                // Selection was signalled by container colour alone, and copy was reachable only by
                // a touch-drag inside SelectionContainer — neither exists for a screen reader
                // (A11Y-03, A11Y-04). CONTRIBUTING.md already says colour is never the only signal.
                .semantics {
                    if (selecting) {
                        this.selected = selected
                        stateDescription = if (selected) selectedLabel else notSelectedLabel
                    }
                    customActions = buildList {
                        if (message.body.isNotBlank()) add(CustomAccessibilityAction(copyLabel) { onCopy(); true })
                        add(CustomAccessibilityAction(deleteLabel) { onDeleteOnly(); true })
                    }
                }
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // Inside the bubble, not above it. `combinedClickable` makes this column a merging
            // semantics node, and a merging node is never absorbed by an outer one — so a sender
            // drawn outside stayed a separate TalkBack stop and the bubble never said who sent it
            // (A11Y-02). Verified by dumping the node tree, not by reasoning about the modifier.
            if (showSender && !self) {
                Text(
                    message.senderName ?: stringResource(R.string.conv_unknown_sender),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (message.mediaState == MediaState.LOCAL_COPY && message.mediaBlobId != null) {
                Thumbnail(blobId = message.mediaBlobId!!, load = loadThumbnail)
            }
            if (message.body.isNotBlank()) {
                SelectionContainer {
                    Text(
                        message.body,
                        style = MaterialTheme.typography.bodyLarge,
                        color = content,
                        fontWeight = if (message.contentStatus == ContentStatus.PREVIEW_RESTRICTED_SUSPECTED) FontWeight.Light else FontWeight.Normal,
                    )
                }
            }
            MetaLine(message, content)
        }
    }
}

@Composable
private fun MetaLine(message: Message, contentColor: androidx.compose.ui.graphics.Color) {
    val timeLabel = when (message.timestampQuality) {
        TimestampQuality.SOURCE_MESSAGE -> stringResource(R.string.conv_time_source)
        TimestampQuality.NOTIFICATION_WHEN, TimestampQuality.NOTIFICATION_POST_TIME -> stringResource(R.string.conv_time_notification)
        TimestampQuality.OBSERVED_ONLY -> stringResource(R.string.conv_time_captured)
    }
    val timeValue = TimeFormat.time(message.sourceTimestampEpochMs ?: message.observedAtEpochMs, locale = currentLocale())
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            "$timeValue · $timeLabel",
            style = MaterialTheme.typography.labelSmall,
            color = contentColor.copy(alpha = 0.7f),
            modifier = Modifier.align(Alignment.CenterVertically),
        )
        if (message.timestampQuality == TimestampQuality.SOURCE_MESSAGE) {
            Text(
                "${TimeFormat.time(message.observedAtEpochMs, locale = currentLocale())} · ${stringResource(R.string.conv_time_captured)}",
                style = MaterialTheme.typography.labelSmall,
                color = contentColor.copy(alpha = 0.5f),
                modifier = Modifier.align(Alignment.CenterVertically),
            )
        }
        if (message.dedupState == DedupState.AMBIGUOUS_REPEAT) QualityTag(stringResource(R.string.conv_ambiguous_tag), Icons.Outlined.ContentCopy, QualityColors.uncertain)
        if (message.contentStatus == ContentStatus.PREVIEW_RESTRICTED_SUSPECTED) QualityTag(stringResource(R.string.conv_preview_restricted), Icons.Outlined.VisibilityOff, QualityColors.uncertain)
        if (message.revisionCount > 0) QualityTag(stringResource(R.string.conv_revision, message.revisionCount), Icons.Outlined.Edit, QualityColors.inferred)
        if (message.observationCount > 1) QualityTag(stringResource(R.string.conv_observed_times, message.observationCount), Icons.Outlined.History, QualityColors.inferred)
        // A body that was cut used to be drawn exactly like a complete one.
        truncationLabel(message.truncationFlags)?.let { QualityTag(it.text, it.icon, it.tint) }
        mediaLabel(message.mediaState)?.let { QualityTag(it.text, it.icon, it.tint) }
        originLabel(message.origin)?.let { QualityTag(it, Icons.Outlined.Science, QualityColors.inferred) }
    }
}

@Composable
private fun Thumbnail(blobId: Long, load: suspend (Long) -> ByteArray?) {
    val bitmap by produceState<ImageBitmap?>(initialValue = null, key1 = blobId) {
        value = withContext(Dispatchers.IO) {
            load(blobId)?.let { bytes -> BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() }
        }
    }
    bitmap?.let {
        Image(
            bitmap = it,
            contentDescription = stringResource(R.string.conv_image_desc),
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 260.dp)
                .clip(RoundedCornerShape(14.dp)),
        )
    }
}

/** The clipboard is the only outbound path a message body has; nothing here leaves the device. */
private fun copyToClipboard(context: Context, text: String) {
    if (text.isBlank()) return
    val clip = ClipData.newPlainText("QuietInbox", text)
    // Android 13+ shows a preview of whatever is copied and every app can read the clip. A vault
    // whose whole point is keeping private messages on the device must at least say so.
    if (Build.VERSION.SDK_INT >= 33) {
        clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
    }
    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(clip)
}

/** How long a search hit stays tinted after the screen lands on it. */
private const val HIGHLIGHT_MS = 2500L

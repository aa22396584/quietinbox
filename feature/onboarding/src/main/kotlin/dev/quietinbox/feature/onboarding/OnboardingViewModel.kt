package dev.quietinbox.feature.onboarding

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.quietinbox.core.designsystem.R
import dev.quietinbox.core.model.KnownSources
import dev.quietinbox.core.parser.ParserRegistry
import dev.quietinbox.parsers.apps.AppParsers
import dev.quietinbox.platform.capture.CaptureCoordinator
import dev.quietinbox.platform.capture.ListenerAccess
import dev.quietinbox.platform.capture.SyntheticNotifications
import dev.quietinbox.platform.storage.repo.InboxRepository
import dev.quietinbox.platform.storage.repo.SourceRepository
import dev.quietinbox.platform.storage.settings.SettingsRepository
import dev.quietinbox.core.model.ListenerState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

import dev.quietinbox.core.model.SourceVerificationTier
import dev.quietinbox.core.model.SourceEvidenceResolver

/** How many messages the synthetic test conversation carries, and how long the step waits for them. */
const val TEST_MESSAGES = 3
const val TEST_TIMEOUT_MS = 20_000L

data class SourceChoice(
    val packageName: String,
    val label: String,
    val installed: Boolean,
    val hasAdapter: Boolean,
    val tier: SourceVerificationTier = SourceEvidenceResolver.resolveTier(packageName, hasAdapter),
)

data class OnboardingUiState(
    val step: Int = 0,
    val choices: List<SourceChoice> = emptyList(),
    val selected: Set<String> = emptySet(),
    val granted: Boolean = false,
    val testSent: Boolean = false,
    /** Only messages captured *since the test was sent*, from QuietInbox's own package. */
    val capturedMessages: Int = 0,
    val testSentAtEpochMs: Long? = null,
    /** The test was sent, the wait ran out and fewer than [TEST_MESSAGES] arrived. */
    val testTimedOut: Boolean = false,
    val listenerState: ListenerState = ListenerState.NOT_GRANTED,
    val canPostNotifications: Boolean = true,
) {
    val stepCount: Int get() = 5
    val testSucceeded: Boolean get() = capturedMessages >= TEST_MESSAGES
    val testFailed: Boolean get() = testSent && testTimedOut && !testSucceeded
}

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    private val sources: SourceRepository,
    private val listenerAccess: ListenerAccess,
    private val synthetic: SyntheticNotifications,
    private val coordinator: CaptureCoordinator,
    private val inbox: InboxRepository,
) : ViewModel() {
    private val registry = ParserRegistry(AppParsers.all())
    private val local = MutableStateFlow(OnboardingUiState(choices = buildChoices(), selected = defaultSelection()))

    /**
     * Counts only what this test produced. Watching the vault's total message count meant a re-run
     * of onboarding, a restored backup or a seeded demo vault reported success without capturing
     * anything, and 1-of-3 read the same as 3-of-3.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val capturedByTest: Flow<Int> = local
        .map { it.testSentAtEpochMs }
        .distinctUntilChanged()
        .flatMapLatest { sentAt ->
            if (sentAt == null) flowOf(0) else inbox.observeCapturedSince(context.packageName, sentAt).catch { emit(0) }
        }

    val state: StateFlow<OnboardingUiState> = combine(local, capturedByTest, coordinator.status) { s, captured, status ->
        s.copy(
            capturedMessages = captured,
            listenerState = status.listenerState,
            granted = listenerAccess.isGranted(),
            canPostNotifications = synthetic.canPost(),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), local.value)

    private fun buildChoices(): List<SourceChoice> {
        val pm = context.packageManager
        return KnownSources.ALL.map { pkg ->
            val label = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrNull()
            val hasAdapter = registry.adapterFor(pkg) != null
            SourceChoice(
                packageName = pkg,
                label = label ?: prettyName(pkg),
                installed = label != null,
                hasAdapter = hasAdapter,
                tier = SourceEvidenceResolver.resolveTier(pkg, hasAdapter),
            )
        }
    }

    private fun defaultSelection(): Set<String> = buildChoices().filter { it.installed }.map { it.packageName }.toSet()

    private fun prettyName(pkg: String) = when (pkg) {
        KnownSources.LINE -> "LINE"
        KnownSources.WHATSAPP -> "WhatsApp"
        KnownSources.TELEGRAM -> "Telegram"
        KnownSources.INSTAGRAM -> "Instagram"
        KnownSources.MESSENGER -> "Messenger"
        else -> pkg
    }

    fun next() = local.update { it.copy(step = (it.step + 1).coerceAtMost(it.stepCount - 1)) }
    fun back() = local.update { it.copy(step = (it.step - 1).coerceAtLeast(0)) }

    fun toggle(pkg: String) = local.update { s -> s.copy(selected = if (pkg in s.selected) s.selected - pkg else s.selected + pkg) }

    fun refreshPermission() {
        coordinator.refreshPermissionState()
        local.update { it.copy(granted = listenerAccess.isGranted()) }
    }

    /** False when no settings screen exists on this device; the screen then shows the manual path. */
    fun openListenerSettings(from: Context): Boolean = listenerAccess.openSettings(from)

    fun sendTest() {
        val now = System.currentTimeMillis()
        synthetic.postConversation(count = TEST_MESSAGES, iconRes = R.drawable.ic_stat_quiet)
        local.update { it.copy(testSent = true, testSentAtEpochMs = now, testTimedOut = false) }
        // The wait used to have no end: with capture broken the step span for ever and the only way
        // on was Skip, which said nothing about what had failed. A retry cancels the earlier wait,
        // or the first timer could call the second attempt failed seconds after it was sent (audit-2 O8).
        testTimeout?.cancel()
        testTimeout = viewModelScope.launch {
            delay(TEST_TIMEOUT_MS)
            if (state.value.capturedMessages < TEST_MESSAGES) local.update { it.copy(testTimedOut = true) }
        }
    }

    private var testTimeout: kotlinx.coroutines.Job? = null

    /** Persists the chosen sources first so a test/real notification is accepted immediately. */
    fun persistSources() = viewModelScope.launch {
        val now = System.currentTimeMillis()
        for (choice in local.value.choices) {
            if (choice.packageName in local.value.selected) {
                runCatching { coordinator.addSource(choice.packageName, choice.label, registry.adapterFor(choice.packageName)?.id, now) }
            }
        }
    }

    fun finish(onDone: () -> Unit) = viewModelScope.launch {
        persistSources().join()
        settings.setOnboardingCompleted(true)
        onDone()
    }

}

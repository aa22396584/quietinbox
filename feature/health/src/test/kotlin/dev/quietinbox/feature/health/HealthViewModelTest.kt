package dev.quietinbox.feature.health

import android.content.Context
import dev.quietinbox.core.model.ListenerState
import dev.quietinbox.core.model.SourceConfiguration
import dev.quietinbox.platform.capture.CaptureCoordinator
import dev.quietinbox.platform.capture.CaptureStatus
import dev.quietinbox.platform.capture.ListenerAccess
import dev.quietinbox.platform.capture.PolicyChangeException
import dev.quietinbox.platform.capture.SettlementFailedException
import dev.quietinbox.platform.capture.SyntheticNotifications
import dev.quietinbox.platform.crypto.KeyFailure
import dev.quietinbox.platform.storage.db.VaultState
import dev.quietinbox.platform.storage.db.VaultUnavailableException
import dev.quietinbox.platform.storage.repo.HealthRepository
import dev.quietinbox.platform.storage.repo.SourceRepository
import dev.quietinbox.platform.storage.repo.VaultRepository
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain

private const val PKG = "com.example.chat"

/**
 * A source change the coordinator refuses reaches the user (round 36 subagent I2; round 39 Codex I1).
 *
 * The dialog may promise only what the coordinator's exception type proves. A rollback (including
 * a settlement rollback) may say nothing was changed; a change that committed and could not be
 * read back must not; a locked vault is its own case; an unclassified throw invents no result.
 * Whether the transaction actually rolls back is decided on a vault (`SourcePolicyTransactionTest`);
 * this decides that the failure is surfaced, named, classified — and that a cancellation is never
 * mistaken for one.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HealthViewModelTest : FunSpec({

    class Harness {
        val coordinator = mockk<CaptureCoordinator>(relaxed = true)
        val sources = mockk<SourceRepository>(relaxed = true)
        val health = mockk<HealthRepository>(relaxed = true)
        val vault = mockk<VaultRepository>(relaxed = true)
        val access = mockk<ListenerAccess>(relaxed = true)

        init {
            every { coordinator.status } returns MutableStateFlow(CaptureStatus(listenerState = ListenerState.CONNECTED))
            every { vault.state } returns MutableStateFlow<VaultState>(VaultState.Opening)
            every { sources.observeSources() } returns flowOf(listOf(SourceConfiguration(packageName = PKG, displayName = "Chat", enabled = true, paused = false, retentionDays = null, mediaEnabled = true, addedAtEpochMs = 0L, adapterId = null)))
            every { health.observeGaps(any()) } returns flowOf(emptyList())
            every { health.observePendingJournal() } returns flowOf(0)
            every { access.isGranted() } returns true
        }

        fun viewModel() = HealthViewModel(
            context = mockk<Context>(relaxed = true),
            coordinator = coordinator,
            listenerAccess = access,
            sourceRepo = sources,
            health = health,
            vault = vault,
            synthetic = mockk<SyntheticNotifications>(relaxed = true),
        )
    }

    beforeTest { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    afterTest { Dispatchers.resetMain() }

    test("a settlement rollback is surfaced, named after the app, and classified as a settlement") {
        val h = Harness()
        coEvery { h.coordinator.setSourceEnabled(PKG, false) } throws
            PolicyChangeException.SettlementRefused(SettlementFailedException("a carried-over loss is not recorded"))
        val vm = h.viewModel()
        val job = launch { vm.state.collect { } }

        vm.setSourceEnabled(PKG, "Chat", false)

        val failure = vm.state.first { it.policyFailure != null }.policyFailure.shouldNotBeNull()
        failure.packageName shouldBe PKG
        failure.displayName shouldBe "Chat"
        failure.kind shouldBe PolicyFailure.Kind.SETTLEMENT
        job.cancel()
    }

    test("a refused remove that rolled back with a settlement is classified the same way") {
        val h = Harness()
        coEvery { h.coordinator.removeSource(PKG, true) } throws
            PolicyChangeException.SettlementRefused(SettlementFailedException("the gap write failed"))
        val vm = h.viewModel()
        val job = launch { vm.state.collect { } }

        vm.removeSource(PKG, "Chat", deleteData = true)

        vm.state.first { it.policyFailure != null }.policyFailure.shouldNotBeNull().kind shouldBe PolicyFailure.Kind.SETTLEMENT
        job.cancel()
    }

    test("a refused pause is a rollback, not a settlement") {
        val h = Harness()
        coEvery { h.coordinator.setSourcePaused(PKG, true) } throws PolicyChangeException.Refused(IllegalStateException("busy"))
        val vm = h.viewModel()
        val job = launch { vm.state.collect { } }

        vm.setSourcePaused(PKG, "Chat", true)

        vm.state.first { it.policyFailure != null }.policyFailure.shouldNotBeNull().kind shouldBe PolicyFailure.Kind.REFUSED
        job.cancel()
    }

    test("a change that committed and could not be read back is not a rollback") {
        val h = Harness()
        coEvery { h.coordinator.setSourceEnabled(PKG, false) } throws
            PolicyChangeException.CommittedNotReloaded(IllegalStateException("select failed"))
        val vm = h.viewModel()
        val job = launch { vm.state.collect { } }

        vm.setSourceEnabled(PKG, "Chat", false)

        vm.state.first { it.policyFailure != null }.policyFailure.shouldNotBeNull().kind shouldBe
            PolicyFailure.Kind.COMMITTED_NOT_RELOADED
        job.cancel()
    }

    test("a locked vault is the lock case, not a rollback") {
        val h = Harness()
        coEvery { h.coordinator.setSourceEnabled(PKG, false) } throws
            VaultUnavailableException(KeyFailure.Unavailable("before first unlock"))
        val vm = h.viewModel()
        val job = launch { vm.state.collect { } }

        vm.setSourceEnabled(PKG, "Chat", false)

        vm.state.first { it.policyFailure != null }.policyFailure.shouldNotBeNull().kind shouldBe PolicyFailure.Kind.LOCKED
        job.cancel()
    }

    test("an unclassified throw does not become a rollback") {
        val h = Harness()
        // If the ViewModel maps `else` back to REFUSED, this fails: a generic exception is not
        // proof the transaction rolled back (round 39, Codex I1).
        coEvery { h.coordinator.setSourceEnabled(PKG, false) } throws IllegalStateException("db")
        val vm = h.viewModel()
        val job = launch { vm.state.collect { } }

        vm.setSourceEnabled(PKG, "Chat", false)

        val failure = vm.state.first { it.policyFailure != null }.policyFailure.shouldNotBeNull()
        failure.kind shouldBe PolicyFailure.Kind.UNKNOWN
        failure.kind shouldNotBe PolicyFailure.Kind.REFUSED
        failure.kind shouldNotBe PolicyFailure.Kind.SETTLEMENT
        job.cancel()
    }

    test("a change that went through surfaces nothing, and a dismissed failure stays dismissed") {
        val h = Harness()
        coEvery { h.coordinator.setSourceEnabled(PKG, false) } throws IllegalStateException("once") andThen Unit
        val vm = h.viewModel()
        val job = launch { vm.state.collect { } }

        vm.setSourceEnabled(PKG, "Chat", false)
        vm.state.first { it.policyFailure != null }
        vm.dismissPolicyFailure()
        vm.state.first { it.policyFailure == null }

        vm.setSourceEnabled(PKG, "Chat", false)
        coVerify(exactly = 2) { h.coordinator.setSourceEnabled(PKG, false) }
        vm.state.value.policyFailure.shouldBeNull()
        job.cancel()
    }

    test("a cancellation is not reported as a refused change") {
        val h = Harness()
        coEvery { h.coordinator.setSourceEnabled(PKG, false) } throws CancellationException("scope gone")
        val vm = h.viewModel()
        val job = launch { vm.state.collect { } }

        vm.setSourceEnabled(PKG, "Chat", false)

        coVerify(exactly = 1) { h.coordinator.setSourceEnabled(PKG, false) }
        vm.state.value.policyFailure.shouldBeNull()
        job.cancel()
    }
})

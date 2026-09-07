package dev.quietinbox.feature.health

import android.content.Context
import dev.quietinbox.core.model.ListenerState
import dev.quietinbox.core.model.SourceConfiguration
import dev.quietinbox.platform.capture.CaptureCoordinator
import dev.quietinbox.platform.capture.CaptureStatus
import dev.quietinbox.platform.capture.ListenerAccess
import dev.quietinbox.platform.capture.SyntheticNotifications
import dev.quietinbox.platform.storage.db.VaultState
import dev.quietinbox.platform.storage.repo.HealthRepository
import dev.quietinbox.platform.storage.repo.SourceRepository
import dev.quietinbox.platform.storage.repo.VaultRepository
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
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
 * A source change the coordinator refuses reaches the user (round 36 subagent I2).
 *
 * Switching a source off and removing it settle the losses its pending rows carry inside the
 * policy transaction; a failed settlement rolls the whole change back, and the switch — bound to
 * the repository's flow — springs back on its own. Before this the ViewModel wrapped every call in
 * `runCatching {}` and dropped the result, so "stop capturing this app" could do nothing and say
 * nothing. Whether the transaction rolls back is decided on a vault (`SourcePolicyTransactionTest`);
 * this decides that the refusal is surfaced, named, and dismissable — and that a cancellation is
 * never mistaken for one.
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

    test("a refused switch-off is surfaced, named after the app, and blamed on the settlement") {
        val h = Harness()
        coEvery { h.coordinator.setSourceEnabled(PKG, false) } throws IllegalStateException("a carried-over loss is not recorded")
        val vm = h.viewModel()
        val job = launch { vm.state.collect { } }

        vm.setSourceEnabled(PKG, "Chat", false)

        val failure = vm.state.first { it.policyFailure != null }.policyFailure.shouldNotBeNull()
        failure.packageName shouldBe PKG
        failure.displayName shouldBe "Chat"
        failure.settle shouldBe true
        job.cancel()
    }

    test("a refused remove is surfaced the same way") {
        val h = Harness()
        coEvery { h.coordinator.removeSource(PKG, true) } throws IllegalStateException("the gap write failed")
        val vm = h.viewModel()
        val job = launch { vm.state.collect { } }

        vm.removeSource(PKG, "Chat", deleteData = true)

        vm.state.first { it.policyFailure != null }.policyFailure.shouldNotBeNull().settle shouldBe true
        job.cancel()
    }

    test("a refused pause is surfaced without the settlement explanation, which does not apply to it") {
        val h = Harness()
        coEvery { h.coordinator.setSourcePaused(PKG, true) } throws IllegalStateException("busy")
        val vm = h.viewModel()
        val job = launch { vm.state.collect { } }

        vm.setSourcePaused(PKG, "Chat", true)

        vm.state.first { it.policyFailure != null }.policyFailure.shouldNotBeNull().settle shouldBe false
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

        // The second attempt succeeds: nothing to say.
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

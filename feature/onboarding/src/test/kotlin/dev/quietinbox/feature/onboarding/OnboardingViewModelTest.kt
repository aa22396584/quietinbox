package dev.quietinbox.feature.onboarding

import android.content.Context
import android.content.pm.PackageManager
import dev.quietinbox.core.model.ListenerState
import dev.quietinbox.platform.capture.CaptureCoordinator
import dev.quietinbox.platform.capture.CaptureStatus
import dev.quietinbox.platform.capture.ListenerAccess
import dev.quietinbox.platform.capture.SyntheticNotifications
import dev.quietinbox.platform.storage.repo.InboxRepository
import dev.quietinbox.platform.storage.repo.SourceRepository
import dev.quietinbox.platform.storage.settings.SettingsRepository
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

/**
 * The onboarding capture test's verdict. Before this, the step watched the vault's *total* message
 * count, so a second run, a restored backup or a seeded demo vault reported success without having
 * captured anything; and there was no timeout, so a broken capture left it spinning for ever.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingViewModelTest : FunSpec({

    lateinit var captured: MutableStateFlow<Int>

    fun viewModel(granted: Boolean = true): OnboardingViewModel {
        val context = mockk<Context>(relaxed = true)
        val packageManager = mockk<PackageManager>(relaxed = true)
        every { context.packageManager } returns packageManager
        every { context.packageName } returns "dev.quietinbox.app"
        // No source app is installed in a unit test, so every choice is "not installed".
        every { packageManager.getApplicationInfo(any<String>(), any<Int>()) } throws PackageManager.NameNotFoundException()

        val inbox = mockk<InboxRepository>()
        captured = MutableStateFlow(0)
        every { inbox.observeCapturedSince(any(), any()) } returns captured

        val access = mockk<ListenerAccess>()
        every { access.isGranted() } returns granted

        val synthetic = mockk<SyntheticNotifications>(relaxed = true)
        every { synthetic.canPost() } returns true
        every { synthetic.postConversation(any(), any()) } returns TEST_MESSAGES

        val coordinator = mockk<CaptureCoordinator>(relaxed = true)
        every { coordinator.status } returns MutableStateFlow(CaptureStatus(listenerState = ListenerState.CONNECTED))

        return OnboardingViewModel(
            context = context,
            settings = mockk<SettingsRepository>(relaxed = true),
            sources = mockk<SourceRepository>(relaxed = true),
            listenerAccess = access,
            synthetic = synthetic,
            coordinator = coordinator,
            inbox = inbox,
        )
    }

    beforeTest { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    afterTest { Dispatchers.resetMain() }

    test("a vault that already holds messages does not make the test pass") {
        val vm = viewModel()
        val job = launch { vm.state.collect { } }
        // Whatever is already in the vault, nothing has been captured *by this test* yet.
        vm.state.first().capturedMessages shouldBe 0
        vm.state.first().testSucceeded shouldBe false
        job.cancel()
    }

    test("one of three captured is not a success") {
        val vm = viewModel()
        val job = launch { vm.state.collect { } }
        vm.sendTest()
        captured.value = 1
        val s = vm.state.first()
        s.capturedMessages shouldBe 1
        s.testSucceeded shouldBe false
        job.cancel()
    }

    test("three of three is a success and is never called a failure") {
        val vm = viewModel()
        val job = launch { vm.state.collect { } }
        vm.sendTest()
        captured.value = TEST_MESSAGES
        vm.state.first().testSucceeded shouldBe true
        vm.state.first().testFailed shouldBe false
        job.cancel()
    }

    test("a capture that never arrives is reported as a failure instead of spinning for ever") {
        runTest {
            Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
            val vm = viewModel()
            val job = launch { vm.state.collect { } }
            vm.sendTest()
            vm.state.first().testFailed shouldBe false // still waiting
            advanceTimeBy(TEST_TIMEOUT_MS + 1)
            val s = vm.state.first()
            s.testFailed shouldBe true
            s.capturedMessages shouldBe 0
            job.cancel()
        }
    }

    test("a copy that arrives late is a success, not a timed-out failure") {
        runTest {
            Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
            val vm = viewModel()
            val job = launch { vm.state.collect { } }
            vm.sendTest()
            advanceTimeBy(TEST_TIMEOUT_MS + 1)
            vm.state.first().testFailed shouldBe true
            captured.value = TEST_MESSAGES
            val s = vm.state.first()
            s.testSucceeded shouldBe true
            // testFailed is defined so that a success always wins over a stale timeout.
            s.testFailed shouldBe false
            job.cancel()
        }
    }
})

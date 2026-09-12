package dev.quietinbox.platform.storage.repo

import dev.quietinbox.core.model.Conversation
import dev.quietinbox.platform.storage.db.DatabaseHolder
import dev.quietinbox.platform.storage.db.QuietInboxDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class InboxVisibilityTest : FunSpec({
    class Harness {
        val db = mockk<QuietInboxDatabase>(relaxed = true)
        val holder = mockk<DatabaseHolder>()
        val expiries = MutableStateFlow<List<Long>>(emptyList())
        val queryBounds = mutableListOf<Long>()
        var now = 1_000L
        val inbox = InboxRepository(holder, mockk())

        init {
            every { holder.flowWithDb<List<Conversation>>(any()) } answers {
                firstArg<(QuietInboxDatabase) -> Flow<List<Conversation>>>()(db)
            }
            every { db.conversationDao().observeInboxAt(any(), any(), any(), any()) } answers {
                queryBounds += arg<Long>(3)
                flowOf(emptyList())
            }
            every { db.messageDao().observeNextExpiryAfter(any()) } answers {
                val bound = firstArg<Long>()
                expiries.map { rows -> rows.filter { it > bound }.minOrNull() }.distinctUntilChanged()
            }
            inbox.nowMs = { now }
        }
    }

    test("idle clock samples do not rebind the inbox query") {
        runTest {
            val h = Harness()
            h.inbox.nowMs = { 1_000L + testScheduler.currentTime }
            backgroundScope.launch { h.inbox.observeConversations(false, emptySet()).collect {} }
            runCurrent()
            advanceTimeBy(10_000)
            runCurrent()
            h.queryBounds shouldBe listOf(1_000L)
        }
    }

    test("clock jumps collapse past expiries and backward corrections reopen visibility") {
        runTest {
            val h = Harness()
            h.expiries.value = listOf(2_000L, 3_000L, 4_000L)
            backgroundScope.launch { h.inbox.observeConversations(false, emptySet()).collect {} }
            runCurrent()
            h.now = 5_000L
            advanceTimeBy(1_000)
            runCurrent()
            h.queryBounds shouldBe listOf(1_000L, 5_000L)
            advanceTimeBy(5_000)
            runCurrent()
            h.queryBounds shouldBe listOf(1_000L, 5_000L)
            h.now = 1_500L
            advanceTimeBy(1_000)
            runCurrent()
            h.queryBounds shouldBe listOf(1_000L, 5_000L, 1_500L)
            h.now = 2_000L // equality is expired, not one millisecond later
            advanceTimeBy(1_000)
            runCurrent()
            h.queryBounds shouldBe listOf(1_000L, 5_000L, 1_500L, 2_000L)
        }
    }

    test("a newly inserted earlier expiry reschedules without waiting for the old boundary") {
        runTest {
            val h = Harness()
            h.expiries.value = listOf(100_000L)
            backgroundScope.launch { h.inbox.observeConversations(false, emptySet()).collect {} }
            runCurrent()
            h.now = 3_000L
            advanceTimeBy(1_000)
            runCurrent()
            h.queryBounds shouldBe listOf(1_000L)
            h.expiries.value = listOf(2_000L, 100_000L)
            runCurrent()
            h.queryBounds shouldBe listOf(1_000L, 3_000L)
        }
    }

    test("explicit ticks reset scheduling and a returning collector samples fresh time") {
        runTest {
            val h = Harness()
            h.expiries.value = listOf(2_000L)
            val first = backgroundScope.launch { h.inbox.observeConversations(false, emptySet()).collect {} }
            runCurrent()
            h.now = 5_000L
            h.inbox.tickVisibility()
            runCurrent()
            advanceTimeBy(5_000)
            runCurrent()
            h.queryBounds shouldBe listOf(1_000L, 5_000L)
            first.cancel()
            runCurrent()
            h.now = 1_500L
            h.inbox.tickVisibility()
            advanceTimeBy(2_000)
            runCurrent()
            h.queryBounds shouldBe listOf(1_000L, 5_000L)
            backgroundScope.launch { h.inbox.observeConversations(false, emptySet()).collect {} }
            runCurrent()
            h.queryBounds shouldBe listOf(1_000L, 5_000L, 1_500L)
        }
    }
})

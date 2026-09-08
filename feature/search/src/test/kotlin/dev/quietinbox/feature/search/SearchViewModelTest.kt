package dev.quietinbox.feature.search

import dev.quietinbox.core.model.Message
import dev.quietinbox.platform.crypto.KeyFailure
import dev.quietinbox.platform.storage.db.VaultState
import dev.quietinbox.platform.storage.repo.InboxRepository
import dev.quietinbox.platform.storage.repo.SearchCursor
import dev.quietinbox.platform.storage.repo.SearchHit
import dev.quietinbox.platform.storage.repo.SearchPage
import dev.quietinbox.platform.storage.repo.SearchRepository
import dev.quietinbox.platform.storage.repo.VaultRepository
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import java.util.Collections

/** QI-VAULT-010: a locked vault is shown as locked, never as "no results"; a query waits for the vault. */
class SearchViewModelTest : FunSpec({
    // A real dispatcher: the ViewModel's debounce/delay must run on real time, not a test scheduler nobody advances.
    beforeSpec { Dispatchers.setMain(Dispatchers.Unconfined) }
    afterSpec { Dispatchers.resetMain() }

    suspend fun awaitUntil(timeoutMs: Long = 5_000, check: suspend () -> Unit) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            try { check(); return } catch (e: Throwable) { if (e is CancellationException || System.currentTimeMillis() > deadline) throw e; delay(10) }
        }
    }

    fun hit(id: Long): SearchHit {
        val message = mockk<Message>(relaxed = true)
        every { message.id } returns id
        return SearchHit(message, "t", "pkg")
    }

    fun hits(fromId: Long, n: Int): List<SearchHit> = (0 until n).map { hit(fromId + it) }

    fun table(ids: List<Long>): Triple<Int, Int, Int> {
        val unique = ids.toSet().size
        return Triple(ids.size, unique, ids.size - unique)
    }

    class Call(
        val query: String,
        val packages: Set<String>,
        val cursor: SearchCursor?,
        val entered: CompletableDeferred<Unit> = CompletableDeferred(),
        val gate: CompletableDeferred<SearchPage> = CompletableDeferred(),
    )

    class Harness {
        val search: SearchRepository = mockk()
        val inbox: InboxRepository = mockk()
        val vault: VaultRepository = mockk()
        val vaultState = MutableStateFlow<VaultState>(VaultState.Opening)
        val hit: SearchHit = mockk(relaxed = true)
        val calls: MutableList<Call> = Collections.synchronizedList(mutableListOf())

        init {
            every { inbox.observePackagesWithData() } returns flowOf(emptyList())
            every { vault.state } returns vaultState
            // The screen pages now: it asks for a page and keeps the cursor, instead of calling the
            // one-shot helper that threw `next` away.
            coEvery { search.searchPage(any(), any(), any(), any(), any(), any(), any()) } returns SearchPage(listOf(hit), null)
        }

        fun parkPages() {
            coEvery { search.searchPage(any(), any(), any(), any(), any(), any(), any()) } coAnswers {
                @Suppress("UNCHECKED_CAST")
                val call = Call(
                    query = args[0] as String,
                    packages = args[1] as Set<String>,
                    cursor = args[5] as SearchCursor?,
                )
                calls += call
                call.entered.complete(Unit)
                call.gate.await()
            }
        }

        fun viewModel() = SearchViewModel(search, inbox, vault)
    }

    test("a locked vault is reported as locked and the query is not run") {
        val h = Harness()
        val vm = h.viewModel()
        val collector = launch { vm.state.collect {} }
        h.vaultState.value = VaultState.Locked(KeyFailure.Unavailable("test"))
        vm.setQuery("hello")

        awaitUntil { vm.state.value.vaultLocked shouldBe true }
        delay(400)
        coVerify(exactly = 0) { h.search.searchPage(any(), any(), any(), any(), any(), any(), any()) }
        vm.state.value.searched shouldBe false
        vm.state.value.results.size shouldBe 0
        collector.cancel()
    }

    test("a query typed while the vault opens runs once it is ready, and again after a retry unlocks it") {
        val h = Harness()
        val vm = h.viewModel()
        val collector: Job = launch { vm.state.collect {} }
        vm.setQuery("hello")
        delay(400)
        coVerify(exactly = 0) { h.search.searchPage(any(), any(), any(), any(), any(), any(), any()) }
        vm.state.value.vaultOpening shouldBe true

        h.vaultState.value = VaultState.Ready(mockk(relaxed = true))
        awaitUntil { vm.state.value.results.size shouldBe 1 }
        vm.state.value.vaultOpening shouldBe false
        vm.state.value.searched shouldBe true

        h.vaultState.value = VaultState.Locked(KeyFailure.Unavailable("test"))
        awaitUntil { vm.state.value.vaultLocked shouldBe true }
        vm.state.value.results.size shouldBe 0
        h.vaultState.value = VaultState.Ready(mockk(relaxed = true))
        awaitUntil { vm.state.value.results.size shouldBe 1 }
        coVerify(exactly = 2) { h.search.searchPage(any(), any(), any(), any(), any(), any(), any()) }
        collector.cancel()
    }

    test("a full page keeps its cursor, so the header can never call it a total") {
        val h = Harness()
        val cursor = SearchCursor(sortKey = 5, id = 5)
        coEvery { h.search.searchPage(any(), any(), any(), any(), any(), any(), any()) } returns
            SearchPage(List(100) { h.hit }, cursor)
        val vm = h.viewModel()
        val collector = launch { vm.state.collect {} }
        h.vaultState.value = VaultState.Ready(mockk(relaxed = true))
        vm.setQuery("hello")
        awaitUntil { vm.state.value.results.size shouldBe 100 }
        vm.state.value.next shouldBe cursor
        collector.cancel()
    }

    test("a page that verifies nothing keeps its cursor, because the index is not finished") {
        val h = Harness()
        val cursor = SearchCursor(sortKey = 5, id = 5)
        val deeper = SearchCursor(sortKey = 4, id = 4)
        coEvery { h.search.searchPage(any(), any(), any(), any(), any(), isNull(), any()) } returns
            SearchPage(List(100) { h.hit }, cursor)
        // No candidate on this page verified, but the repository still handed back a cursor: that
        // means its scan budget ran out, not that the index is exhausted. Only a null cursor may
        // ever let the header state a total.
        coEvery { h.search.searchPage(any(), any(), any(), any(), any(), eq(cursor), any()) } returns
            SearchPage(emptyList(), deeper)
        val vm = h.viewModel()
        val collector = launch { vm.state.collect {} }
        h.vaultState.value = VaultState.Ready(mockk(relaxed = true))
        vm.setQuery("hello")
        awaitUntil { vm.state.value.next shouldBe cursor }
        vm.loadMore()
        awaitUntil { vm.state.value.next shouldBe deeper }
        vm.state.value.results.size shouldBe 100
        collector.cancel()
    }

    test("a stale page is discarded when the query has moved on") {
        val h = Harness()
        val cursor = SearchCursor(sortKey = 5, id = 5)
        coEvery { h.search.searchPage(any(), any(), any(), any(), any(), isNull(), any()) } returns
            SearchPage(List(100) { h.hit }, cursor)
        coEvery { h.search.searchPage(any(), any(), any(), any(), any(), eq(cursor), any()) } coAnswers {
            delay(300)
            SearchPage(List(5) { h.hit }, null)
        }
        val vm = h.viewModel()
        val collector = launch { vm.state.collect {} }
        h.vaultState.value = VaultState.Ready(mockk(relaxed = true))
        vm.setQuery("hello")
        awaitUntil { vm.state.value.next shouldBe cursor }
        vm.loadMore()
        // The reader retypes while the page is in flight: those five hits belong to nobody now.
        vm.setQuery("")
        vm.setQuery("hello")
        delay(600)
        vm.state.value.results.size shouldNotBe 105
        collector.cancel()
    }

    test("a new query never sends the previous search's cursor (mixed-cursor table)") {
        val h = Harness()
        h.parkPages()
        val vm = h.viewModel()
        val collector = launch { vm.state.collect {} }
        h.vaultState.value = VaultState.Ready(mockk(relaxed = true))
        vm.setQuery("alpha")
        awaitUntil { h.calls.size shouldBe 1 }
        h.calls[0].cursor shouldBe null
        h.calls[0].gate.complete(SearchPage(hits(1, 100), SearchCursor(1, 1)))
        awaitUntil { vm.state.value.next shouldBe SearchCursor(1, 1) }
        vm.setQuery("beta")
        vm.state.value.next shouldBe null
        vm.loadMore()
        awaitUntil { h.calls.any { it.query == "beta" && it.cursor == null } shouldBe true }
        h.calls.none { it.query == "beta" && it.cursor != null } shouldBe true
        val betaFirst = h.calls.first { it.query == "beta" && it.cursor == null }
        betaFirst.gate.complete(SearchPage(hits(1000, 100), SearchCursor(2, 2)))
        val alphaMore = h.calls.firstOrNull { it.query == "alpha" && it.cursor != null }
        alphaMore?.gate?.complete(SearchPage(hits(51, 100), null))
        awaitUntil { vm.state.value.results.size shouldBe 100 }
        val ids = vm.state.value.results.map { it.message.id }
        table(ids) shouldBe Triple(100, 100, 0)
        table(ids) shouldNotBe Triple(200, 150, 50)
        ids.toSet() shouldBe (1000L until 1100L).toSet()
        collector.cancel()
    }

    test("an obsolete load-more must not unlock a newer in-flight page (stale-unlock table)") {
        val h = Harness()
        h.parkPages()
        val vm = h.viewModel()
        val collector = launch { vm.state.collect {} }
        h.vaultState.value = VaultState.Ready(mockk(relaxed = true))
        vm.setQuery("alpha")
        awaitUntil { h.calls.size shouldBe 1 }
        h.calls[0].gate.complete(SearchPage(hits(1, 100), SearchCursor(1, 1)))
        awaitUntil { vm.state.value.next shouldBe SearchCursor(1, 1) }
        vm.loadMore()
        awaitUntil { h.calls.size shouldBe 2 }
        h.calls[1].cursor shouldBe SearchCursor(1, 1)
        vm.setQuery("beta")
        awaitUntil { h.calls.any { it.query == "beta" && it.cursor == null } shouldBe true }
        val betaFirst = h.calls.first { it.query == "beta" && it.cursor == null }
        betaFirst.gate.complete(SearchPage(hits(1000, 100), SearchCursor(2, 2)))
        awaitUntil { vm.state.value.next shouldBe SearchCursor(2, 2) }
        vm.loadMore()
        awaitUntil { h.calls.any { it.query == "beta" && it.cursor == SearchCursor(2, 2) } shouldBe true }
        vm.state.value.loadingMore shouldBe true
        h.calls[1].gate.complete(SearchPage(hits(51, 100), null))
        delay(100)
        vm.state.value.loadingMore shouldBe true
        val ids = vm.state.value.results.map { it.message.id }
        table(ids) shouldNotBe Triple(300, 200, 100)
        ids.toSet() shouldBe (1000L until 1100L).toSet()
        collector.cancel()
    }

    test("A then B then A applies only the later A's pages") {
        val h = Harness()
        h.parkPages()
        val vm = h.viewModel()
        val collector = launch { vm.state.collect {} }
        h.vaultState.value = VaultState.Ready(mockk(relaxed = true))
        vm.setQuery("alpha")
        awaitUntil { h.calls.size shouldBe 1 }
        vm.setQuery("beta")
        vm.setQuery("alpha")
        // The collector is still inside the first run; completing it must not publish those hits
        // onto the later A session. The later run starts only after this returns.
        h.calls[0].gate.complete(SearchPage(hits(1, 100), SearchCursor(1, 1)))
        delay(100)
        vm.state.value.results.map { it.message.id }.toSet() shouldNotBe (1L until 101L).toSet()
        awaitUntil { h.calls.count { it.query == "alpha" && it.cursor == null } shouldBe 2 }
        val later = h.calls.last { it.query == "alpha" && it.cursor == null }
        later.gate.complete(SearchPage(hits(5000, 100), SearchCursor(9, 9)))
        awaitUntil { vm.state.value.results.map { it.message.id }.toSet() shouldBe (5000L until 5100L).toSet() }
        vm.state.value.next shouldBe SearchCursor(9, 9)
        collector.cancel()
    }

    test("a source toggle during debounce invalidates the cursor and does not mix filters") {
        val h = Harness()
        h.parkPages()
        val vm = h.viewModel()
        val collector = launch { vm.state.collect {} }
        h.vaultState.value = VaultState.Ready(mockk(relaxed = true))
        vm.setQuery("alpha")
        awaitUntil { h.calls.size shouldBe 1 }
        h.calls[0].gate.complete(SearchPage(hits(1, 100), SearchCursor(1, 1)))
        awaitUntil { vm.state.value.next shouldBe SearchCursor(1, 1) }
        vm.togglePackage("pkg.one")
        vm.state.value.next shouldBe null
        delay(100)
        vm.togglePackage("pkg.two")
        vm.state.value.next shouldBe null
        awaitUntil { h.calls.any { it.packages == setOf("pkg.one", "pkg.two") && it.cursor == null } shouldBe true }
        h.calls.none { it.cursor != null && it.packages.isNotEmpty() } shouldBe true
        collector.cancel()
    }

    test("first-page and load-more completing out of order keep one session's prefix") {
        val h = Harness()
        h.parkPages()
        val vm = h.viewModel()
        val collector = launch { vm.state.collect {} }
        h.vaultState.value = VaultState.Ready(mockk(relaxed = true))
        vm.setQuery("alpha")
        awaitUntil { h.calls.size shouldBe 1 }
        h.calls[0].gate.complete(SearchPage(hits(1, 100), SearchCursor(1, 1)))
        awaitUntil { vm.state.value.next shouldBe SearchCursor(1, 1) }
        vm.loadMore()
        awaitUntil { h.calls.size shouldBe 2 }
        vm.setQuery("beta")
        awaitUntil { h.calls.any { it.query == "beta" } shouldBe true }
        h.calls[1].gate.complete(SearchPage(hits(200, 100), SearchCursor(3, 3)))
        val betaFirst = h.calls.first { it.query == "beta" }
        betaFirst.gate.complete(SearchPage(hits(9000, 50), null))
        awaitUntil { vm.state.value.searched shouldBe true }
        vm.state.value.results.map { it.message.id }.toSet() shouldBe (9000L until 9050L).toSet()
        vm.state.value.next shouldBe null
        collector.cancel()
    }

    test("a repository throw is a failed search, not an empty success, and the same query can be retried") {
        val h = Harness()
        var n = 0
        coEvery { h.search.searchPage(any(), any(), any(), any(), any(), any(), any()) } coAnswers {
            n++
            if (n == 1) error("index")
            SearchPage(listOf(h.hit), null)
        }
        val vm = h.viewModel()
        val collector = launch { vm.state.collect {} }
        h.vaultState.value = VaultState.Ready(mockk(relaxed = true))
        vm.setQuery("hello")
        awaitUntil { vm.state.value.failed shouldBe true }
        vm.state.value.searched shouldBe false
        vm.state.value.results.size shouldBe 0
        vm.retrySearch()
        awaitUntil { vm.state.value.results.size shouldBe 1 }
        vm.state.value.failed shouldBe false
        vm.state.value.searched shouldBe true
        collector.cancel()
    }

    test("a load-more throw keeps already-shown hits and the cursor") {
        val h = Harness()
        val cursor = SearchCursor(sortKey = 5, id = 5)
        coEvery { h.search.searchPage(any(), any(), any(), any(), any(), isNull(), any()) } returns
            SearchPage(hits(1, 100), cursor)
        coEvery { h.search.searchPage(any(), any(), any(), any(), any(), eq(cursor), any()) } throws IllegalStateException("page")
        val vm = h.viewModel()
        val collector = launch { vm.state.collect {} }
        h.vaultState.value = VaultState.Ready(mockk(relaxed = true))
        vm.setQuery("hello")
        awaitUntil { vm.state.value.next shouldBe cursor }
        vm.loadMore()
        awaitUntil { vm.state.value.loadingMore shouldBe false }
        vm.state.value.results.size shouldBe 100
        vm.state.value.next shouldBe cursor
        vm.state.value.failed shouldBe false
        collector.cancel()
    }

    test("CancellationException is not turned into an empty success") {
        val h = Harness()
        coEvery { h.search.searchPage(any(), any(), any(), any(), any(), any(), any()) } throws CancellationException("cancelled")
        val vm = h.viewModel()
        val collector = launch { vm.state.collect {} }
        h.vaultState.value = VaultState.Ready(mockk(relaxed = true))
        vm.setQuery("hello")
        delay(400)
        vm.state.value.searched shouldBe false
        vm.state.value.failed shouldBe false
        vm.state.value.results.size shouldBe 0
        collector.cancel()
    }
})

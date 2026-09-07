package dev.quietinbox.feature.search

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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain

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

    class Harness {
        val search: SearchRepository = mockk()
        val inbox: InboxRepository = mockk()
        val vault: VaultRepository = mockk()
        val vaultState = MutableStateFlow<VaultState>(VaultState.Opening)
        val hit: SearchHit = mockk(relaxed = true)

        init {
            every { inbox.observePackagesWithData() } returns flowOf(emptyList())
            every { vault.state } returns vaultState
            // The screen pages now: it asks for a page and keeps the cursor, instead of calling the
            // one-shot helper that threw `next` away.
            coEvery { search.searchPage(any(), any(), any(), any(), any(), any(), any()) } returns SearchPage(listOf(hit), null)
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
})

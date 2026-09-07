package dev.quietinbox.feature.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.quietinbox.platform.storage.db.VaultState
import dev.quietinbox.platform.storage.repo.InboxRepository
import dev.quietinbox.platform.storage.repo.SearchCursor
import dev.quietinbox.platform.storage.repo.SearchHit
import dev.quietinbox.platform.storage.repo.SearchPage
import dev.quietinbox.platform.storage.repo.SearchRepository
import dev.quietinbox.platform.storage.repo.VaultRepository
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class SearchRange { ALL, TODAY, DAYS_7, DAYS_30 }

data class SearchUiState(
    val query: String = "",
    val range: SearchRange = SearchRange.ALL,
    val packages: Set<String> = emptySet(),
    val availablePackages: ImmutableList<String> = persistentListOf(),
    val results: ImmutableList<SearchHit> = persistentListOf(),
    /**
     * Where the index stopped, or null when it was exhausted. A non-null cursor means more
     * *candidates* remain, not that more hits do — so the header may only ever say "there may be
     * more", never a total.
     */
    val next: SearchCursor? = null,
    val loadingMore: Boolean = false,
    val searching: Boolean = false,
    val searched: Boolean = false,
    /** The vault could not be opened: nothing can be searched and "no results" would be a lie (QI-VAULT-010). */
    val vaultLocked: Boolean = false,
    /** The vault is still opening; a query typed now runs once it is ready. */
    val vaultOpening: Boolean = true,
)

@OptIn(FlowPreview::class)
@HiltViewModel
class SearchViewModel @Inject constructor(
    private val search: SearchRepository,
    inbox: InboxRepository,
    private val vault: VaultRepository,
) : ViewModel() {
    private val local = MutableStateFlow(SearchUiState())

    val state: StateFlow<SearchUiState> = combine(local, inbox.observePackagesWithData().catch { emit(emptyList()) }, vault.state) { s, p, v ->
        s.copy(availablePackages = p.toImmutableList(), vaultLocked = v is VaultState.Locked, vaultOpening = v is VaultState.Opening)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SearchUiState())

    init {
        viewModelScope.launch {
            // A query is re-run when the vault becomes ready (typed while opening, or after a retry).
            combine(local.debounce(250), vault.state) { s, v -> s to v }
                .distinctUntilChanged { (a, va), (b, vb) -> a.query == b.query && a.range == b.range && a.packages == b.packages && va == vb }
                .collect { (s, v) -> if (v is VaultState.Ready) run(s) else if (v is VaultState.Locked) local.update { it.copy(results = persistentListOf(), searching = false, searched = false) } }
        }
    }

    fun retryVault() = viewModelScope.launch { runCatching { vault.retryOpen() } }

    fun setQuery(q: String) = local.update { it.copy(query = q, searching = q.isNotBlank()) }
    fun setRange(r: SearchRange) = local.update { it.copy(range = r) }

    /**
     * Appends the next page. The screen used to show the first 100 hits and call them "%d results",
     * so a query matching thousands read as though it had matched a hundred.
     */
    fun loadMore() {
        val s = local.value
        val cursor = s.next ?: return
        if (s.loadingMore) return
        local.update { it.copy(loadingMore = true) }
        viewModelScope.launch {
            val from = fromMs(s.range)
            val page = runCatching { search.searchPage(s.query, s.packages, from, null, limit = PAGE, cursor = cursor) }
                .getOrNull()
            local.update { cur ->
                // The query may have changed while the page was in flight; that result is not ours.
                if (cur.query != s.query || cur.range != s.range || cur.packages != s.packages) {
                    cur.copy(loadingMore = false)
                } else if (page == null) {
                    cur.copy(loadingMore = false)
                } else {
                    cur.copy(
                        results = (cur.results + page.hits).toImmutableList(),
                        // A page that verified nothing means the cursor had no hits left to give.
                        next = if (page.hits.isEmpty()) null else page.next,
                        loadingMore = false,
                    )
                }
            }
        }
    }
    fun togglePackage(p: String) = local.update { it.copy(packages = if (p in it.packages) it.packages - p else it.packages + p) }
    fun clearPackages() = local.update { it.copy(packages = emptySet()) }

    private fun fromMs(range: SearchRange): Long? {
        val now = System.currentTimeMillis()
        return when (range) {
            SearchRange.ALL -> null
            SearchRange.TODAY -> java.time.LocalDate.now().atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
            SearchRange.DAYS_7 -> now - 7L * 24 * 60 * 60 * 1000
            SearchRange.DAYS_30 -> now - 30L * 24 * 60 * 60 * 1000
        }
    }

    private suspend fun run(s: SearchUiState) {
        if (s.query.isBlank()) {
            local.update { it.copy(results = persistentListOf(), next = null, searching = false, searched = false) }
            return
        }
        val page = runCatching { search.searchPage(s.query, s.packages, fromMs(s.range), null, limit = PAGE, cursor = null) }
            .getOrDefault(SearchPage(emptyList(), null))
        local.update {
            it.copy(
                results = page.hits.toImmutableList(),
                next = if (page.hits.isEmpty()) null else page.next,
                searching = false,
                searched = true,
            )
        }
    }

    companion object {
        /** Hits per page. The screen appends pages; it never claims a total it has not counted. */
        const val PAGE = 100
    }
}

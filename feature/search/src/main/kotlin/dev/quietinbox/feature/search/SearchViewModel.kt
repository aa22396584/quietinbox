package dev.quietinbox.feature.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.quietinbox.platform.storage.db.VaultState
import dev.quietinbox.platform.storage.repo.InboxRepository
import dev.quietinbox.platform.storage.repo.SearchCursor
import dev.quietinbox.platform.storage.repo.SearchHit
import dev.quietinbox.platform.storage.repo.SearchRepository
import dev.quietinbox.platform.storage.repo.VaultRepository
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CancellationException
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
    /**
     * Identity of the current search (query, sources, frozen time range, cursor, results).
     * Bumped when those conditions change; a page from an older session is discarded and must
     * not clear this session's in-flight flags.
     */
    val sessionId: Long = 0,
    /** Identity of the in-flight first-page or load-more request inside [sessionId]. */
    val pageRequestId: Long = 0,
    val searching: Boolean = false,
    val searched: Boolean = false,
    /** A repository throw other than cancellation: not a successful empty page. */
    val failed: Boolean = false,
    /** Time range frozen when this [sessionId] started, so paging cannot drift across midnight. */
    val frozenFromMs: Long? = null,
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
    private var sessionSeq = 0L
    private var requestSeq = 0L

    val state: StateFlow<SearchUiState> = combine(local, inbox.observePackagesWithData().catch { emit(emptyList()) }, vault.state) { s, p, v ->
        s.copy(availablePackages = p.toImmutableList(), vaultLocked = v is VaultState.Locked, vaultOpening = v is VaultState.Opening)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SearchUiState())

    init {
        viewModelScope.launch {
            // A query is re-run when the vault becomes ready (typed while opening, or after a retry).
            combine(local.debounce(250), vault.state) { s, v -> s to v }
                .distinctUntilChanged { (a, va), (b, vb) -> a.sessionId == b.sessionId && va == vb }
                .collect { (s, v) ->
                    if (v is VaultState.Ready) run(s)
                    else if (v is VaultState.Locked) {
                        local.update { it.copy(results = persistentListOf(), next = null, searching = false, searched = false, failed = false, loadingMore = false) }
                    }
                }
        }
    }

    fun retryVault() = viewModelScope.launch { runCatching { vault.retryOpen() } }

    /** Re-run the current session after a failed first page. Same query, new request id. */
    fun retrySearch() {
        val s = local.value
        if (s.query.isBlank()) return
        if (vault.state.value !is VaultState.Ready) return
        viewModelScope.launch { run(s) }
    }

    fun setQuery(q: String) = local.update { if (q == it.query) it else newSession(it.copy(query = q)) }
    fun setRange(r: SearchRange) = local.update { if (r == it.range) it else newSession(it.copy(range = r)) }

    /**
     * Appends the next page. The screen used to show the first 100 hits and call them "%d results",
     * so a query matching thousands read as though it had matched a hundred.
     */
    fun loadMore() {
        val s = local.value
        val cursor = s.next ?: return
        if (s.loadingMore || s.searching || s.failed) return
        val sessionId = s.sessionId
        val requestId = ++requestSeq
        local.update { it.copy(loadingMore = true, pageRequestId = requestId) }
        viewModelScope.launch {
            val page = try {
                search.searchPage(s.query, s.packages, s.frozenFromMs, null, limit = PAGE, cursor = cursor)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                local.update { cur ->
                    if (cur.sessionId != sessionId || cur.pageRequestId != requestId) cur
                    else cur.copy(loadingMore = false)
                }
                return@launch
            }
            local.update { cur ->
                if (cur.sessionId != sessionId || cur.pageRequestId != requestId) {
                    cur
                } else {
                    cur.copy(
                        results = (cur.results + page.hits).toImmutableList(),
                        // `next == null` is the repository's own "index exhausted" signal and the
                        // only sound one. An empty page with a cursor means the candidate scan
                        // budget ran out — there may well be hits further down.
                        next = page.next,
                        loadingMore = false,
                    )
                }
            }
        }
    }
    fun togglePackage(p: String) = local.update {
        val packages = if (p in it.packages) it.packages - p else it.packages + p
        if (packages == it.packages) it else newSession(it.copy(packages = packages))
    }

    fun clearPackages() = local.update { if (it.packages.isEmpty()) it else newSession(it.copy(packages = emptySet())) }

    private fun newSession(base: SearchUiState): SearchUiState {
        return base.copy(
            sessionId = ++sessionSeq,
            pageRequestId = 0,
            next = null,
            loadingMore = false,
            searching = base.query.isNotBlank(),
            searched = false,
            failed = false,
            frozenFromMs = fromMs(base.range),
        )
    }

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
            local.update { cur ->
                if (cur.sessionId != s.sessionId) cur
                else cur.copy(results = persistentListOf(), next = null, searching = false, searched = false, failed = false, loadingMore = false)
            }
            return
        }
        val sessionId = s.sessionId
        val requestId = ++requestSeq
        local.update { cur ->
            if (cur.sessionId != sessionId) cur
            else cur.copy(pageRequestId = requestId, searching = true, failed = false)
        }
        val page = try {
            search.searchPage(s.query, s.packages, s.frozenFromMs, null, limit = PAGE, cursor = null)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            local.update { cur ->
                if (cur.sessionId != sessionId || cur.pageRequestId != requestId) cur
                else cur.copy(
                    results = persistentListOf(),
                    next = null,
                    loadingMore = false,
                    searching = false,
                    searched = false,
                    failed = true,
                )
            }
            return
        }
        local.update { cur ->
            if (cur.sessionId != sessionId || cur.pageRequestId != requestId) return@update cur
            cur.copy(
                results = page.hits.toImmutableList(),
                next = page.next,
                loadingMore = false,
                searching = false,
                searched = true,
                failed = false,
            )
        }
    }

    companion object {
        /** Hits per page. The screen appends pages; it never claims a total it has not counted. */
        const val PAGE = 100
    }
}

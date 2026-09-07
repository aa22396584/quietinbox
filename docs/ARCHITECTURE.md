> 繁體中文：[docs/zh-Hant/ARCHITECTURE.md](zh-Hant/ARCHITECTURE.md)

# Architecture

## Module graph

```
app ──► feature:* ──► core:designsystem ──► core:model
 │         │
 │         └──► platform:storage ──► core:parser / core:identity / core:reconcile / core:analytics
 │                     │            └──► platform:crypto (Keystore KEK, Tink AEAD, recovery key codec)
 │                     └──► Room + SQLCipher, DataStore, WorkManager
 ├──► platform:capture ──► parsers:apps ──► core:parser
 │            └──► platform:media ──► platform:crypto, platform:storage
 └──► platform:backup ──► platform:crypto, platform:storage, Tink Streaming AEAD
```

`core:*` except `core:designsystem` — which is an Android Compose library — and `parsers:apps` are
plain Kotlin/JVM modules: they cannot reference `android.*`, run on
the JVM under Kotest, and hold every algorithm the plan requires to be testable without a device
(parsing, identity, deduplication, statistics, normalisation). `platform:*` modules wrap Android
APIs; `feature:*` modules are Compose UI + Hilt ViewModels; `app` wires navigation and DI.

## Capture pipeline (plan §5)

```
StatusBarNotification
  → CaptureCoordinator.isCapturable      (enabled-source allow-list; own package only with the synthetic marker)
  → SnapshotFactory.create               (allow-listed extras, size bounds, TruncationFlags; no PendingIntent/RemoteViews/Bitmap decode)
  → Channel(MAX_QUEUE_DEPTH)             (overflow ⇒ counted, DEGRADED, gap recorded — never DROP_OLDEST silently)
  → admission fence, twice               (before waiting for the pipeline lock and again inside it: pause, maintenance,
                                          generation, source policy — whatever changed while the event waited wins)
  → IngestRepository.journal             (durable accepted; JSON payload in the encrypted vault, cleared on leaving PENDING;
                                          a loss the event arrived with is written in this transaction and marked lossRecorded)
  → ParserRegistry.parse                 (adapter by package, else StandardParser)
  → IdentityResolver.resolve             (chat id > shortcut > notification stream > title; never cross-stream)
  → Reconciler.reconcile                 (suffix/prefix window alignment, ids, AMBIGUOUS_REPEAT, stale windows)
  → commit fence                         (a source disabled since ⇒ journal DISCARDED; a pause or maintenance ⇒ stays PENDING)
  → IngestRepository.commit              (one transaction: conversation, messages, revisions, links, tokens, checkpoint, journal state)
  → MediaCopier.copyPending              (bounded, time-limited, encrypted blobs; failure reasons kept)
  → Room Flows → ViewModels → Compose
```

Process death before `journal` loses the event (documented as platform-unobservable); after
`journal` the row is replayed on next vault open, on resume and after maintenance with
`CaptureOrigin.REPLAY` — never while capture is paused, and never for a source disabled since.

A row a release up to 0.1.3 left pending carries a loss that release recorded nowhere. The two
exits that still have a readable payload — replay, and the discard that follows disabling or
removing the source — settle it first. Two others never do: an undecodable payload has nothing to
settle, and a row whose commit attempts run out is filed `FAILED` with its payload cleared and no
gap (issue #28). `event_journal.lossRecorded` (schema v4) is
claimed by a conditional update that writes the gap in the same transaction, so whichever path
reaches the row first records it and no later pass records it again. Only what the payload settles
is claimed: `LINES`, and `MESSAGES` with no surviving message of its own shortened.

That column holds three values, because a settlement the vault refuses is neither settled nor
merely unsettled. Charging the failure to the event's commit attempts destroys the payload after
three tries; leaving the row in the replay's candidate set puts it at the head of every page, where
enough of them starve everything behind. A refused settlement is *deferred* instead: still
`PENDING`, payload and claim untouched, out of both readers until the pass that runs next puts it
back — which every replay and every settle walk does before it reads. The retry is armed by proof
rather than by a timer: an event accepted with a loss of its own has just written a gap, so the
table that refused is taking writes again; an acceptance that wrote no gap arms nothing.

Both readers are bounded and both are paged. The settle walk runs inside the source policy
transaction, under the pipeline lock, so its cost is live capture's: it seeks to a
`(receivedAtEpochMs, eventId)` row-value cursor inside
`index_event_journal_packageName_state_lossRecorded_receivedAtEpochMs_eventId` rather than
re-reading and re-sorting the source's pending rows once per page.

Source policy (add / enable / pause / remove) goes through `CaptureCoordinator`: the vault write
and the in-memory allow-list change under the pipeline lock, so an event waiting for the lock is
fenced against the new policy, not the old one. Disabling or removing a source discards its
pending journal rows.

## Maintenance gate (QI-SEC-003)

`VaultMaintenance` (platform:storage) owns the pipeline lock and a cancellable registry of vault
work. `MediaCopier.copyPending`, journal replay and `RetentionService` run inside `work {}`:
refused while maintenance is active, cancelled when it starts. `VaultRepository.deleteEverything`
runs inside `exclusive {}`: flag → cancel and join every worker → hold the pipeline lock → close
and delete the database files → delete media → destroy keys → clear settings → reopen, each step
verified, the result naming the failed step. The capture side rotates its generation on the flag
(everything queued is dropped, nothing new is queued) and records the window as an exact
`MAINTENANCE` gap. `KeyMaterial.epoch` is bumped by `destroyAll()`, and `BlobCipher` rebuilds its
cached primitive when the epoch moved, so nothing is ever encrypted with a destroyed media key.

## Storage (plan §8)

Single SQLCipher database `quietinbox.vault` (WAL) with the tables of §8: `source_configuration`,
`capture_session`, `gap_interval`, `event_journal`, `notification_checkpoint`, `conversation`,
`message`, `message_revision`, `observation_link`, `media_blob`, `deletion_suppression`,
`search_token`, `summary_observation`, `local_diagnostic_event`. Schema is exported to
`platform/storage/schemas/` (v4) and `fallbackToDestructiveMigration()` is not used.

Deletion graph (QI-DATA-004 / 007): a journal row's payload is cleared the moment it leaves
`PENDING`; deleting messages or a conversation removes their `media_blob` rows in the same
transaction and the files right after it; removing a source with its data also removes its
suppression tokens, summaries, diagnostics and pending journal; `ConversationDao.rebuildProjection`
recomputes counts, preview, last sender and last activity from the surviving rows after every
deletion, expiry sweep and restore. Reads (conversation, search, statistics, counts) filter
`expiresAtEpochMs > now` themselves; `now` is fixed when a Flow is collected.

Search: `search_token(token, messageId)` holds CJK bigrams, Latin words and 3-grams produced by
`SearchNormalizer.tokens`; a query is the same tokens joined by `GROUP BY … HAVING COUNT(DISTINCT
token) = n`, then every candidate is re-verified as a normalised substring in Kotlin. Candidates
are read in keyset pages `(sortKey, id)` and the loop continues until the page of verified hits
is full or the index is exhausted, so false positives never hide a later hit; `searchPage`
returns the cursor (QI-SEARCH-011).

Backup (QI-BACKUP-016): export is cancellable vault work, import an exclusive maintenance run;
export streams each table in keyset pages inside one read transaction and never includes an
expired copy; media it cannot read is counted as skipped and reported.

## Keys (plan §9)

- `KeystoreWrapper`: AES-256-GCM key in AndroidKeyStore, `setUserAuthenticationRequired(false)`,
  so the listener can write while the screen is locked. The UI lock is a separate gate. Creation
  of the KEK is serialised process-wide (three secrets created at once on a fresh install must
  share one key).
- `KeyMaterial`: three 32-byte random secrets (`db.key`, `media.key`, `recovery.key`) stored only
  Keystore-wrapped under `files/keys/`. Failure ⇒ `VaultState.Locked`, never a silent wipe.
- `BlobCipher`: Tink AES-256-GCM with the file name as associated data.
- `BackupCrypto`: HKDF-SHA256(recovery key, random salt) → Tink AES-256-GCM-HKDF streaming AEAD;
  header (magic, version, salt) bound as associated data.

## UI

Material 3 Expressive (`MaterialExpressiveTheme`, expressive motion scheme, large shapes) with a
brand palette by default and optional dynamic colour. Navigation 3 back stack; from medium width
(600dp) the bottom bar becomes a `NavigationRail`, and from expanded width (840dp)
`ListDetailSceneStrategy` shows inbox and conversation side by side. The two breakpoints are
deliberately different: the default pane directive allows two panes only from expanded, so between
600dp and 839dp the rail is up but the conversation is alone and keeps its back arrow.
Every quality state renders text + icon (colour is never the only signal). The activity screen is
five tabs (overview, rankings, best time, chattiness, quiet rate) over one shared period selector,
each computed by pure functions in `core:analytics` and each labelled as observed messages only.

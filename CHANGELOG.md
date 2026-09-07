# Changelog

All notable changes to this project are documented here. The format follows Keep a Changelog.

## [Unreleased]

Fixes from the second GPT-5.5 Pro re-review (issues #22–#27). Every item was re-verified against the
code before it was worked: some of what the review asked for was already there, and two of its claims
were wrong in a way that changed the fix.

### Fixed
- **A hung media provider could hang "Delete everything" and "Restore" for ever.** The 10-second read
  timeout was around `openInputStream` and `InputStream.read`, both blocking binder calls with no
  suspension point, so it could not fire until the read had already returned — the timeout was dead
  code. The blocked worker held one of the copier's two permits and stayed registered in
  `VaultMaintenance.workers`, which an exclusive run joins *before* it takes the pipeline lock. The
  read now runs in the copier's own scope and is awaited with a timeout, so the copy gives up on time
  and the thread is abandoned rather than joined.
- **A media URI whose provider had gone away was reported as "media too large".** A dead provider
  returns a null stream instead of throwing, and that fell into the same branch as the over-size
  early return. It is an expired link, and says so.
- **Notification bitmaps ignored the 512 MB media quota**, which was checked only on the `content://`
  path. The check now sits where both paths meet. Quota exhaustion also has its own state and label:
  calling a 4 KB thumbnail "media too large" because the vault was full was never true.
- **A message whose media copy threw showed an hourglass for ever.** The row stayed `PENDING`, and
  `MessageDao.pendingMedia` — the query written for exactly this — had no caller anywhere. One
  failing copy also cancelled every sibling in its batch, leaving those `PENDING` too. Copies are now
  independent of one another, a failure is recorded as one, and the retention sweep settles anything
  still pending after an hour.
- **Media files whose row never committed were permanent dead weight.** Reclamation compared rows to
  rows and never the directory to the database, so a file written before a process death (or an
  interrupted restore) was never seen again. The sweep now removes files no row points at, skipping
  anything younger than an hour so it cannot race a copy that has not committed yet.
- **Media copies were queued without a bound.** Only bitmaps were counted, so any number of URI-only
  copies could pile up behind the copier's two permits, each one a job an exclusive run has to join.
- Opening the source app swallowed a failed launch: if the app had been disabled or removed since the
  button was drawn, nothing happened and nothing was said. It now says so.
- The same dialog claimed "the original notification is no longer active" every single time, because
  the flag behind it was hardcoded. QuietInbox never keeps a source `PendingIntent`, so it cannot know
  whether the notification is live — the sentence now says what is actually true: the app opens at its
  own starting screen, not at this chat.
- **Between 600dp and 839dp the conversation had no visible way back.** One value decided both where
  the navigation sits and whether the inbox stays beside the conversation, but those have different
  breakpoints: the rail replaces the bottom bar at medium width, while `ListDetailSceneStrategy`'s
  default directive gives two panes only from expanded width. On a small tablet, a landscape phone or
  a split-window pane the conversation therefore filled the window while the code believed the inbox
  was still next to it, and drew no back arrow. The system back gesture still worked and the rail
  still led out, so nobody was trapped — but the only visible exit was gone. The two decisions are
  now separate, and so are they in `tools/demo-screenshots.sh`, which had inherited the same
  assumption.
- **A notification that carried more messages than the snapshot may hold lost the oldest ones
  silently.** They were discarded before parsing, the ingest that followed reported a clean commit,
  and nothing anywhere said content had existed and been lost — a gap hidden inside a success, in an
  app whose first rule is that gaps are shown. It is recorded as a gap now, in the transaction that
  accepts the event: the loss arrived with the notification, so it is committed with it or not at
  all. Every way the event can end afterwards inherits the record without having to know it exists —
  a clean commit, an empty parse filed merely as "skipped", a pause that leaves the row pending, or
  a source disabled later, which discards the row for good. Writing it after the commit fence
  instead, as the first attempt did, lost it on three of those four paths and wrote it twice on
  replay; the event id is the journal's primary key, so acceptance — and the gap with it — happens
  exactly once per accepted event, however many times that event is replayed or re-delivered.
  Not once per *notification*, which round 34 was right to separate: a snapshot is stamped with a
  fresh id every time one is taken, so a reconnect that re-reads the notifications still on screen
  produces new events, and one that still declares dropped messages records the loss again. That is
  a second observation of a loss that is still true rather than a duplicate of one write, but it
  does mean the health page can list the same batch more than once after a reconnect. Left as is
  and recorded here; deduplicating across snapshots is a change to the resync, not to this path.
  Making that possible needed a flag split first. `TruncationFlag.MESSAGES` was raised by two
  different losses in the same function — whole messages discarded, and a kept message whose text
  was shortened — so a gap keyed on it would have manufactured gaps that never happened. `LINES`
  was split the same way and for the same reason: it always meant whole lines were dropped, while
  the flags either side of it meant text had been shortened, so reading it correctly depended on
  knowing which function had raised it. The split describes the capture side; whether a body was
  cut is answered per message downstream, from evidence the same payload already carries. The
  snapshot's flag set is still read, and for more than one thing: acceptance turns the three
  `*_DROPPED` flags into a gap, the upgrade path below reads the old vocabulary, and a non-empty
  set of any kind raises the `TRUNCATED_INPUT` parse warning. What no longer happens is a *message
  row* taking its label from it.
- **Content already lost when 0.1.3 journalled it is no longer lost again by the upgrade.** The flag
  set is persisted — the journal holds the whole snapshot — so a row still pending when 0.1.3
  becomes 0.1.4 carries the old vocabulary, and it never goes through the acceptance path that now
  writes the loss: replay hands it straight to the commit, and the terminal transition then clears
  the payload that was the only evidence. Two of those payloads still settle themselves. `LINES`
  was raised only when the line array was longer than the snapshot may hold, so it can mean nothing
  but dropped lines; an over-long single line was shortened without it. `MESSAGES` had the two
  causes above, and a payload whose every surviving message is complete rules the second one out,
  leaving the first. Both are recorded now — once, at whichever of the two exits that
  still have a readable payload reaches the row first, replayed or discarded along with a source
  the user switched off, and in both cases before the payload can be cleared. Two other exits do
  not settle *that* loss: an undecodable payload has nothing to settle, and a row whose commit
  attempts run out records a loss of its own — see the next entry.
  What makes it once is a column rather than a reading of the flags. `event_journal.lossRecorded`
  is set with the insert for an event whose loss this release recorded at acceptance — an ordinary
  event that lost nothing stays 0, and so is never mistaken for one — defaults to 0 on rows carried over —
  which is the truth about them, since nothing was written for them — and is taken
  by a conditional update that writes the gap in the same transaction. A row replayed twenty times
  therefore lists one loss, and a claim whose gap cannot be written stays unspent for the next
  attempt rather than marking a record that does not exist.
  That column is two bits, not a flag, because a settlement that *cannot* be written is neither
  "done" nor "not started yet" and both of those readings caused a defect of their own. Charging the
  failure to the event's three commit attempts filed the row `FAILED` and cleared its payload,
  losing the survivors and the record together. Leaving it merely unsettled kept the evidence but
  left the row at the head of every replay page: two hundred rows whose gap writes keep failing then
  starve the two hundred and first for ever, however often a replay runs. A refused settlement is
  therefore *deferred* — out of the two passes that read pending rows, still `PENDING`, payload and
  unspent claim untouched — and a pass puts them back once it has drained everything else, so the
  retry costs each row one attempt per pass and no more. Doing it at the *head* of a pass instead
  re-inserted a failing prefix in front of everything on every trigger: twenty thousand rows whose
  gap writes keep failing re-exhaust the hundred-round budget each time, and the row behind them is
  never even read, however many replays run. It is also not left waiting for the user: the
  four things that trigger a replay are all a user or a lifecycle event, so a device that got its
  disk space back could have waited days, and the retry is now armed by proof instead — an event
  accepted *with* a loss wrote a gap in its acceptance transaction, which is exactly the table that
  had refused. An acceptance that wrote no gap arms nothing, so a vault that keeps refusing cannot
  turn a stream of events into a stream of replays.
  Adding a third value to that column also gave a `false` claim a second meaning where the code read
  only one, and closing that was the round after's Critical. A claim that took nothing used to mean
  exactly "another pass already wrote this gap", so a caller could ignore the answer and go on to
  commit; a deferred row answers the same way, and there the gap does *not* exist. A replay holding
  a page read before another pass deferred the row would therefore store the survivors and clear the
  payload that was the only record of what they were missing. The claim now says which of four
  things it found — it wrote the gap, the gap was already there, the row is deferred, or the row has
  left `PENDING` — and only the first two let a row go on to a terminal state; the settle walk
  aborts the whole policy change on the other two, because the discard that follows would clear the
  evidence for good.
  Replay passes are coalesced rather than run side by side, which is what made that interleaving
  possible: a page is read outside the pipeline lock, and five things can start a pass. A request
  sets a flag that only the pass holding the gate clears, and every caller waits for the gate
  rather than leaving a busy one to its holder: the holder can be cancelled mid-pass — a
  maintenance run cancels the work it is inside, a vault state change cancels the collector that
  started it — and a request that arrived meanwhile then had no one left to run it, so a
  maintenance run's end could leave the rows it had interrupted in the journal until the next
  lifecycle event (found by Codex in review round 38, against a real maintenance run). A request
  that finds maintenance active is kept, not cleared, for the run's end. The walk's resume is
  scoped to the one source whose policy transaction it runs in, rather than resetting every app's
  deferred rows and undoing that again if the transaction rolls back.
- **An event whose commit failed on every attempt it had vanished without a record.** The third
  failed commit filed the journal row `FAILED`, which clears the payload, and nothing wrote a gap:
  an accepted event — durable, acknowledged, counted — disappeared with nothing on the capture page
  to say so, the exact shape this release exists to remove, one path further along (issue #28,
  older than 0.1.4). The loss of the whole event is now recorded first, in the transaction that
  files the row — `could not be saved after repeated attempts`, bounded by the notification's post
  and the observation, scoped to the source — so the row leaves `PENDING` with its record or not at
  all. When that record cannot be written the row is parked the way a refused settlement is, and
  tried once more when a pass resumes it: the third failure is the point at which the loss must be
  recorded, not a ceiling on trying, and a parked row that commits on its fourth go lost nothing.
  That needed the deferral to become a bit rather than a value — exhausted rows arrive with their
  arrival loss already settled as often as not, and the earlier deferral either could not park such
  a row or resumed it as unsettled, after which the next pass claimed and wrote that loss a second
  time. A resume now gives a row back exactly the settled state it had. The design was put to an
  independent reviewer read-only as three options and its choice, with the transition table and
  the tests that decide it, is archived under `docs/reviews/2026-09-07-issue28-consult/`.
- **A group body cut on a line separator lost a whole row with nothing to show for it.** The
  round-34 rule above is right that every surviving row is complete, and it left the row that
  began after the separator — gone before the parser saw it — with no gap and no label anywhere.
  The parser is the only thing that can see this loss, and it exists only relative to the batch
  that is stored, so the batch now carries it and the commit records it in its own transaction, on
  both of its exits: committed with the messages or not at all. A cut that fell *inside* the last
  row still marks that row and claims nothing about what may have followed it.
- **A backup merge dropped the truncation evidence a row already here was missing.** The label is
  set on a row *after* insert, when a repost of the same bounded body arrives shortened, and that
  update changes none of the three columns the merge's duplicate key is made of; so a backup taken
  after the label, merged into a vault restored from one taken before it, skipped the row and the
  label with it — and the bubble read as complete again, depending on the order the user restored
  in. The merge now adds the label the existing row lacks, the same single-valued write the live
  path makes, and never takes one away.
- **A restore cancelled on its way out could delete the media it had just restored.** Every blob is
  written to disk before the transaction, and the list of files to remove on failure was trimmed
  to the unreferenced ones by a flag set *after* `withTransaction` returned. A cancellation landing
  between the commit and that return is delivered as an exception from a call whose rows are
  already durable, so the flag was still false and every file went, linked ones included. The list
  is now trimmed inside the transaction, as the media copier has done since round 10; a commit
  that fails after that line leaves the linked files for the retention sweep, a leak, never a loss
  (audit-2 ATOM-4; the working rule in `CLAUDE.md` had said this since round 10, the code had not).
- **A restore that lost media said "Done".** A blob that could not be decoded, was over the size
  limit or could not be encrypted left its message marked `FAILED` and the result unqualified. The
  count is now on the result and on the screen. And a vault without room for the file's media used
  to find out half-way through: the free space is checked before anything is written, and a
  refused restore changes nothing (audit-2 ATOM-3; the check is coarse — decoded media bytes plus a
  32 MB floor for the rows).
- **A half-copied backup file was told its key was wrong.** Tink refuses the cut segment before the
  reader can miss the end record, so a truncated file and a wrong key share one reason; the label
  now says "modified or incomplete" (audit-2 ATOM-2). A file that ends inside its own header used
  to get the same answer, because the zero-filled tail still passed the magic and version checks;
  it is now refused as incomplete. Wrong key, a flipped ciphertext byte, a file cut in the body,
  one cut inside the header and one that is not a backup at all are each proved to leave the
  vault byte for byte as it was (audit-2 ATOM-1, ATOM-2).
- **A media copy that finished after its message was deleted left an orphan blob and its file.**
  `media_blob` has no foreign key to `message`, and the linking write did not look at how many
  rows it touched, so a delete or an expiry landing between the copier's read of the row and its
  transaction committed a blob nothing pointed at, to be found by whichever sweep ran next. The
  link is now checked inside the transaction: zero rows undoes the insert and the file goes with it
  (audit-2 MED-7). The media module joins the instrumented lane for it.
- **"Stop capturing this app" could do nothing and say nothing.** Switching a source off and
  removing it first settle the losses its pending rows carry, inside the policy transaction; a
  settlement that fails rolls the whole change back — correctly, since the discard that follows
  would clear the payload it is the last reader of — and the switch, bound to the repository's
  flow, sprang back to "on" without a word, while the capture page also wrapped every policy call
  in a `runCatching {}` that swallowed cancellation. The refusal is now a dialog naming the app,
  saying that nothing changed and capture continues, why, and what to try; a cancellation is
  rethrown, never reported as a refusal.
  Three cases deliberately record nothing. `MESSAGES` beside a message that was itself shortened
  is undecidable — the batch may also have been over the limit — and a loss invented from evidence
  that does not support it is the same defect facing the other way. A carried-over payload that no
  longer decodes cannot be read at all. A paused source's rows are settled when it is resumed or
  switched off, not while it is paused.
  The column joins the unreleased schema 4 rather than adding a fifth version: no released build
  ships schema 4 — 0.1.3 ships 3 and no tag contains the commit that added 4 — so amending it is
  the same call round 33 made for the two columns already in that migration. A development vault
  that already ran the earlier 4 fails Room's identity-hash check and has to be cleared; that is a
  cost paid on a workbench, not on a user's phone.
  Schema 4 also gains the index those reads need. Switching a source off settles its pending rows
  before they are discarded, in pages, inside the policy transaction and under the pipeline lock —
  so what it costs is paid by live capture. Without an index covering `(packageName, state,
  lossRecorded, receivedAtEpochMs, eventId)` each page re-read every pending row of the source and
  sorted the lot in a temporary B-tree, and the work grew with the square of the backlog: measured
  over eight thousand rows, forty-one pages cost 3.8 million SQLite instructions against 128,000
  for one unpaged read. The cursor is now a row-value comparison, which SQLite turns into a seek
  into that index — `SEARCH event_journal USING INDEX … (packageName=? AND state=? AND
  lossRecorded=? AND (receivedAtEpochMs,eventId)>(?,?))`, with no temporary B-tree — and an
  instrumented test asserts that query plan rather than a timing.
- **An event the vault would not take at all is remembered until it can be recorded.** A journal
  insert that fails is recorded as a gap instead — but whatever stopped the insert does not stop at
  one statement, and a disk with no space left fails that gap too. Both writes failing left nothing
  anywhere saying the event had existed, while the release claimed "less precise, never absent".
  The loss is now kept and written as a bounded gap by the next thing that proves the vault is
  writable — an accepted event, or a source-policy load — and forgotten only once it is on disk,
  the same contract the cold-start and vault-lock-out losses already had. Waiting for a policy load
  alone would have left it in memory for as long as the user changed no source, with capture
  working normally throughout, and a process death in that window would have taken it.
  One interval covers the outage rather than one per event: it opens at the first event that could
  not be recorded and closes when the write finally lands. It therefore also covers whatever was
  captured successfully in between, which over-reports rather than under-reports — the direction
  this app errs in on purpose. A locked vault is a different path,
  caught by exception type before this one, and already had its own remembered obligation.
- A loss that arrives *with* an event is committed with it or not at all, which means a gap write
  that fails now rejects the event: the surviving messages are not stored either. That is a
  deliberate change of behaviour and the honest side of the trade — a batch stored while the record
  of what was missing from it silently vanished is the defect this release exists to remove — and
  the caller records the whole event as a less precise loss instead.
- **A message whose body was shortened was shown as if it were complete.** The truncation was
  computed at capture and thrown away at the parser boundary: each message's own `BoundedText`
  knows whether it was cut, and only its text was carried forward. It is stored per message now and
  the bubble says so — as "shortened in a notification", in all five languages, because the flag is
  historical and the label has to say so. It is set-only: a later, complete observation of the same
  message does not clear it, since "this was seen shortened once" stays true. Worded as a statement
  about the text underneath, the chip would have claimed the stored body is incomplete when the last
  notification carrying it was whole.
  The first attempt stored the notification's flag set on every row it produced instead, which is
  the same defect one layer down: a batch of three messages in which only the second was cut marked
  all three, and a notification whose *title* was too long marked messages that had lost nothing at
  all. A row now records what its own body lost, and nothing else. Where several rows are split out
  of one body — the WhatsApp group heuristic — only the last can be the one that lost text, because
  truncation takes the tail; and only when the cut fell inside it. A cut landing on a line
  separator leaves the last surviving row complete: what was lost there is a whole row, and calling
  the surviving one shortened is wrong about text that is all present — that row is recorded as a
  gap with the commit instead (see above). A revision recomputes it:
  replacing a body without replacing what that body lost left a shortened label on text that was
  now complete.
  A repost is evidence too. The same text arriving again, this time from a notification that says
  it was cut, has the same fingerprint — the flag is deliberately not part of it, or one message
  would become two rows — so the reconciler calls it a repost and nothing was written. The row now
  takes the new evidence: the flag is set, never cleared, because a repost whose identical text
  happened not to be cut does not unmake the observation that once it was.
- **Switching a source off, or pausing it, recorded nothing.** Events dropped for it landed in
  `droppedAfterRevoke`, one counter that also holds a revoked permission, a rotated generation and a
  maintenance run — so the one cause the user chose looked exactly like three they did not, and the
  window itself was never recorded. Each now opens a gap of its own that names the source, and
  closes it again on re-enable or resume. Closing is scoped to that source: ending one app's pause
  no longer ends another's.
  The setting and the gap are one write, under the pipeline lock and in one transaction. Written
  separately, as the first attempt did, two rapid flips could commit their settings in one order
  and their gaps in the other — leaving a source enabled with an open "disabled by the user"
  interval — and a process death between the halves left the same contradiction. Setting a flag to
  the value it already holds now writes nothing: a second tap is not a second interval. If the gap
  cannot be written the setting does not move either, so capture is never stopped with nothing on
  the health page saying so. Rows an earlier version left contradicting their own policy are closed
  when the policy loads.
  Removing a source closes the gap it left open, in the removal's own transaction: nothing could
  ever close it afterwards: re-adding neither opens nor closes a gap in its own write, and the
  health page renders an interval with no end as capture still being missing. The policy load that
  follows any such change does close a row that contradicts the configuration — but outside the
  upsert's transaction, so it is a repair, not the boundary the removal itself provides. "Remove and
  delete this source's data" additionally drops the source's name from its gaps — the intervals
  stay, because deleting them would hide a loss the user had already been shown, but they stop
  naming an app that was asked to be forgotten.
- Gaps can say which source they belong to. Most cannot and must not: of the sixteen places that
  record one — fifteen in the coordinator and one in `HealthRepository`, which files a
  `PROCESS_RESTART` gap for a session the last process never closed — ten are process-wide. None can name a conversation, so there is deliberately no
  conversation column. The first version of this argument was wrong about one site: a batch that
  lost messages *is* ingested — the survivors are committed and their conversation resolved — so
  "the ingest that did not happen" was not true of it. The gap for that loss is now written when
  the event is accepted, before the parser runs and long before identity is resolved, so at the
  moment it is written there is still no conversation to name. The claim holds, for a better
  reason than the one first given.
- **Search called its first page a total.** The screen asked for 100 hits and rendered "%d results",
  so a query matching five thousand messages said "100 results" — the repository's own kdoc admitted
  it showed the first page only. It now says "Newest 100 shown; there may be more" while a cursor
  remains, offers Load more, and only calls a number a count once the index is exhausted — which is
  precisely when the repository hands back a null cursor, and never otherwise. A page that verifies
  nothing still keeps its cursor: that means the candidate scan budget ran out, not that the index is
  finished, and treating the two as the same thing would have re-introduced the very claim this fixes.
- **A search hit opened the conversation at today's newest message.** The hit's message id was
  dropped at the navigation boundary, so a match from six months ago left the reader at the bottom
  of the chat with no indication of where it was. The route carries the id now, the screen lands on
  it and tints it briefly. If the message is no longer there — deleted or expired between the search
  and the tap — it stays where it is rather than silently pretending the newest message is the match.
- That same effect had two more defects behind it: it re-ran on every size change and so overwrote a
  restored scroll position, and its index was off by one (an info item precedes the messages; only
  the clamp hid it). It now lands once, then follows new messages only when the reader is already at
  the end.
- The inbox filter was plain in-memory state, so a process death silently brought back the unfiltered
  list. It survives now — the first `SavedStateHandle` in the project.
- The inbox has an "Unviewed" filter. The label had been translated in all five catalogues and had
  no caller at all.
- A search hit on a photo's caption rendered as plain text with no sign the message carried an image.
- The activity page's sample line reports preview-restricted observations. The count was computed on
  every report and shown nowhere, dropping the one dimension that says "this source hides previews".
- **Onboarding could report a successful capture test without having captured anything.** The step
  watched the vault's total message count, so a second run of onboarding, a restored backup or a
  seeded demo vault started above zero and the step said "captured and saved" immediately. It also
  never compared against the three messages it had just sent, so one arriving out of three read
  exactly like three. It now counts only messages from QuietInbox's own package observed since the
  test was posted, and says "n of 3".
- **That step could also wait for ever.** There was no timeout and no failure branch: with capture
  broken the spinner never stopped, and the only way on was a Skip button that said nothing about
  what had gone wrong. After twenty seconds it now reports the failure, shows the listener state,
  offers to send the test again, and relabels the way out as "Continue without verifying". Moving on
  is still never blocked on a successful capture — a device-policy block would otherwise trap the
  user in onboarding.
- The capture page never showed when a copy was last actually *saved*. Its "accepted" tile counts
  events that reached the journal, which happens before parsing — a source whose format the parser
  cannot read still raises it. There is now a separate "last copy saved" line, stamped after the
  commit, and the tile says "admitted" so the two cannot be read as the same thing.
- The last-event timestamp was rendered in only one of the hero's states, and never in Connected —
  which is exactly what a work-profile block looks like: connected, and nothing arriving. It is now
  shown in every state, in the diagnostics summary, and named honestly: it is when an event was last
  *accepted*, after the source filter, not when the system last called.
- "No gaps recorded in this session." read as "nothing was missed", which is the opposite of what
  the app can claim. The gaps section now states, with or without gaps, that a gap is a window
  QuietInbox knows it could not observe and never a count of what was missed.
- Diagnostic codes reached the user raw, as `LOCKDOWN_REMOVAL` or
  `SKIPPED_PREVIEW_RESTRICTED_SUSPECTED`. Each now has a sentence, with the code kept underneath
  because that is what a bug report should quote.
- Nothing anywhere told the user the one thing they can act on when copies arrive as "You have a new
  message": the setting is in the source app, or in Android's own sensitive-notification setting,
  and QuietInbox cannot tell which. The capture page says so and can open the system notification
  settings for each source, onboarding repeats it, and `docs/COMPATIBILITY.md` gains a section
  explaining why the app deliberately does not request `RECEIVE_SENSITIVE_NOTIFICATIONS` to find out.
- Onboarding now ends by naming media copies, reminders and encrypted backup as three separate
  choices that are off until turned on, instead of leaving them to be discovered in Settings.
- The recovery key said it was "the only way to open your backups on another device" without ever
  saying what losing it costs. It is the only way to open them *anywhere*: "Delete everything"
  destroys the key too, so every backup already taken becomes unreadable on this device as well. The
  screen says that now, and warns about screenshots when the screenshot block — whose toggle is on
  that same screen — is off.
- TalkBack could not tell that a message was selected, that a conversation was unviewed, or copy a
  message at all: selection and the unviewed dot were signalled by colour alone, and copying existed
  only as a touch-drag inside `SelectionContainer`. The message row now carries its selection state,
  the inbox row its unviewed state, and each message a Copy and a Delete accessibility action. There
  is a Copy button in the selection toolbar too, which is where a sighted user was missing it.
- In a group chat TalkBack read the sender's name as a node of its own, so the message node never
  said who sent it. Merging from the outside does not fix this — a clickable is itself a merging
  semantics node and nested merging nodes are never absorbed — so the sender's name moved inside the
  bubble, where it is genuinely part of the same node. `MessageBubbleSemanticsTest` asserts it
  against the merged tree, with the unmerged tree as its negative control.
- An avatar monogram cut a surrogate pair in half, so a name beginning with an emoji ("😀 Mom") drew a
  lone half-character as tofu. Two call sites, not one.
- The Traditional Chinese activity tab was labelled 神隱率 — slang for "went dark on you" — which is
  precisely the claim `ActivityAnalytics` and that catalogue's own header forbid. It is 安靜率 now,
  matching the English "Quiet rate" and the percentage it sits above; Simplified Chinese said 沉默率,
  which attributes the silence to the other person in the same way, and is now 安静率. The word also reached the live Play store description.

### Changed
- Database schema 3 → 4: three additive columns — `gap_interval.packageName` and
  `message.truncationFlags`, both nullable, and `event_journal.lossRecorded`, `NOT NULL DEFAULT 0`
  — with `MIGRATION_3_4`, an exported `schemas/4.json` and a migration test
  that asserts existing rows survive with both columns null. The backup format gained the same field,
  appended and defaulted so a newer reader restoring an older file gets null. It does not make the
  archive readable by an older build: `BackupStager` rejects a manifest whose schema is newer than
  its own before it decodes a single record, so 0.1.3 answers a schema-4 archive with
  `UNSUPPORTED_VERSION` regardless of what the record looks like — and the one place that built a backup record positionally now names its arguments,
  because a field inserted anywhere but the end would have shifted every argument after it with no
  compile error.
- CI's instrumented lane and every documented device-test command now include
  `:feature:conversation:connectedDebugAndroidTest`, and bind `ANDROID_SERIAL` —
  `connectedDebugAndroidTest` otherwise runs on every attached device. `CLAUDE.md` had also been
  missing `:platform:backup` since it was added.
- ADR-0005 said the recovery key "is shown once". It is re-showable on demand, and has been all
  along — a key seen once and mistranscribed is only discovered on the day a restore is attempted.
  The ADR now describes what ships, in both languages.
- `SHA256SUMS.txt` no longer opens with a comment line: `sha256sum -c` reports one as "improperly
  formatted". Which of its four files the release actually carries is said in the release notes now,
  with the `--ignore-missing` invocation that verifies them. `v0.1.3`'s copy still has the comment.

## [0.1.3] — 2026-09-07

`versionCode` 7. Three user-visible string defects that the store screenshots had baked in, and the
screenshot harness that let a whole tablet set of the wrong screens reach Google Play.

### Fixed
- The inbox summary counted in the plural regardless of the count: with one uncertain observation the
  English inbox read "plus **1 observations** with uncertain identity", and one saved message would have
  read "1 recognisable **messages** saved". It is now two `plurals` resources in all five catalogues, and
  the sentence about uncertain identity is left out entirely when there is none of it rather than saying
  "plus 0 observations".
- The capture-health page glued a sentence to a fragment: "Connected does not guarantee the source posts
  a notification for every message. **since** 11:18 PM" in English, and the same with a stray space in
  Chinese. Each language now has one sentence with the timestamp in it.
- **Ten of the fourteen tablet store screenshots were not QuietInbox.** `docs/screenshots/tablet/` and the
  `tenInchScreenshots` uploaded to Google Play for en-US and zh-TW held the launcher home screen and the
  system Settings app instead of the app: only `1_inbox` and `2_conversation` were genuine, and the zh-TW
  `3_search` was an English system screen filed as a Traditional Chinese asset. `tools/demo-screenshots.sh`
  looked for the navigation items in the bottom bar only, a tablet puts them in a left rail, so every tab
  tap missed, the run walked out to the launcher, and the 80 KB floor passed 3.3 MB of wallpaper. Both
  locales are re-shot from the demo vault and inspected one by one.
- `tools/demo-screenshots.sh` handles both layouts: it reads the window width in dp, taps the navigation
  rail when the window is ≥ 600dp wide and the bottom bar otherwise, treats the conversation as ready on a
  wide window when the pinned title is on screen twice (list row and detail header) instead of waiting for
  a bottom bar that never appears, and does not press BACK on a layout where the rail never left.
- **Every screenshot is now gated on QuietInbox being the app on screen** (`app-foreground`: at least one
  node of `dev.quietinbox.app.debug` in the UI dump), and a navigation tap that finds no target fails the
  run instead of warning and carrying on. Those two guards are what the earlier tablet set was missing.
- Round-27 review fixes (`docs/reviews/2026-09-06-round27/`): the round-26 "bound both edges of the rail
  candidate" fix was a tautology — `box[2] <= K` already implies `box[0] <= K` — so where several nodes
  carry a tab's label the one furthest into the navigation strip now wins, lowest on a bottom bar and
  leftmost on a rail, instead of whichever Compose emitted first; the selected-item check only counts
  nodes belonging to the app and inside the strip, so a pulled-down notification shade or a selected
  filter chip cannot answer for a navigation item; the tap is re-sent on each confirmation attempt
  rather than sent once and merely re-checked; each of `tap_tab`'s failure paths says which one it was;
  and the inbox summary glued its gap clause on with an ASCII space in every language, which the
  Chinese and Japanese screenshots showed — it goes through the same locale-aware joiner now.
- Round-26 review fixes (`docs/reviews/2026-09-06-round26/`): a tab tap is confirmed rather than assumed —
  after tapping, the harness re-reads the screen and requires the tapped item to be the selected one, so a
  swallowed tap fails the run instead of capturing the previous page; the bottom-bar rule is gated on the
  narrow layout the way the rail rule is gated on the wide one; a failure
  to switch the device into night mode fails the run rather than producing a light `7_inbox_dark`; the UI
  dump retries, since every screenshot now depends on one; and `LAYOUT` is declared with the other globals.

### Changed
- The release workflow now carries the R8 mapping: `dist/` gets `quietinbox-<version>-mapping.txt` for
  `gplay deobfuscation upload` and the GitHub release gets the gzip of it, both covered by
  `SHA256SUMS.txt`. Play Vitals stack traces for 0.1.2 are obfuscated because the mapping for that
  build was never uploaded and cannot be reconstructed.
- Documentation fixes found by a full en/zh-Hant parity audit: `docs/SCOPE.md` (both languages) had four
  stale test counts (`CaptureCoordinatorTest` 16→32, `VaultMaintenanceTest` 4→5, `core:reconcile` 20→22,
  `core:analytics` 32→34), still listed release signing as not done, and still called the package id a
  placeholder; `docs/zh-Hant/ARCHITECTURE.md` was missing the activity-screen paragraph;
  `docs/zh-Hant/adr/0001` was missing the `:parsers:apps` addendum; `docs/zh-Hant/reviews/README.md` was
  missing the whole-repo round row; ADR-0006 and ADR-0007 had no link to their Chinese versions;
  `docs/RELEASE.md` gave the what's-new path without its `fastlane/` prefix; `gradle/libs.versions.toml`
  pointed at a non-existent ADR filename. `CONTRIBUTING.md` and `.github/PULL_REQUEST_TEMPLATE.md` still
  told contributors to add strings to two catalogues when the parity gate requires five, and named only
  the storage instrumented suite when CI runs storage, crypto and backup. The README now shows the
  Traditional Chinese screenshots in its Chinese half and the English ones in its English half, and its
  English half has the module table instead of a pointer at the Chinese one.

## [0.1.2] — 2026-09-06

`versionCode` 6. Three more UI languages and the review rounds 13–23 fixes on top of 0.1.1. 0.1.1 was a GitHub-only release, so the store notes for `versionCode` 6 also carry the 0.1.1 audit fixes in one clause.

### Added
- Simplified Chinese (zh-Hans), Japanese (ja) and Korean (ko) localisations of every UI string and the listener label, an Android 13+ per-app language list (`locales_config.xml`), and Google Play listings, release notes and what's-new texts for zh-CN, ja-JP and ko-KR. `tools/check-strings.py` (run in CI) fails the build when any catalogue is missing a name, a placeholder or a plurals `other` item. The demo vault speaks the app's language too (`DemoLocalisation`: names, group titles and bodies in zh-Hans / ja / ko), so the store screenshots for the new languages read naturally. `localeFilters` also keeps the AndroidX `zh-rCN` / `zh-rTW` / `zh-rHK` resources, so the material3 date and time pickers and content descriptions are Chinese for Chinese users (they were English in 0.1.0 and 0.1.1).

### Fixed
- Dates and times follow the app language. Every screen formatted them with the process default locale, which an Android 13+ per-app language does not update while the process is alive (the notification listener keeps it alive), so a user who switched QuietInbox to Japanese or Korean saw Japanese strings with English dates and AM/PM times until the process restarted; the UI now formats with the composition's locale (`currentLocale()`), covered by `TimeFormatTest` (round 21).
- Round-21/22 review fixes (`docs/reviews/2026-09-06-round21/`, `round22/`): the screenshot tool sets and confirms the app language before anything starts the process and stops the process again before the launch, refuses the inbox, conversation, activity and capture shots of a non-English run when an AM/PM time or an English month is on screen (the app's own nodes only; it assumes an English device language), waits until the pinned title is on screen *and* the bottom bar is gone before the conversation shot and fails the run otherwise, fails on a lingering input method, retries the query check; `TimeFormat` requires its locale at compile time; all five locales re-shot.
- Round-13 review fixes (mini re-review of the post-round-12 commits, `docs/reviews/2026-09-06-round13/`): a cold-start loss or lock-out gap the vault could not settle at the first attempt (locked again, a reset cancelling the write) is kept in memory and written on the next policy load / next Ready instead of being forgotten; a `COLD_START` gap row that lands after the policy already loaded is closed at once rather than at the next policy load; a notification from an enabled source held across a disconnect, pause or maintenance run records a bounded `COLD_START` gap of its own (its arrival predates the gap those events open); backup export bounds its media pages to the highest blob id read inside the row transaction, so a picture committed after the snapshot is never exported without its message; the reviews index no longer says the manifest media count was fixed (it was documented).
- Round-14 review fixes (`docs/reviews/2026-09-06-round14/`): the overflow and stale-held gap writes keep the loss when the write fails (the same retry as a lock-out loss), so the ADR's "only forgotten once written" holds for every cold-start loss; the policy load re-checks for a cold-start row that landed between its settle and the flag flip and closes it (volatile write-then-read on both sides); a stale-held notification records no gap when its source is paused or when the reconnect's resync holds the same notification again; `MediaDao.maxId` / bounded `exportPage` get an instrumented test (`MediaExportBoundTest`); the bitmap-bound test asserts the bound, not the launch order of the copies.
- Round-15 review fixes (`docs/reviews/2026-09-06-round15/`): the stale-held suppression added in round 14 is keyed by the posts actually queued in the same release (framework key *and* post time, read only for capturable sources), and the whole batch is classified against one reading of the generation and the pause, so a disconnect or pause landing mid-release can neither let a notification suppress itself nor hide a replaced post; `sbnOf` is the one notification mock in the coordinator tests.
- Round-16 review fixes (`docs/reviews/2026-09-06-round16/`): a held notification that a disconnect or pause overtakes mid-release records its gap (each item is decided at its own moment; the suppression set is built only from posts actually queued — `enqueue` now reports a full queue — so nothing can be suppressed by itself); the cold-start test verifies that an app the user never enabled has only its package name read; SCOPE names the resync exception.
- Round-19 review fixes (`docs/reviews/2026-09-06-round19/`): the store-screenshot tool switches every input method off while it types the search query and asserts the query before the shot — on Android 13+ the keyboard follows the app language, and the kana / hangul / pinyin layouts had turned "meeting" into gibberish, so the zh-TW (shipped with 0.1.0), zh-CN, ja-JP and ko-KR search screenshots showed no results; all five locales are re-shot without a keyboard in frame. Korean and Traditional Chinese leftovers (소스 앱, 來源 App / 執行期間 / QuietInbox 的提醒), the Japanese store texts (活動, サイレント モード), realistic demo utility bills, `values-tv` / `values-car` no longer taken for locales and flagged placeholders (`%.1f`) checked by the parity gate; `monogram()` has a JVM test.
- Round-20 review fixes (`docs/reviews/2026-09-06-round20/`): the screenshot tool waits for the pinned conversation's title before the conversation shot (the zh-CN one had been the loading screen), refuses any shot under 80 KB, types the search query one key at a time after the input method has settled and dismisses it with ENTER (keys injected too early arrived out of order), reports a failed UI dump separately from a wrong query, and registers its cleanup trap after the helpers it calls, waits until the per-app locale request has stuck before launching the app and names the demo's language on the seed broadcast (`--es lang`, `DemoData.seed(now, locale)`); `__pycache__` is ignored; the five locales were re-shot and inspected one by one.
- `CaptureCoordinatorTest` +8 (32), `MonogramTest` (4), `TimeFormatTest` (2); 212 JVM tests in total; instrumented storage 16.

## [0.1.1] — 2026-09-06

`versionCode` 5. Issue-driven pass over the 2026-09-06 GPT-5.5 Pro audit (issues #1–#17; base `96b0cf9`). Database schema is now v3 (`MIGRATION_2_3`: three nullable columns, no row rewritten).

### Security and privacy (audit P0)
- #1 Capture policy is now atomic with the pipeline. Every event is fenced twice — before waiting for the pipeline lock and again inside it — and once more right before the commit; a pause, a maintenance run, a revoked listener or a source switched off while the event waited wins. Enabling, disabling, pausing and removing a source go through `CaptureCoordinator` and update the in-memory policy under the pipeline lock; disabling or removing a source discards its pending journal rows (`DISCARDED`, payload cleared). Journal replay is held while capture is paused, runs on resume, and discards rows whose source was disabled since instead of replaying them.
- #2 Media copies are off until the disclosure is accepted. `mediaCopyEnabled` now defaults to off and is only effective together with `mediaDisclosureAccepted`; the copier re-reads the setting when it starts. **Behaviour change for existing installs:** an installation that had the old default (on) but never saw the disclosure now reads as off until the switch is turned on and the disclosure accepted in Settings.
- #3 "Delete all data" is a verified, exclusive maintenance run (`VaultMaintenance`): capture is fenced, media copies / journal replay / retention are cancelled and joined, the pipeline lock is held throughout, database files, media files and every key are deleted and *checked*, and the result names the step that failed instead of reporting done. `BlobCipher` ties its cached primitive to a key epoch that a reset bumps, so nothing is ever encrypted with a destroyed media key. The maintenance window is recorded as an exact capture gap (`MAINTENANCE`).
- #4 Notification text no longer outlives its commit: a journal row's payload is cleared the moment it leaves `PENDING`. Deleting messages or a conversation removes their media rows in the same transaction and the files right after it; removing a source with its data also removes its suppression tokens, summaries, diagnostics and pending journal; the conversation projection (counts, preview, last sender, last activity) is rebuilt from what remains after every deletion, expiry and restore. The retention worker no longer waits for "battery not low".
- #5 Keystore KEK creation is serialised process-wide: three secrets created at once on a fresh install can no longer mint two keys under the same alias.

### Data correctness (audit P1)
- #7 Expired copies are hidden at read time (conversation, search, statistics, counts) instead of only after the retention worker ran; retention rebuilds the projection of the conversations it swept.
- #6 (partial) Media blob row and message link are written in one transaction; a copy that fails or is cancelled after its file was written removes the file; a thumbnail that failed to encrypt is simply absent; a queued bitmap stays counted until the copier is done with it; retention treats a blob its message no longer points at as an orphan.

### Data correctness and product behaviour (audit P1/P2, wave 2)
- #13 Nothing is read from a third-party notification before the source policy is known: the framework object is held in a bounded buffer (256), the policy is loaded right away (waiting for the vault), then only notifications from enabled sources are snapshotted and queued; the rest are dropped unread. If the vault does not open within 15 s the buffer is dropped and the window recorded as a bounded `COLD_START` gap — fail closed. The onboarding resync goes through the same buffer.
- #9 Window alignment is id-aware: two items align by source id when both carry one and by fingerprint otherwise, so a never-seen id at an overlapping position is a new message while content stored before a parser learned ids still aligns with its id-bearing repost. Deletion suppression now suppresses when both sides carry the same source id, otherwise by post time: a replay of the same or an older post is suppressed, a later post with the same text is stored. Residual limitations: an app that re-posts the same id-less content with a new post time after a reboot comes back; and because a token is keyed by fingerprint, a genuinely new message with the same fingerprint inside the same post as a deleted one is suppressed too.
- #11 Search pulls candidates in keyset pages and keeps verifying until the page is full or the index is exhausted, so false-positive candidates can neither under-fill a page nor hide a later true hit; `SearchRepository.searchPage` returns a cursor. Analytics take the median interval within conversations only and rank senders by source + conversation + stable key (else name), so two people with the same name are two rows; the report carries the interval sample size.
- #10 Search and conversation pages show "Vault locked" with a retry instead of "no results" / a blank page, and keep loading while the vault opens; a query typed while opening runs once it is ready.
- #15 The daily reminder is posted only when at least one conversation of the chosen sources (or of all sources) has copies newer than the last time it was opened; the text carries the count; the next run is scheduled before the worker returns; the notification permission is checked in the same method as the call.
- #14 The listener no longer declares `disabled_filter_types="ongoing"` (the parsers already treat ongoing notifications as system notices; the system-level exclusion was the only place that behaved differently per API level). Opening the notification-access screen tries the listener detail page, the listener list and the app-info page in turn and shows the manual path when none exists on the device.
- #16 Backup runs under the maintenance gate: export is cancellable vault work, import an exclusive run. Export streams every table in keyset pages inside one read transaction (never a whole table in memory) and never contains an expired copy; media that could not be read is counted and reported ("N media items could not be read and are not in this backup"); restore rebuilds the conversation projection with the shared `rebuildProjection`.
- #8 The inbox tags conversations from a work (or secondary) profile; `docs/COMPATIBILITY.md` documents work profiles, Device Policy, low-RAM devices and how to submit an anonymised fixture (#17). Per-profile source control and a non-null account key are recorded as deferred schema work.
- #12 Lint errors now fail the build (`abortOnError = true`, the one existing error fixed); the Gradle wrapper pins the distribution SHA-256; issue forms (bug, source compatibility, feature), a pull-request template, `CODEOWNERS` and Dependabot for GitHub Actions were added. The About section links the real repository.
- Round-11 review fixes: a cold-start buffer that overflowed, or that still held items when the policy loaded through another path, now releases them with a bounded `COLD_START` gap instead of losing them silently (buffer 256, one open gap per lock-out, closed when the policy loads; held items keep their arrival time); a journal insert that throws is recorded as an exact gap plus a `JOURNAL_FAILED` diagnostic instead of being "marked retryable" on a row that never existed; export decrypts media *outside* the read transaction so a large media set never blocks live capture writes, and an export refused during a reset says so; two differing source ids no longer clear a candidate on their own (the token remembers only the last of several same-fingerprint deletions), post time decides then; the notification-access screens are simply tried in order without a `resolveActivity` pre-check; reminders count only conversations that still have visible copies; the unexpected-error reset path shows a localised message; `BackupRoundTripTest` exercises the gate (an export during an exclusive run is refused); dead `reminder_body` string removed.
- After round 12 (verified by tests only, re-reviewed in round 13): held notifications are released *before* the source-policy flag flips, so a notification arriving during the release cannot overtake the ones held before it (CI on Linux caught the reordering); the in-flight bitmap-bound test keeps every copy in flight until released.
- Round-12 review fixes: a loss the *locked* vault could not record at the time — a cold-start drop, or the pipeline's own lock-out — is remembered and written as a bounded gap as soon as the vault can be written again (before, the gap row failed silently behind the same lock); the cold-start gap starts at the first eviction, not at a survivor; an open cold-start row is closed by reason once the policy loads (idempotent, so a restore cannot leave it open); the cold-start job re-checks the buffer before ending; backup export reads media one keyset page at a time outside the transaction (no whole-table list in memory) and its KDoc says what the transaction still covers; the unused single-intent settings wrappers were removed; the two module-level lint errors that `abortOnError` had exposed outside `:app` are fixed (`relativeTime` reads the locale through the composition; the synthetic publisher checks the notification permission next to `notify()`), so `./gradlew lint` passes for every module.
- Round-10 review fixes: replay excludes paused sources at the query so their rows cannot occupy the whole page; a `BlobCipher` build that raced a reset is retried once under the new epoch and then fails closed, never returning a primitive of a destroyed key; maintenance start/end reach the capture side through explicit listener callbacks instead of a conflating `StateFlow`; a reset that fails part-way still reopens the vault (`finally { retry() }`) and its end-of-run bookkeeping never blocks the caller; `MediaCopier` clears its cleanup list inside the transaction; conversation previews are cut at 200 code points on both the live and the rebuild path; the reset failure snackbar names the step in the UI language.

### Added
- `VaultMaintenanceTest` (5 JVM tests: + a listener sees start and end exactly once even for an instant run): work runs and returns; work is refused while an exclusive run is active; an exclusive run cancels and joins work in flight; exclusive runs and pipeline-lock holders are serialised.
- `CaptureCoordinatorTest` +13 (24): a source disabled while an event waits for the lock is never journaled; a pause between acceptance and commit leaves the event pending; replay is held while paused and runs on resume; replay discards a pending row whose source was disabled; a maintenance run drops the queue, records an exact gap and starts a fresh generation; every maintenance run records its own gap, even two in a row; before the source list is known a notification is held unread and only sources are snapshotted; when the vault does not open, held notifications are dropped unread and a bounded gap is recorded; bitmaps in flight at the copier still count against the queue bound; a held buffer that overflowed records the drop and keeps only sources; a journal insert that throws is recorded as a gap; a cold-start loss and a pipeline lock-out that the locked vault could not record are written as bounded gaps once it opens.
- `VaultRepositoryTest` (3): a surviving database or media file is the failed step, keys are kept and the vault is reopened; the happy path destroys keys and clears settings. `SuppressionRuleTest` (4). `ReconcilerIdAlignmentTest` (2). `ActivityAnalyticsTest` +2 (within-conversation median; same-named senders ranked separately). `SearchViewModelTest` (2) and `ConversationViewModelTest` (1) for the locked / opening vault. `ReminderSchedulerTest` +1 (`ReminderPolicy`). 198 JVM tests in total.
- Instrumented `SearchPagingTest` (2: 250 false-positive candidates hide nothing and pages stay full with a resumable cursor; a deletion token suppresses a replay of the same post but not a later post) and `BackupRoundTripTest` (2: export holds only visible copies, reports skipped media, restore rebuilds the projection and brings the media file back under the current key; an export during an exclusive maintenance run is refused; CI's emulator lane now runs `:platform:backup`).
- Instrumented `DeletionGraphTest` (5): journal payload cleared on leaving PENDING; deleting the newest message rebuilds the projection; expired copies hidden before retention and projection rebuilt after it; removing a source with data leaves nothing behind (rows, files, tokens, summaries, diagnostics, pending journal); delete-everything is verified and no cached cipher outlives the old key. Instrumented `KeystoreWrapperTest` (parallel first use of a shared alias). `MigrationTest` +1 (v2 → v3).
- `AnalyticsViewModelTest` (8 JVM tests over mocked repositories): the first report is computed off the collector's thread; a period switch shows a clean placeholder without the previous period's "capped" label; a vault change recomputes without a loading state; a locked vault is shown as locked and recovers once unlocked (also when it locks while the page is open and the counts never change); an opening vault keeps loading until ready; a failing count query does not leave the page loading; a failing query marks the report as degraded.

### Changed
- Round 7 review: the activity page's loading placeholder no longer carries the previous period's report or "capped" honesty label; a locked vault is shown as "Vault locked" on the activity page instead of an endless spinner, and an opening vault keeps loading until it is ready; a failing vault-count query emits a fallback tick and retries with back-off instead of silently ending the recompute ticks; every query in the analytics computation propagates cancellation through one helper; cancellation is checked between the CPU stages so a period switch during a notification burst is not starved; the period chips reflect a tap at once even while a slow query is still being cancelled.
- Round 8 review: the vault state is now the outer signal of the analytics pipeline (`flatMapLatest`), so unlocking the vault recovers the activity page even when the message counts did not change; the ViewModel test harness mirrors the storage layer (counts are observable only while the vault is ready) and no longer depends on coroutine worker thread names; a query that fails during a computation now marks the report "may be incomplete" (honesty label) instead of silently showing a smaller report; the count-query back-off restarts after a minute without failures (a flapping query keeps backing off).

### Known issues in 0.1.1 (fixed in 0.1.2)
- The material3 date and time pickers and AndroidX content descriptions were English for Chinese users: `localeFilters` kept only the app's own `b+zh+Hant` catalogue and dropped the AndroidX `zh-rTW` / `zh-rCN` / `zh-rHK` resources (also in 0.1.0).
- Dates and times did not follow an Android 13+ per-app language while the process stayed alive (the notification listener keeps it alive): a user who switched QuietInbox to Traditional Chinese on an English system saw Chinese strings with English dates and AM/PM times until the process restarted (also in 0.1.0).

## [0.1.0] — 2026-09-06

First installable vertical slice (plan §3 "v0.1"). Shipped as `versionCode` 4 to both channels on 2026-09-06: Google Play (paid, `dev.quietinbox.app`, submitted for Google review) and GitHub Releases (free, tag `v0.1.0`, signed APK + `SHA256SUMS.txt`). Play internal-track uploads 1–3 were earlier cuts of this version and never reached production. See ADR-0006 for the distribution model.

### Added
- Demo mode (debug builds only): `DemoDataRepository` fills the vault with fully synthetic, bilingual conversations — including one `AMBIGUOUS_REPEAT` pair, a revised message, a placeholder image, a preview-restricted body, capture gaps and diagnostics — so the app can be demonstrated and screenshotted without exposing a real notification. Triggered from Settings → Developer or from a debug-only broadcast receiver; `seed()` is idempotent and `clear()` deletes strictly by the `demo.quietinbox.` and `demo-` tags. Adds `tools/demo-screenshots.sh` (installs, walks onboarding, seeds, captures seven screens per locale) and the instrumented `DemoDataTest`. No schema change and no new permission.
- Activity statistics extended to five free tabs over one shared period selector (7 days / this month / last month / 3 months / all / custom range): a weekday x hour heat map, overall / weekday / weekend rankings, the dominant time band per conversation, observed messages per active day, the share of days with nothing observed plus the longest quiet run, repeated-phrase (CJK n-gram and Latin word) ranking per sender, and the emoji ranking re-scored per period. Nothing is locked behind a purchase and every label still describes observed messages only.
- Onboarding: scope, source selection, notification-access grant with restricted-settings guidance, synthetic test notification, label preview.
- Capture pipeline: `NotificationListenerService` → bounded allow-listed snapshot → bounded queue → encrypted journal → parser → identity → reconcile → single-transaction commit → media copy.
- Parsers: standard parser (MessagingStyle, InboxStyle, BigText, group summaries, preview placeholders, system notices) and five synthetic-only adapters (LINE, WhatsApp, Telegram, Instagram, Messenger).
- Identity and dedup: scoped identity keys, window alignment, `AMBIGUOUS_REPEAT`, revisions, stale-window replay handling, deletion suppression.
- Encrypted vault: Room + SQLCipher, per-installation Keystore-wrapped keys, retention worker, CJK/Latin n-gram search index.
- UI (Material 3 Expressive, zh-Hant + en): inbox with filters and quality labels, conversation with source/capture times and floating toolbar, search, activity statistics, capture health with sources/gaps/diagnostics, settings (theme, app lock, screenshot protection, retention, media disclosure, reminders, recovery key, encrypted backup/restore, delete all).
- Backup: Tink streaming-AEAD container keyed from a recovery key; verified import with atomic merge.
- Tests: 157 JVM tests (72 in `core:*` including two 1,000-iteration property tests, 43 adapter tests, 24 backup, 11 capture, 4 reminder, 3 crypto); instrumented SQLCipher round-trip, schema migration and durable key-write tests; RFC 5869 vectors.
- CI: JVM tests, assemble, network-permission gate, emulator lanes (API 29/35).

### Fixed (pre-release review round 1, 2026-09-06)
- Vault open failure could hang callers forever; pause did not rotate the capture generation; deleting a conversation did not suppress replays; restored media was garbage-collected; restore collapsed legitimate duplicates; journal replay raced live capture; FK violations rolled back whole batches; window alignment drifted with mixed ids; stale replays shrank checkpoints; resync produced spurious "possible repeat" rows; app lock could be bypassed on cold start; 4+ letter Latin and single CJK searches returned nothing; key files were not fsync'd. Database schema is now v2 with an explicit migration.
- Round 2 review: an ambiguous single repeat no longer shrinks the checkpoint window (a following post could duplicate messages); the key-directory fsync now really runs (`Os.fsync`; the java.io attempt silently failed on Android) and its failure is reported; export stages the ciphertext in a private temp file before touching the chosen document; restore staging bounds total text; the checkpoint-loss guard keeps multiplicity; stale window ids are dropped from checkpoints; capture no longer swallows coroutine cancellation.

### Changed (pre-publication reviews, 2026-09-06)
- Licence changed from Apache-2.0 to GPL-3.0-or-later (LICENSE, NOTICE, README, in-app licence text).
- Round 3 review minors: the checkpoint-loss guard links the newest matching rows (not the oldest); restore text staging limit lowered from 64M to 16M chars; best-effort bookkeeping in capture uses `guarded {}` (failures swallowed, cancellation propagated); disconnected session id cleared synchronously.
- Whole-repository review: a deleted conversation can no longer come back as an empty row after a notification replay (the conversation row is created only when something is stored); restoring a backup older than the retention window re-bases message expiry on the current setting instead of letting the next retention run delete everything; messages sent by the device owner are now recognised (`isSelf`) from MessagingStyle semantics; restore encrypts media before the write transaction and keeps duplicate multiplicity; restored sources come back disabled; `onRemoved` uses the same bounded tag as capture; SECURITY.md has a real reporting channel.
- Added `CLAUDE.md` (repository guidance for AI-assisted contributions).
- Rounds 5–6 review: the analytics "only the newest 50,000 messages are counted" notice is now rendered on every tab; a period switch shows a loading state at once while background vault changes recompute quietly, sampled at 400 ms after an immediate first pass, on `Dispatchers.Default`; restore no longer deletes attached blobs when a cancellation lands after the transaction committed; `isSelf` ignores blank names; the vault-count subscription is shared instead of duplicated and a failing vault-count query no longer leaves the page loading forever.
- Round 4 review (pre-publication): restore no longer leaves encrypted media files behind for messages it skipped, and reports only the media it actually attached; media preparation runs inside the cleanup scope; the summary-only count respects the end of the selected period; the demo seeder and its fictional content moved to the `debug` source set behind a `DemoData` interface (release binds a no-op); analytics load at most 50,000 messages per period (the UI says so when capped) and recompute at most once per 400 ms while the vault keeps changing (period switches recompute at once); custom periods are clamped like "All"; `isSelf` compares the MessagingStyle Person key/uri before falling back to the name; `MediaCopier` compresses a shared bitmap once and holds the permit for the whole unit of work; every GitHub Action is pinned to a commit SHA; `keystore.properties` gaps fail with a clear message; instrumented regression tests for the deleted-conversation replay and for `clear()` sparing non-demo rows.

### Known issues in 0.1.0 (fixed in 0.1.1)
- Activity page: while a new period is loading, the red "only the newest 50,000 messages are counted" label of the previous period stays visible (only on vaults with more than 50,000 messages in the previous period).
- Activity page: if the encrypted vault is locked (key unusable), the page shows an endless spinner instead of the "Vault locked" state; the inbox and capture-health pages do explain it.

### Known limitations
See `docs/SCOPE.md` and the in-app "Known limitations".

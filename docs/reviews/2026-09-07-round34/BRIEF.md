# Round 34 — the round-33 fixes

READ-ONLY review. Change no file in this repository except the report path you are
given. Do not activate orchestration or workflow modes.

## Subject

`42f8d18..60fa4f0` — four commits answering round 33, whose three reviewers all
returned REQUEST CHANGES on `9e379d3`. Read `docs/reviews/2026-09-07-round33/` for
the findings, then `git show` each commit:

- `f5f9581` cluster a — the dropped-message loss moved into the transaction that
  accepts the event, keyed by the journal's primary key.
- `a258662` cluster b — a source policy change and the gap recording it made one
  transactional write, idempotent on the flag, with removal closing its own gap and
  a reconciliation on policy load.
- `234e5cd` cluster c — per-message truncation threaded from `BoundedText` through
  the parser; `message.truncationFlags` holds this row's own body only; the domain
  field became a Boolean; `applyRevision` writes it.
- `60fa4f0` importants — retention keeps open gaps, `LINES` became a dropped-content
  flag, the backup compatibility comment corrected, docs.

## What to check hardest

1. **Did any fix introduce a new defect?** Rounds 29–32 each found one that the
   previous round's *fix commit* had created; round 33 found six in the commit it
   reviewed. Assume the same here and look for it specifically.
2. **Cluster a.** Is exactly-once really structural? `eventId` is the journal's
   primary key and the insert ignores conflicts — trace every path that reaches
   `journal()` and say whether any can write the gap twice, or lose it. What happens
   when the lambda throws? When the vault is locked? On the replay path?
3. **Cluster b.** The reconciliation on policy load runs on *every* load, including
   right after a change. Prove it cannot close a gap the same call just opened, for
   enable, disable, pause, resume, add and remove, in every order. Does `addSource`
   need to do anything about gaps? What happens to a *process-wide* gap, or another
   source's gap, when one source is removed with `deleteData`?
4. **Cluster c.** Is `textTruncated` correct at all three `StandardParser` sites and
   in `WhatsAppParser`? The WhatsApp rule is "only the last split row can be the one
   that lost text". Is that right in every case — what about a body cut mid-line, or
   a single-line body? Does any adapter or path still put snapshot-level state on a
   message row? Is the Boolean domain field a loss of information anywhere?
5. **The K6 claim.** The commit argues the old ambiguous `MESSAGES` flag now decides
   nothing, so no versioned conversion is needed and `parserInputVersion` is
   deliberately not bumped. Test that argument against the code: find any remaining
   path where a pre-upgrade payload's snapshot-level flag changes an outcome.
6. **Test honesty.** Cluster b's commit admits its first coordinator test was
   theatre — it asserted against a mocked repository whose fake did the checking.
   Check every test added in these four commits for the same problem: does it fail
   when the behaviour it names is removed? The commits claim specific controls were
   run; verify the claims are true of the tests as they now stand.
7. Docs against code: CHANGELOG, both TEST_MATRIX files, both SCOPE files, both
   ARCHITECTURE files. Counts and claims.

## The project's hard rules

Gaps are shown, never hidden. No destructive Room migrations; schema JSON under
`platform/storage/schemas/` is versioned, and schema 4 is unreleased. Single-writer
pipeline through `pipelineMutex`, owned by `VaultMaintenance` (ADR-0007). Source
policy changes go through `CaptureCoordinator`. Docs must not run ahead of code.
Every user-facing string in all five catalogues.

## Output

Traditional Chinese.

- **Verdict**: APPROVE | APPROVE WITH MINOR FIXES | REQUEST CHANGES | REJECT
- **Critical** — must fix before 0.1.4 ships, each with file:line, the concrete
  sequence that triggers it, and the observable wrong behaviour.
- **Important**, **Minor**.
- **Claims checked and found true** — which of the four commit messages' assertions
  you verified, and how.

Report only what you can point at. If you cannot verify something, say so.

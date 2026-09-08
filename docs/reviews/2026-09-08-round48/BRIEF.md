# Review round 48 — mini re-review of round-47 I1 (abandonClose dedup)

READ-ONLY on product code. Write only your report. No git writes, no Play, no orchestration workflow modes.

HEAD `ad1b48e`. Review `git diff 3e8777b..ad1b48e`.

Round 47 combined REQUEST CHANGES: I2 closed; I1 remained because `abandonClose(currentImportStream.get())` could enqueue unbounded closes on the same hung stream.

## Closed if true

- `getAndSet(null)` so each stream is handed to at most one background close.
- Excess imports refused at the read slot without another close.
- Test: two hung streams, cancel, eight retries, close count unchanged.
- Caller still does not synchronously close. Apply still not on Main.

Evidence: `BackupHangTest` on emulator-5556 BUILD SUCCESSFUL after this commit.

繁體中文. Verdict first.

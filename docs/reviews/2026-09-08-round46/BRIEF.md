# Review round 46 — QuietInbox search paging, backup exclusive hang, restore/search honesty, CI media JVM

READ-ONLY on product code. Do not edit Kotlin/XML/CI except by writing **only** your report file named below. No git writes, no Play, no devices you do not already have. Do not activate orchestration workflow modes.

Repository `/Users/iml1s/Documents/mine/quietinbox`, HEAD `eff84c0`. Review `git diff 0cca955..eff84c0` (one commit). Baseline was `main@0cca9552`.

This is the five-defect train from the 2026-09-06–09-08 review against that baseline: 2 P1 + 3 P2. Not a 0.1.5 release. Issue #33 stays open. Issue #17 stays open. Do not revert Dependabot #18–#21. No INTERNET. Never act on source notifications.

## What was asked

1. **P1 search paging.** Query/filters/frozen time range/cursor/results bound to one session id. Invalidate old cursor on condition change. Obsolete completions must not mutate the new session’s flags (especially `loadingMore`). Each page has its own request id. Not `distinctBy(id)` as the sole fix. Not coroutine cancel alone.
2. **P1 backup exclusive hang.** Stage/read/verify outside `VaultMaintenance.exclusive`; short exclusive apply; re-check key epoch / import token before write. A never-returning `InputStream.read` must let the caller exit and leave maintenance inactive. Not a `withTimeout` around exclusive. Unbounded stuck jobs forbidden.
3. **P2 restore honesty.** Export skipped blob, message still `LOCAL_COPY`, wipe, import → partial-media count > 0 (`skippedMedia` / `mediaNotRestored` as Settings already uses). Distinct from present-but-bad Media bytes. No double-count.
4. **P2 search errors.** Repository throw ≠ empty success. Load-more throw keeps hits + cursor. `CancellationException` rethrown, not empty. #10 Locked/Opening stays distinct. Same query retryable after failure. Five-locale strings if new copy.
5. **P2 CI JVM.** Job must actually run `:platform:media:testDebugUnitTest`. Prefer one aggregate so the module list cannot omit it.

## Evidence already collected (do not treat as a substitute for reading the diff)

- `./gradlew :feature:search:testDebugUnitTest` — 13 tests, 0 failures (XML tests="13" failures="0").
- `ANDROID_SERIAL=emulator-5556 ./gradlew :platform:backup:connectedDebugAndroidTest` — `BackupHangTest` (2) and `BackupRoundTripTest` (3, including `skippedMedia shouldBe 1` on export-skip → wipe → import) green.
- `./gradlew --dry-run test` schedules `:platform:media:testDebugUnitTest`.
- `./gradlew test :app:assembleDebug :app:lintDebug` BUILD SUCCESSFUL; `tools/check-permissions.sh` on the debug APK: no INTERNET.
- `python3 tools/check-strings.py` OK.

## Hunt especially

- Session vs sequential `run()` in the debounce collector (A→B→A while first page is parked).
- Stale `loadingMore=false` on session mismatch (the original bug).
- `retrySearch` reading `local.value.vaultOpening` (defaults true) vs `vault.state`.
- Import: `CompletableDeferred.await` vs joining a blocking read; `withContext(IO)` still waiting; key zeroed while IO uses it; apply after `destroyAll`.
- Restore count: `else if` so one message is never both `skippedMedia` and `mediaNotRestored`.
- CI: does root `test` really schedule media, or only in a local dry-run.
- Test theatre: `awaitUntil { list.any { } }` without `shouldBe true`; HangTest blocking `CountDownLatch.await` on the `runBlocking` thread.
- Docs ahead of code. #33 claimed closed.

## Out of scope

#17, #33 implementation, F1–F4, INTERNET, 0.1.5 / Play, acting on source notifications.

## Output

繁體中文. Verdict first: APPROVE | APPROVE WITH MINOR FIXES | REQUEST CHANGES | REJECT.
Critical / Important / Minor with file:line. Strictest verdict wins the round.
Write the report only to the path given in your prompt.

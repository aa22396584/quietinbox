# Review round 47 — mini re-review of round-46 I1/I2

READ-ONLY on product code. Write only your report file. No git writes, no Play, no orchestration workflow modes.

Repository `/Users/iml1s/Documents/mine/quietinbox`, HEAD `3e8777b`. Review `git diff eff84c0..3e8777b` plus enough of `eff84c0` to see I1/I2 closed.

Round 46 combined **REQUEST CHANGES** (Codex and agy both I1 + I2; Claude blocked on Write permissions).

## Closed if true

- **I1**: Caller of `import` must not synchronously `close()` a provider stream. A stream whose `close` shares a lock with `read` must still let cancel return and leave exclusive free. Slot held until close actually finishes. `BackupHangTest` uses that shared-lock stream.
- **I2**: `openInput` and `apply` (Base64 / encrypt / write) must not run on Main when Settings calls `viewModelScope.launch { backup.import }`. Test records thread names.

## Evidence

`ANDROID_SERIAL=emulator-5556` `BackupHangTest` (3) and full `:platform:backup:connectedDebugAndroidTest` BUILD SUCCESSFUL after this commit.

## Out of scope

#17, #33, F1–F4, INTERNET, 0.1.5, M1 sequential search collector unless it is now worse.

## Output

繁體中文. Verdict first. Critical / Important / Minor with file:line.

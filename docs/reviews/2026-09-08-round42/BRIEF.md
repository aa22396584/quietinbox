# Round 42 — review brief (mini re-review)

READ-ONLY review. Do not change product code. Do not activate any orchestration
workflow mode. Write 繁體中文 to docs/reviews/2026-09-08-round42/codex-gpt-6-astra.md.

Subject: `a63bc63..44296e5` on main of /Users/iml1s/Documents/mine/quietinbox.

Round-41 Codex I1: the Ready/lock-out test now fails if closeOpenGaps runs while
openGap is parked (`check(openReturned)`). Ready publishes unlocked only after
the pipeline-lock settle. Round-41 Codex I3: fingerprint uses length-prefixed
cells, includes local_diagnostic_event, and a body rewrite without a count
change is its own test.

Attack: does removing pipelineMutex.withLock from the Ready collector make
`a Ready that arrives while the lock-out gap write is still in flight` fail?
Does UPDATE message SET body=mutated fail vaultFingerprint? Do not re-litigate
PARSE_/DECODE.

Verdict first.

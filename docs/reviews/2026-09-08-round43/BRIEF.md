READ-ONLY. Do not edit product code. Do not git commit. Write 繁體中文 to
docs/reviews/2026-09-08-round43/codex-gpt-6-astra.md.

Review 44296e5..c13fdfd on /Users/iml1s/Documents/mine/quietinbox.

5d21f7c: the Ready/lock-out test now waits up to 1s for closeOpenGaps while
openGap is parked (`settledDuringWrite`) and requires that wait to time out
(`raced shouldBe null`) before releasing the writer. Removing pipelineMutex
from the Ready collector should let the collector run in that window and fail.

Also diagnostic fingerprint is compared after the body rewrite.

Attack: mutate by replacing Ready collector pipelineMutex.withLock with run.
Does the named test fail? Verdict first. Do not re-litigate PARSE_/DECODE.

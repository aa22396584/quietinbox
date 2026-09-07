# Round 32 — the GitHub Actions upgrade (`68dd97f..17bc5b0`)

Repo: `/Users/iml1s/Documents/mine/quietinbox`, branch `main`. READ-ONLY review of code; the ONLY
file you may write is your own report, named in the launcher prompt. Do not merge, comment on a PR,
push, or edit any source file. DO NOT activate any orchestration workflow mode.

## What this is, and why it needs a review at all

Six commits, all in `.github/`. Four are Dependabot major bumps that were merged; two are mine. None
of it has had an independent review — it went to `main` directly. It matters because
`.github/workflows/release.yml` is what builds and publishes the **signed** APK/AAB for a paid
Google Play app, and **CI never exercises that workflow**: `grep -c download-artifact
.github/workflows/ci.yml` is 0. A break there is invisible until a tag is pushed.

- `812c67a` actions/upload-artifact 4.6.2 → 7.0.1
- `f5bb6f1` actions/setup-java 4.9.1 → 6.0.0
- `d89be1e` gradle/actions/setup-gradle 4.4.4 → 6.3.0
- `1242185` (mine) pin `cache-provider: basic` at all four setup-gradle call sites
- `5f698cf` actions/checkout 4.4.0 → 7.0.1
- `17bc5b0` (mine) actions/download-artifact 4.3.0 → 8.0.1, group the github-actions ecosystem into
  one Dependabot PR, and document that Dependabot alerts are on while automatic security-update PRs
  are off

## The claims I made. Attack them.

1. **Every pin matches its tag.** I resolved each tag to a commit with `gh api` and compared. Redo
   this yourself for all seven actions in both workflows — an annotated tag needs
   `git/ref/tags/<tag>` then `git/tags/<obj>`. A comment saying `# v7.0.1` is only a comment; the SHA
   is what runs. Any mismatch is a supply-chain finding, not a nitpick.
2. **upload-artifact v7 and download-artifact v8 interoperate**, and did so with v4 before this
   commit. I based that on v7's only new input being `archive` (default `true`), on the upstream
   toolkit's "default behavior should remain unchanged if `skipArchive = false`", and on the
   v3→v4 incompatibility notice never being repeated for v5/v6/v7. Check it, and check whether
   moving download to v8 changes the answer.
3. **v8's digest-mismatch-is-now-an-error is an improvement here.** Verify that claim against
   `release.yml`, and say what happens on a mismatch mid-release: which step fails, and whether a
   tag can end up pushed with no GitHub release.
4. **`cache-provider: basic` avoids the proprietary `gradle-actions-caching` component.** Verify from
   the action's own distributed code at the pinned SHA, not from its release notes. Also check
   whether anything else in v6 loads that component regardless of the provider.
5. **The bumps do not break anything this repo actually does.** setup-java v6 removed the legacy
   AdoptOpenJDK distributions and renamed several Maven inputs; checkout v6/v7 changed credential
   persistence and blocked fork-PR checkout for `pull_request_target` / `workflow_run`; setup-gradle
   v6 removed the action's configuration-cache support while `gradle.properties` sets
   `org.gradle.configuration-cache=true`. Confirm each is genuinely inert here, per call site.

## Look for what I did not

- Anything in `release.yml` that CI cannot catch. This is the whole point of the round.
- The `permissions:` blocks, and whether any upgraded action now needs a permission it does not have.
- Whether grouping the Dependabot ecosystem into one PR has a downside I did not weigh.
- Whether `cache-read-only`'s default expression still behaves as intended on a tag push.

## Output (繁體中文)

Verdict: APPROVE | APPROVE WITH MINOR FIXES | REQUEST CHANGES | REJECT, then Critical / Important /
Minor / Observations, each with `file:line` and a concrete fix.

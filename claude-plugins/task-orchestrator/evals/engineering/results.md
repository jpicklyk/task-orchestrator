# Engineering skill eval results

One row per run: date, the sha the graders ran at (`+dirty` marks a tree with uncommitted changes), the model (the
alias asked for, then the model ids the session transcript shows), the scenario, the mode (`baseline` means no skills
loaded; a rerun names what was loaded), the result, the failure observed, and the folder under `runs/` that holds what
the grader saw (final message, the review or audit file, the diff, each check's verdict). A run happens once and is
recorded as it happened, never retried. How to run: [README.md](README.md).

Rows are appended at the end only; the skill items append their rerun rows. A row marked `superseded` is kept for
history and counts for nothing: it was graded by a harness that has since changed (see "Grader history" in the
README), and its sha names a commit that does not contain the graders it ran.

## Baseline summary (rows below that are not superseded)

29 current runs, one per scenario per declared model (sonnet for the build scenarios, sonnet and opus for the review and
audit scenarios), no skills loaded. 26 are from run `rerun-1` (graders at `285d55eb1`, clean tree) and 3 from run
`rerun-2` (harness commit `f9be9a2a0`), which replaced the `rerun-1` rows of `defining-project-terms-2` and
`auditing-codebase-health-3` after those two scenarios were corrected (see "Grader history" in the README). 3 of 29
runs showed a failure. Run artifacts are kept in the checkout that ran them, not committed (README, "Graded
artifacts"); each failing row was read against them when it was recorded.

| Skill | Failing runs | What failed | What a pass does not prove |
| --- | --- | --- | --- |
| `spotting-code-slop` | 1 of 8 | opus did not flag the unrelated renames in `labels.js` in `1-seeded-diff` (mixed-purpose diff); both models caught the other three seeded defects. | The seeded diffs are small and the defects planted. Realistic review `4` passes when a finding names the skill step that no longer matches the code (one recorded finding); it says nothing about other defects in that commit. The false-positive check is one scenario. |
| `designing-modules` | 0 of 4 | No failure observed (sonnet only). | One sample per scenario. Graders catch only the shapes listed in the README (one-implementation abstraction, factory for one case, over-fragmentation, a domain module importing infrastructure, a new module beside the existing transcript measure); a poor design of another kind passes. |
| `organizing-codebases` | 0 of 4 | No failure observed (sonnet only). | One sample per scenario; a pass shows the helper was reused and the names and conventions matched, not that the structure is good. |
| `defining-project-terms` | 1 of 5 | sonnet wrote "seat ownership" in a comment in the realistic run (`5`), a term the repository glossary lists as one to avoid. `2-shipment-package` now passes: its prompt no longer supplies the avoided word or a function name (the first run had been given `trackPackage`, so that row measured the prompt, not the model, and is superseded). | A pass shows the avoided word and the glossary term were handled in the added lines; it does not show a new term was proposed well. `3-new-term` passes when the glossary gains the term; with no rule stated in the fixture's `CLAUDE.md` it still passed once. |
| `auditing-codebase-health` | 1 of 8 | sonnet in `3-decision-record` ranked `fedex.js` second with a line that does not mention decision 0002 (its rank 3 line for `ups.js` does, and its advice stays inside the file); the check reads the first carrier line only. opus passed, citing the record and questioning its premise. The scenario no longer requires a carrier adapter at rank 1 (both models had ranked `labels.js` first, which the record permits), so the earlier "ranks a cold module first" failures are superseded. | Fixture audits pass when the most-changed file ranks first; the realistic audit passes when every ranked path is among the most-changed source files, which does not mean the ranking is the best use of refactoring effort. The decision-record pass is a one-line heuristic over the first carrier row; a failure may be a grader limit as much as a model miss. |

Across the five skills there is no run in which a model failed a build scenario on a slop check (narrating comments,
swallowed or broad catches, guards, new dependencies, scope creep, unused exports, TODOs): 13 build runs, none
observed. That is 13 samples from heuristic graders, not a rate. Whether a skill with no observed failure ships text
for that scenario is the owner's scope call; this file records only what happened.

| date | sha | model | scenario | mode | result | failure observed | artifacts |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 2026-11-22 | 7998dc957 | sonnet (claude-sonnet-5-5) | auditing-codebase-health-1-hot-spot | superseded; baseline | pass | no failure (write no skill text for it) | - |
| 2026-11-22 | 7998dc957 | opus (claude-opus-5-5) | auditing-codebase-health-1-hot-spot | superseded; baseline | pass | no failure (write no skill text for it) | - |
| 2026-11-22 | 7998dc957 | sonnet (claude-sonnet-5-5) | auditing-codebase-health-2-glossary-drift | superseded; baseline | pass | no failure (write no skill text for it) | - |
| 2026-11-22 | 7998dc957 | opus (claude-opus-5-5) | auditing-codebase-health-2-glossary-drift | superseded; baseline | pass | no failure (write no skill text for it) | - |
| 2026-11-22 | 7998dc957 | sonnet (claude-sonnet-5-5) | auditing-codebase-health-3-decision-record | superseded; baseline | fail | ranks a cold module first | - |
| 2026-11-22 | 7998dc957 | opus (claude-opus-5-5) | auditing-codebase-health-3-decision-record | superseded; baseline | pass | no failure (write no skill text for it) | - |
| 2026-11-22 | 7998dc957 | sonnet (claude-sonnet-5-5) | defining-project-terms-1-order-purchase | superseded; baseline | pass | no failure (write no skill text for it) | - |
| 2026-11-22 | 7998dc957 | sonnet (claude-sonnet-5-5) | defining-project-terms-2-shipment-package | superseded; baseline | pass | no failure (write no skill text for it) | - |
| 2026-11-22 | 7998dc957 | sonnet (claude-sonnet-5-5) | defining-project-terms-3-new-term | superseded; baseline | pass | no failure (write no skill text for it) | - |
| 2026-11-22 | 7998dc957 | sonnet (claude-sonnet-5-5) | defining-project-terms-4-no-glossary | superseded; baseline | pass | no failure (write no skill text for it) | - |
| 2026-11-22 | 7998dc957 | sonnet (claude-sonnet-5-5) | designing-modules-1-single-provider | superseded; baseline | pass | no failure (write no skill text for it) | - |
| 2026-11-22 | 7998dc957 | sonnet (claude-sonnet-5-5) | designing-modules-2-config-loader | superseded; baseline | pass | no failure (write no skill text for it) | - |
| 2026-11-22 | 7998dc957 | sonnet (claude-sonnet-5-5) | designing-modules-3-dependency-direction | superseded; baseline | pass | no failure (write no skill text for it) | - |
| 2026-11-22 | 7998dc957 | sonnet (claude-sonnet-5-5) | organizing-codebases-1-reinvented-formatter | superseded; baseline | pass | no failure (write no skill text for it) | - |
| 2026-11-22 | 7998dc957 | sonnet (claude-sonnet-5-5) | organizing-codebases-2-generic-names | superseded; baseline | pass | no failure (write no skill text for it) | - |
| 2026-11-22 | 7998dc957 | sonnet (claude-sonnet-5-5) | organizing-codebases-3-local-conventions | superseded; baseline | pass | no failure (write no skill text for it) | - |
| 2026-11-22 | 7998dc957 | sonnet (claude-sonnet-5-5) | spotting-code-slop-1-seeded-diff | superseded; baseline | pass | no failure (write no skill text for it) | - |
| 2026-11-22 | 7998dc957 | opus (claude-opus-5-5) | spotting-code-slop-1-seeded-diff | superseded; baseline | pass | no failure (write no skill text for it) | - |
| 2026-11-22 | 7998dc957 | sonnet (claude-sonnet-5-5) | spotting-code-slop-2-clean-standard | superseded; baseline | pass | no failure (write no skill text for it) | - |
| 2026-11-22 | 7998dc957 | opus (claude-opus-5-5) | spotting-code-slop-2-clean-standard | superseded; baseline | pass | no failure (write no skill text for it) | - |
| 2026-11-22 | 7998dc957 | sonnet (claude-sonnet-5-5) | spotting-code-slop-3-seeded-diff-defensive | superseded; baseline | pass | no failure (write no skill text for it) | - |
| 2026-11-22 | 7998dc957 | opus (claude-opus-5-5) | spotting-code-slop-3-seeded-diff-defensive | superseded; baseline | pass | no failure (write no skill text for it) | - |
| 2026-11-22 | 5c3a2a9c4 | sonnet (claude-sonnet-5-5) | defining-project-terms-1-order-purchase | superseded; baseline, slop graders | pass | no failure (write no skill text for it) | - |
| 2026-11-22 | 5c3a2a9c4 | sonnet (claude-sonnet-5-5) | defining-project-terms-2-shipment-package | superseded; baseline, slop graders | pass | no failure (write no skill text for it) | - |
| 2026-11-22 | 5c3a2a9c4 | sonnet (claude-sonnet-5-5) | defining-project-terms-3-new-term | superseded; baseline, slop graders | pass | no failure (write no skill text for it) | - |
| 2026-11-22 | 5c3a2a9c4 | sonnet (claude-sonnet-5-5) | defining-project-terms-4-no-glossary | superseded; baseline, slop graders | pass | no failure (write no skill text for it) | - |
| 2026-11-22 | 5c3a2a9c4 | sonnet (claude-sonnet-5-5) | designing-modules-1-single-provider | superseded; baseline, slop graders | pass | no failure (write no skill text for it) | - |
| 2026-11-22 | 5c3a2a9c4 | sonnet (claude-sonnet-5-5) | designing-modules-2-config-loader | superseded; baseline, slop graders | pass | no failure (write no skill text for it) | - |
| 2026-11-22 | 5c3a2a9c4 | sonnet (claude-sonnet-5-5) | designing-modules-3-dependency-direction | superseded; baseline, slop graders | pass | no failure (write no skill text for it) | - |
| 2026-11-22 | 5c3a2a9c4 | sonnet (claude-sonnet-5-5) | organizing-codebases-1-reinvented-formatter | superseded; baseline, slop graders | pass | no failure (write no skill text for it) | - |
| 2026-11-22 | 5c3a2a9c4 | sonnet (claude-sonnet-5-5) | organizing-codebases-2-generic-names | superseded; baseline, slop graders | pass | no failure (write no skill text for it) | - |
| 2026-11-22 | 5c3a2a9c4 | sonnet (claude-sonnet-5-5) | organizing-codebases-3-local-conventions | superseded; baseline, slop graders | pass | no failure (write no skill text for it) | - |
| 2026-11-22 | 5c3a2a9c4 | sonnet (claude-sonnet-5-5) | auditing-codebase-health-4-realistic-hooks-history | superseded; baseline, realistic tier | pass | no failure (write no skill text for it) | - |
| 2026-11-22 | 5c3a2a9c4 | opus (claude-opus-5-5) | auditing-codebase-health-4-realistic-hooks-history | superseded; baseline, realistic tier | pass | no failure (write no skill text for it) | - |
| 2026-11-22 | 5c3a2a9c4 | sonnet (claude-sonnet-5-5) | defining-project-terms-5-realistic-seat-ownership | superseded; baseline, realistic tier | fail | narrating or change-log comments | - |
| 2026-11-22 | 5c3a2a9c4 | sonnet (claude-sonnet-5-5) | designing-modules-4-realistic-cache-share | superseded; baseline, realistic tier | pass | no failure (write no skill text for it) | - |
| 2026-11-22 | 5c3a2a9c4 | sonnet (claude-sonnet-5-5) | organizing-codebases-4-realistic-session-marker | superseded; baseline, realistic tier | pass | no failure (write no skill text for it) | - |
| 2026-11-22 | 5c3a2a9c4 | sonnet (claude-sonnet-5-5) | spotting-code-slop-4-realistic-historical-commit | superseded; baseline, realistic tier | pass | no failure (write no skill text for it) | - |
| 2026-11-22 | 5c3a2a9c4 | opus (claude-opus-5-5) | spotting-code-slop-4-realistic-historical-commit | superseded; baseline, realistic tier | pass | no failure (write no skill text for it) | - |
| 2026-11-22 | 5c3a2a9c4 | sonnet (claude-sonnet-5-5) | defining-project-terms-5-realistic-seat-ownership | superseded; baseline, realistic tier, regraded (doc comments skipped; narrower finding regex) | pass | no failure (write no skill text for it) | - |
| 2026-11-22 | 5c3a2a9c4 | sonnet (claude-sonnet-5-5) | spotting-code-slop-4-realistic-historical-commit | superseded; baseline, realistic tier, regraded (doc comments skipped; narrower finding regex) | pass | no failure (write no skill text for it) | - |
| 2026-11-22 | 5c3a2a9c4 | opus (claude-opus-5-5) | spotting-code-slop-4-realistic-historical-commit | superseded; baseline, realistic tier, regraded (doc comments skipped; narrower finding regex) | pass | no failure (write no skill text for it) | - |
| 2026-11-22 | 285d55eb1 | opus (claude-opus-5-5) | auditing-codebase-health-1-hot-spot | baseline, rerun after review fixes | pass | no failure observed | runs/rerun-1/auditing-codebase-health-1-hot-spot--opus/ |
| 2026-11-22 | 285d55eb1 | sonnet (claude-sonnet-5-5) | auditing-codebase-health-1-hot-spot | baseline, rerun after review fixes | pass | no failure observed | runs/rerun-1/auditing-codebase-health-1-hot-spot--sonnet/ |
| 2026-11-22 | 285d55eb1 | opus (claude-opus-5-5) | auditing-codebase-health-2-glossary-drift | baseline, rerun after review fixes | pass | no failure observed | runs/rerun-1/auditing-codebase-health-2-glossary-drift--opus/ |
| 2026-11-22 | 285d55eb1 | sonnet (claude-sonnet-5-5) | auditing-codebase-health-2-glossary-drift | baseline, rerun after review fixes | pass | no failure observed | runs/rerun-1/auditing-codebase-health-2-glossary-drift--sonnet/ |
| 2026-11-22 | 285d55eb1 | opus (claude-opus-5-5) | auditing-codebase-health-3-decision-record | superseded; baseline, rerun after review fixes | fail | ranks a cold module first | runs/rerun-1/auditing-codebase-health-3-decision-record--opus/ |
| 2026-11-22 | 285d55eb1 | sonnet (claude-sonnet-5-5) | auditing-codebase-health-3-decision-record | superseded; baseline, rerun after review fixes | fail | ranks a cold module first | runs/rerun-1/auditing-codebase-health-3-decision-record--sonnet/ |
| 2026-11-22 | 285d55eb1 | opus (claude-opus-5-5) | auditing-codebase-health-4-realistic-hooks-history | baseline, rerun after review fixes | pass | no failure observed | runs/rerun-1/auditing-codebase-health-4-realistic-hooks-history--opus/ |
| 2026-11-22 | 285d55eb1 | sonnet (claude-sonnet-5-5) | auditing-codebase-health-4-realistic-hooks-history | baseline, rerun after review fixes | pass | no failure observed | runs/rerun-1/auditing-codebase-health-4-realistic-hooks-history--sonnet/ |
| 2026-11-22 | 285d55eb1 | sonnet (claude-sonnet-5-5) | defining-project-terms-1-order-purchase | baseline, rerun after review fixes | pass | no failure observed | runs/rerun-1/defining-project-terms-1-order-purchase--sonnet/ |
| 2026-11-22 | 285d55eb1 | sonnet (claude-sonnet-5-5) | defining-project-terms-2-shipment-package | superseded; baseline, rerun after review fixes | fail | vocabulary drift | runs/rerun-1/defining-project-terms-2-shipment-package--sonnet/ |
| 2026-11-22 | 285d55eb1 | sonnet (claude-sonnet-5-5) | defining-project-terms-3-new-term | baseline, rerun after review fixes | pass | no failure observed | runs/rerun-1/defining-project-terms-3-new-term--sonnet/ |
| 2026-11-22 | 285d55eb1 | sonnet (claude-sonnet-5-5) | defining-project-terms-4-no-glossary | baseline, rerun after review fixes | pass | no failure observed | runs/rerun-1/defining-project-terms-4-no-glossary--sonnet/ |
| 2026-11-22 | 285d55eb1 | sonnet (claude-sonnet-5-5) | defining-project-terms-5-realistic-seat-ownership | baseline, rerun after review fixes | fail | avoided term in code, comments or tests | runs/rerun-1/defining-project-terms-5-realistic-seat-ownership--sonnet/ |
| 2026-11-22 | 285d55eb1 | sonnet (claude-sonnet-5-5) | designing-modules-1-single-provider | baseline, rerun after review fixes | pass | no failure observed | runs/rerun-1/designing-modules-1-single-provider--sonnet/ |
| 2026-11-22 | 285d55eb1 | sonnet (claude-sonnet-5-5) | designing-modules-2-config-loader | baseline, rerun after review fixes | pass | no failure observed | runs/rerun-1/designing-modules-2-config-loader--sonnet/ |
| 2026-11-22 | 285d55eb1 | sonnet (claude-sonnet-5-5) | designing-modules-3-dependency-direction | baseline, rerun after review fixes | pass | no failure observed | runs/rerun-1/designing-modules-3-dependency-direction--sonnet/ |
| 2026-11-22 | 285d55eb1 | sonnet (claude-sonnet-5-5) | designing-modules-4-realistic-cache-share | baseline, rerun after review fixes | pass | no failure observed | runs/rerun-1/designing-modules-4-realistic-cache-share--sonnet/ |
| 2026-11-22 | 285d55eb1 | sonnet (claude-sonnet-5-5) | organizing-codebases-1-reinvented-formatter | baseline, rerun after review fixes | pass | no failure observed | runs/rerun-1/organizing-codebases-1-reinvented-formatter--sonnet/ |
| 2026-11-22 | 285d55eb1 | sonnet (claude-sonnet-5-5) | organizing-codebases-2-generic-names | baseline, rerun after review fixes | pass | no failure observed | runs/rerun-1/organizing-codebases-2-generic-names--sonnet/ |
| 2026-11-22 | 285d55eb1 | sonnet (claude-sonnet-5-5) | organizing-codebases-3-local-conventions | baseline, rerun after review fixes | pass | no failure observed | runs/rerun-1/organizing-codebases-3-local-conventions--sonnet/ |
| 2026-11-22 | 285d55eb1 | sonnet (claude-sonnet-5-5) | organizing-codebases-4-realistic-session-marker | baseline, rerun after review fixes | pass | no failure observed | runs/rerun-1/organizing-codebases-4-realistic-session-marker--sonnet/ |
| 2026-11-22 | 285d55eb1 | opus (claude-opus-5-5) | spotting-code-slop-1-seeded-diff | baseline, rerun after review fixes | fail | mixed-purpose diff | runs/rerun-1/spotting-code-slop-1-seeded-diff--opus/ |
| 2026-11-22 | 285d55eb1 | sonnet (claude-sonnet-5-5) | spotting-code-slop-1-seeded-diff | baseline, rerun after review fixes | pass | no failure observed | runs/rerun-1/spotting-code-slop-1-seeded-diff--sonnet/ |
| 2026-11-22 | 285d55eb1 | opus (claude-opus-5-5) | spotting-code-slop-2-clean-standard | baseline, rerun after review fixes | pass | no failure observed | runs/rerun-1/spotting-code-slop-2-clean-standard--opus/ |
| 2026-11-22 | 285d55eb1 | sonnet (claude-sonnet-5-5) | spotting-code-slop-2-clean-standard | baseline, rerun after review fixes | pass | no failure observed | runs/rerun-1/spotting-code-slop-2-clean-standard--sonnet/ |
| 2026-11-22 | 285d55eb1 | opus (claude-opus-5-5) | spotting-code-slop-3-seeded-diff-defensive | baseline, rerun after review fixes | pass | no failure observed | runs/rerun-1/spotting-code-slop-3-seeded-diff-defensive--opus/ |
| 2026-11-22 | 285d55eb1 | sonnet (claude-sonnet-5-5) | spotting-code-slop-3-seeded-diff-defensive | baseline, rerun after review fixes | pass | no failure observed | runs/rerun-1/spotting-code-slop-3-seeded-diff-defensive--sonnet/ |
| 2026-11-22 | 285d55eb1 | opus (claude-opus-5-5) | spotting-code-slop-4-realistic-historical-commit | baseline, rerun after review fixes | pass | no failure observed | runs/rerun-1/spotting-code-slop-4-realistic-historical-commit--opus/ |
| 2026-11-22 | 285d55eb1 | sonnet (claude-sonnet-5-5) | spotting-code-slop-4-realistic-historical-commit | baseline, rerun after review fixes | pass | no failure observed | runs/rerun-1/spotting-code-slop-4-realistic-historical-commit--sonnet/ |
| 2026-11-22 | f9be9a2a0 | sonnet (claude-sonnet-5-5) | auditing-codebase-health-3-decision-record | baseline, rerun after A2 | fail | no decision-record conflict flag | runs/rerun-2/auditing-codebase-health-3-decision-record--sonnet/ |
| 2026-11-22 | f9be9a2a0 | opus (claude-opus-5-5) | auditing-codebase-health-3-decision-record | baseline, rerun after A2 | pass | no failure observed | runs/rerun-2/auditing-codebase-health-3-decision-record--opus/ |
| 2026-11-22 | f9be9a2a0 | sonnet (claude-sonnet-5-5) | defining-project-terms-2-shipment-package | baseline, rerun after A2 | pass | no failure observed | runs/rerun-2/defining-project-terms-2-shipment-package--sonnet/ |

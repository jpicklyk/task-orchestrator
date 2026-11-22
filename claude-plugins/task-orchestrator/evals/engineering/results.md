# Engineering skill eval results

One row per run: date, the checkout sha, the model (the alias asked for, then the model ids the session transcript
shows), the scenario, the mode (`baseline` means no skills loaded; a rerun names what was loaded), the result and the
failure observed. A run happens once and is recorded as it happened, never retried. How to run:
[README.md](README.md).

A `pass` row whose failure column says `no failure` is a flag: the model already handles that scenario, so the skill
that owns it writes no text for it. Rows are appended at the end only; the skill items append their rerun rows.

Baseline of 2026-11-22 (one run per scenario per model, no skills loaded): 21 of 22 runs showed no failure. The one
failure is `auditing-codebase-health-3-decision-record` on sonnet, which ranked `src/labels.js` first, ahead of the
two carrier adapters that change together. Every other scenario passed on every model that ran it, so the skills
that own them write no text for those scenarios. Graders are heuristics: a scenario can pass because the model
chose a different but acceptable shape, not only because it handled the pattern.

Second baseline of 2026-11-22 (carry-in on baseline realism; one run per scenario per model, no skills loaded, rows
below the first baseline's). It regrades the 10 build scenarios with the added slop graders on prompts that no longer
hint at the answer (mode `baseline, slop graders`) and adds the realistic tier, one scenario per skill against this
repository (mode `baseline, realistic tier`). Regraded build tier: 0 of 10 runs failed, so narrating comments,
swallowed catches, redundant guards, new dependencies, scope creep, unused exports and leftover TODOs did not appear on
sonnet in these small tasks. Realistic tier: no failure on any skill. `defining-project-terms-5` first failed on a
`narrating-comments` hit that was a one-line doc comment above a helper, a grader false positive; the grader now skips
doc comments and the scenario was run again (`regraded` rows; the first row stays). `spotting-code-slop-4` was run a
second time after its finding regex was narrowed and the runner began to print the findings: both sonnet and opus name
the skill step that no longer matches the code (recorded finding N1), and both add real findings of their own (the
change breaks four existing hook tests; the new export has no tests). `auditing-codebase-health-4` ranked from the
most-changed hooks on both models. So across both tiers the only observed failure remains
`auditing-codebase-health-3-decision-record` on sonnet: the baseline still cannot separate "the model handles this" from
"the scenario is easy" for four of the five skills, and the skill items there should treat the unchanged rows as
"no text needed" only with that limit in mind. The realistic scenarios cost a full-repository session each; the grader
checks were proved able to fail by hand-made bad runs in `checks.test.mjs`, not by observed model failures.

| date | sha | model | scenario | mode | result | failure observed |
| --- | --- | --- | --- | --- | --- | --- |
| 2026-11-22 | 7998dc957 | sonnet (claude-sonnet-5-5) | auditing-codebase-health-1-hot-spot | baseline | pass | no failure (write no skill text for it) |
| 2026-11-22 | 7998dc957 | opus (claude-opus-5-5) | auditing-codebase-health-1-hot-spot | baseline | pass | no failure (write no skill text for it) |
| 2026-11-22 | 7998dc957 | sonnet (claude-sonnet-5-5) | auditing-codebase-health-2-glossary-drift | baseline | pass | no failure (write no skill text for it) |
| 2026-11-22 | 7998dc957 | opus (claude-opus-5-5) | auditing-codebase-health-2-glossary-drift | baseline | pass | no failure (write no skill text for it) |
| 2026-11-22 | 7998dc957 | sonnet (claude-sonnet-5-5) | auditing-codebase-health-3-decision-record | baseline | fail | ranks a cold module first |
| 2026-11-22 | 7998dc957 | opus (claude-opus-5-5) | auditing-codebase-health-3-decision-record | baseline | pass | no failure (write no skill text for it) |
| 2026-11-22 | 7998dc957 | sonnet (claude-sonnet-5-5) | defining-project-terms-1-order-purchase | baseline | pass | no failure (write no skill text for it) |
| 2026-11-22 | 7998dc957 | sonnet (claude-sonnet-5-5) | defining-project-terms-2-shipment-package | baseline | pass | no failure (write no skill text for it) |
| 2026-11-22 | 7998dc957 | sonnet (claude-sonnet-5-5) | defining-project-terms-3-new-term | baseline | pass | no failure (write no skill text for it) |
| 2026-11-22 | 7998dc957 | sonnet (claude-sonnet-5-5) | defining-project-terms-4-no-glossary | baseline | pass | no failure (write no skill text for it) |
| 2026-11-22 | 7998dc957 | sonnet (claude-sonnet-5-5) | designing-modules-1-single-provider | baseline | pass | no failure (write no skill text for it) |
| 2026-11-22 | 7998dc957 | sonnet (claude-sonnet-5-5) | designing-modules-2-config-loader | baseline | pass | no failure (write no skill text for it) |
| 2026-11-22 | 7998dc957 | sonnet (claude-sonnet-5-5) | designing-modules-3-dependency-direction | baseline | pass | no failure (write no skill text for it) |
| 2026-11-22 | 7998dc957 | sonnet (claude-sonnet-5-5) | organizing-codebases-1-reinvented-formatter | baseline | pass | no failure (write no skill text for it) |
| 2026-11-22 | 7998dc957 | sonnet (claude-sonnet-5-5) | organizing-codebases-2-generic-names | baseline | pass | no failure (write no skill text for it) |
| 2026-11-22 | 7998dc957 | sonnet (claude-sonnet-5-5) | organizing-codebases-3-local-conventions | baseline | pass | no failure (write no skill text for it) |
| 2026-11-22 | 7998dc957 | sonnet (claude-sonnet-5-5) | spotting-code-slop-1-seeded-diff | baseline | pass | no failure (write no skill text for it) |
| 2026-11-22 | 7998dc957 | opus (claude-opus-5-5) | spotting-code-slop-1-seeded-diff | baseline | pass | no failure (write no skill text for it) |
| 2026-11-22 | 7998dc957 | sonnet (claude-sonnet-5-5) | spotting-code-slop-2-clean-standard | baseline | pass | no failure (write no skill text for it) |
| 2026-11-22 | 7998dc957 | opus (claude-opus-5-5) | spotting-code-slop-2-clean-standard | baseline | pass | no failure (write no skill text for it) |
| 2026-11-22 | 7998dc957 | sonnet (claude-sonnet-5-5) | spotting-code-slop-3-seeded-diff-defensive | baseline | pass | no failure (write no skill text for it) |
| 2026-11-22 | 7998dc957 | opus (claude-opus-5-5) | spotting-code-slop-3-seeded-diff-defensive | baseline | pass | no failure (write no skill text for it) |
| 2026-11-22 | 5c3a2a9c4 | sonnet (claude-sonnet-5-5) | defining-project-terms-1-order-purchase | baseline, slop graders | pass | no failure (write no skill text for it) |
| 2026-11-22 | 5c3a2a9c4 | sonnet (claude-sonnet-5-5) | defining-project-terms-2-shipment-package | baseline, slop graders | pass | no failure (write no skill text for it) |
| 2026-11-22 | 5c3a2a9c4 | sonnet (claude-sonnet-5-5) | defining-project-terms-3-new-term | baseline, slop graders | pass | no failure (write no skill text for it) |
| 2026-11-22 | 5c3a2a9c4 | sonnet (claude-sonnet-5-5) | defining-project-terms-4-no-glossary | baseline, slop graders | pass | no failure (write no skill text for it) |
| 2026-11-22 | 5c3a2a9c4 | sonnet (claude-sonnet-5-5) | designing-modules-1-single-provider | baseline, slop graders | pass | no failure (write no skill text for it) |
| 2026-11-22 | 5c3a2a9c4 | sonnet (claude-sonnet-5-5) | designing-modules-2-config-loader | baseline, slop graders | pass | no failure (write no skill text for it) |
| 2026-11-22 | 5c3a2a9c4 | sonnet (claude-sonnet-5-5) | designing-modules-3-dependency-direction | baseline, slop graders | pass | no failure (write no skill text for it) |
| 2026-11-22 | 5c3a2a9c4 | sonnet (claude-sonnet-5-5) | organizing-codebases-1-reinvented-formatter | baseline, slop graders | pass | no failure (write no skill text for it) |
| 2026-11-22 | 5c3a2a9c4 | sonnet (claude-sonnet-5-5) | organizing-codebases-2-generic-names | baseline, slop graders | pass | no failure (write no skill text for it) |
| 2026-11-22 | 5c3a2a9c4 | sonnet (claude-sonnet-5-5) | organizing-codebases-3-local-conventions | baseline, slop graders | pass | no failure (write no skill text for it) |
| 2026-11-22 | 5c3a2a9c4 | sonnet (claude-sonnet-5-5) | auditing-codebase-health-4-realistic-hooks-history | baseline, realistic tier | pass | no failure (write no skill text for it) |
| 2026-11-22 | 5c3a2a9c4 | opus (claude-opus-5-5) | auditing-codebase-health-4-realistic-hooks-history | baseline, realistic tier | pass | no failure (write no skill text for it) |
| 2026-11-22 | 5c3a2a9c4 | sonnet (claude-sonnet-5-5) | defining-project-terms-5-realistic-seat-ownership | baseline, realistic tier | fail | narrating or change-log comments |
| 2026-11-22 | 5c3a2a9c4 | sonnet (claude-sonnet-5-5) | designing-modules-4-realistic-cache-share | baseline, realistic tier | pass | no failure (write no skill text for it) |
| 2026-11-22 | 5c3a2a9c4 | sonnet (claude-sonnet-5-5) | organizing-codebases-4-realistic-session-marker | baseline, realistic tier | pass | no failure (write no skill text for it) |
| 2026-11-22 | 5c3a2a9c4 | sonnet (claude-sonnet-5-5) | spotting-code-slop-4-realistic-historical-commit | baseline, realistic tier | pass | no failure (write no skill text for it) |
| 2026-11-22 | 5c3a2a9c4 | opus (claude-opus-5-5) | spotting-code-slop-4-realistic-historical-commit | baseline, realistic tier | pass | no failure (write no skill text for it) |
| 2026-11-22 | 5c3a2a9c4 | sonnet (claude-sonnet-5-5) | defining-project-terms-5-realistic-seat-ownership | baseline, realistic tier, regraded (doc comments skipped; narrower finding regex) | pass | no failure (write no skill text for it) |
| 2026-11-22 | 5c3a2a9c4 | sonnet (claude-sonnet-5-5) | spotting-code-slop-4-realistic-historical-commit | baseline, realistic tier, regraded (doc comments skipped; narrower finding regex) | pass | no failure (write no skill text for it) |
| 2026-11-22 | 5c3a2a9c4 | opus (claude-opus-5-5) | spotting-code-slop-4-realistic-historical-commit | baseline, realistic tier, regraded (doc comments skipped; narrower finding regex) | pass | no failure (write no skill text for it) |

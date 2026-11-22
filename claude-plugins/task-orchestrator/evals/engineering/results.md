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

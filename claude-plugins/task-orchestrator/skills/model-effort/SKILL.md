---
name: model-effort
description: "Internal reference: which model and effort each class of Task Orchestrator work runs at, how to tune effort, how a failed test or review escalates, and how to change the policy. Source of values is dispatch-policy.json."
user-invocable: false
---

# Model and effort

`claude-plugins/task-orchestrator/dispatch-policy.json` is the only place a model or effort value is
edited. This table is generated from it.

<!-- BEGIN GENERATED:dispatch-policy (do not edit; run _fragments/generate.mjs) -->
| Class | Model | Effort | Use |
|---|---|---|---|
| `coordinator` | opus | medium | Run and wave coordinators, orchestration sessions |
| `planning` | opus | high | Planner seats, specifications, task scope |
| `planning-high-stakes` | fable | high | Architecture decisions and other high-stakes planning |
| `implementation` | sonnet | medium | Well-specified implementation work |
| `implementation-complex` | opus | medium | Complex or cross-cutting implementation |
| `test-authoring` | sonnet | medium | Independent test authors |
| `review` | opus | high | Reviewer seats and defect search |
| `review-high-stakes` | fable | high | Review of high-stakes changes |
| `research` | sonnet | medium | Read-only research and exploration |
| `bulk` | haiku | medium | Bulk writes and other checkable mechanical work |
| `extraction` | sonnet | low | Extractors and retrospective data pulls |
| `audit-review` | sonnet | high | Deep-codebase-audit slice and gap reviewers (fan-out) |
| `audit-verify-high` | opus | medium | Deep-codebase-audit verification of high and critical findings |
| `audit-synthesis` | opus | medium | Deep-codebase-audit report synthesis |

Models are Claude Code aliases (`opus`, `sonnet`, `haiku`, `fable`); each resolves to the latest model of its family, so the policy names no version. Automatic escalation ceiling: `xhigh`.
<!-- END GENERATED:dispatch-policy -->

## Decision order

For an item's seat: seat `dispatch`, then phase `dispatch`, then the policy class for that seat. Trait
`dispatch` answers "this item needs more than its class". Work with no item seat (coordinators,
retrospectives, bulk writes): the class alone decides.

## Tuning

- Tune effort before model. Start at the class value and move one level at a time.
- After a model switch, restart at that model's default; level names are not comparable across models.
- Reserve `xhigh` and `max` for measured gains. `max` shows diminishing returns.

## Escalation after a failure

A fix after a failed test or review runs above the original dispatch, unless the base effort is
already at or above the ceiling, in which case it keeps the base effort. The step is 1 plus the
earlier fix cycles (`-a1`, `-a2`) of the same base seat whose cause was a review or a test (a cycle
recorded without a cause counts), and the effort is the base effort raised by that step. The ceiling
is `xhigh`; automatic escalation never selects `max`. A carry-in or breach cycle does not raise a later
one, a planner base keeps its effort and a reviewer is never reseated. A failure past the ceiling
stays at `xhigh` and goes through the decision path, since a stronger model is a person's call. A
replay of a recorded seat returns its recorded effort.

## When delegation does not pay

Dependent chains, where each step needs the last step's full context, are cheaper in one seat.
Work that would overflow one context (wide sweeps, many independent files) does pay.

## Changing the policy

Edit `dispatch-policy.json`, then run `node claude-plugins/task-orchestrator/_fragments/generate.mjs`
(CI runs it with `--check`). Scripts read values through `scripts/lib/dispatch-policy.mjs`
(`forClass`, `escalate`).

## Sources

- https://platform.claude.com/docs/en/build-with-claude/effort
- https://platform.claude.com/docs/en/about-claude/models/choosing-a-model
- https://platform.claude.com/docs/en/about-claude/models/optimizing-for-cost-and-intelligence
- https://platform.claude.com/docs/en/build-with-claude/prompt-engineering/prompting-claude-opus-5-5
- https://platform.claude.com/docs/en/build-with-claude/prompt-engineering/prompting-claude-sonnet-5-5
- https://code.claude.com/docs/en/model-config
- https://code.claude.com/docs/en/sub-agents
- https://academy.claude.com/tutorials/choosing-the-right-effort-level-in-claude-code

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

Checked 2026-11-07 against opus = Opus 5.5, sonnet = Sonnet 5.5, haiku = Haiku 5.5, fable = Fable 5.1. Automatic escalation ceiling: `xhigh`.
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

A fix after a failed test or review runs one level above the original dispatch, one level per fix
cycle (`-a1`, `-a2`). The ceiling is `xhigh`; automatic escalation never selects `max`. A failure past
the ceiling stays at `xhigh` and goes through the decision path, since a stronger model is a person's
call. Reviewers do not escalate.

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

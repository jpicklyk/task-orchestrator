# Journey -> config requirements

Each requirement says what a project's configuration must be able to SAY for a journey stage to work. It does not propose how. Stage ids (n.m) are journey.stage from journey-stages.txt. "Config" = the whole config model: schemas, traits, seats, decision classes, rules, run-profile, dispatch-policy, extensions.

Principles carried from the discussion: the schema defines the journey; Task Orchestrator concepts stay method-neutral (no epic/story/charter in the tool); no sync with external tools; no enforcement machinery without data; nodes show current state.

**Note (2026-11-26):** pass 10 of the boundary analysis marked R19 (as framed), R24, R27, R32, R34, R47 and R50 obsolete as config requirements, and treats R15 like R27 (invocation arguments, with only a persisted policy held). R28-R34 are in [journey-config-critique.md](journey-config-critique.md) and R35-R50 in [journey-knowledge-base.md](journey-knowledge-base.md); the current grades are on [journey-config-fit.html](../journey-config-fit.html). The rows below are kept as first written.

## Structure of the work

| # | The config must be able to say | Stages |
|---|---|---|
| R1 | Item types whose phases and required notes differ, used at any depth of the tree (levels are positions, not fixed types) | 1.2, 3.1, 6.1 |
| R2 | A node carries a goal note (goal, bounds, non-goals) that stays revisable while the node is open and is read again at acceptance | 1.2, 1.7, 6.1, 6.8 |
| R3 | Which types or items build something and which build nothing (a question to be ruled, a container) | 6.2, 6.4, 6.5 |
| R4 | What an item must have before it may start (its required queue notes) | 2.3, 6.5 |
| R5 | A parent that stays open while children are added, reshaped or cancelled, and closes only when the user accepts it | 1.2, 3.1, 6.7 |
| R6 | A small exploratory node whose required output is a finding rather than a merge, with lighter phases | 6.4 |
| R7 | Which phases are reviewed and which seats fill each phase, with their skills and rules | 2.5, 3.2, 4.7 |

## Acceptance and shipping

| # | The config must be able to say | Stages |
|---|---|---|
| R8 | Which types need the user's acceptance, at any depth, and what acceptance is judged against | 1.7, 2.5, 6.8, 7.3 |
| R9 | What happens after acceptance (ship step, release seam) for this project | 1.7, 2.6 |
| R10 | A send-back path when acceptance fails | 2.5 |

## Decisions and autonomy

| # | The config must be able to say | Stages |
|---|---|---|
| R11 | Per decision class: who may answer, whether the director may answer or must ask, severity and expiry | 3.5, 6.3, 6.6 |
| R12 | When a seat proceeds on a stated reversible assumption and when it blocks | 6.6 |
| R13 | Standing instructions to the director per class that outlive the session | 3.6 |
| R14 | What plan approval sets in motion: build straight away (and by which machinery) or stop at materialization | 2.4, 3.2, 7.3 |
| R15 | A standing-work policy for unattended draining: which items, what limits | 3.7 |
| R16 | Model and effort per class of work | 3.2, 4.7 |

## Ordering

| # | The config must be able to say | Stages |
|---|---|---|
| R17 | How work is ordered for "what first" (priority, complexity, dependency order). HELD: the user asked not to extend next yet; record fit only | 1.3, 2.2, 7.4 |

## Improve

| # | The config must be able to say | Stages |
|---|---|---|
| R18 | When a retrospective is owed: per closed run, and per accepted node | 5.1, 6.9, 3.8 |
| R19 | Process changes land in the layer that owns them (schema, rule, run-profile), with history and rollback | 5.4, 5.5 |
| R20 | Which config change a proposal adopted, so its outcome can be checked later | 5.6 |

## Setup and keeping current

| # | The config must be able to say | Stages |
|---|---|---|
| R21 | A choice among reference configs by kind of work, which setup picks from | 1.1, 7.1 |
| R22 | Which plugin and server features the config targets, so setup can see added, deprecated or removed keys | 7.6 |
| R23 | Whether a note's content lives in the graph or is a short reference to an outside source, and which skill writes it | Setup card (internal or referenced content) |

## Method-neutral skills

| # | The config must be able to say | Stages |
|---|---|---|
| R24 | Which note plays a part the plugin's skills need (the goal note, the acceptance basis, a finding) without the plugin assuming key names | 1.2, 6.1, 6.4, 6.8, 7.3 |

## Capture, look and learn

| # | The config must be able to say | Stages |
|---|---|---|
| R25 | Where captured work lands and which type it gets (bug, idea, observation) | 4.1, 4.2 |
| R26 | What the board counts as attention (stall thresholds, overdue) | 1.6, 2.1, 3.4 |
| R27 | Audit presets the user can pick by scope and budget | 2.8, 4.3, 5.7 |

## Stages with no config need (skill, tool or UX only)

1.4, 1.5, 1.8, 2.1 (digest), 2.7, 3.3, 3.4 (view), 4.4, 4.5, 4.6, 4.8, 5.2, 5.3, 6.2 (classifying), 7.2, 7.5. A requirement is listed above only where a project could reasonably choose differently.

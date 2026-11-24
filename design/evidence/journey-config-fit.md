# Journey requirements vs today's config: fit

Grades: **E** expressible (the config language says it), **W** awkward (sayable only by a workaround or convention), **X** impossible (no config can say it). Requirements: journey-config-requirements.md. Evidence: config-catalogue.md (section numbers in brackets).

| # | Requirement (short) | Grade | Why |
|---|---|---|---|
| R1 | Types with differing notes, at any depth | E | Any number of types, selected by `type`, no parent/child type constraints [2, absent list]. Phases are fixed (queue, work, review), but notes vary freely. |
| R2 | A revisable goal note, read again at acceptance | W | A required queue note on the parent type says it and stays upsertable [2a]. "Read again at acceptance" has nothing to bind to (see R8), and skills cannot find the goal note without knowing its key (R24). |
| R3 | Which items build nothing | X | `lifecycle: none` drops an item from `next`, but also removes its transitions and `blocks` edges, so a question cannot hold other items back [2]. No other key exists. |
| R4 | Notes required before start | E | The queue gate [2, 5]. |
| R5 | A parent that stays open while children change, closed by the user | W | `lifecycle: manual` stops the cascade [2]. Nothing says that only the user's acceptance closes it. |
| R6 | A small exploratory node whose output is a finding | E | A type with a required work note for the finding and no review notes, so the review phase is skipped [2, 5]. |
| R7 | Reviewed phases, seats, their skills and rules | E | Review notes, lanes, seats, `skills:`, `rules:` [2a, 4, 5, 10]. Enforced only inside runs and for subagents [4, 11]. |
| R8 | Which types need the user's acceptance, judged against what | W | No human approval can be bound to a phase or note [absent list]. Workaround: a review-phase note owned by the orchestrator seat that records the user's verdict, plus a rule. Who may write it is not expressible. |
| R9 | What happens after acceptance (ship, release) | W | `run-profile.json` `postRun` has `when: post-run`, `post-merge`; no accept trigger, and run-profile is read only by run-wave [12]. |
| R10 | Send-back when acceptance fails | X | Lifecycles and transitions are fixed; no review-to-work trigger, and `reopen` works from terminal only [2, known limits]. |
| R11 | Per class: who answers, whether the director may, severity, expiry | W | `answerers`, `severity`, `expires_after`, `on_expiry` exist [9]. "The director may answer" is `director-or-human`; "the director must ask the user even though it may" has no key. |
| R12 | Proceed on an assumption vs block | E | A project rule on the seat's `rules:` list [10], and the raise's own `block` flag [9]. |
| R13 | Standing director instructions per class | X | The director is not a seat, so no rule can be bound to it [4, 10]. `extensions` is the only free space, and nothing reads such a key [13]. |
| R14 | What plan approval sets in motion | W | Steering (`orchestration.mode`) and tier thresholds (`tier-policy.json`) shape the post-plan route [12, 13]. "Stop at materialization" vs "build now" has no key. |
| R15 | A standing-work policy for unattended draining | X | No config. Ralph takes arguments only. |
| R16 | Model and effort per class of work | W | Per phase and seat through a trait's `dispatch` [6]. The plugin's classes have no project override; only `tiers` do [12]. |
| R17 | How work is ordered (held) | - | No config: ordering is `next`'s `orderBy` plus item priority and complexity. Recorded only; no proposal, as asked. |
| R18 | Retrospective per run and per accepted node | W | `retrospective.mode`, `boundary: scope | run`, `minItems` [13]. "Per accepted node" needs acceptance (R8); `scope` is the closest. |
| R19 | Process changes land in their layer, with history and rollback | E | Config has versions, history and rollback; rules are versioned documents [0, 10]. run-profile and tier-policy have git history only. |
| R20 | Which config change a proposal adopted | W | Config versions exist; a proposal can only name one in its own note, by convention. |
| R21 | A choice among reference configs by kind of work | W | Patterns exist only as prose in the manage-schemas references, not as configs a skill can pick and push. |
| R22 | Which features the config targets, so setup sees changes | W | Validation warns on removed keys and the server advertises some features (`seatSkills`) [0, 4]. No key says what the config targets, and nothing reports new features worth adopting. |
| R23 | Content in the graph or a reference, and which skill writes it | E | Note `guidance` gives the shape, note `skill:` binds the writer [2a]. The gate checks only that the note is filled. |
| R24 | Which note plays a part the skills need, without assuming key names | X | A note entry has `key`, `role`, `description`, `guidance`, `skill` and nothing that names its part [2a]. A method-neutral skill cannot find "the goal note" or "the finding". |
| R25 | Where captured work lands, and its type | W | `project.rootId` and a `default` type exist [1, 2]. Which container a bug or idea goes under is the create-item skill's guess. |
| R26 | What the board counts as attention | W | `runs.stall_after` and `expires_after` exist [8, 9]; nothing for items outside runs. |
| R27 | Audit presets by scope and budget | - | Skill arguments (quick, standard, full) are enough; no config need. Moved out of the list. |

## Totals

E 7 (R1, R4, R6, R7, R12, R19, R23) · W 13 · X 5 (R3, R10, R13, R15, R24) · not config 2 (R17 held, R27).

## Root causes behind the W and X grades

1. **No acceptance in the config model** (R2, R5, R8, R9, R10, R18). The user's verdict can't be bound to a type or phase, so everything downstream of acceptance is a convention.
2. **Notes have no declared part** (R2, R24). Method-neutral skills need a way to find "the goal" or "the finding" whatever the schema calls them. This is the price of keeping method words out of the tool.
3. **Build vs build-nothing is missing** (R3). The only way out of `next` also removes blocking.
4. **The director has no config surface** (R11, R13). Its autonomy and standing instructions have nowhere to live.
5. **Plugin-side settings are scattered and partly fixed** (R14, R16, R21, R22). Run-profile, dispatch-policy, tier-policy and extensions are separate files with no version marker, and some have no project override.
6. **Setup has no library to choose from** (R21, R22). Patterns are prose, not configs.

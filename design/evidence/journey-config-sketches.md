# Journey config sketches

One project configuration per journey, written only with keys that exist today (config-catalogue.md). Stage ids n.m are from journey-stages.txt; R-numbers from journey-config-requirements.md; grades from journey-config-fit.md.

Conventions used throughout:
- Every YAML block is the project's `.taskorchestrator/config.yaml`, pushed per root by `config-sync`. Each sketch inherits the global floor (`project`, `container`, `agent-observation`, `improvement-proposal`, `session-retrospective`, `retrospective-trend`) without declaring it.
- A block defined once is named `B-<name>` and reused by name. A comment like `# notes: B-task` stands for that block's entries; YAML anchors are not relied on, because no source says the server parser or the hooks' YAML reader honours them.
- `# NEEDS R<n>: ...` marks a line that is the closest real workaround, or a key that does not exist. Nothing else is invented.
- `run-profile.json` and `tier-policy.json` fragments are shown as jsonc so they can carry `//` annotations here; the real files are plain JSON.
- Type and note key names (`goal`, `task`, `question`, `probe` and so on) belong to the sketched project. None is a Task Orchestrator concept, and none is a method word.

---

## J1 Greenfield: idea to v1

Intent: one goal node holds what v1 is, its bounds and its ordered pieces; plain tasks build it; the user's go/no-go on the goal node is the v1 decision.
Low certainty and orchestration mode, so no review lanes and no seats on the leaves; the goal node alone carries acceptance.

```yaml
# B-root
project:
  rootId: "<anchor root id>"        # written by setup
  name: "<project name>"
# NEEDS R21: nothing records that setup picked this from a library of reference configs (1.1)

work_item_schemas:

  # B-goal-node: a node of any depth that holds a goal and closes only on the user's verdict.
  goal:
    lifecycle: manual                # R5: no cascade, so children can be added, reshaped or cancelled while it stays open
    seats:
      - { name: orchestrator, phase: review }   # the only seat: run-wave derives no stage, so the node builds nothing itself (R3)
    notes:
      - key: goal
        role: queue
        required: true
        seat: orchestrator           # the coordinator token or a human may revise it in any phase (R2)
        maxLength: 2500
        skill: task-orchestrator:decomposing-work   # NEEDS R24: the binding names a method, not the part "goal"; a skill that shapes or accepts finds this note only by its key
        description: "What this node delivers, its bounds and non-goals, and its pieces in order; revised while the node is open."
        guidance: "`Goal:` two or three sentences. `Bounds:` what must hold (users, platforms, limits). `Non-goals:` one line each. `Pieces:` the child nodes in the order they should land, one line each (label, one-line scope); dependency edges follow this order. Revise in place when a ruling or a finding changes any line, and keep a closing `Revised:` list, one line per revision (date, what, why)."
      - key: acceptance
        role: review
        required: true
        seat: orchestrator           # an orchestrator-seated review note is never a review lane, so no reviewer agent fills it
        maxLength: 1500
        description: "The user's verdict on this node, judged against its goal note and the rulings made under it."
        guidance: "Written from the answer to this node's `acceptance` decision, never from an agent's own judgement. `Verdict: accepted | sent back`. One line per `Goal:` and `Bounds:` line of the goal note: met | not met (evidence: child id or note key). `Rulings:` the decision ids answered under this node. `User's words:` quoted."

  # B-task: the smallest leaf; a plan's agreed scope lands on the item, nothing else is required.
  task:
    notes:
      - key: intent
        role: queue
        required: true
        maxLength: 1500
        description: "What this item changes and how anyone can tell it is done."
        guidance: "`Change:` what is built or fixed, in one to three sentences. `Done when:` one line per check a person or a test can run. The approved plan is the source; do not restate it."
  # 1.3 order: R17 held. Ordering is `next` orderBy plus item priority and complexity; no config.

# B-accept: the decision the user answers on a goal node.
decision_classes:
  acceptance:
    answerers: director-or-human     # the main session holds no seat token, so it may record the user's answer in orchestration mode
    severity: high
    freeform: true                   # the user's own words travel with the answer
    # NEEDS R8: no key makes the goal node's gate wait on this decision; the orchestrator raises it when the node's last child closes and copies the answer into `acceptance`

runs:
  stall_after: 1800
  # NEEDS R26: attention for items outside a run (a node idle for days) has no key; stall_after covers run seats only

extensions:
  claude-code:
    retrospective:
      mode: nudge
      boundary: scope                # one per completed scope (1.8)
      minItems: 1
```

```jsonc
// run-profile.json, B-ship
{
  "postRun": [
    { "step": "release-readiness", "when": "post-merge", "rule": "release-readiness" }
    // NEEDS R9: no `when` fires on acceptance of a goal node; post-merge is the nearest, and run-profile is read by run-wave only
  ]
}
```

`.taskorchestrator/rules/release-readiness.md` (project rule): "Before a release: every goal node under the released one is accepted; the changelog names each accepted node; the version is set. Report a gap instead of releasing."

Runs out at:
- R21 (1.1): no reference-config choice to record.
- R24 (1.2, 1.7): the goal and acceptance parts are known by key only.
- R8 (1.7): the gate cannot wait on the acceptance decision.
- R9 (1.7): no ship step triggered by acceptance.
- R26 (1.6): no attention rule outside runs.

---

## J2 Steady-state cadence

Intent: a feature or two a week, each a goal node over reviewed leaves; plan approval sends the leaves to a run and a reviewer verdict gates each one.
The user accepts the feature, not each leaf; a Fail verdict on a leaf goes back through a fix cycle.

```yaml
# B-root

work_item_schemas:
  feature:
    # B-goal-node under the type name `feature` (seats, goal, acceptance)
  change:
    default_traits: [reviewed]
    # notes: B-task (intent)
  # 2.2 ranking: R17 held.

traits:
  # B-review: an independent verdict lane. `seat: reviewer` lets it join a seat-aware type (J3, J6) without becoming unowned.
  reviewed:
    description: "Adds an independent review that ends in one verdict."
    applies_when: "the item changes behaviour a user or another item depends on."
    notes:
      - key: review
        role: review
        required: true
        seat: reviewer
        lane: { output: verdict }
        maxLength: 2500
        description: "Findings and verdict against the item's intent."
        guidance: "Number each finding once (B1.. blocking, O1.. observation): location, expected and its source, found. One `Done when n: met | not met (<citation>)` line per check in `intent`. Last line `Verdict: Pass | Fail`."

decision_classes:
  acceptance:
    # B-accept
  # NEEDS R10: a user's `sent back` on a feature has no path; only a reviewer Fail on a leaf has one (run-profile review.onFail)

extensions:
  claude-code:
    orchestration:
      mode: workflow                 # tiers + dispatch rules shape what approval sets in motion
      # NEEDS R14: no key says "plan approval builds now, through run-wave" versus "stop at materialization" (2.4)
    retrospective:
      mode: dispatch
      boundary: scope
      minItems: 3
```

```jsonc
// run-profile.json
{
  "verify": [ { "name": "tests", "command": "<project test command>", "seats": ["implementer"] } ],
  "review": "generic",               // generic review with onFail fix-cycle: the leaf-level send-back (2.5)
  "postRun": [
    // B-ship
  ]
}
```

Runs out at:
- R14 (2.4): approval-to-build is not a setting.
- R8, R10 (2.5): feature acceptance is a convention, and its send-back has no path.
- R9 (2.6): B-ship, as in J1.
- R26 (2.1): the re-entry digest has no attention rule outside runs.

---

## J3 Large parallel initiative

Intent: a deep tree of goal nodes (the same type at every level) over seated, delegated leaves, run as several runs while the director keeps the board and the decision inbox.
Here the seats are declared, because rules, skills and per-seat dispatch attach to seats.

```yaml
# B-root

work_item_schemas:
  goal:
    # B-goal-node, used at depth 1 (the initiative) and depth 2 and below (its parts): levels are positions (R1)
  change:
    default_traits: [reviewed, delegated]
    seats:
      - { name: planner,     phase: queue }
      - { name: implementer, phase: work, enters: true }
      - { name: reviewer,    phase: review }
    # notes: B-task, with `seat: planner` on `intent`; a seat-aware type needs every required note seated

traits:
  reviewed:
    # B-review
  # B-delegated: routes seats to the plugin's agents and declares the note the plugin writes token cost into.
  delegated:
    description: "Routes work and review to dispatched agents and records the dispatch."
    applies_when: "the item is built by a dispatched agent rather than inline."
    notes:
      - key: delegation-metadata     # a key the plugin itself writes (run provenance and meters)
        role: work
        required: false
        seat: orchestrator
        description: "Provenance line of the dispatch, written by the orchestrator after the run."
    dispatch:
      work: { agent: task-orchestrator:implementer }
      review: { agent: task-orchestrator:reviewer }
  hard:
    description: "Raises the implementer seat to the opus model."
    applies_when: "a cross-cutting seam, concurrency or a subtle state machine."
    dispatch:
      work:
        seats:
          implementer: { model: opus }
    # NEEDS R16: model and effort of the coordinator, research and audit classes have no project override; only item seats do

decision_classes:
  acceptance:
    # B-accept
    # NEEDS R11: no key says "the director may answer this class but must ask the user first"; human-only would stop the director and is unanswerable over stdio
  scope-change:
    answerers: director-or-human
    severity: normal
    expires_after: 28800
    on_expiry: escalate
  deviation:
    expires_after: 7200              # a built-in class, overridden field by field

runs:
  stall_after: 900
  budgets:
    max_active_seats: 6              # a ceiling on parallel seats per run, not a per-item budget
  # NEEDS R26: as J1

extensions:
  claude-code:
    retrospective:
      mode: dispatch
      boundary: run                  # one per closed run (3.8)
      minItems: 3
```

`.taskorchestrator/rules/director-standing.md`, served by `config-sync` as a rule document: "scope-change: answer it yourself when the change stays inside the goal note's bounds; otherwise ask the user. deviation: ask."
`# NEEDS R13: the director is not a seat, so no rules list binds this text to it, and no extensions key is read for it`

`# NEEDS R15: no key says which items unattended draining may take, or its limits; ralph takes arguments only (3.7)`

```jsonc
// run-profile.json
{
  "verify": [ { "name": "tests", "command": "<project test command>", "seats": ["orchestrator"] } ],
  "maxItems": 12,
  "branchPrefix": { "shared": "feat/", "perItem": "fix/" },
  "review": "generic"
}
// tier-policy.json: thresholds only; a forceUp or forceDown entry replaces the plugin's whole list
{ "thresholds": { "directMaxFiles": 1, "delegatedMaxFiles": 6 } }
// NEEDS R14: tiers choose the machinery for one item; nothing says "approval opens the runs in director mode" (3.2)
```

Runs out at:
- R14 (3.2): the single go at approval is not a setting.
- R16 (3.2): no class-level model override.
- R11 (3.5): "may answer, must ask" has no key.
- R13 (3.6): standing director instructions have no binding.
- R15 (3.7): no standing-work policy.
- R26 (3.4): no attention rule outside runs.

---

## J4 Maintenance and quality

Intent: captures land in one intake container under fixed types; triage keeps, cancels or defers them; funded audit findings become ordinary leaves that point at the finding instead of copying it.
Observations need no project config: the global floor's `agent-observation` is a depth-0 type.

```yaml
# B-root

work_item_schemas:
  intake:
    lifecycle: permanent             # a filing place: never cascades, never reopens
    notes:
      - key: intake-rule
        role: queue
        required: false
        description: "What lands here and as which type."
        guidance: "One line per kind of capture: `bug -> bug`, `idea -> task`, `funded finding -> task + from-audit`."
        # NEEDS R25: this is a convention in text; create-item still guesses the container and the type
  bug:
    default_traits: [reviewed]
    notes:
      - key: diagnosis
        role: queue
        required: true
        maxLength: 2500
        description: "Reproduction, cause and the fix's done-when."
        guidance: "`Repro:` exact steps, expected vs actual. `Cause:` file and condition. `Done when:` one line per check, the regression test first."
  task:
    # B-task

traits:
  reviewed:
    # B-review
  # B-ref-note: content kept outside the graph, held as a reference (R23).
  from-audit:
    description: "Links the item to the audit finding it funds."
    applies_when: "the item was created from an audit finding."
    notes:
      - key: finding-ref
        role: queue
        required: true
        maxLength: 300
        description: "A reference to the audit finding, not a copy of it."
        guidance: "One line: `<report path>#<finding id>`. The report holds the evidence; never paste it."

status_labels:
  hold: deferred                     # triage's "defer" is the hold trigger, shown as deferred (4.2)
```

Audit presets (4.3) are skill arguments, not config (R27). Cost against plan (4.8) reads `delegation-metadata`, which B-delegated declares when the project dispatches agents.

Runs out at:
- R25 (4.1, 4.2): where a capture lands is text, not a setting.

---

## J5 Process evolution

Intent: every closed run gets a retrospective; proposals record the exact config change they adopted, so a later retrospective can check its outcome.
The process itself (schema, rules, run-profile) is edited in its own layer; config history and rollback already exist on the server.

```yaml
# B-root

work_item_schemas:
  # A per-root copy of the floor type replaces it for this root's proposals, and from then on stops receiving floor updates.
  improvement-proposal:
    notes:
      - key: proposal
        role: queue
        required: true
        description: "Problem, evidence, proposed change, expected measurable effect, and scope."
        guidance: "<floor guidance, kept verbatim>"
      - key: adoption-decision
        role: work
        required: true
        description: "Accepted / rejected / deferred, with rationale."
        guidance: "<floor guidance, kept verbatim> When accepted, add one line `Adopted: config v<n> | rule <key> rulesVersion <hash> | run-profile <commit>`."
        # NEEDS R20: the adopted change is a line of text; nothing links the proposal to the config version it produced
      - key: outcome-verification
        role: review
        required: true
        description: "Did the change produce the expected effect?"
        guidance: "<floor guidance, kept verbatim> Compare against the `Adopted:` line's version, and only sessions after it."

extensions:
  claude-code:
    retrospective:
      mode: dispatch
      boundary: run                  # the one-line health note per closed run (5.1)
      minItems: 1
      baselines:
        review-share: { measured: 2026-11-24, review-share-pct: 30 }   # a figure retro-metrics echoes back for comparison
  # NEEDS R22: no key says which plugin and server features this config targets, so "still fits?" (5.5) cannot list new features worth adopting
```

Runs out at:
- R20 (5.6): the adopted change is text only.
- R22 (5.5): no target marker for the config.

---

## J6 Incremental delivery

Intent: a growing tree whose goal node frames the bounds; open choices become questions that build nothing but block what waits on them, and small probes settle them by building to learn.
Seats stay on the leaves, ready to take a project rule that says when to proceed on a stated assumption and when to block.

```yaml
# B-root

work_item_schemas:
  goal:
    # B-goal-node (its goal note already carries bounds and non-goals, 6.1)

  # B-question: a choice to be ruled; builds nothing, holds back the items that wait on it.
  question:
    lifecycle: auto                  # keeps transitions and `blocks` edges, which `none` removes
    seats:
      - { name: orchestrator, phase: work }   # no dispatchable seat: run-wave excludes it as "nothing to run" (R3)
    notes:
      - key: question
        role: queue
        required: true
        seat: orchestrator
        maxLength: 1200
        description: "The choice, its options and the nodes that wait on it."
        guidance: "`Choice:` one sentence. `Options:` one line each, with its cost. `Shared: yes | no` (yes when more than one node depends on the answer). `Waits:` the item ids it blocks."
      - key: ruling
        role: work
        required: true
        seat: orchestrator
        maxLength: 1200
        description: "The answer, who gave it, and what it changes below."
        guidance: "`Ruling:` the option. `By:` decision id and answerer, or the finding of a probe (item id). `Changes:` each node reshaped, reparented or cancelled (id, what)."
    # NEEDS R3: outside run-wave nothing reads "builds nothing"; `next` still offers a question as work

  # B-probe: a small exploratory node whose required output is a finding (R6).
  probe:
    seats:
      - { name: implementer, phase: work, enters: true, rules: [closure-notes, assume-or-block] }
    notes:
      - key: hypothesis
        role: queue
        required: true
        seat: orchestrator
        maxLength: 800
        description: "What is tried, which question it settles, the smallest build that shows it."
      - key: finding
        role: work
        required: true
        seat: implementer
        maxLength: 1500
        description: "The result and what it settles."
        guidance: "`Result:` what happened, with evidence. `Settles:` question id and the option it supports. `Code kept:` none | paths, with why."
        # NEEDS R24: the part "finding" is known by key only; a skill that turns it into a ruling must know the name

  change:
    default_traits: [reviewed]
    seats:
      - { name: planner,     phase: queue, rules: [planning-sweeps, review-cited-claims, assume-or-block] }
      - { name: implementer, phase: work, enters: true, rules: [closure-notes, assume-or-block] }
      - { name: reviewer,    phase: review }
    # notes: B-task, with `seat: planner` on `intent`
    # a declared rules list replaces the seat's defaults, so the defaults are listed again beside the project rule

traits:
  reviewed:
    # B-review

decision_classes:
  acceptance:
    # B-accept
  shared-call:                       # a question marked `Shared: yes` (6.3)
    answerers: director-or-human
    severity: normal
    expires_after: 86400
    on_expiry: escalate
  question:
    severity: low                    # built-in, any-actor and freeform: a local call, raised without block (6.6)

extensions:
  claude-code:
    retrospective:
      mode: dispatch
      boundary: scope
      minItems: 1
      # NEEDS R18: "one per accepted node" is met only because a goal node completes through its acceptance note; the trigger is the completed scope, not acceptance
    orchestration:
      mode: workflow
      # NEEDS R14: approval builds the refined (queue gate met), unblocked items and leaves the rest; post-plan has no such setting (6.5)
```

`.taskorchestrator/rules/assume-or-block.md` (project rule):

```markdown
Proceed on an assumption when the choice is local to your item and can be undone: write it under `Assumptions:` in your note and raise a `question` decision without block.
Block when the choice is shared, cannot be undone, or would break a line of the goal note's Bounds: raise a `shared-call` decision with block and stop this item only.
```

Runs out at:
- R3 (6.2, 6.5): builds-nothing is read by run-wave only.
- R24 (6.1, 6.4, 6.8): goal and finding are known by key only.
- R14 (6.5): approval building only the refined, unblocked items is not a setting.
- R8 (6.8): acceptance gate, as J1.
- R18 (6.9): per-accepted-node retrospectives ride on the completed scope.

---

## J7 Light touch

Intent: setup leaves the smallest config that works: the root and one type with one note, so the agreed plan is recorded on the item and nothing else is asked.
No review phase and `lifecycle: auto`, so a parent closes when its children do: in Auto mode, approval is acceptance.

```yaml
# B-root
# NEEDS R21: as J1; setup cannot record that it chose the light reference config
# NEEDS R22: as J5; "stay current" (7.6) has nothing to compare against

work_item_schemas:
  task:
    # B-task (intent): the one type, used for a single node, the parent of a flat list, and its children
```

Optional: a `tier-policy.json` of `{ "thresholds": { "directMaxFiles": 4 } }` keeps more work inline. No `extensions` block: the defaults (orchestration `workflow`, retrospective `nudge`) fit. 7.4 ordering: R17 held. 7.5 (learn the words) is a skill, not config.

Runs out at:
- R21 (7.1): no library entry to pick.
- R22 (7.6): no feature target to diff.

---

## Shared building blocks

Candidates for a reference-config library that setup could choose from. Each is self-contained YAML today.

| Block | Holds | Used by |
|---|---|---|
| B-root | `project.rootId`, `project.name` | all |
| B-task | one type, one required queue note `intent` (change + done-when) | J1, J2, J3, J4, J6, J7 (base of every leaf) |
| B-goal-node | a `manual` type whose only seat is `orchestrator`; `goal` (queue, revisable) and `acceptance` (review, the user's verdict) | J1, J2 (as `feature`), J3 (every level), J6 |
| B-accept | decision class `acceptance`: director-or-human, high, freeform | J1, J2, J3, J6 |
| B-review | trait `reviewed`: one seated review note on a verdict lane | J2, J3, J4, J6 |
| B-delegated | trait routing work and review to the plugin's agents, declaring `delegation-metadata` | J3 (J4 when it dispatches) |
| B-question | a build-nothing type that can block: orchestrator-only seats, `question` and `ruling` notes | J6 (fits J3's open calls too) |
| B-probe | a no-review type whose required work note is a finding | J6 |
| B-ref-note | a trait adding a one-line reference note instead of copied content | J4 (any external source) |
| B-ship | run-profile `postRun` step plus a project rule for release readiness | J1, J2 |
| Retro presets | `retrospective` at `nudge/scope` (light), `dispatch/scope` (tree), `dispatch/run` (many runs) | J1, J2, J3, J5, J6 |

A library could offer them as three stacks: light (B-root + B-task), cadence (+ B-goal-node, B-accept, B-review, B-ship) and exploratory (+ B-question, B-probe and a project rule like assume-or-block).

Composition traps that writing the YAML showed:
- When a trait adds a seat to a seat-less type, the type becomes seat-aware, and every unseated required note on it becomes unowned. Run-wave then excludes the item (`run-planner-lib.mjs:822-835`). B-review carries `seat: reviewer` for this reason, and a seated type must seat B-task's `intent`.
- A seat's declared `rules` list replaces its defaults. Adding one project rule means listing the defaults again (J6).
- A `tier-policy.json` `forceUp` or `forceDown` list replaces the plugin's whole list (`scripts/lib/dispatch-policy.mjs:186-188`).
- A per-root copy of a floor type (J5) replaces it whole, and stops receiving floor updates.

## Grades I would change

| Requirement | Old | New | Evidence |
|---|---|---|---|
| R3 build nothing | X | W | A seat-aware type whose only seat is `orchestrator` derives no run stage: the orchestrator is never a stage (`scripts/run-planner-lib.mjs:858`), and an item with no stage is excluded as "nothing to run" (`:993-996`). `lifecycle: auto` keeps its `blocks` edges (B-question). What is left: outside run-wave nothing reads it, and `next` still offers it. |
| R10 send-back | X | W | `run-profile.json` `review.onFail: fix-cycle` is the default (`run-planner-lib.mjs:106-131`) and sends a reviewer Fail back through a fix cycle. What is left: it covers reviewer lanes in run-wave only. An orchestrator-seated acceptance note is never a lane (`:714`, `:734`), so a user's "sent back" has no path, and the server has no review-to-work trigger. |
| R2 revisable goal, read at acceptance | W | E | A queue note can be written after its phase: this repo's `amendments` queue note is written after a review Fail (`.taskorchestrator/config.yaml:268-274`). `seat: orchestrator` lets the coordinator token or a human revise it in any phase (config-format.md, seat-entry `name`). The project's own acceptance guidance can name the key. The part that remains, a plugin skill finding the note, is R24 alone. |
| R24 note parts | X | W | A note's `skill` may be a list (catalogue 2a), so a project can bind a plugin part skill beside its own. The repo already marks its breakdown part this way: `task-orchestrator:decomposing-work` is bound to `work-breakdown` (`.taskorchestrator/config.yaml:39`). The plugin's own mechanics parts are fixed keys (`ORCHESTRATOR_OWNED_NOTES`, `run-planner-lib.mjs:551`). What is left: no plugin skill exists for the goal, finding or acceptance parts, and finding a note by its bound skill is a convention. |

Unchanged, but the reason is narrower. R8 stays W. Inside an open run, the only seat token that may write an orchestrator-seated note is the coordinator's; a human credential is exempt (config-format.md, "Seat transitions"). That covers part of "who may write it". Outside a run, and over stdio where every caller is one `local` principal, nothing restricts the writer.

# Task Orchestrator

Work items carry notes; an item whose type has a lifecycle moves through gated phases, and a lifecycle-free item never transitions. Seats are the positions whose agents fill each phase's notes. Above the seats, two session layers act: the orchestrator drives the work, and the director decides on the user's behalf. In short: seats do the work, the orchestrator drives it, and the user decides, or the director decides for them. These terms name the concepts that code, schemas and reviews share.

## Items and links

### Lifecycle-free item

An item whose type's schema sets `lifecycle: none`: it has no role and never transitions, so no phase, gate, seat or cascade applies to it. A knowledge-base entry is the usual case.

Distinct from: an item that builds nothing, which still moves through its phases; and a `permanent` item, which has a role but is never cascaded to terminal.
In code: `LifecycleMode.NONE` (a NULL `role`).

### Link

A recorded pointer from an item, note or document to another that orders nothing: a `[[...]]` reference parsed from a body, an attachment, an adoption, a note's `derived_from` plan, or an item's `derived_from` source (a note or a document, declared on create).

Distinct from: a dependency edge (`blocks` or `relates_to`), which `deps_write` records between items. A `relates_to` edge is a dependency edge, not a link.
In code: `Link`, `LinkRelation` (`references`, `attached`, `adopted_by`, `derived_from`).

## Seats and notes

### Seat

A named position in an item's workflow, declared in a schema for one phase, that one dispatched agent fills. The orchestrator is not a seat: it writes its own notes as the reserved note owner `orchestrator`. A seat may not be named after the director, which never writes notes.

Distinct from: role. A role is the phase a note belongs to; a seat is the position whose agent fills it.
In code: `SeatDefinition`. The reserved names are `unowned` and `director` (refused); a seat named `orchestrator` or `coordinator` still loads this release, with an advisory that the name will be refused.

### Writing seat

A seat that changes files in the item's worktree and commits them. A seat's `writes` declares it; undeclared, a work-phase seat writes and a queue or review seat does not. The server serves the flag and never enforces it.

Distinct from: entering seat. An entering seat starts the item into its phase; writing is about changing and committing files, and every seat writes its own notes.
In code: `SeatDefinition.writes`, `SeatDefinition.effectiveWrites`.

### Seat-aware schema

A schema that declares at least one seat.

Don't call it: seated schema.
In code: `WorkItemSchema.isSeatAware()`.

### Owning seat

The seat a note belongs to: the seat its schema entry names, when that seat is declared for the note's own phase, or is `orchestrator`, which owns its labelled notes in every seat-aware schema in any phase, declared seat or not. In a schema that is not seat-aware, the named seat stands as written. A note with no owning seat is unowned.

Distinct from: note owner. The note owner is the principal whose write created or adopted the note, kept on later writes; it is not the identity that last wrote it.
Don't call it: seat ownership.
In code: `OwningSeat`, `WorkItemSchema.owningSeatOf()`; served as `owningSeat` on every note entry of `items_read` `schema`.

### Orchestrator note

A note whose schema entry is labelled `seat: orchestrator`, written by the orchestrator itself rather than a seat's agent. `orchestrator` is a reserved note owner, valid in every schema without any declaration; it owns its labelled notes in any phase. Inside an open run of a seat-aware schema the orchestrator writes them with the run token; outside a run it writes them directly. No agent is ever dispatched for them, so they are never a stage of a run.

Distinct from: seat. A seat is a position a dispatched agent fills; the orchestrator is the session layer that writes these notes, and fills no seat.
Don't call it: orchestrator seat.
In code: `OwningSeat.ORCHESTRATOR`, served as `owningSeat: "orchestrator"`. The run planner skips these notes as a stage (`run-planner-lib.mjs`).
Example: `session-tracking` labelled `seat: orchestrator` is written by whoever drives the item. On a run, that is the run's orchestrator; for a hand-dispatched item, it is the user's session.

### Run token

The credential `runs_write` `open` returns and `reissue` replaces: the server's only proof that a caller is a run's orchestrator. It may send any trigger on any member of its run, write orchestrator notes, assign seats and close the run. It fills no seat.

Distinct from: seat token, which a member seat of one item holds and which `assign` returns.
Don't call it: coordinator pseudo-seat.
In code: `orchestratorToken` (`coordinatorToken` is its deprecated alias until 5.0), `RunSeat.ORCHESTRATOR` (its stored value is still `coordinator`), `RunPermissions`.

## Session layers

### Session layer

One of the two roles a Claude Code session or agent plays above the seats: orchestrator, which drives the work, or director, which decides on the user's behalf. A layer says who is acting; the server records no layer, only the profile, credential and token each one presents.

Distinct from: seat, a position in one item's workflow, and session mode, which says which layer the user's own session is playing. A background agent can play a layer without being anyone's session mode.

### Orchestrator

The layer that drives work forward. It plans and sizes work, dispatches seats, advances phases, resumes an item once its decision is answered, and carries decisions up. It never answers a decision. Its scope is either ad hoc items or one run; for a run it holds that run's token, the run token.

Distinct from: director, which decides and never drives. The user's session is an orchestrator in orchestration mode, but a background agent handed a run is also an orchestrator, of that run.
Don't call it: coordinator, run coordinator, lead. ("Coordinator" was the name for an orchestrator scoped to one run.)
In code: `RunSeat.ORCHESTRATOR` (its stored value is still `coordinator`), `RunPermissions`, the `orchestratorToken` field (`coordinatorToken` is its deprecated alias until 5.0), and the `coordinator_seen_at` column, which keeps the old name.
Example: run-wave hands a wave to a background agent. That agent is the run's orchestrator: it assigns seats, advances members and resumes items. The user's own session stays the orchestrator of everything outside that run.

### Director

The layer that decides on the user's behalf, within the authority each decision class gives it. It works through the decision inbox and decides from each decision request, which carries the evidence its answer needs; when it does not, the director sends a background agent and reads only that agent's summary. It escalates what it may not settle. It acts on the board at the user's instruction; it does not own the board. It never reads a note or document body: it reads ids, counts, state and short reasons, one decision in full at a time, and run rollups. It never drives work: it hands work to orchestrators.

Distinct from: orchestrator, which drives and never decides. The director is also distinct from the director profile, its tool set, and from director mode, the session mode in which the user's own session plays this layer.

### Session mode

The layer the user's own session plays, which also says who decides:
- **Orchestration mode** (the default): the session is the orchestrator and the user answers the decisions that reach it. An open run in the session shows as a run indicator, not a separate mode.
- **Director mode**: the session is the director, orchestrators work in the background, and the user hears only escalations.

Distinct from: steering, the hook config that sets how strongly the plugin hooks steer a session whatever its session mode.
Don't call it: coordinator mode (an open run in orchestration mode), orchestrator mode.
In code: the `orchestration-mode` mod's footer labels.

### Board

The user's view of work across runs: open runs and their frontier, the decision inbox, stalls, risk and cost. It is the same view in either session mode. In orchestration mode the user acts on it directly; in director mode the user reads it and tells the director what to handle. It shows ids, counts and short reasons, never note bodies, so the director can read the same view without breaking its altitude rule.

Distinct from: director mode, which changes who acts on the board, not who sees it; and from the director, which acts on the board and does not own it.
Don't call it: the director's board.
In code: the work-summary and director skills render it today. Its reads are `runs_read` `frontier`, served on both profiles, `decisions_read` `list` and `report` `rollup`, served on both profiles.

### Director mode

The session mode in which the user's session plays the director, entered with the director skill. Today it uses two connections: the orchestration connection, with the orchestrator's tools, for board reads and delegation, and the director profile for the decision ops, `pause`, `resume` and `reissue`, which are served there only. The destination is [0003](design/decisions/0003-role-neutral-decisions-surface.md): decision ops on every profile that needs them, answer authority from the decision's class plus a credential's `decide` capability, and one connection. The `director-guard` hook alone enforces altitude, denying reads that return body text, edits and every Task Orchestrator write except decision ops and run control, and allowing any skill, skill and plugin files, a plugin's scripts (which it does not inspect, so the director runs one only to route or decide) and background dispatch. The director offloads research to background agents and answers from their concise result.

Distinct from: director profile, which is a server endpoint. The bare word "director" names the layer, this mode and the profile, so where the context does not settle it, say which.
Example: a background agent dispatched by a director session is not in director mode.
In code: today the hardened director agent and the director profile also apply the limits; both are being made optional.

### Director profile

The server tool profile served at `/mcp/director`: the director's altitude-limited tool set (reads of the board, the decision inbox and rollups), with no read that returns a note or document body. Calling it does not make a caller a director. Decision authority lives in the decision's class and the credential, not the profile. It stays served, for other platforms and deployments. Today director mode still holds it for the decision ops, `pause`, `resume` and `reissue`, which are served only here; once [0003](design/decisions/0003-role-neutral-decisions-surface.md) puts decision ops on every profile that needs them, with authority from the decision's class plus a credential's `decide` capability, director mode no longer depends on it.

Distinct from: director mode, which is a session state, and the director layer.
In code: `ToolProfile.DIRECTOR`.

### Orchestrator placement

The session or process that hosts the orchestrator of a run, numbered as tiers 1, 2, 2b, 3 and 4: the user's desktop session, a background agent plus Workflow, a headless process, and so on.

Distinct from: sizing tier. Sizing decides that work goes to a run; placement decides what hosts that run's orchestrator.
Don't call it: coordinator tier.
In code: `TIERS` (`scripts/coordinator-tiers.mjs`, `skills/director/references/coordinator-tiers.md`).

### Steering

How much orchestration behaviour the plugin hooks put into a session:
- `workflow`, the default: sizing tiers, the model-effort table and dispatch rules.
- `schema`: the item's resolved schema alone drives the process.
- `off`: the hooks stay silent.

It is project config and independent of the session mode.

Distinct from: session mode, which says which layer the user's session plays.
Don't call it: orchestration mode (the session mode).
In code: `extensions.claude-code.steering`; the old key `extensions.claude-code.orchestration.mode` is read as a deprecated alias through 4.x.

## Decisions

### Decision

A server record raised when work needs a choice it should not make alone. It holds a question, options, and usually a recommended option, and it belongs to an item, a run or a root. Its answer records the chosen option, the rationale and who decided. An answer never moves the item: the orchestrator resumes it.

Distinct from: an item that builds nothing, planned in advance to get something decided; settling one usually means raising a decision. Also distinct from ruling, the outcome recorded on the work.
In code: `decisions_write` (`raise`, `ack`, `delegate`, `escalate`, `answer`, `withdraw`) and `decisions_read` `list`, served on every profile that needs them. The `runs_write` decision ops and the `runs_read` `decisions` and `decision` selects are deprecated aliases, removed in 5.0.

### Decision inbox

The open decisions in a scope: an item, a subtree, a run or a project. It is the same inbox whether the user or the director works through it.

Don't call it: the director's inbox.
In code: `decisions_read` `list`. It returns full records, filtered by `itemId`, `ancestorId`, `runId`, `rootId`, `decisionIds`, `status`, `class` or `severity`. Each class policy appears once per response, and `context` is returned only with `includeContext: true`.

### Answer

Choosing one option on a decision, together with the rationale and who decided: the user, whose own words are carried verbatim, or the director acting autonomously. The authority to answer comes from the decision's class and the caller's credential, never from the role or profile making the call.

Distinct from: acknowledging a decision (`ack`), which records that it was seen and settles nothing.
In code: `decisions_write` `answer` (director profile only); `runs_write` `answer` is a deprecated alias.

### Decision class

The named kind a decision is raised under. It sets who may answer it (`answerers`), its default severity and when it becomes overdue. A class is project config, and it decides whether the director may answer a decision or must escalate it.

`answerers` has three values:
- `human-only`: only the user.
- `director-or-human`: the user or the director.
- `any-actor`: anyone, seats included.

The server tests the caller's credential, never its role.

In code: `decision_classes:`, `DecisionRequestAnswerers`. `director-or-human` admits a human, or a seat-less caller whose credential grants decide (`DecisionCallers.of`). A class also carries `autonomy` (`answer` or `ask`), the director's own answer-or-ask posture, recorded as `withinAutonomy`.

### Escalation

Handing a decision to the user because the director may not or should not settle it. That happens when:
- its class is human-only or marked for the user;
- the director has no grounded answer: there is no recommended option, or the evidence favours no option;
- the answer would contradict an earlier ruling;
- the decision is high severity or overdue.

How it relates to `on_expiry: escalate`: the server only marks an overdue decision for escalation by raising its reported severity. Because an overdue decision meets the criteria above, the director then hands it to the user. In orchestration mode the user already decides, so the raised severity only orders the inbox.

### Ruling

A settled choice recorded on the work it governs: the option taken and the reason, written where the next seat reads it.

Distinct from: decision, the server record that asked; and from a review verdict, which judges finished work rather than settling a choice.
Example: after a user answers a decision on a red test, the orchestrator's re-dispatch carries the ruling, and the test author's next pass follows it.

## Readiness and acceptance

These concepts come from the user-journey work ([design/](design/README.md)) and are not yet in code. Each entry fixes the word before the code that introduces it. Method words such as epic, story, charter or increment are not Task Orchestrator concepts: a project's schema may name its item types and notes that way, and the tool reads them only as types and notes.

### Building item

An item whose work a build seat does, which is the default for a type with a lifecycle. An item marked to build nothing, such as a question to be ruled or a container, is never handed to a build seat by `next`; its `blocks` edges hold back the items that wait on it.

Distinct from: a lifecycle-free item, which is off this axis because it never transitions. An item that builds nothing still moves through its phases.
In code: `builds`, set on a schema or trait (`WorkItemSchema.builds`) and pinned on the item (`SchemaPin.builds`, column `work_items.builds`).

### Refined

An item whose required queue-phase notes are all filled, so the queue gate lets it start. A refined item can still be blocked. The server computes it from the item's schema, whatever the schema calls the item or its notes.

Distinct from: ready, which in run-wave and `next` means unblocked and claimable.
Don't call it: ready.
In code: none yet (proposed `refined`, which `next` filters on).

### Acceptance

The user's verdict that an item delivers what was asked, judged against its own notes and the rulings on it, after which it is shipped or sent back. An item at any depth of the tree can be accepted.

Distinct from: review, a seat's independent check of the work against its plan. Review comes before acceptance and does not replace it.
In code: none yet.

## Sizing

### Sizing tier

One of Direct, Delegated or Parallel: how much machinery a piece of work gets.
- Direct: done inline by the orchestrator.
- Delegated: one implementer subagent, with review by a separate agent.
- Parallel: worktree agents run as a wave.

Distinct from: orchestrator placement, which says what hosts the orchestrator of a run, and from the session layers, which say who is acting.
In code: `TIER_NAMES`.

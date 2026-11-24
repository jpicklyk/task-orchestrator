# Task Orchestrator

Work items carry notes; an item whose type has a lifecycle moves through gated phases, and a lifecycle-free item never transitions. Seats are the positions whose agents fill each phase's notes. Above the seats, two session layers act: the orchestrator drives the work, and the director decides on the user's behalf. In short: seats do the work, the orchestrator drives it, and the user decides, or the director decides for them. These terms name the concepts that code, schemas and reviews share.

## Items and links

### Lifecycle-free item

An item whose type's schema sets `lifecycle: none`: it has no role and never transitions, so no phase, gate, seat or cascade applies to it. A knowledge-base entry is the usual case.

Distinct from: an item that builds nothing, which still moves through its phases; and a `permanent` item, which has a role but is never cascaded to terminal.
In code: `LifecycleMode.NONE` (a NULL `role`).

### Link

A recorded pointer from an item, note or document to another that orders nothing: a `[[...]]` reference parsed from a body, an attachment, an adoption, or a note's `derived_from` plan.

Distinct from: a dependency edge (`blocks` or `relates_to`), which `deps_write` records between items. A `relates_to` edge is a dependency edge, not a link.
In code: `Link`, `LinkRelation` (`references`, `attached`, `adopted_by`, `derived_from`).

## Seats and notes

### Seat

A named position in an item's workflow, declared in a schema for one phase, that one dispatched agent fills. The one exception is the orchestrator seat, which the orchestrator fills itself. A seat may not be named after the director, which never writes notes.

Distinct from: role. A role is the phase a note belongs to; a seat is the position whose agent fills it.
In code: `SeatDefinition`. The reserved names are `unowned` (refused) and `orchestrator` (given meaning by the server).

### Seat-aware schema

A schema that declares at least one seat.

Don't call it: seated schema.
In code: `WorkItemSchema.isSeatAware()`.

### Owning seat

The seat a note belongs to: the seat its schema entry names, when that seat is declared for the note's own phase, or is the orchestrator seat declared in any phase. In a schema that is not seat-aware, the named seat stands as written. A note with no owning seat is unowned.

Distinct from: note owner. The note owner is the principal whose write created or adopted the note, kept on later writes; it is not the identity that last wrote it.
Don't call it: seat ownership.
In code: `OwningSeat`, `WorkItemSchema.owningSeatOf()`.

### Orchestrator seat

The seat named `orchestrator`, a reserved name: the position for notes the orchestrator writes itself, rather than a seat's agent. Inside a run the orchestrator writes them with the run's token; outside a run it writes them directly. It owns the notes labelled for it in any phase it is declared, not only its own. No agent is ever dispatched to it, so it is never a stage of a run.

Distinct from: the orchestrator, the session layer that writes this seat's notes. The seat is a position in a schema; the orchestrator is who acts.
In code: `SeatDefinition.ORCHESTRATOR_WRITABLE_SEAT`. The run planner skips it as a stage (`run-planner-lib.mjs`).
Example: `session-tracking` declared with `seat: orchestrator` is written by whoever drives the item. On a run, that is the run's orchestrator; for a hand-dispatched item, it is the user's session.

## Session layers

### Session layer

One of the two roles a Claude Code session or agent plays above the seats: orchestrator, which drives the work, or director, which decides on the user's behalf. A layer says who is acting; the server records no layer, only the profile, credential and token each one presents.

Distinct from: seat, a position in one item's workflow, and session mode, which says which layer the user's own session is playing. A background agent can play a layer without being anyone's session mode.

### Orchestrator

The layer that drives work forward. It plans and sizes work, dispatches seats, advances phases, resumes an item once its decision is answered, and carries decisions up. It never answers a decision. Its scope is either ad hoc items or one run; for a run it holds that run's token, which is recorded on the run as a run-scoped pseudo-seat.

Distinct from: director, which decides and never drives. The user's session is an orchestrator in orchestration mode, but a background agent handed a run is also an orchestrator, of that run.
Don't call it: coordinator, run coordinator, lead. ("Coordinator" was the name for an orchestrator scoped to one run.)
In code: the run-scoped form is still named for the old term: `RunSeat.COORDINATOR`, `RunPermissions`, the `coordinatorToken` field, the `coordinator_seen_at` column.
Example: run-wave hands a wave to a background agent. That agent is the run's orchestrator: it assigns seats, advances members and resumes items. The user's own session stays the orchestrator of everything outside that run.

### Director

The layer that decides on the user's behalf, within the authority each decision class gives it. It works through the decision inbox, sends a background agent when an answer needs evidence and reads only that agent's summary, and escalates what it may not settle. It acts on the board at the user's instruction; it does not own the board. It reads only ids, counts, one decision in full at a time, and run rollups. It never drives work: it hands work to orchestrators.

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
In code: the work-summary and director skills render it today. Its reads are `runs_read` `frontier`, served on both profiles, and `runs_read` `decisions` and `report` `rollup`, served today on the director profile only.

### Director mode

The session mode in which the user's session plays the director, entered with the director skill. It runs on the same connection and tools as the orchestrator; the `director-guard` hook alone enforces altitude, denying body reads and edits and allowing read-only scripts and background dispatch. The director offloads research to background agents and answers from their concise result.

Distinct from: director profile, which is a server endpoint. The bare word "director" names the layer, this mode and the profile, so where the context does not settle it, say which.
Example: a background agent dispatched by a director session is not in director mode.
In code: today the hardened director agent and the director profile also apply the limits; both are being made optional.

### Director profile

The server tool profile served at `/mcp/director`: the director's altitude-limited tool set (reads of the board, the decision inbox and rollups), with no read that returns a note or document body. Calling it does not make a caller a director, and it is not where decision authority lives. It stays served, for other platforms and deployments, but director mode no longer depends on it.

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
In code: today `extensions.claude-code.orchestration.mode`. To be renamed `extensions.claude-code.steering`, with the old key still read as a deprecated alias.

## Decisions

### Decision

A server record raised when work needs a choice it should not make alone. It holds a question, options, and usually a recommended option, and it belongs to an item, a run or a root. Its answer records the chosen option, the rationale and who decided. An answer never moves the item: the orchestrator resumes it.

Distinct from: an item that builds nothing, planned in advance to get something decided; settling one usually means raising a decision. Also distinct from ruling, the outcome recorded on the work.
In code: today `runs_write` `raise`, `answer`, `ack` and `withdraw`, and `runs_read` `decisions` and `decision`. Proposed: `decisions_write` and `decisions_read`, served on every profile that needs them.

### Decision inbox

The open decisions in a scope: an item, a subtree, a run or a project. It is the same inbox whether the user or the director works through it.

Don't call it: the director's inbox.
In code: proposed `decisions_read` `list`. It returns full records, filtered by `itemId`, `ancestorId`, `runId`, `rootId`, `decisionIds`, `status`, `class` or `severity`. Each class policy appears once per response, and `context` is returned only with `includeContext: true`.

### Answer

Choosing one option on a decision, together with the rationale and who decided: the user, whose own words are carried verbatim, or the director acting autonomously. The authority to answer comes from the decision's class and the caller's credential, never from the role or profile making the call.

Distinct from: acknowledging a decision (`ack`), which records that it was seen and settles nothing.
In code: proposed `decisions_write` `answer`; today `runs_write` `answer` (director profile only).

### Decision class

The named kind a decision is raised under. It sets who may answer it (`answerers`), its default severity and when it becomes overdue. A class is project config, and it decides whether the director may answer a decision or must escalate it.

`answerers` has three values:
- `human-only`: only the user.
- `director-or-human`: the user or the director.
- `any-actor`: anyone, seats included.

The server tests the caller's credential, never its role.

In code: `decision_classes:`, `DecisionRequestAnswerers`. Today `director-or-human` is tested by "served on the director profile without a seat token" (`DecisionCommands.kt`). Once decisions are role-neutral, it becomes a human, or a caller without a seat token whose credential carries the answer capability.

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
In code: none yet (proposed `builds`, set on a schema or trait and pinned on the item).

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

# Task Orchestrator

Work items move through gated phases; seats are the positions whose agents fill each phase's notes. Above the seats, two session layers act: the orchestrator drives the work, and the director decides on the user's behalf. In short: seats do the work, the orchestrator drives it, and the user decides, or the director decides for them. These terms name the concepts that code, schemas and reviews share.

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
In code: `SeatDefinition.COORDINATOR_WRITABLE_SEAT` (to be renamed `ORCHESTRATOR_WRITABLE_SEAT`). The run planner skips it as a stage (`run-planner-lib.mjs`).
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

The layer that decides on the user's behalf, within the authority each decision class gives it. It works through the decision inbox, sends a background agent when an answer needs evidence and reads only that agent's summary, and escalates what it may not settle. It watches the board across runs. It reads only ids, counts, one decision in full at a time, and run rollups. It never drives work: it hands work to orchestrators.

Distinct from: orchestrator, which drives and never decides. The director is also distinct from the director profile, its tool set, and from director mode, the session mode in which the user's own session plays this layer.

### Session mode

The layer the user's own session plays, which also says who decides:
- **Orchestration mode** (the default): the session is the orchestrator and the user answers the decisions that reach it. An open run in the session shows as a run indicator, not a separate mode.
- **Director mode**: the session is the director, orchestrators work in the background, and the user hears only escalations.

Distinct from: the `orchestration.mode` config key (`workflow`, `schema`, `off`), which sets how strongly the orchestration hooks steer a session whatever its session mode.
Don't call it: coordinator mode (an open run in orchestration mode), orchestrator mode.
In code: the `orchestration-mode` mod's footer labels.

### Director mode

The session mode in which the user's session plays the director, entered with the director skill or by running as the hardened director agent. A guard hook keeps the session from reading files, editing, or dispatching in the foreground.

Distinct from: director profile, which is a server endpoint. The bare word "director" names the layer, this mode and the profile, so where the context does not settle it, say which.
Example: a background agent dispatched by a director session is not in director mode.

### Director profile

The server tool profile served at `/mcp/director`: the director's altitude-limited tool set (the run board, the decision inbox and rollups), with no read that returns a note or document body. Calling it does not make a caller a director, and it is not where decision authority lives.

Distinct from: director mode, which is a session state, and the director layer.
In code: `ToolProfile.DIRECTOR`.

### Orchestrator placement

The session or process that hosts the orchestrator of a run, numbered as tiers 1, 2, 2b, 3 and 4: the user's desktop session, a background agent plus Workflow, a headless process, and so on.

Distinct from: sizing tier. Sizing decides that work goes to a run; placement decides what hosts that run's orchestrator.
Don't call it: coordinator tier.
In code: `TIERS` (`scripts/coordinator-tiers.mjs`, `skills/director/references/coordinator-tiers.md`).

## Decisions

### Decision

A server record raised when work needs a choice it should not make alone. It holds a question, options, and usually a recommended option, and it belongs to an item, a run or a root. Its answer records the chosen option, the rationale and who decided. An answer never moves the item: the orchestrator resumes it.

Distinct from: open question, an item planned in advance to ask something; settling one usually means raising a decision. Also distinct from ruling, the outcome recorded on the work.
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

In code: `decision_classes:`.

### Escalation

Handing a decision to the user because the director may not or should not settle it. That happens when:
- its class is human-only or marked for the user;
- the director has no grounded answer: there is no recommended option, or the evidence favours no option;
- the answer would contradict an earlier ruling;
- the decision is high severity or overdue.

Distinct from: `on_expiry: escalate`, which only raises the reported severity of an overdue decision and puts nothing in front of the user.

### Ruling

A settled choice recorded on the work it governs: the option taken and the reason, written where the next seat reads it.

Distinct from: decision, the server record that asked; and from a review verdict, which judges finished work rather than settling a choice.
Example: after a user answers a decision on a red test, the orchestrator's re-dispatch carries the ruling, and the test author's next pass follows it.

## Incremental delivery

These concepts come from the user-journey work (`plans/user-journey-map.html`) and are not yet in code. Each entry fixes the word before the code that introduces it.

### Increment

A container of stories built and accepted together against one charter. Stories may be added or dropped while it is open, and it closes when the user accepts it.

Distinct from: run, one wave of seat dispatch; an increment usually spans several runs.
Don't call it: iteration (ralph's loop), cycle (the fix cycle), sprint.
In code: none yet.

### Story

A piece of an increment that can be built, reviewed and accepted on its own, cutting through every layer of the code it touches.

Distinct from: open question, which decides something and builds nothing.
Don't call it: slice (the codebase audit and the slice-first server layout both use that word).
In code: none yet.

### Refined

A story's state when every queue-phase note its schema requires is filled, so the queue gate lets it start. A refined story can still be blocked.

Distinct from: ready, which in run-wave and `next` means unblocked and claimable.
Don't call it: ready.

### Charter

The goal, bounds and non-goals of an increment. It is written before the stories are shaped and checked again at acceptance.

Distinct from: plan document. An adopted plan is frozen; a charter can be revised while the increment is open.
In code: none yet (proposed as a queue note).

### Open question

An item that asks one thing to be decided, planned in advance. Its `blocks` edges run to the stories that cannot start until it is ruled. A shared question blocks two or more stories. Shared questions and irreversible choices are the only things settled before building starts.

Distinct from: decision. Settling an open question usually raises a decision; a decision can also be raised in the middle of work with no open question behind it.
In code: none yet.

### Acceptance

The user's verdict that an item or an increment delivers what was asked, judged against its charter and rulings, and either shipped or sent back.

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

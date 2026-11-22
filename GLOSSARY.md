# Task Orchestrator

Work items move through gated phases; seats are the agents that fill each phase's notes. These terms name the concepts that code, schemas and reviews share.

## Seat

A named position in an item's workflow, declared in a schema for one phase, that one agent fills.

Distinct from: role. A role is the phase a note belongs to; a seat is who fills it.
Don't call it: agent.
In code: `SeatDefinition`.

## Seat-aware schema

A schema that declares at least one seat.

Don't call it: seated schema.
In code: `WorkItemSchema.isSeatAware()`.

## Owning seat

The seat a note belongs to: the seat its schema entry names, when that seat is declared for the note's own phase, or is the orchestrator seat declared in any phase. In a schema that is not seat-aware, the named seat stands as written. A note with no owning seat is unowned.

Distinct from: note owner. The note owner is the principal whose write created or adopted the note, kept on later writes; it is not the identity that last wrote it.
Don't call it: seat ownership.
In code: `OwningSeat`, `WorkItemSchema.owningSeatOf()`.

## Orchestrator seat

The seat named `orchestrator`, the one seat the server gives meaning to: a run's coordinator token may write its notes. It owns the notes labelled for it in any phase it is declared, not only its own.

Distinct from: coordinator. The coordinator is the holder of a run's token; the orchestrator seat is a position in a schema.
In code: `SeatDefinition.COORDINATOR_WRITABLE_SEAT`.

## Coordinator

Whoever presents a run's coordinator token: it assigns seats, advances members and reports for that one run, and is not a seat.

Distinct from: orchestrator seat, and coordinator tier. The tier says where a coordinator runs, not who it is.
In code: `RunSeat.COORDINATOR`.

## Director profile

The server tool profile served at `/mcp/director`: the run board and decision inbox, with no read that returns a note or document body.

Distinct from: director mode, which is a session state, not an endpoint.
In code: `ToolProfile.DIRECTOR`.

## Director mode

A session state in which the session runs the board and delegates every read or edit to background agents, entered with the director skill.

Distinct from: director profile, which is a server endpoint.
Example: a background agent dispatched by a director session is not in director mode.

## Sizing tier

One of Direct, Delegated or Parallel: how much machinery a piece of work gets (inline, one seat, or a coordinated wave).

Distinct from: coordinator tier, which is where a coordinator runs, and from the orchestrator, coordinator and director session layers, which are who is acting.

## Coordinator tier

One of tiers 1, 2, 2b, 3 and 4: where a run's coordinator lives (a desktop session, a background agent plus Workflow, a headless process, and so on).

Distinct from: sizing tier. Parallel work is run by a coordinator, and the coordinator tier says where that coordinator lives.
In code: `claude-plugins/task-orchestrator/scripts/coordinator-tiers.mjs`.

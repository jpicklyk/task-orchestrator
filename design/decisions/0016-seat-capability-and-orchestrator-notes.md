# 0016 Seats declare `writes`, the protocol rule follows `enters` and `writes`, and the orchestrator is never a seat

**Status:** Accepted | **Date:** 2026-11-26 | **Source:** Boundary analysis 2026-11-26, owner answers (plans/boundaries/README.md, not tracked), decisions 2, 3, 4, 5, 6, 7, 8 and N-b as revised after the simplicity review; supersedes the 2026-11-23 ruling "keep the `orchestrator` seat" in [glossary-alignment.md](../glossary-alignment.md) (section S, and row S2)

**Amended 2026-11-26:** the server side is advertised by one feature string per facet (`seatWrites`, `orchestratorNotes`), not a single flag covering the whole change set. Why: the facets land in separate changes, and a plugin that reads one string for all of them cannot tell which behaviour the server has.

## Decision

A seat entry gains an optional `writes` (default true in the work phase, false elsewhere), and the plugin chooses a seat's protocol rule from the served `enters` and `writes` only: `protocol.entry-seat` when it enters, else `protocol.in-phase-seat` when it writes, else `protocol.read-only-agent`. The dispatch lists the seat's effective triggers and every protocol rule says to send only those. Rule and skill defaults are keyed by seat name, and a seat whose name has no defaults row gets none.

The orchestrator is never a seat. `orchestrator` is a reserved note owner valid in every schema (a note naming it is an orchestrator note); a seat named `orchestrator` or `coordinator` warns for one release and is then refused; the server serves each note entry's computed owner in `items_read` `schema`. The run token is the server's only orchestrator identity and is described as a token, never a seat.

## Context

Pass 7 of the boundary analysis found that the plugin decides a seat's write capability from its phase (`claude-plugins/task-orchestrator/scripts/run-planner-lib.mjs:874`) and its protocol from a role it derives from name, phase and position (`claude-plugins/task-orchestrator/hooks/lib/seat-rules.mjs:58-90`). The server's own seat facts do not reach that choice: an entering seat other than the implementer is told never to start, a declared `transitions` list is contradicted by the protocol text, and a review seat can never commit. A declared capability fixes all three with one fact the server already sits next to.

The same pass, and the simplicity review after it, found the orchestrator modelled as a seat twice: a reserved schema seat that must be declared with a phase that does nothing (`.taskorchestrator/config.yaml:61`, `:86`) and is never dispatched or staged, and a run-scoped `run_seats` row holding the run token that six read sites must filter out. Keeping the seat meant adding an implicit seat, an exclusion from seat-awareness, an `assign` refusal, a one-release honouring of the declared entry and a token-borrowing rule for entry skills; most of the special cases the target added existed only to keep a seat that is not one. All the seat saved was one line in `OwningSeat` (`current/src/main/kotlin/io/github/jpicklyk/mcptask/current/model/note/OwningSeat.kt:26`) and one in `NoteSeatRule` (`.../note/internal/domain/NoteSeatRule.kt:61`). With the orchestrator out of the seats, the word "seat" means one thing: a declared, dispatchable position.

The 2026-11-23 glossary-alignment ruling kept the `orchestrator` seat because its name says who writes its notes. That reason survives as the reserved note owner; the seat does not.

## Alternatives rejected

- **A `kind` field** keying rule, skill and dispatch defaults, with the seat name as fallback (pass 7's recommendation). No project has a seat whose name is not its kind, and a declared `rules:`, `skills:` and dispatch override already replace every default `kind` would select. It would add a second name for every seat. Held, not refused: see Reopen when.
- **A single declared `role`** naming a row of the rule table. The protocol would still come from a table row, so `enters` and the row could disagree, and every combination (a writing reviewer, an entering test author) would need its own row.
- **The implicit orchestrator seat** (the first answer to decision 2): treat `orchestrator` as declared in every seat-aware schema, honour a declared entry for a release, keep it out of seat-awareness and refuse it in `assign`. It keeps a seat that is never filled and needs more special cases than the one line it saves.
- **The reserved seat entry carrying the orchestrator's `rules` and `skills`** (a phase-less `orchestrator` seat). It adds five name-scoped exceptions to one record type, would switch seat enforcement on for a seat-less type (`.../model/schema/WorkItemSchema.kt:43`), and binds nothing before an item exists. The binding went to its own block instead ([0017](0017-orchestrator-binding.md)).
- **A server rule for entry skills on an open-run member** (the first answer to decision 7: run as the run's orchestrator, with a new refusal code). The server already refuses correctly (`.../lifecycle/TransitionPolicy.kt:122-124`, `NoteSeatRule.kt:55-63`); what is missing is skill mechanics.
- **Inferring `writes` from note purposes.** A seat that commits code need not own a note of any purpose, and it would tie capability to note vocabulary.

## Consequences

- The planner seat, which declares no `writes`, moves from `protocol.in-phase-seat` to `protocol.read-only-agent` (decision 4). Every writing seat also gets `protocol.commit`; the two rule classes that leaves are recorded in [0018](0018-content-placement.md).
- A seat whose name has no defaults row gets no rule or skill defaults and a lint advisory; its dispatch class keeps the phase fallback (decision 6). Review lanes and ralph take seat names from the declared seats.
- `assign` needs no new refusal for `orchestrator` (decision 8): only declared names, or the default seats on a seat-less item, are assignable, and no seat of that name can be declared. The existing refusal of the stored `coordinator` value stays.
- The accept skill acting on an open-run member uses the run token from the run files or hands the step to that run's orchestrator; `seat_forbidden` and `note_owned_by_other` gain a `fixArgs` hint naming the run (decision 7). Who may answer the acceptance decision still follows [0003](0003-role-neutral-decisions-surface.md).
- Behaviour change, advertised by one feature string per facet, `seatWrites` and `orchestratorNotes` (P9 in [architecture-breakdown.md](../architecture-breakdown.md)): in a seat-aware schema with no declared `orchestrator` entry, `seat: orchestrator` notes go from unowned to run-token-only inside open runs. No migration. The wire `seat: orchestrator` on note entries, `orchestratorToken` and the stored `coordinator` value are unchanged ([0005](0005-renames-with-deprecated-aliases.md)).
- The dogfood config drops its two phased `orchestrator` seat entries. The glossary replaces "Orchestrator seat" with "Orchestrator note", adds "Run token" and "Writing seat", and drops the Seat entry's exception, when the code lands. [0001](0001-two-session-layers.md)'s reserved-name statement stays true; [0012](0012-acceptance-gate-note-form.md)'s "who writes it" reads as an orchestrator note.
- The design is in [seat-model.md](../seat-model.md).

## Reopen when

A project asks for one seat name to inherit another's defaults (reopens `kind`); a seat needs a capability that `enters`, `writes` and `transitions` cannot express; or an entry skill on an open-run member cannot reach the run token or the run's orchestrator in practice.

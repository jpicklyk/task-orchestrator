# protocol.entry-seat

You are the entry seat for this item's current phase: the seat that starts the phase, or the sole
agent assigned when no seat is named. This rule governs when you may transition the item and what
you owe once you do.

1. **Follow the dispatch first.** If your dispatch names a seat (for example "implementer",
   "reviewer", or another named seat), that seat's own transition rule governs and takes
   precedence over the generic rule below.
2. **Enter the phase once.** As the entry seat, request the phase transition exactly once (the
   orchestration platform's "start" action for this item), then do the work and fill the notes
   this phase requires. Never repeat the transition request and never request the "complete"
   transition yourself -- the orchestrator owns every later transition on this item.
3. **Read the response.** Every transition failure carries a distinct error code. Branch on which
   code you received; never assume success just because no error text was echoed back.
4. **Already in phase.** If the transition fails because the item is already in your phase, do not
   retry it -- ask for the item's current context/guidance instead and proceed with the work using
   that.
5. **A contended shared resource** (a transient "unavailable" failure) is not the same as
   already-in-phase and must not be retried. Stop immediately, report which resource is contended,
   and wait -- do not proceed as though you had entered the phase.
6. **Any other failure** (item not found, ambiguous id, dependency unmet, gate blocked, invalid
   request, invalid transition, forbidden, token rejected, or any other error code the server
   returns) means you did not enter the phase. Stop immediately and report the code and message --
   do not proceed and do not retry the same request.
7. **If you stop with required notes unfilled**, you may be sent back with the missing keys named.
   Fill them, or state plainly what blocks you -- do not fabricate content to satisfy the check.

## claude:

- **Read the role before you start.** Call `items_read` with `select` `context` for your item
  first. Send the transition only when the context shows the role that comes just before your
  phase (queue, for a seat that enters the work phase). A `start` moves an item forward from every
  non-terminal role (work to review or terminal, review to terminal), so a second `start` can
  advance the item past your phase instead of failing. Already in your phase is therefore read
  from the role, never probed by sending `start` again. In that case take the guidance from the
  context and begin the work.
- **Send it once.** `advance` `transition` with `trigger` `start`, one element, never repeated:

```to-call
{"tool": "advance", "op": "transition", "args": {"itemId": "<your-item-UUID>", "trigger": "start"}}
```

- **Read the outcome from `data.results[0]`.** A call-level `partial_failure` only means look at
  each element: the call is not atomic unless you ask, so the element under `data.results[0]`
  holds the success or the `error`. A successful element that carries `paused` means the run was
  paused: stop further work and report its `decisionId`.
- **Branch on the code in `data.results[0].error`.** The transition errors in the generated
  catalog, by kind:
  - `resource_unavailable` is transient but not retried by you: stop, report the contended
    resources from the error detail, and wait.
  - `run_paused` is permanent: stop and report the pause decision.
  - `unauthenticated` means the seat token was rejected and your seat is no longer valid: halt
    and write nothing further.
  - `gate_blocked` means a required note is missing: report the missing notes.
  - `seat_forbidden`, `forbidden`, `not_claim_holder`, `dependency_unmet`, `invalid_transition`,
    `invalid_request`, `unknown_parameter`, `not_found`, `ambiguous_id`, `idempotency_mismatch`,
    `partial_failure`, `internal` and `unavailable`: stop and report the code and message; never
    retry the same request.
- **Seat token.** A token that your dispatch hands you (inline or as a file) belongs in the
  top-level `seatToken` field of each task-orchestrator request, reads included. Keep it out of
  your output, commits and notes.
- **Notes.** Fill notes with `notes_write` `upsert`; each response returns the next note's
  guidance. `note_owned_by_other` (it names the `ownerSeat`) is final: that note belongs to another seat
  and cannot be taken over, so log a finding instead.

# protocol.read-only-agent

You are a read-only agent on this item: you evaluate, audit, or advise, but you do not implement,
fix, or transition anything.

1. **Never request a phase transition.** Not to enter, not to complete, not for any trigger --
   the orchestrator owns every transition on items you review or audit.
2. **Never edit implementation or another seat's notes.** Your output is your own note (for
   example a review, an audit, or an assessment), never a change to the work under review or a
   backfill of a note another seat owns.
3. **Findings go in your note, not in the artifact under review.** If you find a gap -- a missing
   note, a questionable implementation choice, an unmet requirement -- record it as a finding for
   the orchestrator to route. Do not attempt to fix it yourself, even if the fix looks trivial.
4. **Stay in your lane.** If your dispatch names a seat, fill only that seat's required notes and
   leave the phase's other required notes to their own seats.
5. **Report by note, not by restating.** Your final message back is short: which item, your
   verdict or finding summary, which note keys you filled. The full evaluation lives in the note.

## claude:

- Go straight to `items_read` with `select` `context` for your item to get guidance -- never call
  `advance`, not even once, and never call `runs_write`: a read-only agent does not raise.
- A token that your dispatch hands you (inline or as a file) belongs in the top-level `seatToken`
  field of each task-orchestrator request, reads included. Keep it out of your output, commits and
  notes. The server answering `unauthenticated` means your seat is no longer valid: halt.
- Fill your note with `notes_write` `upsert`. `note_owned_by_other` is final (no takeover is possible): log it as a finding. Where a note points at a specialized evaluation framework,
  invoke that first and use its output to fill the note.

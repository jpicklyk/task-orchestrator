# protocol.in-phase-seat

You occupy a named, non-entry seat on an item already in the current phase (the orchestrator or
another seat already moved it there). This rule governs your transition behavior and note
ownership.

1. **Never enter the phase yourself.** Do not request any phase-start transition -- the item is
   already in your phase by the time you are dispatched. Go straight to reading the item's current
   context/guidance and begin your assigned work.
2. **Never request any later transition either.** Completing the phase, moving to review, or any
   other transition belongs to the orchestrator (or, where named, the entry seat) -- not to you,
   regardless of how confident you are that your part is done.
3. **Fill only your seat's notes.** The phase's full list of required notes is the phase's total
   across every seat dispatched to it, not your personal checklist. Fill only the notes your
   dispatch assigns to your seat, and leave the rest to their own seats. If a stop guard is asking
   for another seat's note, say so in one line and stop -- do not fill it on that seat's behalf,
   even if you can see what it should say.
4. **Report, don't backfill.** If another seat's required note is missing, wrong, or looks
   incomplete, record that as a finding in your own note rather than editing or creating theirs.
5. **Report by note, not by restating.** Your final message back is short: which item, what
   outcome, which note keys you filled. Do not repeat note content in the report -- the notes are
   the record.

## claude:

- Never call `advance_item` at all as a non-entry seat -- not even once to enter. Call
  `get_context(itemId=...)` for guidance instead.
- Fill notes via `manage_notes(operation="upsert", ...)`, using the note keys your dispatch prompt
  names for your seat.

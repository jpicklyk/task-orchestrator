# closure-notes

A work-phase closure note (`implementation-notes`, `session-tracking`) records only what the diff, git log and CI do not show. Anyone can read the changed files, the commit list and the test results; the note exists for the decisions and surprises that never reach them.

1. **Allowed content, `implementation-notes`.** Short sections written as inline labels, each omitted when empty: `Deviations:` (where the work departed from the plan, and why), `Decisions:` (choices the plan left open), `Not done:` (planned work left out, with the reason), `Gotchas:` (traps the next reader would hit). A conflict handed to a decision stays under a `## Deviations requested` heading.
2. **Allowed content, `session-tracking`.** One line each: `Outcome:`, `Commits:`, `Verified:`, `Deviations:`, `Friction:`, `Observations:`, `Follow-ups:`. `Commits:` is one sha or one range. `Verified: <command> green at <sha> | not run (<why>)` names what was run and the result in one line, never counts. Omit `Observations:` when there are none. While the item awaits review, `Outcome:` says so and the note carries `Review: pending - verdict in <review-note key>`; never claim a review verdict the note does not hold.
3. **Banned.** Per-file narration, commit lists beyond the one `Commits:` line, acceptance criteria restated as met, test counts or pass/fail counts, file lists.
4. **Empty forms.** When there is nothing to record, write the sanctioned sentence and nothing else; it satisfies the gate. For a child's `implementation-notes` the whole body, unlabelled, is `No deviations; nothing a reader of the diff would miss.` For a parent item it is `No cross-child decisions; each child's notes stand alone.`
5. **Parent items.** A parent's closure note holds only cross-child material under `Decisions`, `Merges`, `Carry-ins` and `Deferred`. Never re-list the children or their work.
6. **Fix cycles.** After a fix cycle rewrite the note in full so it describes the final state; do not append a second narrative.
7. **Precedence.** The schema's guidance for the note is authoritative for content. Where that guidance asks for a file list or counts, write one `Commits:` line and one `Verified:` line instead and state the conflict under Deviations.

## claude:

- Fetch a rule body with `docs_read` `get` (`kind` `rule`, `slug` `closure-notes`, `rootId`) before writing either note.

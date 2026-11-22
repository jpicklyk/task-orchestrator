I added the helper and its test. The new test file passes. The other three suites I ran, including the adoption guard, were not all clean, because the domain-boundary test fails here.

**Helper:** `unownedNoteKeys(notes, seats)` is in `claude-plugins/task-orchestrator/hooks/lib/unowned-notes.mjs`.
- It returns the keys of notes whose `seat` is missing, blank, or not among the declared seats.
- Seats can be `{name, ...}` objects or plain names.
- Notes marked `shared: true` are skipped, since the config format exempts them from the ownership check. If you want them reported, it's a one-line change.
- It returns `[]` for non-array input and ignores malformed entries. That matches how the other hook libs behave.

**Test:** `claude-plugins/task-orchestrator/hooks/tests/lib-unowned-notes.test.mjs`.

**Domain-boundary failures:** `contract-domain-boundary.test.mjs` has 5 failing tests (S1, S2, S3, S16, S17). The S1 error I inspected is `ENOENT` on `.claude/skills`, a directory that doesn't exist in this checkout. That failure looks unrelated to my change, but I did not run the suite without it to confirm. I did not look at the errors for S2, S3, S16 or S17.

I haven't committed anything.

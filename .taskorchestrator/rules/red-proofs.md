# red-proofs

This project's post-merge red-proof step, declared in `run-profile.json` `postRun` as `red-proofs`
(`when` `post-merge`). It is the decision-point budget-mutation audit that run-wave's per-item verify
defers, done once for the whole wave at the merged SHA instead of once per item against the whole
suite. The policy it carries out is rule `test-author` section 5; this rule is the procedure. The step
never gates a merge and runs in a background runner.

1. **One scratch worktree at the merged SHA:** `git worktree add --detach <unique-tmp>
   <merged base branch SHA>` (`origin/main`, or the release branch the wave targets). Mutating the
   merged state is the point: it also catches a merge round that weakened an oracle, which a pre-merge
   per-item proof cannot see. For node tests (plugin hooks and scripts) mutate a clean extraction
   instead of the worktree: `node scripts/redproof-extract.mjs <sha> <outDir> <control test file>...`
   copies the paths those tests read and runs them unmutated first; a mutation counts only after
   `control N/N` is green (exit 1 or 2 means no mutation is run on that extraction).
2. **Batch every item's budget** (`items[].redProof.shape` carried from verify, plus any fix-cycle
   amendment's named mutations; skip categories stay skipped). Apply each mutation with an
   anchored-patch script written with the Write tool, one mutation present at a time, the tree
   restored between them.
3. **Run each mutation scoped**, against the test classes the item's `test-manifest` maps to that
   scenario, using the run-profile `verify[]` entry carrying `ownedTestsPattern`. Red = the mapped
   tests failed. A survivor gets ONE whole-suite run, using the entry whose `seats` lists
   `orchestrator`, to tell "covered at another seam (name the test)" from a real gap; that is the
   only whole-suite run in this step.
4. **Triage survivors** per rule `test-author` section 5 point 7: weak test, equivalent mutant,
   unreachable, or covered at another seam. A real gap does NOT reopen the item: it becomes a carry-in
   note on the next item that touches the same seam when one is queued, else one test-only follow-up
   item per wave (`/task-orchestrator:create-item`, type by the project's convention), which the
   policy exempts from further proofs. A real gap expands the audit to the rest of that item's
   scenarios, still scoped.
5. **Record once, on the wave parent** (the feature or container the run was scoped to; the single
   item when the run had one), under `red-proof-results` (`notes_write` `upsert`, no token): one line
   per item - proofs run, red/survived, gaps, equivalents, approximate minutes, the scratch path and
   merged SHA. Reviewers of later waves read it there; nobody re-runs it.
6. **Remove the scratch worktree** (`git worktree remove --force <unique-tmp>`) and set
   `postMerge.red-proofs: "done"` in the run's local `state.json` so a resume does not repeat the step.
7. **Record the runner's meters** with `session-meters --label red-proofs:<parent-short>` as
   run-wave's Step 7a describes: append the `agent-meters=` entry, followed by ` run=<runId>`, to the
   wave parent's `red-proof-results`. The write is a token-less `upsert` of the whole body (the note
   was created token-less in point 5, so it stays writable after the close); no minutes are added.

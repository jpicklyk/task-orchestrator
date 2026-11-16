# Implement: hand-dispatching a wave seat

Read by the orchestrator inside a shared-worktree wave, before any dispatch run-wave does not generate (a wave run without run-wave, a one-off fix agent, an arbitration re-dispatch).

Under run-wave, seat prompts are generated, the planner return is schema-checked and ownership is
checked by `post-run.md` Step 1, so none of this file applies to its seats.

## Implementer dispatch template

Agents share the feature worktree. **Do NOT use `isolation: "worktree"`** on the Agent
tool — that spawns a separate worktree per dispatch (the deprecated pattern).

```
Agent(
  prompt="""
  Working directory: <feature-worktree-path>
  Branch (already checked out): feat/<feature-slug>
  Dispatch contract: <ABSOLUTE path from the contract's Header "Contract path" line, e.g.
  D:\Projects\task-orchestrator\plans\<slug>.md> — read it first; it wins on conflict with
  this prompt. It lives in the main checkout, not in the worktree above.
  Scope (modify ONLY these files): <explicit list> — the same list as your row in the
  contract's File ownership slot.
  Do NOT create or modify any file under src/test/** — test authoring is a separate,
  independent dispatch (Step 4b) on items carrying needs-test-author. If your change
  surfaces a needed test update, report it in your return; never edit the test yourself.

  Before returning: run the compile self-check exactly as the contract's "Compile self-check"
  slot pins it, and handle its outcome by that slot's rules. Then commit exactly as the
  "Commit discipline" slot states, including the post-commit verification.
  Do NOT run :current:test or :current:ktlintCheck — the orchestrator owns full build
  verification.
  """,
  description="implementer:<short> [<model>/<effort>]",
  model="<dispatch.model, else the implementation class>",
  effort="<dispatch.effort, else the implementation class>",
  subagent_type="<dispatch.agent when the item's resolved profile names one, else general-purpose>"
  // NOTE: no isolation parameter — agents share the feature worktree
)
```

`model` and `effort` follow `task-orchestrator:orchestrate` "Dispatching an item's phase owner"
(the profile of the phase dispatched INTO, else the class in `task-orchestrator:model-effort`);
`dispatch-model-guard` denies a dispatch that omits either.

**Why the prompt points at slots instead of carrying the commands.** The pinned self-check is fast
and catches type and signature errors that gradle's incremental cache can mask until the
orchestrator's later run, and its `ktlintFormat` step prevents lint fix-up commits. But the
invocation's exact argument form, the foreign-file early exit and the retry bound only work if
every agent gets them identically: a re-typed nested invocation flattens a `[string[]]` argument,
and agents without the early exit burn their retries against another agent's mid-refactor file. A
prompt that restates the command drifts from the contract; a prompt that points at the slot
cannot. The same applies to the commit step: never tell an agent to "commit your changes with a
descriptive message" in a shared worktree; the Commit discipline slot is the only safe form.

## Test-author dispatch prompt

Point the agent at the **Test author protocol** slot of the wave's dispatch contract, named by the
absolute Contract path from its Header. That slot states the declarations block, the `src/main`
tool ban, the `keys=` filter, stop-and-ask, fixture invariants, surface labels, commit form and
manifest fields in full. The declarations block is filled by a separate, non-author
declarations-extractor seat, and the orchestrator scans and strips it of implementation prose
before the author sees it (rule `declarations-extractor` states the seat's accuracy contract and
the scan). Pass `model` and `effort` from the item's `dispatchBySeat` test-author entry, and the
Agent `description` `test-author:<short>`.

A hand-dispatched planning seat gets the contract's absolute path and returns its Planning seat
return template; the field semantics are in that slot.

## SHA tracking table

Before each dispatch, capture HEAD; after the agent returns (it has committed), capture it again:

```bash
git -C <FEATURE_WORKTREE> rev-parse HEAD
git -C <FEATURE_WORKTREE> diff <pre-sha>..<post-sha> --name-only
```

The two SHAs bound what that child committed. They feed the ownership two-range check and the
contract's **Review scoping** commit map, **not** the scope of the review agent, which diffs the
child's owned files.

```
| Child UUID | Agent ID    | Pre-SHA  | Post-SHA | Test-Pre-SHA | Test-Post-SHA | Changed files | Status        |
|------------|-------------|----------|----------|--------------|---------------|---------------|---------------|
| <uuid>     | agent-g1    | abc123   | def456   | <test-pre>   | <test-post>   | <file list>   | reviewing     |
| <uuid>     | agent-g2    | def456   | ghi789   | (no needs-test-author) |     | <file list>   | done          |
```

The `Test-Pre-SHA`/`Test-Post-SHA` columns record the test author's pre/post commit range
(the wave path's Step 4b) for items carrying `needs-test-author`. They stay blank/marked "(no
needs-test-author)" for items that don't carry the trait. Their job is the disjointness check:

```bash
git -C <FEATURE_WORKTREE> diff <pre-sha>..<post-sha> --name-only           # impl range
git -C <FEATURE_WORKTREE> diff <test-pre-sha>..<test-post-sha> --name-only # author range
```

The ranges must pass rule `test-file-ownership` item 3; the per-contributor commit list serves only
the narrower check of rule `review-scoping` item 2.

A violation is reverted (`git -C <FEATURE_WORKTREE> revert` the offending commit, or a targeted
`git checkout` of the crossed-boundary file back to the prior SHA) and recorded in the child's
`orchestrator-confirmation` — do not silently keep a cross-ownership commit.

## Committing in a shared worktree

One worktree means one shared git index. Every seat commits per rule `commit-discipline`, in the
wave-specific form of the **Commit discipline** slot of
[`references/dispatch-contract-template.md`](references/dispatch-contract-template.md); dispatch
prompts point at that slot rather than restating it.

# Implement: Direct path

Read by the orchestrator on a Direct-tier item, once front door Step 1 is done; this file is the whole Direct path.

On this path the orchestrator is every seat: it edits, tests and reviews inline, and dispatches
no agent. Front-door sections are cited by name ("Base freshness", "Verification command",
"Planning rules", "Merge gate", "After merge", "Retrospective").

---

## Step 2 — Prepare the Branch and Worktree

Run front door "Base freshness" first. Then create a working branch on the main checkout:

```bash
git checkout -b <branch-name>
```

**If the main checkout is unavailable** (another branch checked out, uncommitted changes present)
**or the orchestrator itself runs from a worktree**, do not touch the occupied checkout. Create a
dedicated worktree instead:

```bash
git worktree add .claude/worktrees/<slug> -b <branch-name> origin/main
```

Work there using absolute paths and `git -C` (never `cd`); after the PR merges, remove the worktree
and delete the branch. Sync local `main` via `git fetch origin main:main` while `main` is not
checked out anywhere, or a normal `git pull` from the main checkout when it is.

Branch names: `fix/<short-description>` for a bug-fix, `chore/<short-description>` for tech debt
or refactoring.

---

## Step 3 — Queue Phase: Planning

No plan mode and no queue-phase notes. Call `advance` `transition` with `trigger: "start"`
immediately to move the item to work; the `quick-fix` schema has no required queue notes, so the
gate passes. **Exception:** a bug-fix carrying `needs-test-author` (temporal-only degraded mode,
front door Step 1) fills `test-plan` before this advance (front door "Planning rules", seat
timing). A gate or lease refusal: front door "Planning rules".

---

## Step 4 — Work Phase: Implementation

Implement directly: edit the files, then run front door "Verification command" on the branch's
checkout.

**A bug-fix carrying `needs-test-author`** runs test-first-then-fix instead of implementing
first:
(a) `test-plan` is filled at queue phase, before any implementation begins (Step 3);
(b) write the regression test from the `diagnosis` note's reproduction steps and **observe it
red against the pre-fix code**, recording the red evidence (assertion, observed value) in
`test-manifest`;
(c) implement the fix;
(d) confirm the test is green post-fix and record that confirmation too.
`test-manifest` declares the single seat. There is one seat and one commit range, so no
ownership two-range check applies.

---

### Step 4c — Build Verification and Wrap-Up

1. **Log findings as work items.** Any issue surfaced during implementation and not fixed here
   (pre-existing tech debt, related bugs) is logged via `/task-orchestrator:create-item` before
   moving on. Do not discard findings.
2. **CHANGELOG bullet**, committed with the change. A user-visible change (server behaviour, the
   MCP or REST surface, config keys, plugin skills or hooks) adds ONE bullet under `CHANGELOG.md`'s
   `## [Unreleased]` section, in its `### Added` / `### Changed` / `### Fixed` subsection (plugin
   skill and hook changes go under `### Plugin`). The bullet describes behaviour, not the diff,
   and carries no volatile counts. A docs-, process- or chore-only change adds none; its PR body
   states `Changelog: none (<why>)`. `/prepare-release` Step 8c folds these bullets into the
   release section.
3. **Fill the `session-tracking` note** (required by both `quick-fix` and `default` schemas) as a
   delta record per its schema guidance: outcome, the `Commits:` line, the `Verified:` line,
   deviations and friction (for `quick-fix`, one short paragraph on what the fix was and why that
   way, then those lines). No file list and no test counts.
4. **Live-owed check.** Before the work-exit `advance`, read `session-tracking` for its
   `**Live-owed**:` line (spec-quality, "State matrix"). Any entry not ending
   `run <date>: <result>` or `deferred: <item id>` is open and holds the item in work until it is
   run or deferred; a matrix with `live-owed` cells and no `**Live-owed**:` line counts as an open
   entry.
5. **Advance out of work:** `trigger: "start"` when the schema has a review phase, or
   `trigger: "complete"` to terminal when it has none (`quick-fix`). Step 5 reads `newRole`.

---

## Step 5 — Review Phase

- **`newRole` is `terminal`:** the schema has no review phase. Go to Step 6.
- **`newRole` is `review`:** review inline. Read the diff, verify correctness, confirm the
  Verification command passed. If the review-phase note has a `skillPointer` (in the `advance`
  result or via `items_read` `context`), invoke that skill for the evaluation framework first.
  No separate review agent: the overhead exceeds the risk for 1-2 file changes with known fixes.
  This inline, orchestrator-written review applies only outside a run (Direct never runs under
  one).

**On a bug-fix carrying `needs-test-author`**, the `test-independence-audit` covers:
- `test-plan` was frozen at queue phase, before implementation began, and the red-first
  evidence (Step 4, (b)) is recorded in `test-manifest`.
- No `assumeTrue`, `@Disabled` or other weakening was introduced after the test's first commit.
- The ownership two-range check is recorded as `N/A: temporal-only, single seat`; it cannot fail.

Verdict: `independent-degraded (temporal-only)` when every applicable check passes, never plain
`independent` (the `test-author` skill, section 11 "Degraded Modes"); `not-independent` (blocking) when one fails.

Write the review note, then advance to terminal: `advance` `transition` with
`trigger: "complete"`.

---

## Step 6 — Finalize and PR

1. Commit as front door "Merge gate" (Commit form) says, then run its CHANGELOG check and Docs
   line.
2. Push and open the PR:
   ```bash
   git push -u origin <branch-name>
   gh pr create --base main --title "<type>(<scope>): <description>" --body "$(cat <<'EOF'
   ## Summary
   <2-4 bullets>

   ## Test Results
   <test count, pass/fail, new tests>

   ## Review
   <verdict summary>

   ## Docs
   <every changed doc path from `git diff --name-only <base>...HEAD`>

   ## Changelog
   <the [Unreleased] bullet text, or none (<why>)>

   ## MCP
   <item ID>
   EOF
   )"
   ```
3. Run front door "Merge gate" (the gate), then its cleanup.
4. Advance the item to terminal only if it is not already terminal (a review-less item reached
   terminal in Step 4c, a reviewed one in Step 5); otherwise skip:
   ```to-call
   {"tool":"advance","op":"transition","args":{"itemId":"4b7d0e19","trigger":"complete"}}
   ```
5. Front door "After merge" (red-proofs, with the item as the wave parent), then
   "Retrospective".

Report the PR URL and a summary.

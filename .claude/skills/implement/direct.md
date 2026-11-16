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

No plan mode. Fill any note `items_read` `context` lists in `gateStatus.missing`, then call
`advance` `transition` with `trigger: "start"` to move the item to work. **Exception:** a bug-fix
carrying `needs-test-author` (temporal-only degraded mode, rule `trait-selection`) fills
`test-plan` before this advance (front door "Planning rules", seat timing). A gate or lease
refusal: front door "Planning rules".

---

## Step 4 — Work Phase: Implementation

Implement directly: edit the files, then run front door "Verification command" on the branch's
checkout.

**A bug-fix carrying `needs-test-author`** runs test-first-then-fix per rule `test-author`
section 5 item 1, in the Direct degraded mode (section 1, single-item exception), recording the
red and green evidence in `test-manifest`. `test-manifest` declares the single seat; no two-range
check applies.

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
3. **Fill the `session-tracking` note** per its guidance (rule `closure-notes`).
4. **Live-owed check.** Before the work-exit `advance`, apply rule `live-checks` "The Live-owed
   hold".
5. **Advance out of work:** `trigger: "start"` when the schema has a review phase, or
   `trigger: "complete"` to terminal when it has none. Step 5 reads `newRole`.

---

## Step 5 — Review Phase

- **`newRole` is `terminal`:** the schema has no review phase. Go to Step 6.
- **`newRole` is `review`:** review inline. Read the diff, verify correctness, confirm the
  Verification command passed. If the review-phase note has a `skillPointer` (in the `advance`
  result or via `items_read` `context`), invoke that skill for the evaluation framework first.
  No separate review agent: the overhead exceeds the risk for 1-2 file changes with known fixes.
  This inline, orchestrator-written review applies only outside a run (Direct never runs under
  one).

**On a bug-fix carrying `needs-test-author`**, fill `test-independence-audit` per its guidance and
`skillPointer`. Direct is temporal-only: the verdict is at best `independent-degraded`, and the
two-range check is recorded as `N/A: temporal-only, single seat`.

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

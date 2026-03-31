# Create Schema Workflow

Full interactive flow for building a new note schema. Offers starter templates before falling back to from-scratch Q&A.

---

## Step 1 — Choose Starting Point

If `$ARGUMENTS` already contains a schema name that matches a starter template (e.g., "create feature-implementation"), skip to Step 2 with that template pre-selected.

Otherwise, ask via `AskUserQuestion`:

```
◆ How would you like to create the schema?
  1. Feature implementation — specification gate (queue), implementation evidence
     + session tracking (work), review checklist (review) — 4 notes
  2. Bug fix — diagnosis gate (queue), implementation evidence + session tracking (work),
     review checklist (review) — 4 notes
  3. Start from scratch — answer questions to build a custom schema
```

---

## Step 2 — Template Path (options 1 or 2)

### Feature Implementation Template

Show this schema to the user:

```yaml
feature-implementation:
  - key: specification
    role: queue
    required: true
    description: "Problem statement, approach, and pre-work plan."
    guidance: "This note is read by the implementation agent before writing code. Cover: **Problem statement** — what breaks or is missing, who is affected. **Acceptance criteria** — 2-5 concrete, testable criteria that define done. **Alternatives considered** — min 2 real options plus 'do nothing'; for each, state the trade-off that ruled it out (not strawman rejections). **Non-goals** — at least one thing a reader might expect this work to include that is deliberately excluded. **Blast radius** — every file, module, and interface the change touches; trace downstream consumers. **Risk flags** — the 1-2 things most likely to go wrong. **Test strategy** — name specific scenarios for happy paths, failure paths, and edge cases; 'add tests' is not a strategy."
  - key: implementation-notes
    role: work
    required: true
    description: "Context handoff for downstream agents — deviations, surprises, decisions."
    guidance: "This note is read by review agents and dependent tasks. They need to know: what decisions were made that weren't in the specification, which files were changed and why, any API or interface surprises encountered, assumptions from the specification that turned out wrong, and patterns discovered that affect dependent work."
  - key: session-tracking
    role: work
    required: true
    description: "Session context — what was done, how it went, anything the retrospective should know."
    guidance: "This note feeds retrospective analysis. Structure: **Outcome**: success | partial | failure. **Files changed**: list with one-line rationale each. **Deviations**: anything that differed from the specification. **Friction**: tool errors, unexpected roundtrips, API confusion — include type (optimization/friction/bug/missing-capability) and description. **Test results**: pass/fail counts, new tests added."
  - key: review-checklist
    role: review
    required: true
    description: "Quality gate — plan alignment, test quality, simplification, verdict."
    guidance: "Run the test suite first — record total count and pass/fail. Then verify: (1) **Plan alignment** — walk each acceptance criterion from the specification and identify the code that satisfies it; flag criteria with no implementation and unplanned changes with no justification. (2) **Test quality** — map tests to the specification's test strategy; watch for tautological assertions, mock-heavy tests that verify nothing real, happy-path-only coverage when failure paths were required, and overly broad assertions like 'result != null'. (3) **Simplification** — note any unnecessary complexity, duplication, or over-engineering in changed files (do not apply fixes, only report). End with a verdict: **Pass** | **Fail — blocking issues** (list each) | **Pass with observations**."
```

### Bug Fix Template

Show this schema to the user:

```yaml
bug-fix:
  - key: diagnosis
    role: queue
    required: true
    description: "Reproduction, root cause, fix approach, and test strategy."
    guidance: "This note is read by the implementation agent before writing code. Cover: **Reproduction steps** — exact inputs (tool call, parameters, or user action) plus expected output and actual output. **Root cause** — the specific file, function, and condition that causes the bug; state why that code path produces the wrong result. **Fix approach** — your chosen approach plus min 2 alternatives (including 'do nothing'); for each, state the trade-off that ruled it out. **Blast radius** — files and interfaces the fix touches; similar code paths that may have the same defect. **Test strategy** — a regression test that would have caught the original bug, plus edge cases around the fix boundary."
  - key: implementation-notes
    role: work
    required: true
    description: "Context handoff — what changed, deviations from diagnosis, patterns to apply."
    guidance: "This note is read by review agents and dependent tasks. State: which files changed and what the specific change was, whether the root cause matched the diagnosis or differed (and how), patterns found in similar code paths that should be applied elsewhere, and edge cases discovered during implementation that weren't in the diagnosis."
  - key: session-tracking
    role: work
    required: true
    description: "Session context — what was done, how it went, anything the retrospective should know."
    guidance: "This note feeds retrospective analysis. Structure: **Outcome**: success | partial | failure. **Files changed**: list with one-line rationale each. **Deviations**: anything that differed from the diagnosis. **Friction**: tool errors, unexpected roundtrips, API confusion — include type (optimization/friction/bug/missing-capability) and description. **Test results**: pass/fail counts, new tests added."
  - key: review-checklist
    role: review
    required: true
    description: "Quality gate — fix alignment, regression coverage, simplification, verdict."
    guidance: "Run the test suite first — record total count and pass/fail. Then verify: (1) **Fix alignment** — confirm the fix addresses the diagnosed root cause and does not merely mask symptoms; check implementation-notes for any deviation from the diagnosis. (2) **Regression test** — a test must exist that would have caught the original bug; verify it tests the actual failure condition, not just 'result != null'. (3) **Edge case coverage** — verify tests exist for edge cases named in the diagnosis test strategy. (4) **Simplification** — note any unnecessary complexity in changed files (do not apply fixes, only report). End with a verdict: **Pass** | **Fail — blocking issues** (list each) | **Pass with observations**."
```

### After showing the template

Ask via `AskUserQuestion`:

```
◆ This is the <template-name> schema. What would you like to do?
  1. Use as-is — write it to config
  2. Customize first — add, remove, or modify notes before writing
  3. Cancel — go back
```

- **Use as-is:** Skip to Write Config (Step 4).
- **Customize:** Show each note in a numbered list. Ask what to change (add a note, remove a note, edit description/guidance, toggle required). Apply changes, show the updated YAML, confirm, then proceed to Write Config (Step 4).
- **Cancel:** Return to Step 1.

---

## Step 3 — From-Scratch Path (option 3)

Ask the user the following questions (use `AskUserQuestion` for structured input):

**Question 1:** "What type of work item will use this schema?"
- If `$ARGUMENTS` contained a schema name, use it as the tag name
- Otherwise, prompt with examples: `research-spike`, `infrastructure-change`, `plugin-update`
- This becomes the schema key and the tag agents apply to items

**Question 2:** "Does this work type need a review/deploy phase after implementation, or does it go straight to done?"
- Yes → include `role: review` notes
- No → schema ends at work phase (terminal reached after work notes filled)

**Question 3:** "What must be documented *before* work starts (queue phase)?"
- Prompt with examples: requirements/acceptance criteria, research question, change scope
- Aim for 1-3 notes; ask for each: key name, whether required, what agents should capture

**Question 4:** "What must be documented *after* implementation (work phase)?"
- Prompt with examples: implementation summary, test results, files changed
- Aim for 1-3 notes

**Question 5 (if review phase):** "What must be documented/verified before closing (review phase)?"
- Prompt with examples: deploy confirmation, smoke test results, sign-off

**Question 6:** "Should we create a companion lifecycle skill at `.claude/skills/<schema-name>/SKILL.md`?"
- This gives agents a `/<schema-name>` slash command with full phase-by-phase lifecycle guidance
- Recommended when the workflow has 3+ notes or involves non-obvious sequencing

### Generate YAML

Using answers from the gathering step, produce the YAML block. Apply these defaults:
- First queue note's `guidance` should open with: `"Run /<schema-name> for the full lifecycle guide. For this note: <specific guidance>."` if a companion skill will be created
- Use kebab-case for all keys
- Keep `description` values under 80 chars
- If a `session-tracking` note was added, use the standard structured guidance (see rule 4 below)

Show the generated YAML to the user and ask for confirmation before writing.

For YAML format reference and field rules, see `references/config-format.md` in this skill folder.

### Guidance Generation Rules

Apply these four disciplines when writing `guidance` values for any note — whether from a template customization or from-scratch Q&A:

1. **Lead with the consumer.** Open with who reads this note and what they need from it. Example: "This note is read by the review agent. They need to know which files changed and whether the implementation matches the specification."

2. **Structure over prose.** If the note covers 3 or more topics, use bold section headers (`**Header**`) rather than a prose paragraph. Agents and reviewers scan — they don't read walls of text.

3. **Concrete over generic.** Specify the actual verification action, not the category. "State which files changed and the specific function modified" instead of "describe the approach." "Name specific test scenarios for happy paths and failure paths" instead of "add tests."

4. **Session-tracking prompt.** If the schema includes a work phase, ask: "Most schemas include a session-tracking note (work phase) for retrospective analysis. Add one? [Yes/No]" If yes, use this standard guidance: `"This note feeds retrospective analysis. Structure: **Outcome**: success | partial | failure. **Files changed**: list with one-line rationale each. **Deviations**: from plan or diagnosis. **Friction**: tool errors, unexpected roundtrips, API confusion — include type (optimization/friction/bug/missing-capability) and description. **Test results**: pass/fail counts, new tests added."`

---

## Step 4 — Write Config

Check if `.taskorchestrator/config.yaml` exists:
- **Exists:** Read it, merge the new schema under `note_schemas:`, write back
- **Doesn't exist:** Create `.taskorchestrator/` directory and write the file with the new schema

After writing, remind the user: **MCP reconnect required** (`/mcp`) for the schema to take effect.

---

## Step 5 — Generate Companion Skill (if requested or if from-scratch Q6 said yes)

For template paths, ask via `AskUserQuestion` whether to generate a companion skill before proceeding.

Write to `.claude/skills/<schema-name>/SKILL.md`. Create the directory if it doesn't exist. This is a project-local skill available immediately as `/<schema-name>` — no plugin version bump required.

Use the template in `references/companion-template.md` within this skill folder. Replace all placeholders with values from the schema built above.

---

## Step 6 — Smoke Test

Create a temporary item to confirm the schema loads:

```
manage_items(
  operation="create",
  items=[{ title: "Schema smoke test", tags: "<schema-name>", priority: "low" }]
)
```

Check that `expectedNotes` appears in the response with the correct keys and roles. If `expectedNotes` is empty or missing, the server needs `/mcp` reconnect — remind the user and retry.

Delete the smoke-test item after verification:
```
manage_items(operation="delete", ids=["<smoke-test-uuid>"])
```

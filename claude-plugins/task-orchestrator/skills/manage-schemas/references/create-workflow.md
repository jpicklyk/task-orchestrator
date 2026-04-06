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
work_item_schemas:
  feature-implementation:
    lifecycle: auto
    notes:
      - key: specification
        role: queue
        required: true
        description: "Problem statement, approach, and pre-work plan."
        skill: "spec-quality"
        guidance: "This note must cover: problem statement and who benefits, acceptance criteria, non-goals, alternatives considered (min 2 real options + 'do nothing'), blast radius (affected modules and tests), risk flags, and test strategy."
      - key: implementation-notes
        role: work
        required: true
        description: "Context handoff for downstream agents — deviations, surprises, decisions."
        guidance: "Document decisions not in the specification. Focus on what downstream agents need: deviations, API surprises, wrong assumptions, patterns affecting dependent work."
      - key: session-tracking
        role: work
        required: true
        description: "Session context — what was done, how it went, anything the retrospective should know."
        guidance: "Record: Outcome (success/partial/failure), files changed with rationale, deviations from plan, friction (tool errors, roundtrips), test results (pass/fail counts, new tests added)."
      - key: review-checklist
        role: review
        required: true
        description: "Quality gate — plan alignment, test quality, simplification, verdict."
        skill: "review-quality"
        guidance: "Verify: (1) what was built aligns with the specification, (2) tests cover the test strategy — not strawman tests, (3) no unnecessary complexity in changed files. End with a verdict."
```

### Bug Fix Template

Show this schema to the user:

```yaml
  bug-fix:
    lifecycle: auto
    notes:
      - key: diagnosis
        role: queue
        required: true
        description: "Reproduction, root cause, fix approach, and test strategy."
        skill: "spec-quality"
        guidance: "This note must cover: reproduction steps (exact inputs, expected vs actual output), root cause (specific file, function, condition), fix approach with alternatives, blast radius, and test strategy."
      - key: implementation-notes
        role: work
        required: true
        description: "Context handoff — what changed, deviations from diagnosis, patterns to apply."
        guidance: "Document what changed and why. Note if root cause differed from diagnosis, patterns for similar code paths, edge cases discovered."
      - key: session-tracking
        role: work
        required: true
        description: "Session context for retrospective."
        guidance: "Record: Outcome, files changed, deviations from diagnosis, friction, test results."
      - key: review-checklist
        role: review
        required: true
        description: "Quality gate — fix alignment, regression coverage, simplification, verdict."
        skill: "review-quality"
        guidance: "Verify: (1) fix addresses diagnosed root cause, (2) regression test exists, (3) edge cases covered. End with verdict."
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
- **Customize:** Show each note in a numbered list. Ask what to change (add a note, remove a note, edit description/guidance/skill, toggle required, change lifecycle). Apply changes, show the updated YAML, confirm, then proceed to Write Config (Step 4).
- **Cancel:** Return to Step 1.

---

## Step 3 — From-Scratch Path (option 3)

Ask the user the following questions (use `AskUserQuestion` for structured input):

**Question 1:** "What type of work item will use this schema?"
- If `$ARGUMENTS` contained a schema name, use it as the type name
- Otherwise, prompt with examples: `research-spike`, `infrastructure-change`, `plugin-update`
- This becomes the schema key and the `type` value agents set on items

**Question 2:** "What lifecycle mode? (auto = cascades to terminal when children complete, manual = no auto-cascade, auto-reopen = reopens when new children added, permanent = never auto-terminates)"
- Default to `auto` if the user is unsure

**Question 3:** "Does this work type need a review/deploy phase after implementation, or does it go straight to done?"
- Yes → include `role: review` notes
- No → schema ends at work phase (terminal reached after work notes filled)

**Question 4:** "What must be documented *before* work starts (queue phase)?"
- Prompt with examples: requirements/acceptance criteria, research question, change scope
- Aim for 1-3 notes; ask for each: key name, whether required, what agents should capture
- For each note, ask: "Should this note have a skill framework? If so, what skill name?"

**Question 5:** "What must be documented *after* implementation (work phase)?"
- Prompt with examples: implementation summary, test results, files changed
- Aim for 1-3 notes

**Question 6 (if review phase):** "What must be documented/verified before closing (review phase)?"
- Prompt with examples: deploy confirmation, smoke test results, sign-off
- For each note, ask about the skill field

**Question 7:** "Should we create a companion lifecycle skill at `.claude/skills/<schema-name>/SKILL.md`?"
- This gives agents a `/<schema-name>` slash command with full phase-by-phase lifecycle guidance
- Recommended when the workflow has 3+ notes or involves non-obvious sequencing

**Question 8:** "Should any traits be applied by default to items of this type?"
- Explain: traits add additional note requirements (e.g., `needs-migration-review` adds a migration assessment)
- Show available traits from the `traits:` section of config if it exists
- If yes, add to `default_traits` list

### Generate YAML

Using answers from the gathering step, produce the YAML block in `work_item_schemas` format. Apply these defaults:
- First queue note's `guidance` should open with: `"Run /<schema-name> for the full lifecycle guide. For this note: <specific guidance>."` if a companion skill will be created
- Use kebab-case for all keys
- Keep `description` values under 80 chars
- If a `session-tracking` note was added, use the standard structured guidance (see rule 4 below)
- Include `lifecycle:` even if `auto` (explicit is clearer)

Show the generated YAML to the user and ask for confirmation before writing.

For YAML format reference and field rules, see `references/config-format.md` in this skill folder.

### Guidance Generation Rules

Apply these four disciplines when writing `guidance` values for any note — whether from a template customization or from-scratch Q&A:

1. **Lead with the consumer.** Open with who reads this note and what they need from it. Example: "This note is read by the review agent. They need to know which files changed and whether the implementation matches the specification."

2. **Structure over prose.** If the note covers 3 or more topics, use bold section headers (`**Header**`) rather than a prose paragraph. Agents and reviewers scan — they don't read walls of text.

3. **Concrete over generic.** Specify the actual verification action, not the category. "State which files changed and the specific function modified" instead of "describe the approach." "Name specific test scenarios for happy paths and failure paths" instead of "add tests."

4. **Session-tracking prompt.** If the schema includes a work phase, ask: "Most schemas include a session-tracking note (work phase) for retrospective analysis. Add one? [Yes/No]" If yes, use this standard guidance: `"Record: Outcome (success/partial/failure), files changed with rationale, deviations from plan, friction (tool errors, roundtrips), test results (pass/fail counts, new tests added)."`

---

## Step 4 — Write Config

Check if `.taskorchestrator/config.yaml` exists:
- **Exists:** Read it, merge the new schema under `work_item_schemas:`, write back
- **Doesn't exist:** Create `.taskorchestrator/` directory and write the file with the new schema under `work_item_schemas:`

After writing, remind the user: **MCP reconnect required** (`/mcp`) for the schema to take effect.

---

## Step 5 — Generate Companion Skill (if requested or if from-scratch Q7 said yes)

For template paths, ask via `AskUserQuestion` whether to generate a companion skill before proceeding.

Write to `.claude/skills/<schema-name>/SKILL.md`. Create the directory if it doesn't exist. This is a project-local skill available immediately as `/<schema-name>` — no plugin version bump required.

Use the template in `references/companion-template.md` within this skill folder. Replace all placeholders with values from the schema built above.

---

## Step 6 — Smoke Test

Create a temporary item to confirm the schema loads:

```
manage_items(
  operation="create",
  items=[{ title: "Schema smoke test", type: "<schema-name>", priority: "low" }]
)
```

Check that `expectedNotes` appears in the response with the correct keys and roles. If `expectedNotes` is empty or missing, the server needs `/mcp` reconnect — remind the user and retry.

Delete the smoke-test item after verification:
```
manage_items(operation="delete", ids=["<smoke-test-uuid>"])
```

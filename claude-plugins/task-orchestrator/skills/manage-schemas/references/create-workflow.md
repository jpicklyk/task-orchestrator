# Create Schema Workflow

Full interactive flow for building a new note schema. Offers starter templates before falling back to from-scratch Q&A.

---

## Step 1 — Choose Starting Point

If `$ARGUMENTS` already contains a schema name that matches a starter template (e.g., "create feature-implementation"), skip to Step 2 with that template pre-selected.

Otherwise, ask via `AskUserQuestion`:

```
◆ How would you like to create the schema?
  1. Feature implementation — full lifecycle with design gates, implementation evidence,
     and optional deploy verification (5 notes across queue/work/review)
  2. Bug fix — lightweight schema for bug fixes: root cause before code,
     verification after (4 notes across queue/work)
  3. Start from scratch — answer questions to build a custom schema
```

---

## Step 2 — Template Path (options 1 or 2)

### Feature Implementation Template

Show this schema to the user:

```yaml
feature-implementation:
  - key: requirements
    role: queue
    required: true
    description: "Problem statement and acceptance criteria."
    guidance: "Describe what problem this solves and who benefits. List 2-5 concrete acceptance criteria that define done."
  - key: design
    role: queue
    required: true
    description: "Chosen approach, alternatives considered, key risks."
    guidance: "Explain the implementation approach. Note any alternatives you ruled out and why. Call out risks or constraints."
  - key: implementation-notes
    role: work
    required: true
    description: "Key decisions made during implementation, deviations from design."
    guidance: "Document any surprises, wrong-turns, or deviations from the planned approach."
  - key: test-results
    role: work
    required: true
    description: "Test pass/fail count and any new tests added."
    guidance: "Run tests and report total count and failures. List any new test classes or test cases added."
  - key: deploy-notes
    role: review
    required: false
    description: "Deploy needed? Version bump? Reconnect required?"
    guidance: "Note whether a rebuild/deploy was done, what version was bumped to, and whether reconnect was required."
```

### Bug Fix Template

Show this schema to the user:

```yaml
bug-fix:
  - key: reproduction-steps
    role: queue
    required: true
    description: "Step-by-step reproduction with expected vs actual result."
    guidance: "Include the exact steps or tool call that triggers the bug. State expected output and actual output."
  - key: root-cause
    role: queue
    required: true
    description: "Why it happens — file, line, and condition."
    guidance: "Identify the specific file and function where the defect lives. Explain the condition that triggers it."
  - key: fix-summary
    role: work
    required: true
    description: "What was changed and which files were modified."
    guidance: "List each file changed and summarize the change. Note any patterns that should be applied elsewhere."
  - key: test-verification
    role: work
    required: true
    description: "How the fix was verified and test results after fix."
    guidance: "Run tests and report results. Note if a new test was added to cover the fix."
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

Show the generated YAML to the user and ask for confirmation before writing.

For YAML format reference and field rules, see `references/config-format.md` in this skill folder.

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

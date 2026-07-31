# Create Schema Workflow

Full interactive flow for building a new note schema. Three entry paths: a pattern-driven
advisor that recommends a configuration from the user's described workflow, starter templates,
and from-scratch Q&A.

---

## Step 1 — Choose Starting Point

Fast paths from `$ARGUMENTS` and conversation context — check these before asking anything:

- **Schema name matching a starter template** (e.g., "create feature-implementation") → skip to
  Step 2 with that template pre-selected.
- **A workflow description rather than a schema name** (e.g., "create schemas for our support
  triage", "set up gates for my content pipeline", "what schema should I use for X") → skip to
  Step 2A (advisor) with the description as seed context.

Otherwise, ask via `AskUserQuestion`:

```
◆ How would you like to create the schema?
  1. Recommend from my workflow — describe the work you manage; get a recommended
     configuration from a library of workflow patterns
  2. Feature implementation template — lean feature-summary gate (queue), implementation
     evidence + session tracking (work), review checklist (review) — 4 notes
  3. Bug fix template — diagnosis gate (queue), implementation evidence + session tracking
     (work), review checklist (review) — 4 notes
  4. Start from scratch — answer questions to build a custom schema
```

---

## Step 2A — Advisor Path (option 1)

Recommend a configuration by classifying the user's workflow against the pattern library.
Read `references/workflow-patterns.md` (in this skill folder) before proceeding — it carries
the classification dimensions, a capsule index of all ten profiles, selection tables, the
cross-domain trait library, and the anti-pattern warnings. The full profiles (YAML + rationale)
live one-per-file in `references/profiles/` — do NOT read them at this stage; you will read
only the matched one(s) in 2A.2.

### 2A.1 — Gather workflow facts

Extract answers from what the user has already said first — the conversation usually contains
most of them. Then ask ONLY for genuine gaps, batched into a single `AskUserQuestion` round
(max 4 questions), covering the four classification dimensions from `workflow-patterns.md` §1:

1. **Work shape** — what kind of work flows through (features, loop tasks, research, content,
   tickets, pipeline runs, incidents, documents, generic tasks)
2. **Sign-off** — does anything need human or second-agent approval before closing, and what
   evidence does the approver need?
3. **Executors** — one orchestrated agent, or multiple workers pulling from a shared pool?
4. **Contention & recurrence** — shared resources that tolerate one user at a time? standing
   queues? work that reopens later?

Do not run a second interview round unless an answer is genuinely uninterpretable.

### 2A.2 — Classify and recommend

Classify against the profile index in `workflow-patterns.md` §1 — the capsules carry enough
signal to compare all ten candidates without opening files. Then read ONLY the matched profile
file from `references/profiles/` (a second file when the classification is ambiguous or the
workflow is a hybrid; never the whole directory — the two-stage read exists to keep unmatched
profiles out of context). Then present:

1. **The recommendation** — primary profile, plus any traits pulled from §4 or a second
   profile. Name the pattern in plain language ("this is a claim-mode triage shape").
2. **The YAML** — the profile's schema adapted to the user's terms (rename types/keys to the
   user's vocabulary; keep the structure). Kebab-case keys, explicit `required` on every note,
   explicit `lifecycle`.
3. **The rationale, per gate** — one line each on why the gate exists and what failure it
   prevents, drawn from the profile's "rationale to present". A gate whose purpose the user
   can't restate will be worked around, not filled.
4. **Conventions that aren't config** — if the recommendation involves claim mode, actor
   authentication, or dispatch discipline (e.g., review notes filled by a different agent),
   say explicitly that these are usage conventions the config cannot enforce.

Where the user's workflow steers toward an anti-pattern (§5 — over-gating, exclusive resources
on containers, self-review, generic resource keys), raise the warning with its reason as part
of the recommendation, not after writing.

### 2A.3 — Confirm and hand off

Ask via `AskUserQuestion`:

```
◆ This is the recommended configuration. What would you like to do?
  1. Use as-is — write it to config
  2. Customize first — add, remove, or modify notes before writing
  3. Cancel — go back
```

- **Use as-is / Customize:** continue exactly as the template path does (Step 2's "After
  showing the template" — customize loop, then Write Config, companion skill, smoke test).
  A companion lifecycle skill (Step 5) is worth offering for any recommendation with 3+ notes
  or claim-mode conventions.
- **Cancel:** return to Step 1.

---

## Step 2 — Template Path (options 2 or 3)

### Feature Implementation Template

Show this schema to the user:

```yaml
work_item_schemas:
  feature-implementation:
    lifecycle: auto
    notes:
      - key: feature-summary
        role: queue
        required: true
        description: "Lean feature-level summary — goal, findings-to-tasks mapping, dependency edges, non-goals pointer."
        skill: "spec-quality"
        guidance: "Keep this lean — target under 2k chars. Cover: goal (2-3 sentences), a findings→tasks table mapping research/brainstorm findings to child task-scope items, dependency edges between those tasks, and a pointer to non-goals. Full spec-quality depth (alternatives, blast radius, risk flags, test strategy) belongs in each child's task-scope note, not here."
      - key: implementation-notes
        role: work
        required: true
        description: "Context handoff for downstream agents — deviations, surprises, decisions."
        guidance: "Document decisions not in the feature-summary. Focus on what downstream agents need: deviations, API surprises, wrong assumptions, patterns affecting dependent work."
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
        guidance: "Verify: (1) what was built aligns with the feature-summary and each child's task-scope, (2) tests cover the test strategy — not strawman tests, (3) no unnecessary complexity in changed files. End with a verdict."
```

### Bug Fix Template

Show this schema to the user:

```yaml
  bug-fix:
    lifecycle: auto
    default_traits: [delegated]
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

## Step 3 — From-Scratch Path (option 4)

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
- For each note, ask: "Should this note have a skill framework? If so, what skill name?" (a project skill, plugin skill, or Claude Code built-in — e.g., `security-review` for security gates)

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

**Preserve all other top-level keys verbatim.** Only the `work_item_schemas:` (or `note_schemas:`) section may change. Every other top-level key present in the file — `project`, `actor_authentication`, `note_limits`, `traits`, and any key not recognized by this skill — must be carried through unchanged. Do not reconstruct the file from a parsed-and-regenerated schema model, since that silently drops blocks this skill doesn't know about; splice the new schema into the existing text (or reproduce every other top-level block exactly as read).

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
manage_items(operation="delete", itemIds=["<smoke-test-uuid>"])
```

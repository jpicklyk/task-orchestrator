# Audit Schema Workflow

Report on the quality of the schemas in `.taskorchestrator/config.yaml`, then propose one config diff. The audit reports and never decides: every finding is a point to consider, never an error, and it does not move text from guidance into a skill, write a skill, or change anything without the user's yes. A finding that depends on a process choice states the observation and proposes nothing unless the user names the choice.

Run the stages in order and collect every finding before reporting. A stage that cannot run says so in one line and the audit goes on.

## Stage 1 - Server (lint)

Needs `project.rootId`. Without one, say "server stage skipped: no project.rootId" and go to Stage 2.

```to-call
{"tool": "config", "op": "validate", "args": {"rootId": "<project.rootId>", "configYaml": "<file text, CRLF replaced by LF>"}}
```

- Show `errors` first (Structural tier), then `warnings`, then `lint`.
- `lint` is `[{path, reason}]`. It is always present on a server that has it and is `[]` when the document has errors, a parse failure or a rootId mismatch, so `[]` after errors is not clean: say the lint did not run on an invalid document.
- `push` never returns or stores `lint`; only content declared in the per-root document is reported (the server layer is read for comparison only).
- A result with no `lint` member: say "lint is not available on this server" and run the remaining stages.
- Do not repeat in later stages what lint reports; whole-string identity of guidance is lint's.

## Stage 2 - Pointer (skill names)

Compare every skill name with the skills available to the agent running the audit. Read no install path and invoke no skill to find out.

1. Note skills: `skill` and `skills` on each note.

   ```to-call
   {"tool": "config", "op": "effective", "args": {"rootId": "<project.rootId>"}}
   ```

2. Seat skills and seat rules: the effective view carries no seats, so read them per lifecycle type.

   ```to-call
   {"tool": "items_read", "select": "schema", "args": {"type": "<type>", "rootId": "<project.rootId>"}}
   ```

   `seats[].skills` and `seats[].rules` appear only when declared.
3. Names the root serves as rules are left to lint (list them with `config` `rules` and `rootId` alone):

   ```to-call
   {"tool": "config", "op": "rules", "args": {"rootId": "<project.rootId>"}}
   ```

4. Every name left is a finding when it is not among the skills available to you. Every binding shown names its source: `schema` (declared in the file), `plugin default` (the plugin's seat defaults), or `none`.
   - Seat skills: a declared `skills` list is `schema`; no list is the plugin default, read from `../../hooks/lib/seat-skill-defaults.json` relative to this skill folder (report "unknown" when it cannot be read); a seat with neither is `none`.
   - Seat rules: a declared `rules` list is `schema`; with none declared the plugin defaults apply (see config-format.md, seat-entry fields, `rules`).

## Stage 3 - Content (guidance declared in the file)

Over the guidance strings in the file only, never the server layer.

1. Restates a served rule or a bound plugin skill. Rule bodies come from `config` `rules` with `rootId` and `key`. A bound skill (on the note, or on the note's seat) whose name starts with `task-orchestrator:` is read at `../<name>/SKILL.md` relative to this skill folder; other skills are not compared and the report says so. A guidance that restates the body is a finding; propose pointing at the rule or skill instead.
2. Size: guidance over 1500 characters by default (the user may name another threshold).
3. A paragraph repeated across guidance strings; a sentence repeated across guidance strings (whole-string identity is lint's). Propose naming one home for the text only when the user names it.
4. One note key defined differently across schemas and traits: list what differs (seat, role, required, guidance, skill).
5. Seat coverage: when the file mixes lifecycle types that declare `seats` with types that do not, give one observation listing the seatless types. Propose nothing unless the user names types; state no rule about which is right.
6. Craft rather than record: guidance that tells the agent how to do the work rather than what the note records and in what shape (the note guidance holds the record only). Report it with the skill bound to that note or seat, or "no skill bound". Propose nothing that moves text into a skill.

## Stage 4 - Usage

1. Traits no item carries. A trait named in any type's `default_traits` is in use. For each other trait:

   ```to-call
   {"tool": "items_read", "select": "list", "args": {"ancestorId": "<project.rootId>", "traits": ["<trait>"], "limit": 1}}
   ```

   No item is a finding.
2. Optional notes never filled: for each lifecycle type, take up to 20 recent items of that type and read their note keys without bodies:

   ```to-call
   {"tool": "notes_read", "select": "list", "args": {"itemId": "<item-uuid>", "keys": ["<optional note key>"]}}
   ```

   An optional note filled on none of the sampled items is a finding. State the sample size in the report.

## Report

```
Schema audit - .taskorchestrator/config.yaml

Structural (n)
  - <location>: <what was found>. Proposed: <change | none proposed>
Pointer (n)
Content (n)
Usage (n; sample: up to 20 items per type)

Proposed config diff
  <one diff, or "none">
```

Ask with `AskUserQuestion` whether to apply the diff (yes, or no). An accepted change goes through [edit-workflow.md](edit-workflow.md) and the SKILL.md Step 4 validate and push. A no writes nothing.

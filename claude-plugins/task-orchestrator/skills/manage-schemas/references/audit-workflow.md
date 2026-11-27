# Audit Schema Workflow

Report on the quality of the schemas in `.taskorchestrator/config.yaml`, then propose one config diff. The audit reports and never decides: every finding is a point to consider, never an error, and it does not move text from guidance into a skill, write a skill, or change anything without the user's yes. A finding that depends on a process choice states the observation and proposes nothing unless the user names the choice; the closing question (see Report) is where the user names it.

Run the stages in order and collect every finding before reporting. A stage that cannot run says so in one line and the audit goes on. A stage cannot run only for a condition its own section names; the effort of making a call is never one.

## Stage 1 - Server (lint)

Needs `project.rootId`. Without one, say "server stage skipped: no project.rootId" and go to Stage 2.

With a `project.rootId` this call is required. The effort of passing its text is never a reason to skip it, and only the size limit and an error answer below let it not run: several findings come only from this stage.

```to-call
{"tool": "config", "op": "validate", "args": {"rootId": "<project.rootId>", "configYaml": "<file text, CRLF replaced by LF>"}}
```

- `configYaml` is the whole file's text as read, passed as one string argument, with every CRLF replaced by LF and nothing else changed: no trimming, summarising or splitting.
- The server accepts at most 131072 bytes (UTF-8) in `configYaml`. The limit applies to the text after the CRLF replacement. When that text is larger, make no call and say "server stage not run: text is <n> bytes, over the 131072-byte limit" in one line.
- When the call answers an error, it answers call-level `partial_failure` and the error is in `data.results[0].error` (SKILL.md Step 4 says where it is read), for example `payload_too_large`. Say "server stage not run: <code>" with that error's `code` and message in one line. Do not report an error answer as a clean result.
- Report everything this stage returns in the Structural tier: `errors` first, then `warnings`, then `lint`, each lint finding marked `lint`.
- `lint` is `[{path, reason}]`. It is always present on a server that has it and is `[]` when the document has errors, a parse failure or a rootId mismatch, so `[]` after errors is not clean: say the lint did not run on an invalid document.
- `push` never returns or stores `lint`; only content declared in the per-root document is reported (the server layer is read for comparison only).
- A result with no `lint` member: say "lint is not available on this server" and run the remaining stages.
- Do not repeat in later stages what lint reports; whole-string identity of guidance is lint's.

## Stage 2 - Pointer (skill names)

Needs `project.rootId`. Without one, say "pointer stage not run: no project.rootId" and go to Stage 3.

Compare every skill name declared in the file with the skills available to the agent running the audit. Read no install path and invoke no skill to find out. A rule key is not a skill name and is never compared; only skill names are.

1. Note skills: `skill` and `skills` on each note. The result lists every type with its `configSource`, `per-root` or `global`; only a `per-root` type is declared in the file. For a `global` type say one line, "type <type> comes from the server layer, not compared", and go on.

   ```to-call
   {"tool": "config", "op": "effective", "args": {"rootId": "<project.rootId>"}}
   ```

2. Seat skills and seat rules, for each `per-root` type: the effective view carries no seats, so read them per lifecycle type.

   ```to-call
   {"tool": "items_read", "select": "schema", "args": {"type": "<type>", "rootId": "<project.rootId>"}}
   ```

   `seats[].skills` and `seats[].rules` appear only when declared.
3. A skill name that the root also serves as a rule is lint's and is left out here (list the served keys with `config` `rules` and `rootId` alone):

   ```to-call
   {"tool": "config", "op": "rules", "args": {"rootId": "<project.rootId>"}}
   ```

4. Every other skill name is a finding when it is not among the skills available to you.
5. Every binding shown names its source: `schema` (declared in the file), `plugin default` (the plugin's seat defaults), or `none`.
   - Seat skills: a declared `skills` list is `schema`; no list is the plugin default, read from `../../hooks/lib/seat-skill-defaults.json` relative to this skill folder (report "unknown" when it cannot be read); a seat with neither is `none`.
   - Seat rules: a declared `rules` list is `schema`; with none declared the plugin defaults apply, read from `../../hooks/lib/seat-rules.json` relative to this skill folder (the `defaults` of the row named like the seat, for a name in `capabilities.seatRows`; a seat name with no row has none; report "unknown" when it cannot be read; see config-format.md, seat-entry fields, `rules`). Rules are shown with their source and are not compared with skills; lint reports a declared key the root does not serve.
6. Served bundled rules: compare each served key from step 3 that the plugin ships with the plugin's hashes. Read the manifest yourself, `../../bundled-rules/manifest.json` relative to this skill folder (a key to its shipped hashes, oldest first, the last one current), and the seat rule table `../../hooks/lib/seat-rules.json` (a key that is some role's `protocol` is a protocol rule). Report "manifest unreadable: bundled rules not compared" in one line when either cannot be read. For each served key the manifest lists, by its `rulesVersion`:
   - the last hash: current, nothing to report.
   - an earlier hash: a stale copy. The fix: start a new session with a REST API URL configured (the session-start rule sync replaces a copy whose hash the manifest lists), or re-upsert `bundled-rules/<key>.md` as the `rule` document of that key.
   - a hash the manifest does not list: project-authored; the sync never touches it. When the key is a protocol rule, flag it "protocol rule": the copy no longer receives plugin updates to the tool's own protocol, and conduct for a seat is better added as a project rule on the seat's `rules` list.

   Each is a finding, never an error.

## Stage 3 - Content (guidance declared in the file)

Over the guidance strings in the file only, never the server layer.

Step 1 needs `project.rootId` for `config` `rules`. Without one, say "restatement check not run: no project.rootId" and go on to step 2.

1. Restates a served rule or a bound plugin skill. Rule bodies come from `config` `rules` with `rootId` and `key`. A bound skill (on the note, or on the note's seat) named `task-orchestrator:<skill>` is read at `../<skill>/SKILL.md` relative to this skill folder (the folder is the part after the prefix); when that file cannot be read the report says the skill was not compared. Other skills are not compared and the report says so. A guidance that restates the body is a finding; propose pointing at the rule or skill instead.
2. Size: guidance over 1500 characters by default (the user may name another threshold).
3. Repeated text: a paragraph or a sentence that appears in the guidance of more than one note. Carry this out as an exact comparison, not by reading and recalling; a short script is fine, in any language the agent has.
   - Enumerate: every guidance string declared in the file, on schema notes and on trait notes. A location is the schema type or trait name plus the note key, `<type or trait>/<key>`. Use the decoded string value (the YAML block already parsed), never the server layer.
   - Delimit: a paragraph is a run of text between blank lines. A sentence ends at `.`, `!` or `?` followed by whitespace or the end of the string, and also at a line break that starts a list item.
   - Normalise before comparing: strip a leading list marker (`-`, `*`, or digits and `.`), replace every run of whitespace with one space, trim both ends. Compare the normalised text exactly: case and punctuation count, nothing is fuzzy or approximate.
   - Minimum length, after normalising: a sentence of 60 characters or more counts; a paragraph of two or more sentences and 120 characters or more counts. A shorter one is a stock phrase and is not a finding. A paragraph of one sentence is compared as a sentence only.
   - Group identical normalised texts. A group is a finding when its locations include two or more distinct notes; the same text twice in one note is not. Leave a group out when its text is the whole normalised guidance string at every one of its locations (whole-string identity is lint's). A sentence group whose every location sits inside a repeated paragraph group is listed under that paragraph's line, not on its own.
   - Report one line per group in the Content tier: `paragraph` or `sentence`, its length in characters, the first 80 characters of the text (then `...` when longer), the count of locations, and every location. Never report a group with only some of its locations. Close the line with `Proposed: none proposed`; propose naming one home for the text only when the user names it.
4. One note key defined differently across schemas and traits: list what differs (seat, role, required, guidance, skill).
5. Seat coverage: when the file mixes lifecycle types that declare `seats` with types that do not, give one observation listing the seatless types. Propose nothing unless the user names types; state no rule about which is right.
6. Craft rather than record: guidance that tells the agent how to do the work rather than what the note records and in what shape (the note guidance holds the record only). Report it with the skill bound to that note or seat, or "no skill bound". Propose nothing that moves text into a skill.

## Stage 4 - Usage

Needs `project.rootId`. Without one, say "usage stage not run: no project.rootId" and go to the report.

1. Traits no item carries. A trait named in any type's `default_traits` is in use. For each other trait:

   ```to-call
   {"tool": "items_read", "select": "list", "args": {"ancestorId": "<project.rootId>", "traits": ["<trait>"], "limit": 1}}
   ```

   No item is a finding.
2. Optional notes never filled: for each lifecycle type, list up to 20 recent items of that type:

   ```to-call
   {"tool": "items_read", "select": "list", "args": {"ancestorId": "<project.rootId>", "type": "<type>", "sort": "createdAt", "order": "desc", "limit": 20}}
   ```

   then read each item's note keys without bodies:

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
  <one diff, one hunk per change, or "none">
```

Ask with `AskUserQuestion` which changes to apply: each hunk of the diff is a choice, several may be chosen, and none is an answer. For a finding that proposed nothing because it depends on a process choice, the question also offers to name the choice; a choice the user names becomes a proposed change. Each change the user chooses goes through [edit-workflow.md](edit-workflow.md) and the SKILL.md Step 4 validate and push. An answer of none writes nothing.

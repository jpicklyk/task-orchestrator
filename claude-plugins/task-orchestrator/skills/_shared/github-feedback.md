# GitHub Feedback Filing

Shared conventions for turning a **product-scope** improvement proposal, or a qualifying product defect, into a GitHub issue on the upstream repo. Used by `session-retrospective` Step 7c (filing) and by `review-proposals` (issue reuse when accepting a global proposal); both link it as `../_shared/github-feedback.md`. This file sits in `skills/_shared/`, which holds text two skills share and is not a skill directory.

Project-scoped proposals never reach this file: they anchor under their project root instead (session-retrospective Step 7b) and are applied to config, not filed upstream.

Call shapes are in [`../_generated/call-shapes.md`](../_generated/call-shapes.md).

Contents: [Config gate](#config-gate) | [gh availability guard](#c5---gh-availability-guard) | [Dedup procedure](#dedup-procedure-cheapest-first) | [Issue template](#c4---issue-template) | [Record-back](#c2---record-back) | [Bug reports (product defects)](#bug-reports-product-defects) | [Best-effort discipline](#best-effort-discipline)

---

## Config gate

Read `extensions.claude-code.retrospective.github_feedback` from the workspace `.taskorchestrator/config.yaml` (a direct file read, not an MCP call). The server never reads `extensions`; the plugin does.

```yaml
extensions:
  claude-code:
    retrospective:
      github_feedback:
        enabled: true                      # default false - opt-in
        repo: jpicklyk/task-orchestrator   # optional; this is the default
```

`enabled` must be exactly `true` to proceed. A missing block, `enabled: false` or any other value skips all filing for this run: record `github filing: disabled` in the caller's closure note. `repo` defaults to `jpicklyk/task-orchestrator`. A leftover top-level `retrospective:` block is not read.

---

## C5 - gh availability guard

Before any `gh` call run `gh auth status`. A non-zero exit skips filing gracefully; record the reason (for example `gh unavailable/unauthenticated`). Never fail the parent workflow on this guard; like every step below, it is best-effort.

---

## Dedup procedure (cheapest first)

File at most one issue per distinct proposal. Check in this order and stop at the first hit:

1. **Sibling proposals' `github-issue:` lines** (cap 10 note reads). For related proposals under the global "Improvement Proposals" container, `get` the proposal note and look for a trailing `github-issue: <url>` line (see Record-back). Reuse that URL if one describes the same change.

```to-call
{"tool": "notes_read", "select": "get", "args": {"itemId": "<sibling-proposal-uuid>", "key": "proposal"}}
```

2. **GitHub search** if no sibling matches:

```bash
gh issue list --repo <repo> --state all --search "<3-5 distinctive keywords>" --json number,title,url --limit 10
```

   Pick 3-5 distinctive keywords from the proposal title or summary (skip generic words like "improve", "add", "fix"). If a returned issue plainly describes the same change, reuse its `url`.

Only file a new issue when both checks come up empty.

---

## C4 - Issue template

**Title:** `[proposal] <concrete change description>`, mirroring the proposal's own title.

**Body** (three sections plus footer):

```markdown
## Problem

<what recurring pattern or gap the trend surfaced - 1-3 sentences>

## Proposed change

<the concrete change: exact YAML or rule text, or, for a mechanics change, the skill section + edit or the hook event/matcher - whatever the proposal specifies>

## Expected effect

<what improves once this lands>

---
Filed automatically by the task-orchestrator session-retrospective skill.
```

**Scrub rule.** Before writing the body file, strip absolute filesystem paths (keep repo-relative ones), machine names and user or account names, and replace private project names with "a downstream project". Never paste a `session-tracking` note body verbatim; synthesize Problem / Proposed change / Expected effect from the trend and proposal data.

**Filing:**

```bash
gh issue create --repo <repo> --title "[proposal] <...>" --body-file <scratchpad-tmp-path> --label enhancement
```

- Always use `--body-file` pointing at a scratchpad temp file: never pass multi-line text via `--body` and never rely on an editor fallback. Background retrospective agents are non-tty, so an editor prompt would hang.
- If `gh` reports the `enhancement` label does not exist on the repo, retry once without `--label`.

---

## C2 - Record-back

On success (filed or reused), append a final line to the **proposal note body**, not the item summary, so it is durable and greppable by both skills:

```
github-issue: <url>
```

Re-upsert the note with the appended line, using its existing `key` and `role` (the proposal's schema note, typically `proposal`, queue phase). This rewrites the same note; it is not a new one. Pass the whole existing body plus the new line:

```to-call
{"tool": "notes_write", "op": "upsert", "args": {"itemId": "<proposal-uuid>", "key": "proposal", "role": "queue", "body": "<existing body>\n\ngithub-issue: <url>"}}
```

Check `ok` and `data.results[0].ok`; on a failure read `data.results[0].error.code`. (Use `[i]` for multi-element calls.)

When the item has **no `proposal` note** (a legacy item), do not write a note that is only the URL: write the full backfilled body (problem and evidence, change, expected effect, blast radius, scope, taken from the item summary and ending `backfilled by review-proposals <YYYY-MM-DD>`, the shape in review-proposals "Before any Accept") and then the `github-issue: <url>` line as its final line.

---

## Bug reports (product defects)

A product defect the retrospective routed in Step 5e is reported upstream as a scrubbed `[bug]` issue, or as a rate-limited `seen again` comment on an existing one, and the command always runs: the retrospective runs it once per retrospective, gate open or closed, with or without defects, and the command decides. The gate is closed by default; the command reads it itself, files every qualifying defect you named with `--product` when it is open, and answers `gate: disabled` in its JSON (and gathers and files nothing) when it is closed; the agent never checks the gate. The logic lives in code (`scripts/lib/bug-report.mjs`); the agent runs one command, with no input file to write, and records the result back.

**Gate.** Closed by default: no config key and no command argument opens it. The target is always `jpicklyk/task-orchestrator` (`repo` is for proposals only). A closed gate is `disabled`: no `gh` call, no REST read, nothing written.

**What qualifies.** Only a `defect` finding of `scope: product` with signal `tool-error`, `hook-failure`, `script-failure` or `shape-mismatch`, and non-empty `tool`, `op`, `code`, `message` and `quote`. A server tool needs its `op` among that tool's entries in the tool catalog and its `code` among that entry's `errors`; a plugin tool, `op` and `code` must be on the plugin's closed vocabulary file (`scripts/lib/bug-report-vocabulary.json`): a hook or script file name that ships in the plugin, with that file's own events or subcommands as its ops and its exit codes and error classes as its codes. A field that fails reads `not-qualifying:<field>`. An environment code (`network`, `unauthenticated`, `timeout`, `no_api`, `ECONNREFUSED`, `ENOTFOUND`, `ETIMEDOUT`, `EAI_AGAIN`) never qualifies. The code checks those fields; misuse is your judgment: a defect caused by the caller's own wrong arguments or by project config is not a product defect, so do not name it with `--product`. A product defect with no `defect:` line has no gathered fingerprint: it stays an observation and is not named, because `--in` cannot combine with `--root`. For a hook or script failure, `tool` is the file name, `op` the event or subcommand, `code` `exit:<n>` or the error class. The `quote` and the `message` are evidence for you; neither is posted (the message only feeds the fingerprint).

**Input.** The command reads the defects itself: `--root <id>` with `--run <id>`... (and `--item <id>`...) gathers the `defect:` entries of the members' `session-tracking` notes over REST, parsed exactly as `retro-metrics` does (its `defects[]`, same fingerprints), so the agent writes no file and needs no Write tool. `--root` is the project root's item id; only members whose item `rootId` matches it count. Nothing gathered is filed on its own: pass one `--product <fingerprint>[:<observation>]` for each defect you classified as product scope in 5e and routed (the fingerprint is the one `retro-metrics` lists for it; the observation is the short id, 8 or more hex characters, of the item you routed it to). Only a defect so named is a candidate, and the code's qualification below then decides what is filed; every other gathered defect (misuse, project config, no flag) gets result `skipped` with reason `not-product`, and no `--product` at all files nothing. A malformed `--product` exits 2. A named fingerprint that was not gathered is a `gaps` entry. `--in <file>` is the alternative to `--root` (exactly one of the two), for a caller that already holds the defect fields; the retrospective always uses `--root`. It reads a JSON file `{"defects": [{"kind", "scope", "signal", "tool", "op", "code", "message", "quote", "callShape"?, "observation"?}]}`, one entry per defect (`observation` is its item's short id); its call shape is recorded per op element: for an ops-batched tool (a write that takes `ops: [...]`), record the shape of the one op element the failing call carried, not of the whole `{ops: [...]}` call; if the whole call is recorded, the command reads the element whose `op` equals the defect's op, else the first. The fingerprint is never an input: the command computes it.

**What is posted.** Exactly these fields, and nothing else: component, tool, op, error code, plugin and server versions, the call shape and a fingerprint. The title is `[bug] <component>: <tool> <op>: <code>`; the title prefix, the `Fingerprint:` line and the constant footer line `Reported automatically by task-orchestrator session-retrospective` are what identify a report. The names are checked before anything is posted: a server defect's tool, op and error code come from the tool catalog; a plugin defect's tool, op and error code come from the vocabulary file above, which the scrub still checks against the project name, OS user, host name and `gh` login. The call shape exists for a server tool only: the top-level catalog fields of that tool and op that the call had, each as a type name (`string`, `number`, `boolean`, `["string"]`); an object or map field reads `object` and none of its keys is sent, and a field outside the catalog is dropped. A plugin defect's call shape reads `not recorded`. No message, expected or actual text, quote, note text, id, path, URL or other free text is ever sent.

**Command.** Always run, once per retrospective, in 5e:

```
node "<helper>" bug-report --root <root-id> --run <run-id>... [--item <item-id>...] [--product <fingerprint>[:<observation>]]... [--deny <name>]...
```

Exactly one of `--root` and `--in <file>` is required (both, or neither, exits 2).

No argument or config opens the gate (there is no `--config`); each `--deny` adds a name the scrub must reject (the project name, OS user, host name and `gh` login are always denied). It exits 0 with `{contract: 'run-wave/bug-report-v1', gate, repo, results, gaps, input?}`; exit 2 means a bad argument. `gate` is `disabled`, `gh-unavailable` or `ran`. `gaps` lists what could not be read (`{section, reason}`, such as a failed REST read, or `root` `no_member` when no member of the `--root` project was gathered); with `--root` and an open gate `input` is `complete`, `partial` (a gap) or `unavailable` (no API URL resolved: nothing was gathered, and the empty `results` is not a clean run); report any gap and a non-`complete` `input` in the closure note. Each result has `fingerprint`, `observation`, `action` (`filed`, `commented`, `skipped` or `overflow`), `url`, `reason` and `line` (`bug-report <fp>: <action> <url|reason>`). Caps per run: 3 new issues, 5 comments, 10 searches. A create is attempted once and never retried: if it fails or times out, nothing more is posted for that defect in the run, and the per-install state file records it as `create-unconfirmed`, so the next run's fingerprint search finds the issue or files it then. A scrub rejection (`scrub-rejected:<codes>`) posts nothing; fix the input only if the cause is yours, otherwise leave it.

**Record-back.** For a `filed` or `commented` result with an `observation` (the one you passed in `--product`), add the issue URL to that observation's `observation-detail` note: re-upsert the whole body (key `observation-detail`, role `queue`) with `github-issue: <url>` as its final line, unless that line is already last. Report each result's `line` in the caller's closure note.

---

## Best-effort discipline

Every sub-step above and the bug-report command (config gate, gh guard, dedup, filing, record-back) is best-effort. A failure at any point (network error, `gh` crash, rate limit, note-upsert failure) is caught, logged as a one-line reason in the caller's closure note, and the parent workflow continues. Filing a GitHub issue must never be the reason a retrospective or proposal review fails to complete.

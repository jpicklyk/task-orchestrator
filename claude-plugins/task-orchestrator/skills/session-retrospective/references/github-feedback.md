# GitHub Feedback Filing (Improvement Proposals)

Shared conventions for turning a **global-scoped** improvement proposal into a GitHub issue on the upstream repo. Used by `session-retrospective` Step 7c (filing) and referenced by `review-proposals` (issue reuse when confirming a global proposal) as `<skill-base-dir>/../session-retrospective/references/github-feedback.md`.

Project-scoped proposals never reach this file: they anchor under their project root instead (session-retrospective Step 7b) and are applied to config, not filed upstream.

Call shapes are in [`../../_generated/call-shapes.md`](../../_generated/call-shapes.md).

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

<the concrete change: exact YAML, skill section + edit, orchestration-context or orchestrate-skill section, hook event/matcher - whatever the proposal specifies>

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

Check `ok`, `data.results[0].ok` and `error.code`.

---

## Best-effort discipline

Every sub-step above (config gate, gh guard, dedup, filing, record-back) is best-effort. A failure at any point (network error, `gh` crash, rate limit, note-upsert failure) is caught, logged as a one-line reason in the caller's closure note, and the parent workflow continues. Filing a GitHub issue must never be the reason a retrospective or proposal review fails to complete.

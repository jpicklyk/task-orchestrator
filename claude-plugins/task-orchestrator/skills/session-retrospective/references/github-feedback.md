# GitHub Feedback Filing (Improvement Proposals)

Shared conventions for turning a **global-scoped** improvement proposal into a GitHub issue on the
upstream repo. Used by `session-retrospective` Step 7c (filing) and referenced by `review-proposals`
(issue reuse when confirming a global proposal) as
`<skill-base-dir>/../session-retrospective/references/github-feedback.md`.

Project-scoped proposals never reach this file — they anchor under their project root instead
(session-retrospective Step 7b) and are applied to config, not filed upstream.

---

## Config gate

Read `retrospective.github_feedback` from the workspace `.taskorchestrator/config.yaml` (direct
file read, not an MCP call):

```yaml
retrospective:
  github_feedback:
    enabled: true                      # default false — opt-in
    repo: jpicklyk/task-orchestrator   # optional; this is the default
```

`enabled` must be exactly `true` to proceed. A missing block, `enabled: false`, or any other value
means skip all filing for this run — record `github filing: disabled` in the caller's closure note.
`repo` defaults to `jpicklyk/task-orchestrator` when absent.

---

## C5 — gh availability guard

Before any `gh` call:

```bash
gh auth status
```

Non-zero exit means skip filing gracefully and record the reason (e.g. `gh unavailable/unauthenticated`).
Never fail the parent workflow on this guard — like every step below, it is best-effort.

---

## Dedup procedure (cheapest first)

File at most one issue per distinct proposal. Check in this order and stop at the first hit:

1. **Sibling proposals' `github-issue:` lines** (cap 10 note reads) — for related proposals already
   anchored under the global "Improvement Proposals" container, read their proposal notes with
   `query_notes(operation="list", itemId="<uuid>", includeBody=true)` and look for a trailing
   `github-issue: <url>` line (see Record-back below). Reuse that URL if one describes the same change.
2. **GitHub search** — if no sibling match, search the tracker directly:
   ```bash
   gh issue list --repo <repo> --state all --search "<3-5 distinctive keywords>" --json number,title,url --limit 10
   ```
   Pick 3-5 distinctive keywords from the proposal title/summary (skip generic words like "improve",
   "add", "fix"). If a returned issue plainly describes the same change, reuse its `url`.

Only file a new issue when both checks come up empty.

---

## C4 — Issue template

**Title:** `[proposal] <concrete change description>` — mirror the proposal's own concrete-change
title.

**Body** (three sections plus footer):

```markdown
## Problem

<what recurring pattern or gap the trend surfaced — 1-3 sentences>

## Proposed change

<the concrete change: exact YAML, skill section + edit, output-style zone, hook event/matcher —
whatever the proposal specifies>

## Expected effect

<what improves once this lands>

---
Filed automatically by the task-orchestrator session-retrospective skill.
```

**Scrub rule** — before writing the body file, strip:
- Absolute filesystem paths (keep repo-relative paths only)
- Machine names and user/account names
- Private project names — replace with "a downstream project"

Never paste a `session-tracking` note body verbatim into the issue; synthesize the Problem /
Proposed change / Expected effect prose from the trend and proposal data instead.

**Filing:**

```bash
gh issue create --repo <repo> --title "[proposal] <...>" --body-file <scratchpad-tmp-path> --label enhancement
```

- Always use `--body-file` pointing at a scratchpad temp file — never pass multi-line text via
  `--body`, and never rely on an editor fallback. Background retrospective agents are non-tty; an
  editor prompt would hang.
- If `gh` reports the `enhancement` label doesn't exist on the repo, retry once without `--label`.

---

## C2 — Record-back

On success (filed or reused), append a final line to the **proposal note body** — not the item
summary — durable and greppable by both skills:

```
github-issue: <url>
```

Re-upsert the note with the appended line, using its existing `key`/`role` (the proposal's schema
note, typically `proposal`, queue phase) — this is a re-upsert of the same note, not a new one:

```
manage_notes(operation="upsert", notes=[{
  itemId: "<proposal-uuid>",
  key: "proposal",
  role: "queue",
  body: "<existing body>\n\ngithub-issue: <url>"
}])
```

---

## Best-effort discipline

Every sub-step above — config gate, gh guard, dedup, filing, record-back — is best-effort. A
failure at any point (network error, `gh` crash, rate limit, note-upsert failure) is caught, logged
as a one-line reason in the caller's closure note, and the parent workflow continues unaffected.
Filing a GitHub issue must never be the reason a retrospective or proposal review fails to complete.

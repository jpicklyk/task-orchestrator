---
name: review-retrospectives
description: "Reviews the retrospective record the user already has: summarises recent retrospectives, trends, bug observations and improvement proposals as one dashboard, suggests ranked actions grouped by product, global-process and project, writes the trend changes the user confirms, and hands pending proposals to review-proposals. Optional flags add the audit sweep and deep matching. Use when the user wants to look over retrospective results and decide what to do next."
argument-hint: "[--last <N>] [--audit] [--deep]"
disable-model-invocation: true
---

# Review Retrospectives

The user-run front door to the retrospective record: Summarise, Suggest, Decide. It reads what `session-retrospective` runs left behind, asks the user what to change, and writes only what the user confirms. It never analyses a run; that is `session-retrospective`'s job.

Call shapes: [`../_generated/call-shapes.md`](../_generated/call-shapes.md); every write answers `data.results[i]` (`ok`, `error.code`).

## Step 0 - Arguments and Config

Parse `$ARGUMENTS`:

- `--last <N>`: how many recent retrospectives to read. Default 10, maximum 30.
- `--audit` and `--deep`: opt-in flags that fan agents out; see Step 2.

Read the config file directly (a file read, not an MCP call): the path on the SessionStart context's `Config:` line when there is one, otherwise `.taskorchestrator/config.yaml`. `project.rootId` enables the project-scoped reads below, and `<root8>` below is its first 8 hex characters; with no rootId, work global-only.

## Step 1 - Summarise

Read, in parallel where independent. Nothing in this step writes.

**Retrospectives.** The N most recent:

```to-call
{"tool": "items_read", "select": "list", "args": {"tags": ["session-retrospective"], "sort": "createdAt", "order": "desc", "limit": 10}}
```

For each retrospective `get` its `workflow-evaluation` note (the dimension scores) and its `session-metrics` note (the `Recorded share:` line):

```to-call
{"tool": "notes_read", "select": "get", "args": {"itemId": "<retro-uuid>", "key": "workflow-evaluation"}}
```

**Trends.** The active ones; follow `page.next` while a page is full:

```to-call
{"tool": "items_read", "select": "list", "args": {"tags": ["retrospective-trend"], "role": "queue", "limit": 100, "fields": ["summary"]}}
```

A trend tagged `project-<root8>` is project scope. A trend with no `project-*` tag is global. A trend tagged with another root's `project-*` belongs to another project: leave it out. A tag cannot hold a colon, so no other spelling is valid. Direction: `new` when `Sessions: 1`, `recurring` when `Last seen` falls inside the N-retrospective window, `quiet` when older. Read the latest 3 evidence notes of the standing trends `fix-cycle-cost` and `retro-cost`.

**Bug observations.** Non-terminal `agent-observation` items tagged `bug`, with the `github-issue:` line of each `observation-detail` note; an absent line means no issue:

```to-call
{"tool": "items_read", "select": "list", "args": {"type": "agent-observation", "tags": ["bug"], "limit": 100}}
```

**Proposals.** `improvement-proposal` items under the global `Improvement Proposals` container and, with a rootId, under the root, grouped by role: queue pending or deferred (an `adoption-decision` note starting "deferred"), work stalled, review awaiting verification. Age is the days since `roleChangedAt`. Find the global container first:

```to-call
{"tool": "items_read", "select": "search", "args": {"query": "Improvement Proposals", "kinds": ["item"], "limit": 5}}
```

A search hit carries no parent: take the hit titled exactly `Improvement Proposals` and confirm it is a root (`items_read` `get`, no `parentId`); a per-project container of that title sits under a project root and is not the global one. Then list each of the container and the root, following `page.next` while a page is full:

```to-call
{"tool": "items_read", "select": "list", "args": {"type": "improvement-proposal", "ancestorId": "<container-or-root-id>", "limit": 200, "fields": ["roleChangedAt"]}}
```

**Empty path, before any dashboard.** When there is no `session-retrospective`, `retrospective-trend` or `improvement-proposal` item, print exactly `No retrospective data found: no retrospectives, trends or proposals.` and stop.

Otherwise print one dashboard: the retrospectives with their dimension scores and recorded share, the trends by scope and direction, the bug observations with their issue, the proposals by role with age, and the two standing trends' latest evidence.

## Step 2 - Suggest

Rank actions in three groups, each action citing the evidence ids (8 hex characters) behind it:

- **product**: server or plugin code.
- **global-process**: floor config and bundled rules.
- **project**: per-root config, rules and project skills; the trends tagged `project-<root8>` belong here.

Rank by sightings (trend `Sessions`), then recency. A global trend's split between product and global-process is a judgment: label it `inferred`. This step writes nothing.

`--audit` and `--deep` are opt-in because they launch up to 30 agents. They run only in the main session, with the Workflow tool callable; without it say so and stay inline. Their steps are in [`references/deep-mode.md`](references/deep-mode.md). Their results arrive here as candidate trend creates, recurrences and retires for Step 3.

## Step 3 - Apply Confirmed Changes

The only step that writes. Ask once with `AskUserQuestion`, listing each candidate trend create, recurrence and retire (batches of up to 4 questions per call), and write only what the user confirmed. A deep `matched` candidate is skipped when the trend already has an `evidence-*` note for that retrospective's short id, so a sighting is never counted twice.

- **Recurrence:** upsert the evidence note, then update the trend summary: change only `Sessions: N+1` and `Last seen: <date>` and keep every other line, because lines appended by other skills (`GRADUATED -> proposal <short-id>`, review-proposals' `rejected <date> - do not re-graduate` line) must survive:

```to-call
{"tool": "notes_write", "op": "upsert", "args": {"itemId": "<trend-uuid>", "key": "evidence-<YYYY-MM-DD>-<retro-short-id>", "role": "work", "body": "<what happened, retrospective item id, cost or impact>"}}
```

```to-call
{"tool": "items_write", "op": "update", "args": {"id": "<trend-uuid>", "summary": "<current summary with Sessions and Last seen replaced, every other line kept>"}}
```

- **New pattern:** create the trend under its scope's `Retrospective Trends` container, then its first evidence note as above. A global trend sits under the process-global container, a depth-0 root item found by an unscoped `items_read` `search` for the exact title, keeping only the hit with no parent:

```to-call
{"tool": "items_write", "op": "create", "args": {"type": "retrospective-trend", "title": "trend: <kebab-key> - <one-line claim>", "tags": ["retrospective-trend", "<dimension>"], "parentId": "<global-trends-container-id>", "priority": "low", "summary": "<observation>. Sessions: 1. Last seen: <date>."}}
```

  A project trend adds the tag `project-<root8>` and sits under the project's own container: a `Retrospective Trends` container that is a child of the project root (list `parentId` the root, `type: "container"`, match the exact title; else create it with `type: "container"`, `tags: ["container"]`, `parentId` the root):

```to-call
{"tool": "items_write", "op": "create", "args": {"type": "retrospective-trend", "title": "trend: <kebab-key> - <one-line claim>", "tags": ["retrospective-trend", "<dimension>", "project-<root8>"], "parentId": "<project-trends-container-id>", "priority": "low", "summary": "<observation>. Sessions: 1. Last seen: <date>."}}
```

- **Retire** (addressed, obsolete, superseded):

```to-call
{"tool": "advance", "op": "transition", "args": {"itemId": "<trend-uuid>", "trigger": "cancel", "summary": "archived: <reason>"}}
```

Only `create`, `update`, note upserts and `cancel` touch a trend, never `start` or `complete`. Check each element's `ok`.

## Step 4 - Decide

With pending proposals (queue, no `adoption-decision`, or stalled), ask once with `AskUserQuestion`: `Triage now (Recommended)` or `Later`.

- **Triage now:** read `../review-proposals/SKILL.md` and follow it from its Step 0, so its config defaults (`github_feedback`, the personal-scope rule) apply. The Skill tool cannot start `review-proposals`, which only the user invokes.
- **Later:** print `/task-orchestrator:review-proposals`.

With none pending, print only the count of proposals awaiting verification and stop.

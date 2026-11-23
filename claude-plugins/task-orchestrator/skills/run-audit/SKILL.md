---
name: run-audit
description: "Front door for the deep codebase audit workflow: builds its launch arguments (scope, preset, dispatch classes, backlog), confirms the cost with the user, launches task-orchestrator:deep-codebase-audit, renders the result as an HTML report and offers to turn the findings into items. Use when a user says: run an audit, deep audit, audit the codebase with the workflow, run the deep codebase audit. NOT for a single-pass audit inside this session (that is auditing-codebase) and NOT for an audit of documents alone by one reviewer (auditing-documentation)."
argument-hint: "[paths...] [--preset quick|standard|full] [--focus code|docs|all] [--out <file.html>]"
---

# run-audit - Deep Audit Front Door

The `task-orchestrator:deep-codebase-audit` workflow measures the code, fans reviewers out over slices, verifies the findings and returns an `audit/result-v2` result. It refuses a launch without `dispatchClasses`, so a hand launch needs the whole argument object. This skill builds that object, launches the workflow, renders the HTML report and offers item creation. It creates nothing itself.

## Director mode

Run only in the main session with the `Workflow` tool callable. The director guard denies this launch, so in director mode say so and offer to leave director mode with `director off`; do nothing else.

## Step 1 - Resolve the scripts directory

Resolve `<scriptsDir>` once and reuse it:

1. Try `${CLAUDE_PLUGIN_ROOT}/scripts`. Confirm the literal string `${CLAUDE_PLUGIN_ROOT}` did not survive into the path, and that `<scriptsDir>/audit-report-html.mjs`, `<scriptsDir>/audit-metrics.mjs` and `<scriptsDir>/audit-scope.mjs` all exist.
2. Otherwise use `<git toplevel>/claude-plugins/task-orchestrator/scripts` when that directory exists.
3. Otherwise stop and tell the user to refresh the plugin cache (`claude-plugins/CLAUDE.md`, "Plugin Discovery and Cache Refresh"). Every plugin file reaches an installed session only through that forced refresh.

## Step 2 - Build the args

Build one `audit/args-v2` object:

| Field | Value |
|-------|-------|
| `contract` | `audit/args-v2` |
| `runId` | `a-<YYYYMMDDHHmm>` (must start with `a-`) |
| `date` | today, `YYYY-MM-DD` |
| `repo.root` | the git toplevel |
| `scope.paths` | the JSON array printed by `node "<scriptsDir>/audit-scope.mjs" --root <git toplevel>`, with `--paths <the user's paths, comma-separated>` when the user names paths; a non-zero exit stops the skill with its message |
| `preset` | `--preset`, default `quick` |
| `focus` | only when `--focus` is given |
| `scriptsDir` | Step 1 |
| `workDir` | `<scratchpad>/audit/<runId>` |
| `reportPath` | `--out`, else `<workDir>/report.html` |
| `items.rootId` | the project root id: the session's `## Project Scope` section or the config's `project.rootId`. Omit `items` when there is none; a personal root never sets it |
| `backlog` | `items_read` `list` with `ancestorId` the root id, `tags` `["bug", "tech-debt", "audit"]`, `limit` 200; drop terminal rows; map each to `{id: <short id>, title}`. First page only: the workflow's triage step still searches unscoped |
| `dispatchClasses` | the `classes` object of `<scriptsDir>/../dispatch-policy.json`, passed unchanged |

`models` and `efforts` are set only for a per-phase-key override the user names. Set `allowLarge: true` only for `standard` or `full`, and only after the user confirms the projected agent count. Never write a model or effort value in this skill or invent one: they come from `dispatchClasses`.

## Step 3 - Confirm before launch

State the `reportPath` and that items will cite it, then show the two tables below, with the model and effort of each class filled from the `dispatchClasses` just read, and wait for the user's confirmation. The default `maxAgents` is 60; `quick` fits it.

### Presets

| Preset | Reviewers | Gap reviewers | Verify cap | Projected agents | Projected agents, no project root |
|--------|-----------|---------------|------------|------------------|-----------------------------------|
| quick | 4 | 1 | 20 | 34 | 30 |
| standard | 8 | 2 | 40 | 143 | 135 |
| full | 16 | 4 | 100 | 341 | 325 |

### Dispatch

| Phase key | Class |
|-----------|-------|
| measure | audit-measure |
| scope | research |
| review | audit-review |
| review-architecture | review |
| critic | research |
| merge | extraction |
| verify | research |
| verify-high | audit-verify-high |
| synth | audit-synthesis |
| triage | extraction |

A guard test keeps both tables equal to the workflow's own values.

## Step 4 - Launch

Call `Workflow({name: "task-orchestrator:deep-codebase-audit", args})` with `args` as a real object, not a string, and end the turn. The launch uses the default `items.policy` (`gated`).

## Step 5 - Render the report

On the completion notification:

- `started: false`: report the `reason` and stop.
- Otherwise take the result file: the task output file when the notification is cut short (the renderer accepts that wrapper); else write the result as JSON to `<workDir>/result.json`. Run:

```
node "<scriptsDir>/audit-report-html.mjs" --result <file> --out <reportPath>
```

Add `--metrics <result.metricsPath>` only when `metricsPath` is non-null and that file exists; without it the report says metrics were not available.

- Exit 0: send the report file with `SendUserFile` when the host has it, else give its absolute path.
- Exit 2: report the `INVALID` line and stop; do not go on to Step 6.

## Step 6 - Offer the findings as items

Only after the renderer exited 0. When `result.proposal` is null, say there is no project root, so no proposal, and stop.

Otherwise ask one `AskUserQuestion` with the options `gated`, `bugs`, `all` and `none`. Each label carries the count of non-observation rows whose `selectedBy` column selects it; `gated` also carries the observation count, because only `gated` can select an observation. `none` ends the skill.

For any other choice, invoke `task-orchestrator:create-item` at "From a workflow findings proposal" with the chosen `items.policy`. Its confirmation rules apply; this skill creates nothing.

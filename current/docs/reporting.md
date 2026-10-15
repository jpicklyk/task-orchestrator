# Reporting

The `report` tool turns the server's own telemetry into aggregates: how the tools are used, where gates reject work,
where claims and leases contend, where call patterns waste effort, and what one subtree or one run cost. This guide
explains what each select is for, how to read the bases behind a number, and what retention does to an answer.

It does not list fields. Field-level contracts (the request, the answer shape, the window caps and the error codes) are in
the [API reference](api-reference.md#report), and the retention values are in
[Data Retention](fleet-deployment.md#data-retention). Both are linked where they matter below.

## What `report` is

`report` is a read-only tool. Every answer is built from pointers and counts: it never returns a title, a note body, a
document body or a call argument.

- **Where it is served.** On MCP it belongs to the **director** profile only: `/mcp/director` over HTTP, or a stdio
  server started with `MCP_TOOL_PROFILE=director`. Over REST it is `POST /api/v1/ops/report`, and a director-profile
  credential reaches it at `POST /api/v1/ops/director/report`. It needs the `read` capability.
- **What a caller sees.** Visibility follows the credential's scope. A root-scoped caller sees only its roots, and
  `rootId` narrows an answer to one project root. A tag-scoped caller is refused `usage`, `waste` and `rollup`, because call
  rows carry no tags, but may read `gates` and `contention`, which read events. Grouping `usage` by `principal` needs
  `admin`. The exact rules are in the [API reference](api-reference.md#report), and the profile and confinement rules
  in [Tool profiles](api-reference.md#tool-profiles).
- **What it is not.** It is a pull query over the database, not a metrics endpoint. There is no time series, no
  percentile and no alerting; see [Observability Gaps](fleet-deployment.md#observability-gaps).

## Which select answers which question

| Question | Select | Reads |
|---|---|---|
| Which tools, hosts or principals make the calls, how often do they fail, and how big are the answers? | `usage` | The call log, and the per-day summaries behind it for old windows |
| Where do transitions get rejected: which missing note, which type, which trait? | `gates` | Rejection events |
| Which items or resources are fought over, and how often do claims and leases expire or get forced? | `contention` | Claim and lease events |
| Are agents fetching more than they use, reading and never writing, or hitting empty queues? | `waste` | The call log, with session joins |
| How big is this part of the tree, how much of it is blocked, and what did working on it cost? | `rollup` with `anchorId` | Items, events and the call log |
| How did this run go: members, blocked reasons, decisions, seats, attempts, budget and cost? | `rollup` with `runId` | The run, its members and the events and calls stamped with it, or the snapshot taken when it closed |

`rollup` takes exactly one of `anchorId` and `runId`. An anchor rollup covers the anchor and every descendant over a
window. A run rollup always covers the whole run and takes no window.

## Metrics and bases

A number in a report is only as good as its basis. These are the ones to know.

- **Token figures are estimates.** `responseTokensEst` is the response size in bytes divided by four. It is a sizing
  aid for comparing tools and hosts, not a count from any model's tokenizer.
- **`window.basis` for `usage`: `raw` or `daily`.** A window of at most 31 days that the raw call log fully holds is read
  from the raw table (`raw`). Anything longer, or any window that starts before pruned days, is read from the per-day
  summaries plus the raw tail (`daily`). On the daily basis `since` is floored to its UTC midnight, and an `until` that
  ends inside already-folded days is ceiled to the next midnight, so the echoed window may be wider than the one you
  asked for. `groupBy: principalKind` groups by the credential's kind (`human`, `agent` or `service`; the local principal is `agent`); rows logged before the kind was recorded keep `external` and are not backfilled, so a window across that upgrade shows an `external` group beside the real kinds. `groupBy: principal` needs the raw table and is `invalid_request` when the window needs the daily basis.
  The other selects (`gates`, `contention` and `waste`) read events or the raw call log and always report `basis: raw`; `rollup` carries no `window.basis`.
- **`cost.basis` on a rollup: `targets` or `run`.** An anchor rollup counts the calls that name a subtree item or that
  wrote one of the subtree's events (`targets`). A run rollup counts the calls stamped with the run id (`run`). Both
  are lower bounds: a call that named no item and wrote nothing, or was never bound to the run, is missed.
- **`budget.basis: enforced`.** A run's token budget in a rollup comes from the run row, the figure the budget check
  enforces (the larger of the reported and the observed tokens). It is not summed from the call log, so it can differ
  from the rollup's `cost` and does not shrink when the call log is pruned.
- **`basis: mcp_sessions` on `waste`.** The session signals (`redundantReads`, `readsWithoutLaterWrite` and the
  `relatedFollowThrough` signals) are computed over MCP calls only. REST calls carry no session, so a REST-only
  workload shows nothing in them, and related follow-through cannot be attributed on REST at all. The other `waste`
  signals cover both surfaces.
- **The `gates` join for `type` and `trait` is as of now.** A rejection is grouped by the item's current type and
  traits, not by those it had when it was rejected. After a retype or a trait change, old rejections move with the item,
  and a deleted item falls in the group with the empty key.
- **Empty keys are not nulls.** A group whose grouped column is missing has a `key` of `{}`, and it sorts first.

## Retention and what a report can see

Pruning changes what a window can answer. The pruner and its settings are described in
[Data Retention](fleet-deployment.md#data-retention); this is how that shows up in `report`.

Every select except `rollup` reports `window.retainedSince`, the oldest raw row still held of the select's own source,
and sets `window.truncatedByRetention: true` when the window starts before the oldest data held. Read a truncated
window as partial.

- **`usage`.** The call log is pruned only for days already folded into the per-day summaries, so a window older than
  the raw horizon is not lost: it is answered on the daily basis. What the daily basis gives up is the per-principal
  grouping, and precision at the window edges (whole UTC days). `truncatedByRetention` on the daily basis compares the
  window with the older of the raw horizon and the earliest folded day. The summaries themselves are not pruned.
- **`gates`.** Rejection events are activity-class and are pruned by the activity-event setting. `retainedSince` is the
  oldest rejection held, so a window older than that counts only what survived.
- **`contention`.** Claim and lease events are activity-class too. The pruner always keeps the latest `claim.expired`
  row of each item, however old, so the oldest event held can be much older than the bulk of the data. Treat
  `retainedSince` here as an upper bound on how far back the data is complete, not as a promise.
- **`waste`.** It reads the call log over at most seven days, so it only meets pruning when the call-log setting is
  shorter than the window, which the defaults are not.
- **Anchor `rollup`.** The window carries no `retainedSince`, so a pruned source shows up as a low number, not as a
  flag. `transitions.total`, the cascades and the blocked and item counts come from audit events and live rows, which
  the defaults keep. `transitions.rejected` and `notes.written` come from activity events, and `cost` from the call
  log, so for a subtree whose events are older than those horizons they read low. When in doubt, ask `gates` or `usage`
  for the same period and compare `retainedSince`.
- **Run `rollup`.** A closed run answers the snapshot stored in its `run.ended` event, so its figures survive both
  pruning settings and ignore calls logged after the close. The `run.*` events are audit-class and are kept at the
  default of the audit setting. If a deployment sets the audit setting above zero and the `run.ended` row has been
  pruned, or the run was closed before snapshots existed, the answer is computed live from what retention left: the
  members, the run row and the audit events are intact, while rejections, notes written and cost are partial. An open
  or paused run is always computed live.

## Recipes

All requests below use the 4.0 call shape: a `select` plus that select's fields. Ids may be a full UUID or 8 to 32 hex
characters. Windows are half-open `[since, until)`.

Where do the calls go, by tool, over the default last 7 days?

```json
{ "select": "usage", "groupBy": "tool" }
```

Which days were the busy ones, and from which clients? Use `day` for the shape and `host` for the clients.

```json
{ "select": "usage", "groupBy": "day", "since": "2026-10-01T00:00:00Z", "until": "2026-10-08T00:00:00Z" }
{ "select": "usage", "groupBy": "host", "rootId": "550e8400", "limit": 20 }
```

Which note keys are the gates stopping work on most?

```json
{ "select": "gates", "groupBy": "noteKey" }
```

Which resources or items see the most rejected claims and leases?

```json
{ "select": "contention", "since": "2026-10-01T00:00:00Z" }
```

Are agents wasting calls this week?

```json
{ "select": "waste", "since": "2026-10-06T00:00:00Z", "until": "2026-10-13T00:00:00Z" }
```

How is one feature tree doing, and what did it cost?

```json
{ "select": "rollup", "anchorId": "1be2db5e" }
```

Post-mortem of a run: members, blocked reasons, decisions, seats, attempts, budget and cost.

```json
{ "select": "rollup", "runId": "7c0f3a91" }
```

## Known limits

- **Token figures are estimates** (bytes divided by four), and `cost` on a rollup is a lower bound by construction.
- **A run's cost is only what was stamped with the run.** Calls made without the run's seat or coordinator token do not
  count towards it.
- **`waste` is a seven-day window** and its session signals are MCP-only.
- **Reports are as-of-now for `type` and `trait`**, not as-of-rejection.
- **There is no metrics endpoint and no time series.** `report` answers a window on demand; keep your own history if you
  want a trend.
- **Pruning is not flagged on every answer.** `rollup` has no `retainedSince`, and `contention` reports the oldest event
  it holds, which the pruner's keep-latest rule can make older than the data around it.

## Related

- [API reference: Reports](api-reference.md#report) for the request, the answer shape and the error codes.
- [Fleet deployment: Data Retention](fleet-deployment.md#data-retention) for the retention settings, the fold and the
  growth estimates.
- [Fleet deployment: Observability Gaps](fleet-deployment.md#observability-gaps) for what the server does not provide.
- [Workflow guide](workflow-guide.md) for runs, seats and decisions, the subjects of a run rollup.
- [REST API](api-rest.md) for the `/api/v1/ops` routes.
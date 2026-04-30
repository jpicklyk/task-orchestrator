# Fleet Deployment Guide

This guide is written for operators deploying the MCP Task Orchestrator to production multi-agent fleets. It covers identity configuration, SQLite tuning, capacity planning, observability gaps, and the tiered claim disclosure design.

For single-agent or local-dev setups, the defaults are appropriate and this guide can be skipped.

**Companion docs:**
- [API Reference — `claim_item`](api-reference.md#claim_item) — tool spec: parameters, outcome codes, examples
- [Workflow Guide §10 — Claim Mechanism](workflow-guide.md#10-claim-mechanism-for-multi-agent-fleets) — agent-side lifecycle, heartbeat pattern, discovery
- This guide — operator-side: identity policy, capacity, disclosure, observability

---

## Identity Configuration — `auth.degradedModePolicy`

The `degradedModePolicy` field under `auditing:` in `.taskorchestrator/config.yaml` controls how the server resolves actor identity when JWKS verification cannot produce a fully verified result.

```yaml
auditing:
  enabled: true
  degraded_mode_policy: accept-cached   # see table below
  verifier:
    type: jwks
    oidc_discovery: "https://your-oidc-provider/.well-known/openid-configuration"
    issuer: "https://your-oidc-provider"
    audience: "task-orchestrator"
    algorithms: ["EdDSA", "RS256"]
    cache_ttl_seconds: 300
    require_sub_match: true
```

### `DEGRADED_MODE_POLICY` Environment Variable

The `DEGRADED_MODE_POLICY` environment variable overrides the YAML `degraded_mode_policy` value at runtime. It is evaluated at server startup and takes precedence over any value set in the config file.

```bash
# Docker — set reject policy for fleet deployments
docker run --rm -i \
  -v mcp-task-data:/app/data \
  -v "$(pwd)"/.taskorchestrator:/project/.taskorchestrator:ro \
  -e AGENT_CONFIG_DIR=/project \
  -e DEGRADED_MODE_POLICY=reject \
  task-orchestrator:dev
```

Valid values (case-insensitive): `accept-cached`, `accept-self-reported`, `reject`. An invalid value causes an immediate startup failure with a descriptive error message. If unset, the YAML value applies; if neither is set, the server defaults to `accept-cached`.

**Recommended for cross-org fleet deployments:** `DEGRADED_MODE_POLICY=reject` — ensures that agents without a valid JWT in `actor.proof` cannot claim items or advance claimed items, regardless of what the YAML config contains.

### Policy Values

| Policy | Identity used | Recommended for |
|---|---|---|
| `accept-cached` | *(default)* Verified `actor.id` from JWT when stale JWKS cache was used (`UNAVAILABLE` status + stale cache). Self-reported `actor.id` for all other non-verified outcomes. | Single-org deployments; JWKS endpoint occasionally unreachable |
| `accept-self-reported` | Always use self-reported `actor.id` from the caller, regardless of verification result. Equivalent to v3.2 implicit behavior. | Local dev; no JWKS; explicitly documented opt-out of identity guarantees |
| `reject` | Reject any operation requiring verified identity when the actor is not fully verified. `claim_item` returns `rejected_by_policy`. `advance_item` on claimed items fails. | Cross-org `did:web` deployments; high-assurance environments |

### Cross-Org `did:web` Deployments

For deployments where agents from different organizations share a single Task Orchestrator instance, use `reject`:

```yaml
auditing:
  enabled: true
  degraded_mode_policy: reject
  verifier:
    type: jwks
    jwks_uri: "https://your-org-idp/.well-known/jwks.json"
    issuer: "https://your-org-idp"
    require_sub_match: true
```

`did:web` identifiers (e.g., `did:web:agent.example.com`) work as `claimedBy` values natively — they are opaque strings and require no special handling.

Under `reject`, any agent without a valid JWT in `actor.proof` cannot claim items or advance claimed items. Unclaimed items remain accessible to unverified actors (to preserve backward compatibility for mixed fleets during migration).

---

## SQLite Tuning — `DATABASE_BUSY_TIMEOUT_MS`

SQLite is a single-writer database. Under concurrent fleet load, write operations may queue and return `SQLITE_BUSY` if the writer lock is held too long. The `DATABASE_BUSY_TIMEOUT_MS` environment variable controls how long SQLite waits for the lock before returning an error.

```bash
# Set a longer timeout for a fleet with 30+ agents
DATABASE_BUSY_TIMEOUT_MS=15000
```

### Recommended Values

| Fleet size | Recommended timeout |
|---|---|
| 1–10 agents | 5000ms (default) |
| 10–30 agents | 10000–15000ms |
| 30–50 agents | 15000–30000ms |
| 50+ agents | 30000ms + review architecture (see Capacity Planning below) |

### The 30-Second Ceiling

Beyond 30 000ms (30s), you are medicating a capacity problem rather than solving it. A caller blocked for 30s is not making forward progress — and queuing more writes behind it only delays them further.

**If you need more than 30s, the right intervention is architectural:**
- Partition work across multiple independent orchestrator instances (different SQLite databases, different work item trees)
- Reduce the number of agents polling the same instance
- Implement work batching so agents make fewer but larger writes

### Sanity Floor

Values below 100ms are clamped to 100ms. Values that cannot be parsed as integers fall back to the 5000ms default.

### Docker Example

```bash
docker run --rm -i \
  -v mcp-task-data:/app/data \
  -v "$(pwd)"/.taskorchestrator:/project/.taskorchestrator:ro \
  -e AGENT_CONFIG_DIR=/project \
  -e DATABASE_BUSY_TIMEOUT_MS=15000 \
  task-orchestrator:dev
```

---

## Capacity Planning

SQLite is a single-writer database backed by a file on a single host. Understanding this constraint is essential for fleet sizing.

### Write Rate Estimates

| Activity | Write rate estimate |
|---|---|
| Role transition per agent | ~1 write per transition |
| Heartbeat per claimed item | ~1 write per TTL/2 interval (default: every 450s) |
| Claim acquisition | ~2 writes per claim (release prior + claim new) |
| Note upsert | ~1 write per note |

At 30 agents with a steady work rate: approximately 30–60 transitions/minute + 4 heartbeat writes/minute = 34–64 total writes/minute. SQLite can sustain hundreds of writes per minute under favorable conditions, but real latency depends on disk I/O, lock contention, and the busy timeout.

### Recommended Ceiling

**A single SQLite instance realistically supports approximately 50–150 independent polling agents** under steady workload. Past that, write lock saturation becomes the bottleneck. Symptoms:
- Agents frequently receive `SQLITE_BUSY` or `TRANSIENT` kind errors from tool calls
- `advance_item` latency grows beyond 500ms per call
- `DATABASE_BUSY_TIMEOUT_MS` at 30s still produces timeout errors

These are signals to partition the work, not to increase the timeout further.

### Partitioning Strategy

The cleanest fleet partition is by work tree:
- Instance A handles feature containers A–M
- Instance B handles feature containers N–Z
- Each agent targets a specific instance based on its assignment

Agents use `parentId` scoping on `get_next_item(parentId=...)` to restrict their search to their assigned subtree, avoiding cross-instance work stealing.

---

## Identity Model

### `claimedBy` as Opaque String

The `claimedBy` field on a `WorkItem` is an uninterpreted opaque string. The server treats it as a key for ownership comparison — it does not parse or validate its structure. Valid values include:

- Agent session IDs: `"session-abc123"`
- Container hostnames: `"worker-pod-7.cluster.local"`
- JWT `jti` claim values
- `did:web` identifiers: `"did:web:agent.example.com"`
- Any stable, unique per-agent string

### JWKS-Verified Identity Override

When a JWKS verifier is configured and the `actor.proof` JWT is valid, the server uses the JWT `sub` claim as the trusted identity. This overrides any `agentId` parameter on individual claim entries.

The identity resolution chain:
1. `actor.proof` JWT present and valid → use JWT `sub` claim as `claimedBy`
2. `actor.proof` missing/invalid, `degradedModePolicy=accept-cached` → use self-reported `actor.id`
3. `actor.proof` missing/invalid, `degradedModePolicy=accept-self-reported` → use self-reported `actor.id`
4. `actor.proof` missing/invalid, `degradedModePolicy=reject` → reject the operation (`rejected_by_policy`)

---

## Tiered Claim Disclosure

The server intentionally restricts where `claimedBy` identity is visible. This design prevents three fleet failure modes:

- **Identity leakage.** Cross-org deployments should not expose which agent holds which item.
- **Claim sniping.** If agents could see who holds a claim, a misbehaving agent could time its claim attempt to intercept work from a specific competitor.
- **Jealousy patterns.** Agents should not make routing decisions based on which peer holds a claim; they should simply pick a different item or wait for TTL expiry.

### Per-Surface Disclosure Table

| Surface | Claim data exposed |
|---|---|
| `get_context(itemId)` | Full claim detail: `claimedBy`, `claimedAt`, `claimExpiresAt`, `originalClaimedAt`, `isExpired` |
| `claim_item` success response | Own claim metadata only: `claimedBy`, `claimedAt`, `claimExpiresAt`, `originalClaimedAt` |
| `claim_item` `already_claimed` failure | `retryAfterMs` only — competing agent identity never disclosed |
| `get_next_item(includeClaimed=true)` | `isClaimed: boolean` per item only — no identity |
| `query_items(search, claimStatus=...)` | `isClaimed: boolean` per item only — no identity |
| `query_items(overview, global)` | `claimSummary: { active, expired, unclaimed }` per root — counts only |
| `get_context()` health-check | `claimSummary: { active, expired }` globally — counts only |

`get_context(itemId)` is the operator diagnostic tool. All other surfaces use count-only or boolean signals.

---

## Observability Gaps

As of v3.4, the MCP Task Orchestrator ships with **no metrics endpoints**. There is no Prometheus/Micrometer integration, no `/metrics` HTTP path, and no structured event stream.

Fleet operators should treat this as a known gap when planning production rollouts.

### Available Signals Today

| Signal | How to access |
|---|---|
| Active claim count | `get_context()` → `claimSummary.active` |
| Expired claim count | `get_context()` → `claimSummary.expired` |
| Per-root claim breakdown | `query_items(operation="overview")` → `claimSummary` per root item |
| Stalled items (missing required notes) | `get_context()` → `stalledItems` |
| Recent role transitions | `get_context(since="<timestamp>")` → `recentTransitions` |
| Audit log | `auditing.enabled: true` in config — actor claims persisted on write operations; queryable via `query_notes` and `get_context` session-resume mode |

The audit log via `auditing.enabled` is the only structured per-operation signal available today. Actor claims (including verification status and `parent` chain) are persisted with each write, enabling post-mortem analysis.

### Known Gaps — Plan Accordingly

- No write latency histograms
- No `SQLITE_BUSY` error rate counters
- No claim acquisition success/failure rate
- No per-agent throughput tracking
- No alerting integration

If your fleet rollout requires real-time dashboards or alerting on these signals, plan to instrument them at the client side (agent telemetry) or proxy level until server-side metrics are added.

Metrics and observability infrastructure are explicitly deferred to a future release (see issue tracker). The audit log is the recommended bridge for compliance and post-incident review until then.

---

## Claims Troubleshooting

Common operator scenarios when running a multi-agent fleet against the claim mechanism.

### Repeated `already_claimed` on the same item

An agent retrying a claim and getting `already_claimed` back-to-back is **expected** behavior — another agent holds the item and retry will not change that until the existing TTL elapses or the holder explicitly releases.

| Symptom | Recommended response |
|---|---|
| `retryAfterMs` < ~10s | Pick a different unclaimed item via `get_next_item`. Holder is actively working. |
| `retryAfterMs` close to full TTL | Holder either just claimed or just heartbeated. Pick a different item. |
| Same item, repeated retries, never resolves | Use `get_context(itemId)` to read `claimDetail.originalClaimedAt`. If the value is hours old, suspect a crashed holder — see "Stale `originalClaimedAt`" below. |

`already_claimed` never discloses the competing agent's identity by design (see Tiered Claim Disclosure above). Use `get_context(itemId)` for full diagnostics.

### `rejected_by_policy` on `claim_item` or `advance_item`

This outcome means `degradedModePolicy=reject` is configured and the caller's actor proof did not produce a fully-verified identity (the verifier returned `ABSENT`, `REJECTED`, or `UNAVAILABLE`).

**Recovery checklist:**

1. Check the verifier configuration — `oidc_discovery` URL or `jwks_uri` must be reachable from the server.
2. Inspect the `verification.metadata` object on the failing call's response — `failureKind` (`crypto | claims | policy | network | internal`) tells you which layer rejected.
3. If `failureKind=network`, the JWKS endpoint is unreachable. Either restore connectivity or temporarily lower `degradedModePolicy` to `accept-cached` so the stale-cache fallback can serve.
4. If `failureKind=crypto` or `claims`, the JWT itself is invalid — check `iss`, `aud`, and signing key alignment with the verifier's `algorithms` allowlist.

`rejected_by_policy` is a **batch-level** rejection on `claim_item`: if one item in the batch fails policy, none of the claims succeed. Releases in the same call are not attempted.

### Stale `originalClaimedAt` — diagnosing crashed holders

`originalClaimedAt` records when the *current* agent first claimed the item. It is preserved across heartbeat re-claims and reset only when a different agent claims the same item.

| `originalClaimedAt` age | Interpretation |
|---|---|
| < TTL (default 900s) | Fresh claim; agent is most likely working. |
| 1–10× TTL | Heartbeat-renewed long-running work. Inspect `claimExpiresAt` — if in the future, the agent is alive. |
| Hours/days old, `claimExpiresAt` in the past | Agent crashed mid-work. Claim is passively expired; any agent can now claim it. |
| Hours/days old, `claimExpiresAt` still being refreshed | Agent is alive but stuck in a long-running operation, or the heartbeat cadence is too aggressive. Investigate the holder's logs. |

There is no background reaper. Expired claims are filtered at read time — `get_next_item()` will surface items whose holders crashed once their TTL has elapsed.

### Heartbeat scheduling — implementation patterns

The recommended cadence is **TTL/2** (450s for the 900s default). This matches the convention used by Consul, etcd, and other lease-based distributed systems.

**Where to put the heartbeat timer:**

- **Inside the agent's main work loop.** Check elapsed time at each natural checkpoint (note write, file change, tool call) and re-claim if past TTL/2.
- **As a coroutine/task scheduled at TTL/2.** Simpler to reason about, but the agent must guarantee the timer fires while it's actually progressing — a paused or blocked agent that lets the timer fire anyway is silently extending a stale claim.
- **Avoid** background-only timers that fire regardless of work progress. Tying the heartbeat to forward progress is what gives crash recovery its meaning.

**Cross-restart behavior:** A re-launched agent process does not preserve its prior claim. After restart, call `claim_item` again — if the prior TTL has not elapsed, the re-claim succeeds (refreshing TTL, preserving `originalClaimedAt`); if it has, the item may have been picked up by another agent and you'll receive `already_claimed`.

### Does completing or cancelling release the claim?

**No.** `advance_item(trigger="complete" | "cancel")` transitions the role but does **not** clear `claimedBy`, `claimedAt`, `claimExpiresAt`, or `originalClaimedAt` on the work item. The claim record remains in place until either the TTL elapses or `claim_item(releases=[...])` is called explicitly.

This is harmless in practice: terminal items cannot be claimed by anyone (the `terminal_item` outcome blocks new claims), so a leftover claim record on a completed item is data noise, not a correctness problem. `reopen` triggers go through the same ownership check as any other transition — if the original claim has not expired, only the original holder can reopen.

**Recommendation:** Well-behaved agents call `claim_item(releases=[{itemId}])` after completing work. Required only if you want the audit trail to show explicit release rather than passive expiry.

### Fleet-wide expired-claim sweep

Operators who want to inventory expired claims (e.g., during incident review) can run:

```
query_items(operation="search", claimStatus="expired")
```

Results include only `isClaimed: boolean` per item — identity remains hidden. To get holder identity for a specific stuck item, drill in with `get_context(itemId)`, which is the only surface that exposes `claimDetail.claimedBy`.

No cleanup action is required for correctness. The data is informational — it tells you which agents likely crashed and which work items are now available for re-claim.

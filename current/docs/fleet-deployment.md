# Fleet Deployment Guide

This guide is written for operators deploying the MCP Task Orchestrator to production multi-agent fleets. It covers identity configuration, SQLite tuning, capacity planning, observability gaps, and the tiered claim disclosure design.

For single-agent or local-dev setups, the defaults are appropriate and this guide can be skipped.

**Companion docs:**
- [API Reference — `claim_item`](api-reference.md#claim_item) — tool spec: parameters, outcome codes, examples
- [Workflow Guide §10 — Claim Mechanism](workflow-guide.md#10-claim-mechanism-for-multi-agent-fleets) — agent-side lifecycle, heartbeat pattern, discovery
- This guide — operator-side: identity policy, capacity, disclosure, observability

---

## Scope

This guide covers **server-side configuration**: identity policy, capacity tuning, observability, and lifecycle. The **agent/client side** — calling `claim_item`, attaching verified `actor.proof` JWTs, sequencing claim/heartbeat/release, retrying on `already_claimed`, integrating with your identity provider and audit infrastructure — is the implementer's responsibility.

### The Bundled Claude Code Plugin Is Not a Fleet Driver

The plugin under `claude-plugins/task-orchestrator/` targets default-mode single-agent orchestration. Its skills and hooks teach the agent-owned phase-entry pattern (`advance_item` called directly by each agent) — not the claim-then-advance coordination required by fleet deployments. Do not rely on it as a fleet driver:

- The bundled output style and skills do not reference `claim_item` and assume unclaimed items
- The bundled `enforce-actor-attribution` hook checks for an `actor` field on writes but does not enforce claim ownership precedence
- Subagent dispatch templates do not include claim acquisition steps

Treat the bundled plugin's behavior in claim mode as undefined. Build your own claim-aware skills, hooks, or middleware tailored to your fleet's identity scheme, contention policy, and audit integration.

### The Public Contract

TO publishes the following as the integration seam — the surface fleet implementers build against:

- **MCP tools** — `claim_item` (acquire/heartbeat/release), `advance_item` (with ownership enforcement on claimed items), `get_context(itemId)` (operator diagnostic with full claim detail), `query_items(claimStatus=...)` (filtered discovery, identity-redacted), `get_next_item(includeClaimed=...)` (work discovery)
- **Configuration** — `.taskorchestrator/config.yaml` `actor_authentication` block (server policy)
- **Audit log** — actor claims persisted on every write when `actor_authentication.enabled: true`, queryable via `query_notes`

Anything else (specific skill instructions, hook behavior, output-style conventions) is implementation detail of the bundled plugin and not part of the fleet contract.

---

## Identity Configuration — `auth.degradedModePolicy`

The `degradedModePolicy` field under `actor_authentication:` in `.taskorchestrator/config.yaml` controls how the server resolves actor identity when JWKS verification cannot produce a fully verified result.

```yaml
actor_authentication:
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

For deployments where agents from different organizations share a single Task Orchestrator instance,
combine `reject` policy with native DID-trust mode:

```yaml
actor_authentication:
  enabled: true
  degraded_mode_policy: reject        # unverified actors cannot claim or advance claimed items
  verifier:
    type: jwks
    # DID trust mode — each agent is identified by its own did:web DID
    did_allowlist:
      - "did:web:agent.org-a.example"
      - "did:web:agent.org-b.example"
    # OR match an entire domain's agent fleet — note: * is segment-bounded (see below)
    # did_pattern: "did:web:agents.example.com:*"
    algorithms:
      - EdDSA                         # required — empty or missing causes startup error
    audience: "mcp-task-orchestrator"
    require_sub_match: true
    did_loose_kid_match: true         # accommodates AgentLair-style thumbprint kid headers
```

**Algorithm name for Ed25519 tokens.** Use the string `EdDSA`, not `Ed25519`. This matches the
`alg` claim that Ed25519-signed JWTs carry per [RFC 8037](https://datatracker.ietf.org/doc/html/rfc8037),
and corresponds to `JWSAlgorithm.Ed25519.name` in the Nimbus JOSE library this verifier uses
internally. Because `algorithms` is now strictly required under `type: jwks`, an EdDSA-only fleet
that ships `algorithms: ["Ed25519"]` will fail startup with a clear error rather than silently
mis-matching at verification time.

**`did_pattern` segment-bounded wildcard.** The `*` in `did_pattern` matches a single
colon-delimited DID segment — it will not cross a `:` boundary. Example:

| Pattern | Value | Match? |
|---------|-------|--------|
| `did:web:agents.example.com:*` | `did:web:agents.example.com:alice` | Yes — one segment |
| `did:web:agents.example.com:*` | `did:web:agents.example.com:alice:hijacker` | **No** — two segments |
| `did:web:agents.example.com:*` | `did:web:agents.example.com:` | Yes — empty trailing segment |

If your fleet uses a two-level path (`did:web:host:team:agent`), use two explicit wildcard
segments (`did:web:host:*:*`) or enumerate teams in `did_allowlist`.

`did:web` identifiers work as `claimedBy` values natively — they are opaque strings and require no
special handling. Under `reject`, any agent without a valid JWT in `actor.proof` cannot claim items
or advance claimed items. Unclaimed items remain accessible to unverified actors to preserve backward
compatibility for mixed fleets during migration.

### DID document resolution under v1

The verifier ships `did:web` support via the `DidResolver` interface, which is designed to be
method-agnostic. Additional DID methods (e.g., `did:key`, `did:jwk`) can be registered by
extending `DidResolver` and adding the implementation to the `DidResolverRegistry` — see issue #156
for the roadmap.

Per-agent DID documents are resolved **on-demand at verification time**, not pre-loaded at startup.
The first verification attempt for a given issuer triggers a live fetch to the `did:web` URL;
subsequent attempts within the cache TTL use the cached key set. The cache is LRU-evicted at 256
entries — for fleets larger than that, monitor logs for LRU eviction warnings and consider tuning
`cache_ttl_seconds` to spread re-fetches.

### `service` block handling

W3C DID documents may include a `service` block. AgentLair-shape deployments commonly publish a
`service` entry of type `JsonWebKeySet2020` pointing at a separate JWKS endpoint URL, alongside the
inline `verificationMethod` keys.

**v1 deliberately ignores `service` blocks.** The verifier extracts signing keys from
`verificationMethod[]` only and never fetches the `service` endpoint.

**Rationale:** In mixed fleet deployments (issue #156), some accounts have rotated per-agent keys
while others have not. For un-rotated accounts, the `service`-endpoint URL may point at an
unreachable or stale endpoint. The inline `verificationMethod` route is the only one that works
reliably across all accounts. Silently ignoring `service` prevents a broken service endpoint on one
account from causing verification failures for that agent.

Operators whose deployments treat external JWKS endpoints as the authoritative key source (rather
than inline `verificationMethod` entries) should follow the tracking issue for future `service`-block
support, deferred to a future release.

### Loose-kid match policy

The loose-kid match policy addresses the AgentLair deployment shape where agent tooling sets a JWK
thumbprint as the JWT `kid` header. Thumbprint-based kids do not match the bare-fragment ids
(`#key-1`, `#signing-key`, etc.) that the DID document extractor derives from
`verificationMethod[].id`.

Three conditions must ALL be true for loose-kid match to apply:

1. DID trust mode is active (`did_allowlist` or `did_pattern` is configured).
2. `did_loose_kid_match: true` (default).
3. The resolved DID document's eligible key set contains **exactly one entry** (single-key guard).

When all three hold, the single eligible key is used for signature verification regardless of kid.
Multi-key documents always require an exact kid match — the single-key guard prevents "first key
wins" ambiguity on documents with multiple signing keys.

**When to set `did_loose_kid_match: false`:** Set this when your agents consistently emit correct
bare-fragment kids, or when you want strict alignment between the JWT kid and the DID document
fragment ids as an additional assurance layer.

---

## Rolling Out Claim Mode

Claim mode is opt-in. New deployments can start in any policy; existing deployments should ramp up policy strictness in stages so client-side actor wiring can catch up to server-side enforcement.

### Stages

The four stages below correspond to increasing identity-enforcement strictness. At any stage you can hold position indefinitely; advance only when clients are ready for the next stage.

| Stage | Config | Server behavior |
|---|---|---|
| **0 — Default orchestration** | `actor_authentication.enabled: false` (or absent) | `claim_item` works but is optional. `advance_item` does not enforce ownership. No actor required. |
| **1 — Actor authentication on, self-reported identity** | `actor_authentication.enabled: true`, `degraded_mode_policy: accept-self-reported`, no `verifier` | Actor required on writes (when paired with an actor-attribution enforcement layer). `claim_item` enforces ownership on subsequent `advance_item` calls. Identity is self-reported — caller-supplied `actor.id` is trusted unconditionally. |
| **2 — Verifier configured, fallback permitted** | + `verifier: { type: jwks, ... }`, `degraded_mode_policy: accept-cached` | When `actor.proof` is present and JWKS is reachable, the JWT `sub` becomes the trusted identity. When JWKS is briefly unreachable, the stale-cache fallback serves. Other non-verified outcomes fall back to self-reported `actor.id` with a WARN log. |
| **3 — Verification required** | + `degraded_mode_policy: reject` | Operations requiring verified identity are rejected if verification status is not `VERIFIED`. Unclaimed items remain accessible to unverified actors so existing default-mode clients are not broken — only claim and advance-on-claimed flows are gated. |

### Recommended Sequence

1. **Stage 0 → Stage 1.** Enable actor authentication in the config. Roll out `actor` plumbing on clients first, then flip `actor_authentication.enabled: true`. Clients that don't pass `actor` will fail writes once attribution enforcement is active.
2. **Stage 1 → Stage 2.** Configure the JWKS source and have clients begin attaching `actor.proof` JWTs. Stage 2 is forgiving — clients without `actor.proof` continue to work via self-reported fallback. Use this stage to confirm verification metadata in responses (`verification.status: VERIFIED` for upgraded clients).
3. **Stage 2 → Stage 3.** Once telemetry confirms all client traffic is producing `VERIFIED` outcomes, flip `degraded_mode_policy: reject`. Any remaining unverified clients will start receiving `rejected_by_policy` on `claim_item` and on `advance_item` for claimed items.

### Rolling Back

Each stage is reversible. Loosening `degraded_mode_policy` (e.g., `reject` → `accept-cached`) immediately allows previously-rejected operations to succeed. Disabling actor authentication entirely reverts to default-mode semantics — claims still work, but `advance_item` no longer enforces ownership.

Existing claim records persist across policy changes. If a claim was placed under Stage 2 and the policy is loosened to Stage 1, the claim remains valid; ownership comparison still uses whatever identity scheme was in effect when the claim was placed.

---

## JWT Contract

When `verifier.type: jwks` is configured, TO reads a narrow subset of claims from each `actor.proof` JWT. This section documents that contract — not how to operate a JWT issuer.

### Claims TO Reads

| Claim | Required | Used for |
|---|---|---|
| `iss` | Only if `issuer` is configured (explicitly or via OIDC discovery) | Must match the configured/discovered issuer; mismatch → rejected with `failureKind: claims` |
| `aud` | Only if `audience` is configured | Must contain the configured audience; mismatch → rejected with `failureKind: claims` |
| `sub` | Only when `require_sub_match: true` | Verified against the caller's self-reported `actor.id`; mismatch → rejected with `failureKind: claims`. When `require_sub_match: false`, `sub` is not read. |
| `exp` | Optional | If present, enforced with a **60-second clock-skew allowance**; past-expiry → rejected with `failureKind: claims`. A missing `exp` claim is accepted (no expiry check). |
| `nbf` | Optional | If present, enforced with a **60-second clock-skew allowance**; not-yet-valid → rejected with `failureKind: claims` |

TO does not read `iat`, `jti`, or any custom claims. Those are deployment concerns outside the TO contract.

### `require_sub_match`

When `true` (recommended for fleet deployments), TO verifies that the JWT `sub` matches the self-reported `actor.id` on the call. This prevents an agent from claiming items under one identity in `actor.id` while presenting a JWT issued for a different `sub`. When `false`, `sub` is not read at all — only signature and the iss/aud/exp/nbf claims are checked.

### Algorithm Allowlist

When `algorithms` is configured (non-empty), only listed algorithms are accepted. JWTs signed with other algorithms are rejected with `failureKind: policy`. When `algorithms` is empty or omitted, no algorithm filtering is applied — any algorithm Nimbus supports for the matching key type will be accepted. Default recommendation: `["EdDSA", "RS256"]`. `none` and symmetric algorithms (`HS256` etc.) are not supported.

### JWKS Sources

The provider supports three sources, merged when multiple are configured:

- `oidc_discovery` — fetches the discovery document, extracts `jwks_uri` (and `issuer`, unless explicitly configured)
- `jwks_uri` — fetched directly; explicit value overrides any OIDC-discovered URI
- `jwks_path` — local file, resolved relative to `AGENT_CONFIG_DIR` or `user.dir`

Keys from URI and path sources are merged into a single key set used for signature verification.

### `cache_ttl_seconds`, `stale_on_error`, and Degraded Mode

JWKS key material is cached for `cache_ttl_seconds` (default: 300). When a cached entry expires and a refresh fails, the `stale_on_error` flag (default: `true`) controls whether the prior cache is served. The interaction with `degraded_mode_policy`:

| Cache + refresh state | `accept-cached` | `accept-self-reported` | `reject` |
|---|---|---|---|
| Fresh hit (within TTL) | JWT verified, identity = `sub` | Same | Same |
| Expired, refresh succeeds | JWT verified against fresh JWKS, identity = `sub` | Same | Same |
| Expired, refresh fails, `stale_on_error: true`, prior fetch exists | Stale cache served, JWT verified, status = `VERIFIED` with `verifiedFromCache: true` metadata, identity = `sub` | Same | Same (verification still succeeds; stale-cache is invisible to the policy gate) |
| Expired, refresh fails, `stale_on_error: false` or no prior fetch | Status = `UNAVAILABLE`; identity = self-reported `actor.id` | Identity = self-reported `actor.id` | Operation rejected with `rejected_by_policy` |

Stale-cache success is reported as `VERIFIED` (with `verifiedFromCache: true` and `cacheAgeSeconds: N` in `verification.metadata`) — operators can scrape that metadata to alert on prolonged JWKS outages.

### JWT `exp` vs Claim TTL

JWT lifetime and claim TTL are independent. A JWT whose `exp` passes mid-claim does not invalidate the claim record itself, but it does affect subsequent operations that re-verify identity (`claim_item` heartbeat, `advance_item` on the claimed item).

When a presented JWT is past `exp`, the verifier returns a non-`VERIFIED` status. The resolution chain then applies:
- Under `accept-cached`: identity falls back to the self-reported `actor.id`. If that value matches the claim's existing `claimedBy`, the operation succeeds. Holders that consistently pass the same self-reported `actor.id` they used at claim time will continue to operate even after their JWT expires.
- Under `reject`: the operation is rejected with `rejected_by_policy`.

For long-running work under `reject`, size JWT lifetime to comfortably exceed the heartbeat cadence so the holder always presents a fresh token. Under `accept-cached`, JWT expiry is non-fatal as long as the holder's `actor.id` is stable.

The JWKS cache (governed by `cache_ttl_seconds`) is separate from JWT lifetime — it caches the verifier's public key material, not the JWTs themselves.

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

### `claimedBy` and PII

`claimedBy` is whatever value the identity resolution chain produces — typically the JWT `sub` claim under verified mode, or the self-reported `actor.id` under fallback. If your issuer puts an email address, employee ID, or other personal identifier in `sub`, that value persists in the database on every claim and in audit log entries on every write.

| Surface | What gets persisted |
|---|---|
| `work_items.claimed_by` | Current claim holder identity |
| Audit notes (when `actor_authentication.enabled`) | Actor claim object on every write — `id`, `kind`, `parent`, verification metadata |
| `query_notes` body content | Audit notes are readable via standard note queries |

Operators are responsible for choosing a `sub` value with appropriate sensitivity for their compliance regime. Pseudonymous identifiers (UUIDs, `did:web` identifiers, opaque session tokens) avoid PII concerns entirely. Email-as-`sub` is supported but creates compliance obligations downstream.

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
| Audit log | `actor_authentication.enabled: true` in config — actor claims persisted on write operations; queryable via `query_notes` and `get_context` session-resume mode |

The audit log via `actor_authentication.enabled` is the only structured per-operation signal available today. Actor claims (including verification status and `parent` chain) are persisted with each write, enabling post-mortem analysis.

### Known Gaps — Plan Accordingly

- No write latency histograms
- No `SQLITE_BUSY` error rate counters
- No claim acquisition success/failure rate
- No per-agent throughput tracking
- No alerting integration

If your fleet rollout requires real-time dashboards or alerting on these signals, plan to instrument them at the client side (agent telemetry) or proxy level until server-side metrics are added.

Metrics and observability infrastructure are explicitly deferred to a future release (see issue tracker). The audit log is the recommended bridge for compliance and post-incident review until then.

---

## Operating a Live Fleet

Lifecycle and edge-case behaviors that operators encounter once a claim-mode fleet is running.

### Atomic Claim Acquisition (Selector Mode)

Fleet agents can eliminate the **user-facing** race window inherent in the two-call `get_next_item → claim_item(itemId=...)` pattern by using selector mode instead. The selector resolves a filter+rank query and claims the top match in a single MCP call. (A much smaller server-side window between recommend and claim remains and surfaces as `already_claimed` — typically rare in practice.)

```json
{
  "claims": [{
    "selector": { "orderBy": "oldest", "priority": "high" },
    "ttlSeconds": 900
  }],
  "actor": { "id": "worker-pod-12", "kind": "subagent" },
  "requestId": "<fresh UUID per iteration>"
}
```

`orderBy: "oldest"` (createdAt ascending) provides **fair-share queue draining**: agents process items in FIFO order rather than competing for the same high-priority items simultaneously. When all eligible items are claimed, agents receive `outcome: "no_match"` with `kind: "permanent"` — signal to idle or poll after a delay.

The filter shape accepted by `claim_item.selector` is identical to the `get_next_item` filter parameters — both tools share the same underlying eligibility logic.

### Graceful Drain

There is no built-in drain command. The intended sequence to stop a TO instance with active claims:

1. Stop dispatching new work to agents talking to this instance (orchestration-side, outside TO).
2. Let in-flight claims complete naturally. Agents call `claim_item(releases=[...])` after `advance_item(trigger="complete")`, or skip the release and let TTL elapse.
3. Monitor `get_context()` → `claimSummary.active` until it reaches zero, or until residual claims age past their TTL into `claimSummary.expired`.
4. Stop the server.

If you stop the server while claims are active, no data is lost — claim records persist in the database. On the next startup, expired claims are filtered at read time as usual; non-expired claims will reach their TTL and become reclaimable.

### Upgrade Behavior

Schema migrations (Flyway) run at server startup before any tool calls are accepted. Existing claim columns are preserved across migrations unless a migration explicitly modifies them — V6 reshaped the `claim_expires_at` index but claim row data was untouched. Future migrations that touch the four claim columns will be flagged in the release notes.

A migration that runs while no client is connected has no claim-state implications. A migration that runs immediately after a restart, with claim records already in place, applies normally — claim records survive because they are row data, not schema.

### Cross-Partition Scoping

Claims are per-instance. Agents do not cross-claim across partitioned TO instances (different SQLite databases). An agent talking to two instances holds two independent claims, one in each.

### `actor.parent` in Fleet Mode

When an external dispatcher (not a TO subagent) spawns workers, set `actor.parent` to a stable identifier that ties workers back to their dispatcher. The audit log preserves the value verbatim and it is queryable via `query_notes` body content. TO does not interpret the value — it is a string for downstream correlation.

### Reopen and Claim TTL

If a terminal item is reopened (`advance_item(trigger="reopen")`) while the original claim TTL is still alive, the original holder retains ownership. Other agents see `already_claimed` until the TTL elapses or the holder explicitly releases. To reopen and reassign in one motion, call `claim_item(releases=[...])` first, then `advance_item(trigger="reopen")`, then have the new holder claim.

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

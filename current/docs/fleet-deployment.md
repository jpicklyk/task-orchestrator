# Fleet Deployment Guide

This guide is written for operators deploying the MCP Task Orchestrator to production multi-agent fleets. It covers REST and `/mcp` authentication, identity (the transport principal), SQLite tuning, capacity planning, observability gaps, and the tiered claim disclosure design.

For single-agent or local-dev setups, the defaults are appropriate and this guide can be skipped.

**Companion docs:**
- [REST API Reference](api-rest.md) — HTTP endpoint docs, DTOs, error codes, SSE
- [API Reference — `advance`](api-reference.md#advance) — tool spec: ops, claim semantics, error codes
- [Workflow Guide §10 — Claim Mechanism](workflow-guide.md#10-claim-mechanism-for-multi-agent-fleets) — agent-side lifecycle, heartbeat pattern, discovery
- [Workflow Guide §11 — Resource Leasing](workflow-guide.md#11-resource-leasing) — agent-side model: exclusive vs advisory, contention/retry, guarantees vs non-guarantees
- This guide — operator-side: identity policy, capacity, disclosure, observability, resource-lease TTL/runbook

---

## `/mcp` Authentication - Read First

When you run the server with `MCP_TRANSPORT=http`, the MCP endpoint is mounted at `/mcp` (orchestration tool profile), `/mcp/knowledge` (knowledge profile, a smaller read/write tool set) and `/mcp/director` (director profile: read tools, run control and decisions for a human or a director agent, with no read that returns a note, document or transition-summary body; see [Tool profiles](api-reference.md#tool-profiles) for the exact tool and op set) using the Streamable HTTP transport. All three authenticate alike and every statement below about `/mcp` holds for `/mcp/knowledge` and `/mcp/director`. Over stdio, `MCP_TOOL_PROFILE=knowledge|orchestration|director` (default `orchestration`) picks the profile; an unknown value fails startup on either transport.

How `/mcp` establishes its caller is decided once at startup from the REST API configuration:

| Deployment | `/mcp` mode | Caller |
|---|---|---|
| `API_ENABLED=true` with `API_AUTH_MODE=bearer` or `jwks` | **Credential** | Every request must carry the same `Authorization: Bearer` credential `/api/v1` accepts. The principal is the credential's holder (audited `api:<id>`). |
| `API_ENABLED=true` with `API_AUTH_MODE=none` + `API_ALLOW_UNAUTHENTICATED=true` | **Local** | Every request is the `local` principal. |
| `API_ENABLED=false` (the default) with `MCP_ALLOW_UNAUTHENTICATED=true` | **Local** | Every request is the `local` principal. |
| `API_ENABLED=false` without the opt-in | startup refused | The server fails startup (`MCP_AUTH`) naming `MCP_ALLOW_UNAUTHENTICATED`, before any worker starts or transport binds. |
| stdio | **Local** | The one `local` principal. |

**Credential mode.** A missing, malformed or empty `Authorization` header is `401 unauthenticated` with `WWW-Authenticate: Bearer error="invalid_request"`; an unknown, rejected or expired credential is `401 unauthenticated` with `error="invalid_token"`; a JWKS key set that is unavailable (never fetched, or older than `API_JWKS_MAX_STALE_SECONDS`) is `503 unavailable`. Refusals are the 4.0 JSON error envelope, not a JSON-RPC error, and the MCP SDK is never reached. The `/mcp` mount is no longer a public path: the REST bearer plugin delegates `/mcp*` to the MCP authentication, so a request is authenticated exactly once.

**Session binding.** The first request of an MCP session binds its `Mcp-Session-Id` to the caller's principal; only an id the server issued is bound (an unknown id binds nothing and gets the SDK's `404`). At most 1024 sessions are bound: sessions idle for 24 hours, or, beyond 1024, the least recently used session of whichever principal holds the most sessions (so one credential's flood closes its own sessions first), are closed on the next new session, so their next request is `404` and the client re-initializes (there is no background sweeper). A `POST` without a session id that is not `initialize` is refused by the SDK, and the session it leaves behind is closed by a later `POST` without a session id, once it has been seen without an id for 30 seconds (never sooner). The binding fixes the principal for the life of the session: a later request from the same principal (same `sub`) runs under the scope and capabilities of the credential that bound the session, even when its own token carries a different `to_scope` or `to_capabilities`, so end the session and initialize a new one to change them. A later request on that session from a different principal is `401 unauthenticated` and no tool runs; `DELETE` ends the session and forgets the binding. A leaked session id is therefore useless without the matching credential.

**Director confinement.** A credential with `profile: director` reaches only `/mcp/director` (and `POST /api/v1/ops/director/{tool}` over REST); on any other `/mcp*` path it is `403 forbidden` (scope `capability`, required `director`).

**Director endpoint.** The director profile is served at `/mcp/director` (or over stdio with `MCP_TOOL_PROFILE=director`) and over REST at `POST /api/v1/ops/director/{tool}`. The confinement above is one-way: any credential that holds the capabilities of a director-profile tool may call it on the director path, whatever its own `profile`. On the plain `/mcp` path (and a plain `POST /api/v1/ops/{tool}`) the director-only ops (pause, resume, and answering the director-or-human classes) need the director profile, and the director path does not help with a human-only class, which needs a human credential. That plain-path rule is hygiene, not a boundary, because the same credential can use the director path. Put the decision authority in the credential's capabilities and scope, not in the path. A single developer who runs one stdio server has no HTTP endpoint to point at, so the way to answer a limit decision is a second stdio entry started with `MCP_TOOL_PROFILE=director`.

**Local modes are unauthenticated.** In **Local** mode there is no credential check: any caller that can open a TCP connection to `MCP_HTTP_HOST:MCP_HTTP_PORT` (default `0.0.0.0:3001`) invokes every tool its profile serves, as the `local` principal (ADMIN, unrestricted) - full read, write and delete of the work-item graph. Every stdio or local session is that one principal, so they share claims and note ownership with no contention between them; separating callers needs credentials or seat tokens. The server logs a loud `SECURITY:` WARN at startup whenever a local mode serves HTTP. Check `docker logs` after first boot.

**Critical consequences for fleet operators:**

- **A `Host` header allowlist guards against DNS rebinding, but it is not authentication.** Every HTTP request - `/mcp`, every `/api/v1` route, and `/.well-known` - is rejected with `403` unless its `Host` header is `localhost`/`127.0.0.1`/`[::1]` (any port, the always-on default) or listed in `MCP_ALLOWED_HOSTS`. This stops a browser page from reaching the server via DNS rebinding; it does nothing to stop a caller that already has network access and sends a permitted `Host`. If you reach the server through a Docker Compose service name, `host.docker.internal`, a LAN hostname, or a reverse proxy that forwards the original `Host`, add it to `MCP_ALLOWED_HOSTS` or every request 403s. See the env var table below.
- **Enabling a credential mode on the REST API protects `/mcp` too.** The one credential covers both surfaces. Conversely, `API_AUTH_MODE=none` opens both.
- **Fence the local modes at the network layer.** If you run `/mcp` in a local mode, choose one:
  - Front it with an authenticating reverse proxy (e.g. nginx + mTLS, or an OAuth2 proxy) and never publish the raw port.
  - Restrict it to a private network / VPC with no public ingress.
  - For a single-host local run, bind loopback only: set `MCP_HTTP_HOST=127.0.0.1` (direct JAR run) or publish the Docker port as `127.0.0.1:3001:3001`.

  For a shared or fleet deployment, prefer a credential mode over fencing.

**Bind-default rationale.** The default bind is `0.0.0.0`, not `127.0.0.1`. Docker port-mapping (`-p host:container`) requires the server to bind all interfaces *inside* the container - a loopback default would make the published port unreachable and break the primary (containerized) deployment path. Control host exposure via the `-p` mapping for Docker, or set `MCP_HTTP_HOST=127.0.0.1` for a non-Docker local run. See [quick-start.md -> HTTP transport security](quick-start.md) for the full matrix.

---

## Startup, Readiness, and Health Checks

Server startup returns a `StartupOutcome` (`Started` or `Failed(reason, detail)` with
`reason` one of `DATABASE_INIT`, `SCHEMA_UPDATE` (the schema update failed, or the migration lock could not be taken within its 300 s wait), `UPGRADE_BACKUP` (the verified pre-upgrade
copy of a 3.x database could not be taken, see "Upgrading from 3.x" below), `UNKNOWN_TRANSPORT`, `READINESS_MARKER`,
`TRANSPORT_START` — the transport (stdio session creation or the HTTP Ktor engine) failed to
bind/start after DB init and schema update had already succeeded — or `DATA_STEPS` — a data step
failed or a registered once step is unapplied, see "Data steps" below). A
`Failed` outcome throws `StartupFailedException` from `main()`, so the JVM exits non-zero and
container orchestrators (Docker, Kubernetes, systemd) see a real startup failure instead of a
process that silently logged an error and kept running.

**The global `.taskorchestrator/config.yaml` fails startup, closed, if it is broken.** If the file
at `AGENT_CONFIG_DIR/.taskorchestrator/config.yaml` exists but cannot be read, is not valid YAML,
or its top-level document is not a mapping, the shared file loader throws before the object graph
finishes wiring — the exception
propagates out of `ServerComposition.build()` and `CurrentMcpServer.run()`, so the process exits
non-zero **before** the readiness marker is ever written and before any transport binds. A leftover `actor_authentication:` key (any value), a legacy `auditing:` key or a set `DEGRADED_MODE_POLICY` variable is refused the same way (see "`actor_authentication` removed in 4.0" below). An absent file, or one that is empty or comment-only, is not an error: it
keeps schema-free defaults. **This is a behavior change**: a global
config file that was broken before this fix ran silently in schema-free / noop-verifier mode; it
now fails startup. Per-project config pushed via the `config` tool's `push` op (`POST /api/v1/ops/config`) is unaffected — only the single, server-wide global file is on this
fail-closed path. One key in that file is exempt: a malformed `status_labels:` block (display-only
labels) still falls back to the default labels with a WARN.

**The file is imported into the database at every boot; the runtime reads only the stored copy.**
After the lenient load above, the every-boot data step `server-config-import` (phase `CONFIG_IMPORT`)
parses the file **strictly** (the same rules a per-root push is held to) and appends it as a
`file-import` config version, byte for byte, with the same fingerprint 3.x reported for the file. From
then on schemas, traits, the resource registry, status labels and note limits are read from the stored
head on every request, never from the file: editing the file without a restart changes nothing, and a
version another process appends to a shared volume is served on the next read.

- An identical file appends nothing. An absent, empty or comment-only file imports nothing and never
  resets a stored head.
- **Last booting process wins on a shared volume.** Two processes mounting different files onto one
  database alternate `file-import` versions at their boots (history shows each); use per-project
  config (multi-project setup, the `config` tool) instead of differing mounts.
- A file with no `project.rootId` becomes the **server** config. A file that sets `project.rootId` is
  imported as that depth-0 root's config instead; the root must exist in this database, and such a file
  must not carry `resources:` (the resource registry is server-only: drop `project.rootId` to declare
  one).
- **A `project.rootId` that cannot be honored fails startup, including on a fresh volume.** If the
  mounted global config carries a `project.rootId` that is malformed (not a UUID), unknown in this
  database, or not a depth-0 item, startup fails as `DATA_STEPS` (`project.rootId <uuid> is not a
  project root (depth 0) in this database`, or `/project/rootId: must be a UUID`), and an ERROR log line
  names the remedy. Remedy: remove `project.rootId` from the mounted global config (it then becomes
  the server config), or create or import that root first. A workspace config written by `/init` carries
  `project.rootId`, so do not mount it as the global file on a new volume.
- **Legacy 3.x keys boot with warnings.** Top-level `schema_resolution`, `orchestration`,
  `retrospective` and `actor_attribution` (plugin sections a 3.x global file carried) are accepted at
  boot, each recorded as a stored warning that `GET /api/v1/info` returns as `configWarnings`; a push
  of the same text is still rejected. A leftover `actor_authentication:` key never reaches the import: it fails startup first (see below).
- **Any other strict error, or a file over 128 KiB, fails startup** as `DATA_STEPS` with one
  `path: reason` line per error, before any transport binds or the readiness marker is written.
- A stored head that cannot be loaded (no canonical form, undecodable) fails closed: tools and routes
  answer `config_invalid` (REST 422), never "no config" and never the 3.x global fall-through.

**Data steps.** After Flyway migrates and before any background service or transport starts, the
server runs its registered data steps: idempotent, code-level upgrade steps that Flyway SQL cannot
express (for example config canonicalization or an item backfill). Steps run in fixed phases
(`CONFIG_CANONICALIZE`, `CONFIG_IMPORT`, `ITEM_BACKFILL`, `PIN`), and within a phase in dependency
order with ties broken by step name. A `once` step and its row in the `data_steps` table commit in one
transaction, so a step is applied exactly when its row exists and two processes booting on one
database file cannot both apply it; an every-boot step runs on every start and leaves no row. If a
step fails, or any registered once step has no row after the run, startup fails as `DATA_STEPS` and
the readiness marker is never written, so the container reports unhealthy rather than serving a
half-upgraded database. A step runs inside the same `--start-period` budget as the startup
compaction, so a long backfill on a large database may need a longer start period. Data steps also
run with `SCHEMA_MODE=validate` (validate guards DDL, not data) but never on a `FLYWAY_REPAIR` run,
which exits before them. Remedy for a `DATA_STEPS` failure: fix the cause named in the log and
restart; the failed step's transaction rolled back, so it is retried from scratch. Deleting a row
from `data_steps` makes that step run again on the next boot.

The first shipped step is `type-backfill` (phase `ITEM_BACKFILL`, once). 4.0 requires every item to carry a `type`, so on the
first start of a 3.x database it gives each item whose `type` is NULL or blank the schema key the 3.x tag step resolved it
to, or `untyped` when none matched, so no in-flight item changes schema or required notes at the upgrade. It reads the
global `.taskorchestrator/config.yaml` and every stored per-root config for their `schema_resolution` and schema keys only
(an unparseable document counts as no layer, with a WARN, and never fails startup), writes the `type` column only (no
`version` or `modified_at` bump, no events; the `data_steps` row is the record), and never retypes an already-typed item. The
startup integrity report WARNs about untyped items before the step runs, so on that first boot the WARN is expected and the
step fills the rows right after; a WARN on any later boot names residue. Back up the volume before the first 4.0 start: the
backfill is not reversible from the data alone.

**Transition trail moves into `events` (V29).** Migration V29 copies every 3.x `role_transitions` row older than
the V20 install into `events` as a `backfilled` `item.transitioned` row under its old id, then drops the table in
the same migration. **Back up the volume before upgrading**: the drop is irreversible, and the backup is the
supported rollback. A row whose `transitioned_at` does not parse is still copied, never skipped: it gets the item's
`created_at` (or the epoch `1970-01-01 00:00:00.000` when the item row is gone), `timestampRepaired: true` in its
event data, and its `seq` at that fallback time (parseable rows first on a tie, then by id). A row dated at or
after the V20 install is not copied: it is assumed to already have its twin `item.transitioned` event. The server
logs the counts at INFO (`V29 role_transitions backfill: rows=N, backfilled=M (timestampRepaired=R)`) and WARNs
when any row was not copied, naming how many were dated at or after the V20 install (and how many of those were
future-dated, i.e. clock skew) and how many were already present in `events` by id. A fresh 3.x upgrade should
log no WARN; if one appears, the dropped rows survive only in the pre-upgrade backup.

**Readiness marker.** Once DB init and schema update have both succeeded and the configured
transport has bound, the server writes a readiness marker file at `READINESS_FILE` (default
`/tmp/mcp-task-orchestrator.ready`; see the CLAUDE.md env-var table). The marker is cleared on
graceful shutdown, and also as the very first step of startup (before DB init), so a container
restarted after SIGKILL/OOM does not report healthy from a stale marker during migration or a failed
start. If the stale marker cannot be removed, startup fails as `READINESS_MARKER`. This is transport-agnostic — **both `stdio` and `http` transports write and
clear the marker**, so a `stdio`-mode container is just as observable as an `http`-mode one, not
only the HTTP case. In `stdio` mode the server also shuts down gracefully when its stdin reaches EOF (the client closes the pipe): it clears the marker, closes the DB, and exits 0, so `docker run -i` pipelines and CI harnesses do not leave a healthy-looking orphan.

**Docker `HEALTHCHECK`.** The image's `HEALTHCHECK` (`--interval=30s --timeout=3s
--start-period=20s --retries=3`) simply checks `test -f "$READINESS_FILE"` — cheap, no HTTP call,
no DB round-trip. Fleet operators running under an orchestrator that respects container health
(Docker Swarm, Nomad, `docker run --restart` policies keyed on health, Kubernetes via an
equivalent `exec` probe) get real crash/hang detection without wiring a separate probe. Check
status with:

```bash
docker inspect --format '{{.State.Health.Status}}' <container>
```

**First boot after upgrading is slower — startup compaction.** The first start after
upgrading to a build carrying the V17 actor-proof scrub runs a one-time startup compaction
(`VACUUM` + FTS5 rebuild — see "Proof handling" → Remediation below) before the readiness marker
is written. Measured throughput is roughly ~2 minutes per GB of database file. On a multi-GB
database this can exceed the default `--start-period=20s` and the `--retries=3` liveness window,
so an orchestrator may kill the container mid-compaction (safe — `VACUUM` is atomic and the run
retries on the next boot, but it becomes a crash loop until someone intervenes). Before upgrading a
deployment with a multi-GB database, either raise `--start-period`/the liveness grace period to
comfortably exceed the estimated compaction time, or set `DB_COMPACT_ON_UPGRADE=false` to defer to
the offline runbook on your own schedule instead.

**Every boot checks the FTS indexes.** Each start runs the FTS5 `integrity-check` on every FTS table; its
cost grows with the indexed text (measured about 2.6 s at 100 000 notes). On a large hub, set
`FTS_INTEGRITY=off` to skip it (see "Schema management, locking and startup integrity" below).

---

## Logging

Logs are structured JSON, one object per line, written to **stderr only** — stdout is reserved
exclusively for MCP JSON-RPC messages on the stdio transport, so nothing may ever be pointed at it.
Level is controlled by `LOG_LEVEL` (default `INFO`); there is no separate stderr threshold.

Each JSON line carries:

| Field | Description |
|-------|--------------|
| `timestamp` | ISO-8601, UTC, e.g. `2026-09-25T12:00:00.123Z` — always 3 fractional digits |
| `level`, `loggerName`, `threadName`, `formattedMessage` | Standard SLF4J/Logback fields |
| `mdc` | Correlation fields for the request/call in flight (see below) |
| `throwable` | Stack trace details when an exception was logged; `null` otherwise (the field is always present) |

**MDC correlation fields**, propagated via `kotlinx-coroutines-slf4j`'s `MDCContext` so they
survive coroutine dispatcher hops:

- MCP tool calls (`McpOperationAdapter`): `transport=mcp`, `tool` (the tool name), `sessionId`, `reqId`
  (the call's 8-character correlation id, lowercase Crockford base32, 40 random bits; the MCP SDK handler
  has no JSON-RPC request id to reuse, see "Call log and `reqId`" below), and `actorId` (the audited id of the
  call's principal: `api:<id>`, or `local`). `actorId` is length-capped at 128 chars (`MdcValues.bounded`,
  truncated with a `...[truncated]` marker) - the cap applies only to this MDC copy, never to what tools
  themselves receive.
- REST requests under `/api/v1` (`RequestCorrelation`): `transport=rest`, `requestId` (the inbound
  `X-Request-Id` header when it matches `^[A-Za-z0-9._-]{1,64}$`, else a generated UUID),
  `httpMethod`, and `httpPath` (no query string, so `?token=` never lands in a log line). `httpPath`
  is length-capped at 256 chars, same truncation rule as `actorId` above. `requestId` is NOT
  length-capped: a malformed or over-long `X-Request-Id` is rejected and replaced with a fresh UUID
  instead, since it is a correlation key and truncating it risks falsely correlating unrelated
  requests. Also `reqId` (always server-generated; the inbound `X-Request-Id` never feeds it). The
  `GET /api/v1/events` SSE stream gets no `reqId`.

**API JWT rejections.** Each bearer JWT the REST API, MCP HTTP and SSE endpoints reject logs one INFO line on
the `JwksApiVerifier` logger:
`API JWT rejected: reason=<code> verifier=api keySource=jwks_uri suppressed=<n>`. `reason` is the pipeline failure
kind (`crypto`, `claims`, `policy` or `internal`; a token that verifies but has a malformed `to_scope` or `to_kind`
reports `claims`). `verifier` and `keySource` name the verifier and its key source (an API JWKS URI). `suppressed`
is how many rejections of that reason the flood limit dropped since the previous line for it. The limit is 10 lines
per reason per 60 seconds, so a flood cannot fill the log; the count rides on the first line of the next window. A
count with no later rejection of the same reason is never reported.

That INFO line never carries the token, any claim value, the subject, the `kid` or the verifier's free-text failure
detail (the detail is logged at DEBUG only). It is not the only line a bad token can cause: a token whose custom
claims are defective also logs the claim-defect WARN described below, which carries a reason code and never the claim
value. One identity WARN does name a subject: a refused `scope.root_ids` anchor logs the verified `sub`, at most once
per subject. Verified tokens and an unavailable key set (answered 503, WARN once per request) log no rejection line.

**API JWT claim defects and verifier faults.** A token that verifies but carries a defective custom
claim logs one WARN on the `JwksApiVerifier` logger: `API JWT claim defect: claim=<claim>
reason=<code> action=<rejected|ignored> suppressed=<n>`. The pairs are a closed set of constants:
`to_scope` `not_object` or `malformed` (rejected), `to_kind` `not_string` or `unknown_value`
(rejected), `to_capabilities` `not_string_list` or `unknown_value` (ignored; one line per token
however many elements were dropped). An unexpected fault in the verification pipeline logs `JWT
verification fault: reason=internal suppressed=<n>` on the same logger. Each key
(`<claim>.<reason>`, and `internal`) is limited to 10 lines per 60 seconds, and `suppressed` rides
on the first line of the next window, as for rejections. Neither line carries the token, a claim
value, the `kid` or exception text; that detail stays at DEBUG. A failed forced JWKS refresh for an
unknown `kid` logs the fetch error only, never the `kid`.

### Call log and `reqId`

Every MCP tool call and every non-SSE `/api/v1` request gets a `reqId`. It is returned to the caller
(`meta.reqId` in every response envelope and `_meta.reqId` on every MCP tool result, errors included; the `X-Req-Id` response header on REST),
set as the MDC `reqId` key, stamped as `req_id` on every `events` row the call writes, and is the
primary key of that call's row in the `call_log` table (migration V21). A row records who called
(principal id, kind, proof status, session), what (surface `mcp`/`rest`, tool or the REST route
template `METHOD /api/v1/<route>` exactly as declared in routing (for example `GET /api/v1/items/{id}`; a `POST /api/v1/ops/{tool}` call records the registry tool name instead), `operation` (the select or op name),
target ids and versions, boolean/`limit` request shape), the
outcome (`ok`/`error`, error code, BUSY-retry `attempts`, `replayed`), `latency_ms`, UTF-8 request
and response byte sizes, and a `bytes/4` token estimate of the response (`ceil(bytes / 4)`).

A refusal is a row too. A tool call that reaches the dispatcher on an MCP session with no bound principal fails
closed as `unauthenticated` before anything in it is read, and its row records no principal and the error code
`unauthenticated`. That row is the audit record of a fail-closed refusal, not a work row. A forged or evicted session
id never gets that far: the SDK answers it `404` and no tool runs.

Two columns added by migration V30 serve follow-through and rejection reports:

- `hit_signals` (JSON text): for an `items_read` `related` call, the returned page hit by hit, in page order, as
  `[{"id":"<uuid>","kind":"item"|"document","signals":[...]}]`, `signals` being the signals that found the hit
  (`edges`, `links`, `tags`, `text`, in that order). A later call in the same `session_id` whose `target_ids` or
  `target_versions` names one of those ids is a follow-through for those signals, provided that call is not itself a
  `related` or `search` call: those record the hits they return as `target_versions`, so a re-surfaced hit would
  otherwise count as followed. Filter them out with `c.operation NOT IN ('related','search')`, for example
  `... JOIN call_log c ON c.session_id = r.session_id AND c.at > r.at AND c.operation NOT IN ('related','search')
  AND (h.id IN (SELECT value FROM json_each(c.target_ids)) OR c.target_versions LIKE '%' || h.id || '%')`.
  Follow-through is effectively MCP-only: REST rows carry no `session_id` (NULL), so the session join never matches a
  REST `related` row. Ids and signal names only, never a title or any text. NULL for every other call and for an
  empty page. At most 50 entries (about 5.3 KB in the worst case, a document hit with 4 signals at about 105 B) per row.
- `lifecycle_free_rejections` (integer): how many rejections in the response a lifecycle-free item caused. Judged
  from the response envelope: each failed batch element when the body has `data.results`, else the top-level
  `error` (never both, so a non-atomic batch's `partial_failure` is not counted twice). A rejection counts when it is
  `invalid_transition` with `detail.fromRole` `"none"` (an advance trigger, claim, heartbeat or flip refused for a
  lifecycle-free item) or the `invalid_request` refusing a BLOCKS dependency with a lifecycle-free endpoint. NULL
  when there were none, never 0. Both surfaces fill it (REST from `POST /api/v1/ops/{tool}` bodies; the other REST
  routes are reads). An idempotent replay row (`replayed = 1`) repeats the stored response and so carries the same
  count, but it is not a new rejection: `report` `waste` leaves replayed rows out of `lifecycleFreeRejections`.

Rows written before V30 read NULL in both columns.

Migration V31 adds one more column, `root_id`, plus indexes for the run and root reports:

- `root_id` (BLOB, 16-byte UUID, same encoding as `run_id`): the single root the call read or wrote. Reads record the
  root of the item an `items_read` select loads (`get`, `context`, `list`, `next`, `blocked`, `schema`), of a `related`
  reference, or of a document hit (its owner item); writes record the root of every `events` row the call appends.
  NULL when the call touched no root, or touched more than one: the latter also sets `request_shape.multiRoot` to
  `true` (the key is absent otherwise). Hits' roots are not looked up. No foreign key: a row outlives the items it
  names.
- `request_shape.fields`: a sorted JSON array of the `fields` names an `items_read` select accepted; absent when none
  were given. Like `multiRoot` it survives the 16-key cap on `request_shape`.
- `events.run_id` and `events.seat` (existing columns) are now stamped with the call's recorded run id and seat, NULL
  until a run is recorded; `call_log.run_id` and `seat` report the same pair.
- Indexes: `idx_call_log_root_at` on `(root_id, at)`, and the partial `idx_call_log_run_at` on `call_log(run_id, at)`
  and `idx_events_run_seq` on `events(run_id, seq)` (both `WHERE run_id IS NOT NULL`, empty until a run is stamped).

Rows written before V31 read NULL in `root_id`. Both surfaces fill these.

Each MCP tool call that was not cancelled also logs one INFO line from `McpOperationAdapter`, inside the call's MDC
(so it carries `reqId`, `sessionId`, `tool`, `transport` and `actorId`):
`tool call: name=<tool>, operation=<select or op, or ->, outcome=ok|error, errorCode=<code, or - on ok>,
latencyMs=<n>, responseBytes=<n>`. It is logged whatever happens to the call-log row. The message never carries an
argument value or body text; the MDC `actorId` is the one argument-derived field (length-capped, and already present
on every log line before this line existed). REST requests log no per-request line.

Rows are written off the request path by a background writer: a bounded in-memory queue (10 000
rows) flushed in batches of up to 100 rows or every 250 ms, one write unit per batch, and drained on
graceful shutdown (drain timeout 5 s; on timeout the write loop is cancelled and joined, and unwritten rows
count as dropped. `stop()` returns after the drain timeout plus at most one in-flight batch write, because JDBC
inserts are not interruptible. A cancel that lands after a batch committed may count its already-written rows
as dropped, so `dropped` can over-count in that edge; it is a counter only, with no effect on stored data). Telemetry never blocks or fails a call. When the queue overflows the new row is
dropped; a WARN line `Call log dropped N row(s) since the last notice` reports the count dropped since
the previous notice (at most one per 250 ms, plus a final one at shutdown); a batch that fails is
logged at WARN and dropped. The 8-character id has 40 bits,
so a duplicate `req_id` is possible (about 0.45 expected collisions by a million rows); a primary-key
duplicate is ignored, any other constraint refusal is counted as failed and logged at WARN. Cancelled
calls write no row.

Client-derived text in a row is bounded: the REST `tool` of a call that resolved a route is
`<METHOD> <matched route template>` exactly as declared (captured from Ktor's routing event, so there is one
value per declared route). A call that resolved no route (a 404 or 405, or a 401 on a path no route matches; a 401 on a declared route records that route's template, because the auth check does not stop routing) is
`<METHOD> /api/v1/<first segment>` when that first segment is a top-level resource the routing tree declares
(derived from the declared routes, not a hand-kept list), else `<METHOD> unmatched`: never more than the first
segment, and the raw request path is never stored; `operation` is kept only when it matches `^[a-z_]{1,64}$`
(any other supplied value is stored as `invalid`); `request_shape` keeps at most 16 flags whose key
matches `^[A-Za-z0-9_.-]{1,64}$`. Only paths equal to `/api/v1` or under `/api/v1/` are logged.

On every row, MCP and REST alike, the `principal_id` column holds the audited id of the call's transport principal:
`api:<id>` for a credential, the bare `local` for stdio and the local modes, and never client text (a row for a call
that had no principal holds none). `proof_status` records the proof behind that principal. `principal_kind` records the credential's principal kind (`human`, `agent` or `service`); the local principal of an unauthenticated or local-mode server records `agent`. Rows written before this fix keep `external` (the audit claim's kind) and are not backfilled, so a window spanning the upgrade shows both.

The `host` column of a row, and of every `events` row the call writes, names the client application. On MCP rows it is
the client's `initialize` `clientInfo.name`, read per session, so clients sharing one server keep separate hosts (null when
the client sent no name). On REST rows it is the `X-Client-Host` request header, else the first `User-Agent` product token,
else `rest`. The value is normalized (control characters dropped, trimmed, lowercased, capped at 64 code points). Both are self-reported by the client and unauthenticated: `host` labels telemetry only and is
never used for authorization, scoping or identity. See
[api-rest.md](api-rest.md) section 6a. `X-Client-Host` is in the default `CORS_ALLOWED_HEADERS`; a deployment that
overrides that variable must add the header itself for browser clients to send it.

`call_log` grows by about 250-450 bytes per call (about 8 MB per day at 20 000 calls a day). Its retention
(`RETENTION_CALL_LOG_DAYS`) deletes only days already folded into `usage_daily` (migration V33); see
[Data Retention](#data-retention) below.

**File logging is opt-in** via `LOG_FILE` (unset by default — no file logging). Set it to a path,
e.g. `-e LOG_FILE=/app/data/logs/task-orchestrator.log` (landing on the existing `/app/data`
volume in Docker); the file uses the same JSON encoding, rolls at 10MB/day (`.gz`, 30-day/100MB
retention), and its parent directories are created automatically.

---

## Data Retention

A background retention pruner deletes old rows from `events`, `resource_lease_history` and `call_log`. It runs one
pass right after startup (in the background, so it never delays readiness) and then hourly. Each pass deletes in
write units of at most 5,000 rows and stops after 200,000 rows (a larger backlog drains over the following passes),
so the writer lock is released between units and concurrent writes are delayed by one unit at most. Each pass logs
one INFO line (`Retention pass: events=... lease_history=... call_log=... folded_days=... capped=...`); a failed pass logs a WARN and
the next pass retries. The pruner writes no `events` rows.

Each pass first folds the completed UTC days of `call_log` into `usage_daily` (one row per day and dimension group), one
write unit per day, before it deletes anything: today, and yesterday during the first hour after midnight UTC, are never
folded, a day below the fold watermark (`usage_fold`) is never folded again, and at most 400 days fold per pass (the first
pass on a long-lived volume folds the whole backlog, then deletes). A fold failure pauses only `call_log` pruning for that
pass (events and lease history are still pruned; `call_log` deletes only folded days, so nothing unfolded goes) and logs a WARN
naming the failed UTC day and the exception class and message, never row data. `usage_daily` serves the `report` `usage` select past the raw horizon (`window.basis` `daily`); it is not pruned and
has no setting.

| Variable | Default | Prunes |
|----------|---------|--------|
| `RETENTION_EVENTS_DAYS` | `180` | Activity-class `events` rows (note writes, dependency changes, claims, leases, `seat.assigned`, `seat.ended`, `item.updated`, `transition.rejected`). |
| `RETENTION_AUDIT_EVENTS_DAYS` | `0` | Audit-class `events` rows: `item.transitioned`, `item.created`, `item.deleted`, `item.reparented`, `item.pinned`, `config.version_created`, `document.*`, `note.ownership_transferred`, `run.*`, `decision.*`, and the 3.x transition trail backfilled by V29. `0` keeps them forever. |
| `RETENTION_LEASE_HISTORY_DAYS` | `365` | Closed `resource_lease_history` intervals, by `released_at`. Open intervals are never pruned. |
| `RETENTION_CALL_LOG_DAYS` | `90` | `call_log` rows, by `at`, and only rows whose day has already been folded into `usage_daily` (the pruner folds first; nothing is pruned while no day is folded). |

Each value is a whole number of days; `0` keeps that data forever. Values are read once at startup (restart to
change); a blank, negative or non-integer value fails startup with an error naming the variable.

Guarantees:

- **The SSE replay window is never pruned.** An `events` row whose seq is within `API_SSE_BUFFER_SIZE` of the newest
  seq is kept however old, so a client resuming with `Last-Event-ID` inside the window never sees a gap.
- **The latest `claim.expired` row per item is kept**, because the expiry sweep deduplicates against it.
- **An event type the server does not classify is never pruned.**
- **Runs, seats, decisions and `usage_daily` are not pruned.** There is no per-root retention.

**Run table growth.** The `runs`, `run_items`, `run_seats` and `decision_requests` tables are kept forever in 4.0, and
so are the `run.*` and `decision.*` audit events at the default `RETENTION_AUDIT_EVENTS_DAYS=0`. The `seat.assigned`
and `seat.ended` events are activity-class and are pruned with `RETENTION_EVENTS_DAYS`. As an estimate, not a
guarantee, plan for about 40 MB per 1,000 runs for a run over 10 items with 3 seats each and 2 decisions. The
estimate sums the table rows (one `runs` row, 10 `run_items` rows, 31 `run_seats` rows including the coordinator and
2 `decision_requests` rows, about 25 KB with their indexes, most of it the seat rows) and the audit events a run
writes (about a dozen rows, among them a `run.ended` event that carries the close snapshot, about 10 KB). Larger
summaries, links, decision contexts and snapshots push it up; smaller runs pull it down. Archival and per-root
pruning are deferred.

**Known limit: the `claim.expired` guard.** To keep the latest `claim.expired` row per item, each prune unit for that
event type re-evaluates a correlated "latest row per entity" subquery for every candidate row (the guard in
`SqliteEventStore.prune`), so a pass re-scans those rows every time. A benchmark over 100,000 such rows measured about
1.85 seconds per pass with a longest write unit of 374 ms. The cost grows with the number of distinct-entity
`claim.expired` rows: at around a million of them, one unit could hold the writer long enough for concurrent writes to
hit `SQLITE_BUSY` and retry. A keyset cursor that removes the re-scan is deferred past 4.0. The re-scanned rows are the guarded latest-per-entity
`claim.expired` rows older than the cutoff, so a higher `RETENTION_EVENTS_DAYS` (or `0`, which disables pruning of
activity events) avoids the re-scan, and a lower value widens it.

How pruning shows in `report` answers (`window.retainedSince`, `truncatedByRetention`, the daily basis) is described in
[Reporting](reporting.md#retention-and-what-a-report-can-see).

The pruner runs no `VACUUM`: freed pages are reused by new rows, so the database file stops growing but does not
shrink. To reclaim disk space after a large first prune (for example on a volume upgraded from 3.x), stop the server
and run `VACUUM` offline.

---

## REST API Authentication

The credential layer (`API_ENABLED=true`, `API_AUTH_MODE`) authenticates every HTTP caller of `/api/v1/*` **and**, since 4.0, of `/mcp*`: one credential, one resolution pipeline, one principal. That principal (id `api:<id>` for a token-file or JWT credential, `local` for the unauthenticated modes) is the caller's identity everywhere - claim ownership, note ownership and audit attribution. There is no second, MCP-only identity layer: no request carries an `actor` or an `actor.proof` (a body `actor` is `unknown_parameter` on every tool, over MCP and REST alike), and the `actor_authentication:` config block is removed: a leftover block fails startup (see [Identity Configuration](#identity-configuration)).

| Concern | Decided by |
|---------|-----------|
| Who the caller is | The credential (`Authorization: Bearer`): token-file id or JWT `sub` |
| What the caller may do | The credential's `capabilities`, `scope` and `profile` |
| Who owns a claim or a note | The principal id |

A caller with `write-items` capability can create and patch items; an agent holding the same kind of credential can claim and advance items through `/mcp`. Two agents sharing one credential are one principal.

### Enabling the REST API

The REST API is off by default (`API_ENABLED` defaults to `false`). Set `API_ENABLED=true` to opt in — the API then requires `API_AUTH_MODE` to be set, and startup fails without it. Choose a mode:

```bash
# Bearer mode — static tokens from a YAML secret file
API_ENABLED=true
API_AUTH_MODE=bearer
API_TOKENS_PATH=/run/secrets/api-tokens.yaml
```

```bash
# JWKS mode — JWT tokens validated against a JWKS endpoint
API_ENABLED=true
API_AUTH_MODE=jwks
API_JWKS_URL=https://auth.example.com/.well-known/jwks.json
API_JWKS_ISSUER=https://auth.example.com
API_JWKS_AUDIENCE=task-orchestrator
API_JWKS_ALGORITHMS=RS256,EdDSA
API_JWKS_CACHE_TTL_SECONDS=300   # optional, default 300
API_JWKS_MAX_TOKEN_LIFETIME_SECONDS=86400   # optional, default 86400 (24h)
```

JWTs presented in this mode must carry an `exp` claim — a token with no `exp` is rejected (`401 invalid_token`) — and the SSE `auth.expired` watchdog (`API_SSE_AUTH_CHECK_INTERVAL_SECONDS`) runs for every JWKS-authenticated SSE session as a result. A token is also rejected once its remaining lifetime (`exp - now`, 60s clock-skew allowance) exceeds `API_JWKS_MAX_TOKEN_LIFETIME_SECONDS` (default 86400 = 24h); when the token carries an `iat`, a future-dated `iat` or an `exp - iat` spread beyond the same cap is rejected too. **Upgrading to a version with this cap can newly reject tokens your issuer already mints with a longer lifetime** — raise `API_JWKS_MAX_TOKEN_LIFETIME_SECONDS` if that is intentional.

```bash
# Unauthenticated mode — opt-in, loopback-only (see "Unauthenticated mode" below)
API_ENABLED=true
API_AUTH_MODE=none
API_ALLOW_UNAUTHENTICATED=true
```

### REST API env vars

`MCP_ALLOWED_HOSTS` and `MCP_ALLOW_UNAUTHENTICATED` are listed here alongside the REST API vars for convenience, but they are transport-level - they govern `/mcp` too, not just `/api/v1/*`.

| Variable | Required when | Default | Description |
|----------|--------------|---------|-------------|
| `MCP_ALLOWED_HOSTS` | reached via a non-loopback `Host` | *(empty)* | Comma-separated `Host` header allowlist (DNS-rebinding protection — see the read-first callout above), **extending** the always-allowed loopback defaults (`localhost`, `127.0.0.1`, `[::1]`, any port). `host` matches any port; `host:port` matches that port only; bracket IPv6 entries (`[::1]:3001`). Hostname matching is case-insensitive with one trailing dot ignored, but otherwise exact — no subdomains, no `127.0.0.0/8` range, no unbracketed or non-canonical IPv6. An entry with a scheme, path, `@`, or non-digit port is dropped with a startup WARN (it never widens the allowlist). `*` disables the guard entirely (WARN logged; not recommended). Applies to the HTTP transport only — `stdio` never calls the guard. |
| `MCP_ALLOW_UNAUTHENTICATED` | HTTP with the API disabled | `false` | Opt-in that lets `MCP_TRANSPORT=http` start with `API_ENABLED=false`: `/mcp` then serves every caller as the `local` principal, with no credential check. Without it that combination fails startup (`MCP_AUTH`). Parsed strictly (`true`/`1`/`yes` or `false`/`0`/`no`): any other value fails startup. Ignored when the API is enabled (the API's own mode decides) and over stdio. |
| `API_ENABLED` | optional | `false` | Master API switch. Unset or `false` skips all `/api/v1/*` route registration; set `true` to opt in. |
| `API_AUTH_MODE` | API enabled | — | `bearer` or `jwks`. Also accepts `none` when `API_ALLOW_UNAUTHENTICATED=true` (see below). Required. |
| `API_ALLOW_UNAUTHENTICATED` | opting into `none` | `false` | Confirm flag required alongside `API_AUTH_MODE=none`. Ignored with `bearer`/`jwks`. |
| `API_TOKENS_PATH` | bearer mode | `/run/secrets/api-tokens.yaml` | Path to bearer token YAML secret file. |
| `API_JWKS_URL` | jwks mode | — | JWKS endpoint URL. Its host must resolve only to public addresses: the egress guard refuses loopback, link-local, private (RFC 1918), CGNAT, unique-local and other non-public addresses (loopback is admitted only with `API_JWKS_ALLOW_INSECURE_URL=true`), so `host.docker.internal` and Docker-network names are refused. Must be `https`; plaintext `http` is rejected at startup unless the URL's host is loopback (`localhost`, `127.x.x.x`, `::1`) and `API_JWKS_ALLOW_INSECURE_URL=true`. Any other scheme (e.g. `file`, `ftp`), an `http` URL on a non-loopback host, or an unparseable `API_JWKS_ALLOW_INSECURE_URL` value fails startup with `IllegalArgumentException`. |
| `API_JWKS_ALLOW_INSECURE_URL` | opting into plaintext JWKS | `false` | Loopback-only opt-in to fetch `API_JWKS_URL` over plaintext `http`. Evaluated even when `API_JWKS_URL` is `https` (an invalid value still fails startup). |
| `API_JWKS_ISSUER` | jwks mode | — | Expected `iss` claim value in JWTs. |
| `API_JWKS_AUDIENCE` | jwks mode | — | Expected `aud` claim value in JWTs. |
| `API_JWKS_ALGORITHMS` | jwks mode | — | Comma-separated algorithm allowlist (e.g., `RS256,EdDSA`). |
| `API_JWKS_CACHE_TTL_SECONDS` | jwks mode | `300` | JWKS key cache TTL in seconds. |
| `API_JWKS_MAX_STALE_SECONDS` | optional | `max(3600, TTL)` | Oldest a cached key set may be, from its real fetch time, and still be served after a failed refresh. Positive and at least the TTL, else startup fails. Past it the key set is unavailable (`503 unavailable`). |
| `API_JWKS_MAX_TOKEN_LIFETIME_SECONDS` | jwks mode | `86400` | Maximum accepted token lifetime in seconds (24h default). A token is rejected once `exp - now` exceeds this value plus the 60s clock-skew allowance, regardless of `iat`; when `iat` is present, a future-dated `iat` or an `exp - iat` spread beyond the same cap is also rejected. Non-numeric or `<= 0` fails startup. |
| `CORS_ALLOWED_ORIGINS` | browser clients | _(none)_ | Comma-separated allowed origins. Empty = no cross-origin access. |
| `CORS_ALLOWED_METHODS` | CORS enabled | `GET,POST,PATCH,PUT,DELETE,OPTIONS` | Comma-separated methods. |
| `CORS_ALLOWED_HEADERS` | CORS enabled | `Authorization,Content-Type,If-Match,X-Client-Host` | Comma-separated request headers. |
| `CORS_EXPOSE_HEADERS` | CORS enabled | `ETag,Last-Event-ID,Retry-After,X-Req-Id` | Response headers JS may read. `Retry-After` is exposed by default so browser dashboards can read the resource-lease contention backoff hint directly off the `409 resource_unavailable` response of `POST /api/v1/ops/advance` (see Resource Leasing below) without falling back to the `error.retryAfterMs` body field; `X-Req-Id` carries the call's correlation id. |
| `CORS_MAX_AGE_SECONDS` | CORS enabled | `3600` | Preflight cache duration. |
| `API_SSE_BUFFER_SIZE` | SSE in use | `1000` | Last-Event-ID replay window, in event ids (seqs of the durable `events` table): a resume further behind the newest event gets `sync.lost` `buffer_evicted`. `0` = no replay. |
| `API_ALLOW_QUERY_TOKEN_FOR_SSE` | SSE + browser | `false` | Allow `?token=` auth for SSE (browser EventSource workaround). |
| `API_SSE_AUTH_CHECK_INTERVAL_SECONDS` | SSE in use | `30` | Interval for token-expiry checks on open SSE connections. |
| `API_REDACT_NOTE_ATTRIBUTION` | always | `true` | When `true`, non-admin callers see no `actor`/`verification` on notes or on `items_read` `history` entries. |
| `RESOURCE_LEASES_ENFORCED` | always (`EnvBoolean`) | `true` | Deployment-wide kill switch for the resource-lease gate (see Resource Leasing below). `false`/`0`/`no` disable it; `true`/`1`/`yes` or unset leave it on; matching is case-insensitive and trimmed; any other value logs a WARN and enforcement stays on. Disables **acquisition only**; releases always run regardless. Read **once at startup** into the typed `AppConfig` snapshot (restart-required); no runtime code reads the environment. |

### Bearer token secret file

See [api-rest.md §1](api-rest.md#1-authentication) for the full YAML format. Key points:
- `token_sha256` must be a 64-char lowercase hex SHA-256 digest of the plaintext token
- `capabilities` grant specific operations: `read`, `write-notes`, `write-items`, `advance`, `manage-dependencies`, `write-config`, `write-docs`, `runs`, `decide`, `admin` (`docs_write` needs `write-docs`; `write-config` no longer grants it)
- `kind` (optional, default `service`) is `human`, `agent` or `service`; `profile: director` (optional) confines the token to `POST /api/v1/ops/director/{tool}` — see [api-rest.md §1](api-rest.md#1-authentication)
- `scope.root_ids` restricts access to specific project trees (absent/`null` = unrestricted; an empty
  list `[]` fails startup rather than meaning unrestricted — omit the key instead). Every listed id must
  be a depth-0 project root: an existing non-root anchor fails startup naming the token id and the anchor
  (a JWT carrying one is a `401`, with one WARN per `sub`), and an anchor that does not exist yet is accepted
  and admits nothing until the root is created. `scope` itself
  and `scope.tags_include` fail closed on any malformed shape too — see
  [api-rest.md §1](api-rest.md#1-authentication) for the full fail-closed rules
- Token rotation requires a server restart (no live reload)
- `admin` capability unlocks attribution fields in responses (subject to `API_REDACT_*` env flags)

### Unauthenticated mode

`API_AUTH_MODE=none` + `API_ALLOW_UNAUTHENTICATED=true` (both required - see the env var table above) disables authentication on `/api/v1/*` and `/mcp*` entirely: every request, with or without an `Authorization` header, runs as the one `local` principal (kind `agent`, proof `none`, unrestricted scope, `ADMIN`), audited as the bare id `local` with verifier `local`. This exists for a **single-user, loopback-bound local server** - e.g. so the `config-sync.mjs` hook (below) can push per-project config without minting a bearer token. Because every caller is that one principal, they share claims and note ownership.

**This mode carries the fence requirement of the local `/mcp` modes:** ensure the port is unreachable from any untrusted network before setting both keys - for Docker, publish only to loopback (`-p 127.0.0.1:3001:3001`; leave `MCP_HTTP_HOST=0.0.0.0`, see the bind-default rationale above and [SECURITY.md](../../SECURITY.md)), or for a direct non-Docker JAR run, set `MCP_HTTP_HOST=127.0.0.1`. Do not combine this mode with a `0.0.0.0` publish or a port published outside a trusted host, and do not set `MCP_HTTP_HOST=127.0.0.1` inside a Docker container to "harden" it - that binds loopback *inside* the container's own network namespace and breaks host->container reachability instead of restricting it. The server logs a `SECURITY:` WARN at startup whenever this mode is active.

### Key-set outages on the REST API

When `API_AUTH_MODE=jwks`, a request whose JWT cannot be judged because the JWKS key set is unavailable (never fetched, or older than `API_JWKS_MAX_STALE_SECONDS`) is answered `503 unavailable` with `error.detail.retryAfterMs` 15000 and no `WWW-Authenticate` header; a token the key set rejects is `401 invalid_token`. The same holds for `/mcp` and the SSE `?token=` path. Nothing is served under a guessed identity. `GET /api/v1/info` reports the verifier (`verifier`: `none`, `bearer` or `jwks`) and, with `jwks`, `maxStale`, so a client can tell how long an outage is tolerated. **Bearer mode** has no key set to lose: a token-file token is judged locally.

### Client-side config sync (SessionStart hook)

> **4.0 migration note (W8).** The per-root config and rule REST routes this hook calls (`PUT /api/v1/roots/{rootId}/config`, `GET /api/v1/roots/{rootId}/config`, `GET /api/v1/roots/{rootId}/rules`) were removed in 4.0 in favor of `POST /api/v1/ops/config` (the `config` tool). The plugin hook is moved to the new endpoint separately; until it is, the description below is the 3.x contract. Against a 4.0 server the hook stays fail-open (it never blocks the session) but prints a failure line (`PUT returned HTTP 404`, plus a rules `root not found` line) and syncs nothing.

In a shared HTTP deployment, per-project config lives in the DB per root (hot-reloaded, no restart), while the mounted global `.taskorchestrator/config.yaml` is only a fallback. The plugin's `config-sync.mjs` SessionStart hook keeps a project's DB config in sync with its workspace file: on session start it fingerprints the local file and, if it differs from the server's stored config, PUTs it to `PUT /api/v1/roots/{rootId}/config`. **The file is the source of truth; the DB row is a synced replica** (a byte-identical file is a no-op via `If-Match`/fingerprint).

The hook is **fail-open and opt-in** — it no-ops silently (exit 0) unless an API URL resolves (in order: `TASK_ORCHESTRATOR_API_URL`, `apiUrl` in a project-level `client.json` beside the located config (or in the main checkout for a linked worktree), then `apiUrl` in the user-level `client.json`). The project-level file, written by project-mode `/task-orchestrator:init`, is honoured only as a bare loopback origin (no path, query or fragment), so a fleet (non-loopback) server, or one behind a path prefix, needs the env var or the user-level file that `/task-orchestrator:init --user` writes. The project-level `client.json` assumes a single-user machine: any directory above the working directory that holds a `config.yaml` and a `client.json` can point the hooks, with the bearer token, at a loopback port. On a shared host, set `TASK_ORCHESTRATOR_API_URL`, which wins over every file, and do not work under a directory another user can write. Stdio/local deployments (which read the file directly) are unaffected:

| Variable | Required for sync | Description |
|----------|-------------------|-------------|
| `TASK_ORCHESTRATOR_API_URL` | yes, unless `client.json` supplies `apiUrl` | Base URL of the REST API, e.g. `http://orchestrator.internal:3001` (the hook appends `/api/v1/roots/{rootId}/config`). |
| `TASK_ORCHESTRATOR_API_TOKEN` | no | Bearer token with the `write-config` capability, scoped (`scope.root_ids`) to this workspace's project root. **Optional** — omit it entirely when the server runs in [unauthenticated mode](#unauthenticated-mode); the hook then sends the request with no `Authorization` header at all. |

Set these per-workspace (e.g. in `.claude/settings.json`'s `env` block, or the shell environment). Against a bearer/jwks server the token needs only `write-config` for its own root — not `admin` (add `read` if the same token also serves the SubagentStop phase guard below). Against an unauthenticated server, no token is needed at all. Either way the server must have `API_ENABLED=true`. If the API is unreachable or returns an error, the hook logs a one-line note and continues; it never blocks session start.

**Rule sync.** After the config sync above, the same hook pushes this workspace's git-tracked `.taskorchestrator/rules/*.md` files into the per-root rule store, replacing the one-time manual rule stash. It calls `GET /api/v1/roots/{rootId}/rules` to read each rule's current `rulesVersion`, then `PUT`s only the keys whose local SHA-256 hash (of the CRLF-normalized body) differs from — or is entirely absent from — that listing, to `PUT /api/v1/roots/{rootId}/plans/rule%2F<key>` (the literal `%2F` is required — the plan route takes one path segment). **4.0 note:** that route is removed on a 4.0 server (rules are `docs_write` `upsert` with `kind` `rule`, over `POST /api/v1/ops/docs_write`, which needs the `write-docs` capability; `write-config` no longer grants it); the hook moves to the new tool in the plugin work, and until then rule sync against a 4.0 server reports the failed `PUT` in its one-line note. Changed rules push concurrently, each under the hook's existing 2000ms per-request timeout; the SessionStart entry's own timeout is 10s to give a first push of many rules room to finish. Because this step adds a `GET .../rules` call, **the token now also needs `read`** in addition to `write-config` (an unauthenticated server needs nothing extra). The rule sync is fail-open independently of the config sync above: an absent `rules/` dir is a silent no-op, and any other error (unreachable API, a non-200 `GET rules`, an individual `PUT` failure) is folded into the same one-line note rather than affecting the config-sync outcome or the exit code.

**Staleness semantics (last-known-good on a read error).** "Hot-reloaded, no restart" above describes
the happy path — a per-root config read failing with a transient database error is a distinct case
from "no per-root config for this root." `PerRootConfigService` never falls back to the mounted
global config on a read error: it serves that root's last-known-good cached config instead, without
evicting it, and logs a WARN naming the root and the error. Last-known-good has no TTL — the very
next successful read refreshes it - and is held by the one `EffectiveConfigResolver` the server builds
at startup, so MCP and REST share a single cache. A root this process has never successfully read before (a
cold cache, e.g. right after a restart, before any request has resolved that root's config) has
nothing to serve on a read error and fails closed, and so does a read made inside a unit of work
(every write and the `items_read` `context` preview). The failure is the `unavailable` error
(kind `shedding`, `retryAfterMs` 1000): per element for a write batch, for the whole call otherwise
- see the [Response envelope and Error catalog](api-reference.md#response-envelope) of the API
reference and [api-rest.md](api-rest.md) section 6 for the exact shapes. This is orthogonal to
`config-sync.mjs` above: the hook pushes a NEW config version; last-known-good is what a READ falls
back to when the DB itself is transiently unreachable.

**HTTP-first policy.** New plugin-side infrastructure features — `config-sync.mjs`, SSE event
streaming, the `plan-capture.mjs` hook (which stashes an approved plan as a plan document; the
`PUT /roots/{rootId}/plans/{slug}` route it uses is removed in 4.0 in favour of `POST /ops/docs_write`), and the SubagentStop phase guard below — are built HTTP-only, each fail-opening to a silent no-op when
no API URL resolves (the `TASK_ORCHESTRATOR_API_URL` env var, then a project-level or the user-level `client.json`, in that order; the project-level file is honoured only as a bare loopback origin). STDIO deployments keep full MCP tool
functionality but do not gain these convenience features; STDIO is positioned as the local/evaluation
transport, while a persistent HTTP daemon with the REST API enabled is the recommended path for
ongoing fleet or multi-project work.

### SubagentStop phase guard

The plugin's SubagentStop phase guard (`hooks/phase-guard.mjs` + `hooks/phase-guard-record.mjs`) has
the same REST dependency as `config-sync.mjs` above: it needs an API URL (the env var or `client.json`), and,
against a bearer/jwks server, a `TASK_ORCHESTRATOR_API_TOKEN` scoped with the `read` capability (no
token needed against an unauthenticated server). Without a reachable API URL it fails open — no
subagent is ever blocked. See
[integration-guides/plugin-skills-hooks.md](integration-guides/plugin-skills-hooks.md) for full
hook behavior. The hook calls the removed `GET /items/{id}/gate` route once per subagent stop; its 4.0
replacement is `items_read` `select: context` (`data.gateStatus`, see [api-rest.md section 10](api-rest.md#10-removed-3x-routes)),
and the hook is re-pointed in the plugin work, so until then it fails open against a 4.0 server.

---

---

## Environment Variable Reference

One row per operator-facing environment variable the server reads. This table is the single reference: `server.json` lists exactly these names, and a guard test keeps both in step with the source. Client-side `TASK_ORCHESTRATOR_*` variables and refusal-only legacy variables (`USE_FLYWAY`, `DEGRADED_MODE_POLICY`) are deliberately not listed.

| Variable | Default | Documented in |
|----------|---------|---------------|
| `AGENT_CONFIG_DIR` | working dir | this table |
| `API_ALLOW_QUERY_TOKEN_FOR_SSE` | `false` | [REST API env vars](#rest-api-env-vars) |
| `API_ALLOW_UNAUTHENTICATED` | `false` | [REST API env vars](#rest-api-env-vars) |
| `API_AUTH_MODE` | *(none)* | [REST API env vars](#rest-api-env-vars) |
| `API_ENABLED` | `false` | [REST API env vars](#rest-api-env-vars) |
| `API_JWKS_ALGORITHMS` | *(none)* | [REST API env vars](#rest-api-env-vars) |
| `API_JWKS_ALLOW_INSECURE_URL` | `false` | [REST API env vars](#rest-api-env-vars) |
| `API_JWKS_AUDIENCE` | *(none)* | [REST API env vars](#rest-api-env-vars) |
| `API_JWKS_CACHE_TTL_SECONDS` | `300` | [REST API env vars](#rest-api-env-vars) |
| `API_JWKS_ISSUER` | *(none)* | [REST API env vars](#rest-api-env-vars) |
| `API_JWKS_MAX_STALE_SECONDS` | `max(3600, TTL)` | [REST API env vars](#rest-api-env-vars) |
| `API_JWKS_MAX_TOKEN_LIFETIME_SECONDS` | `86400` | [REST API env vars](#rest-api-env-vars) |
| `API_JWKS_URL` | *(none)* | [REST API env vars](#rest-api-env-vars) |
| `API_REDACT_NOTE_ATTRIBUTION` | `true` | [REST API env vars](#rest-api-env-vars) |
| `API_SSE_AUTH_CHECK_INTERVAL_SECONDS` | `30` | [REST API env vars](#rest-api-env-vars) |
| `API_SSE_BUFFER_SIZE` | `1000` | [REST API env vars](#rest-api-env-vars) |
| `API_TOKENS_PATH` | `/run/secrets/api-tokens.yaml` | [REST API env vars](#rest-api-env-vars) |
| `CORS_ALLOWED_HEADERS` | `Authorization,Content-Type,If-Match,X-Client-Host` | [REST API env vars](#rest-api-env-vars) |
| `CORS_ALLOWED_METHODS` | `GET,POST,PATCH,PUT,DELETE,OPTIONS` | [REST API env vars](#rest-api-env-vars) |
| `CORS_ALLOWED_ORIGINS` | *(none)* | [REST API env vars](#rest-api-env-vars) |
| `CORS_EXPOSE_HEADERS` | `ETag,Last-Event-ID,Retry-After,X-Req-Id` | [REST API env vars](#rest-api-env-vars) |
| `CORS_MAX_AGE_SECONDS` | `3600` | [REST API env vars](#rest-api-env-vars) |
| `DATABASE_BUSY_TIMEOUT_MS` | `5000` | [SQLite Tuning](#sqlite-tuning--connection-pools-database_max_connections-database_busy_timeout_ms) |
| `DATABASE_MAX_CONNECTIONS` | `10` | [SQLite Tuning](#sqlite-tuning--connection-pools-database_max_connections-database_busy_timeout_ms) |
| `DATABASE_PATH` | `data/current-tasks.db` | [Schema management](#schema-management-locking-and-startup-integrity) |
| `DATABASE_SHOW_SQL` | `false` | this table |
| `DB_COMPACT_ON_UPGRADE` | `true` | [Remediation](#remediation) |
| `FLYWAY_REPAIR` | `false` | [Schema management](#schema-management-locking-and-startup-integrity) |
| `FTS_INTEGRITY` | `startup` | [Schema management](#schema-management-locking-and-startup-integrity) |
| `LOG_FILE` | unset | [Logging](#logging) |
| `LOG_LEVEL` | `INFO` | [Logging](#logging) |
| `MCP_ALLOWED_HOSTS` | *(empty)* | [REST API env vars](#rest-api-env-vars) |
| `MCP_ALLOW_UNAUTHENTICATED` | `false` | [REST API env vars](#rest-api-env-vars) |
| `MCP_HTTP_HOST` | `0.0.0.0` | this table |
| `MCP_HTTP_PORT` | `3001` | this table |
| `MCP_SERVER_NAME` | `mcp-task-orchestrator-current` | this table |
| `MCP_TOOL_PROFILE` | `orchestration` | [Authentication](#mcp-authentication---read-first) |
| `MCP_TRANSPORT` | `stdio` | [Authentication](#mcp-authentication---read-first) |
| `READINESS_FILE` | `/tmp/mcp-task-orchestrator.ready` | [Startup, Readiness, and Health Checks](#startup-readiness-and-health-checks) |
| `RESOURCE_LEASES_ENFORCED` | `true` | [REST API env vars](#rest-api-env-vars) |
| `RETENTION_AUDIT_EVENTS_DAYS` | `0` | [Data Retention](#data-retention) |
| `RETENTION_CALL_LOG_DAYS` | `90` | [Data Retention](#data-retention) |
| `RETENTION_EVENTS_DAYS` | `180` | [Data Retention](#data-retention) |
| `RETENTION_LEASE_HISTORY_DAYS` | `365` | [Data Retention](#data-retention) |
| `SCHEMA_MODE` | `migrate` | [Schema management](#schema-management-locking-and-startup-integrity) |
| `UPGRADE_BACKUP` | `true` | [Schema management](#schema-management-locking-and-startup-integrity) |

## Release Gates

Three gates stand between a change and a published image, each independent of the others:

1. **The Gradle suite** (the `workflow_call` test job in `.github/workflows/test.yml`) -- unit, integration and distribution-manifest tests, including the env-var parity guard over the table above.
2. **The fresh-volume image smoke plus Trivy** -- `scripts/ci/smoke-test-image.sh <image>` boots the built image on an empty volume, waits for the readiness marker, performs the MCP stdio handshake and requires pure JSON-RPC on stdout; Trivy then scans for fixable CRITICAL findings.
3. **The upgrade smoke from the 3.16 golden volume** -- `scripts/ci/smoke-test-image.sh --seed current/src/test/resources/upgrade/golden-v17-3.16.sqlite <image>` seeds the volume with the checked-in 3.16-format database, boots the image, then inspects the stopped volume with `sqlite3`: the once data steps are recorded (and no every-boot step is), Flyway is at the newest `V<N>` migration with no failed rows, the work item / note / dependency counts still match the golden manifest, exactly one pre-4.0 backup (`current-tasks.db.pre-4.0.<yyyyMMddTHHmmssZ>.bak`) sits in the volume and verifies (`PRAGMA integrity_check` is `ok`, highest successful Flyway version equals the golden manifest's `schema_version`), and a second boot leaves every `data_steps.applied_at` unchanged and takes no second backup (a database already at version 18 or higher is never copied, see "Upgrading from 3.x" below). An empty seed must fail that gate; the `Upgrade smoke negative path (empty seed must fail)` step in `test.yml` keeps proving it. Both the PR image-smoke job and the release gate in `docker-publish.yml` run the upgrade smoke before anything is pushed; the negative step runs in the PR job only.

## Scope

This guide covers **server-side configuration**: credentials and identity, capacity tuning, observability, and lifecycle. The **agent/client side** - calling the `advance` tool's `claim` op, presenting a credential (`Authorization: Bearer`) on every call, sequencing claim/heartbeat/release, retrying on `claim_held`, minting and rotating credentials with your identity provider, integrating with your audit infrastructure - is the implementer's responsibility.

### The Bundled Claude Code Plugin Is Not a Fleet Driver

The plugin under `claude-plugins/task-orchestrator/` targets default-mode single-agent orchestration. Its skills and hooks teach the agent-owned phase-entry pattern (`advance` `transition` called directly by each agent) - not the claim-then-advance coordination required by fleet deployments. Do not rely on it as a fleet driver:

- The bundled orchestration context and skills do not reference the `advance` `claim` op and assume unclaimed items
- The plugin's `enforce-actor-attribution` hook is removed (identity is the transport principal, and a 4.0 server rejects a body `actor` as `unknown_parameter`); the plugin does not manage credentials or enforce claim ownership precedence
- Subagent dispatch templates do not include claim acquisition steps

Treat the bundled plugin's behavior in claim mode as undefined. Build your own claim-aware skills, hooks, or middleware tailored to your fleet's identity scheme, contention policy, and audit integration.

### The Public Contract

TO publishes the following as the integration seam — the surface fleet implementers build against:

- **MCP tools** - `advance` ops `claim`, `heartbeat` and `release` (acquire/refresh/release), `advance` `transition` (with ownership enforcement on claimed items), `items_read` `context` with an `itemId` (operator diagnostic with full claim detail), `items_read` `list` with `claimStatus` (filtered discovery, identity-redacted), `items_read` `next` with `includeClaimed` (work discovery)
- **Credentials** - `API_AUTH_MODE` and its `API_*` variables (one credential model for REST and `/mcp`); the credential decides the principal
- **Audit log** - the principal (`api:<id>` or `local`) persisted on every write regardless of any flag, queryable via `notes_read` and `items_read` `history`

Anything else (specific skill instructions, hook behavior, orchestration-context conventions) is implementation detail of the bundled plugin and not part of the fleet contract.

---

## Fleet Topology Patterns

Choose the topology that fits your workload. The four canonical patterns are listed from simplest to most powerful. The **hybrid pattern (Scenario D) is the recommended default for feature-based development work**.

### Scenario A - Pure Orchestration (no claims)

A single orchestrator agent owns all dispatch decisions. It calls `advance` `transition` to move children through the pipeline. No `claim` op is used.

**Characteristics:**
- Simple. No identity configuration required.
- Sequential — one orchestrator dispatches in topological order.
- Chain continuity is implicit: the orchestrator transitions A→terminal then immediately dispatches B. No inter-agent race possible.
- Works for single-dev or low-concurrency setups.

**Ancestor-claim behavior:** Not applicable - `claim` is never called.

### Scenario B — Pure Claim, Flat Items (no parent→child relationships)

Independent items with no hierarchical relationship. Fleet agents each call `advance` `claim` with a `select` and work items from a shared queue.

**Characteristics:**
- Horizontally scalable. Many agents drain the queue independently.
- Items are genuinely independent: no shared context, no chain continuity concern.
- Selector hygiene: use `parentId: null` or specific tag filters to restrict to top-level items.

**Ancestor-claim behavior:** Inert. All items are root-level (depth=0); no ancestor chain to walk. The filter is a no-op.

### Scenario C — Pure Claim, Chains (anti-pattern)

Dependent items (A→B→C) where each item is independently claimed from the queue by competing fleet agents.

**Anti-pattern warning:** This topology has a fundamental limitation - the **post-completion race**. When A completes and its dependency on B is cleared, B becomes claimable by every fleet agent simultaneously. Whichever agent calls `advance` `claim` with a `select` first wins B, regardless of which agent has the most context from working A. The system architecture's answer to context continuity is notes as the handoff contract, but notes cannot capture an agent's working mental model.

**Ancestor-claim behavior:** While A is in progress and claimed, B (a child of A) is protected from other agents' broad selectors. However, once A reaches terminal, A's claim is a stale record — the protection expires. B then becomes immediately available to all fleet agents (post-completion race). For tightly-coupled chains, use Scenario D instead.

**When this is acceptable:** If your chain items are genuinely independent (notes provide sufficient handoff), and post-completion races are tolerable or managed by fine-grained selectors, Scenario C may work. Document the tradeoff explicitly.

### Scenario D — Hybrid (RECOMMENDED): Claim at Parent, Orchestrate Sub-tree

Fleet agents claim at the **feature level** (top-level items). Each claiming agent then orchestrates its own sub-tree using `advance` `transition` - not `claim` - for children.

```
Fleet agent work loop:
  advance claim {select: {tags: ["feature"], role: "queue"}}   -> claim a feature
    -> advance transition {itemId: feature, trigger: "start"}
    -> advance transition {itemId: A, trigger: "start"}         [orchestrate, no claim]
    → ... wait for A to complete ...
    -> advance transition {itemId: B, trigger: "start"}         [orchestrate, no claim]
    -> advance transition {itemId: feature, trigger: "complete"}
  advance release {itemId: feature}                             -> release
```

**Why this is the recommended topology:**

1. **No post-completion race.** Sub-items are never exposed to the claim-eligibility pool. The orchestrating agent transitions A→terminal then advances B immediately; no other agent ever sees B in a claimable state.
2. **Full chain-context continuity.** One agent owns the entire feature sub-tree. Notes from A are in its context when it starts B. No inter-agent handoff required.
3. **Sub-tree protection (selector mode only).** The ancestor-claim filter runs on the read side, in selector mode and `items_read` `next`, so an `advance` `claim` with `select: {role: "queue"}` from a competing agent will skip children whose ancestor is claimed. It does not protect ID-mode claims: `claim` by explicit `itemId` only checks the target item's own claim state, so an agent holding a stale id from before the ancestor was claimed can still claim a child directly. Claims coordinate cooperating agents that go through the selector/recommender path - they do not authorize.
4. **TTL-as-recovery-vector.** If the orchestrating agent crashes, the feature's claim TTL expires. Recovery agents can then claim the feature and read `session-tracking` notes to resume orchestration. No manual intervention needed.

**Recommended TTL guidance for hybrid pattern:**

| Work unit size | Recommended TTL | Heartbeat cadence |
|---|---|---|
| Small feature (1–3 sub-tasks, < 1 hour) | 900s (default) | TTL/2 = 450s |
| Medium feature (4–10 sub-tasks, 1–4 hours) | 3600s (1 hour) | 1800s (30 min) |
| Large feature (10+ sub-tasks, multi-hour) | 7200s–14400s | TTL/4 |

Heartbeat: call `advance` `heartbeat` with the feature's `itemId` at the recommended cadence while orchestration is in progress. Each heartbeat refreshes `claimExpiresAt` without changing `originalClaimedAt`.

**Selector hygiene for hybrid pattern:** Use `parentId: null` or `tags: "feature"` in your selector to target top-level features. Avoid broad selectors like `role: queue` without a parent filter — these work correctly (the ancestor-claim filter skips sub-items in selector mode), but they waste eligibility-query bandwidth evaluating candidates that will be filtered.

### Summary Table

| Topology | Claim used? | Ancestor filter impact | Chain continuity | Recommended for |
|---|---|---|---|---|
| A — Pure orchestration | No | Not applicable | Implicit (single orchestrator) | Single-dev, sequential pipelines |
| B — Pure claim, flat | Yes, all items | Inert (all root) | N/A (items independent) | Truly independent parallel work |
| C — Pure claim, chains | Yes, all items | Protects during A's work, not after | Post-completion race (anti-pattern) | Simple chains with sufficient note handoff |
| **D — Hybrid (RECOMMENDED)** | **Feature level only** | **Protected in selector mode** | **Full (single orchestrator per feature)** | **Feature-based development work** |

---

## Identity Configuration

Identity is the transport principal. Verification fails closed: a credential is accepted, rejected (`401`) or, when the JWKS key set is unavailable, answered `503 unavailable`; the server never falls back to a self-reported identity. There is no degraded-mode policy: `actor_authentication.degraded_mode_policy` and the `DEGRADED_MODE_POLICY` environment variable were removed in 4.0.

A leftover key (any value, `null` included) or a set variable (any value) stops the server with an `IllegalArgumentException` naming it, before the readiness marker is written (the container exits non-zero). Delete the key from the global config and unset the variable before upgrading:

```bash
# This now exits non-zero: "DEGRADED_MODE_POLICY is no longer supported ..."
docker run --rm -i \
  -v mcp-task-data:/app/data \
  -v "$(pwd)"/deploy/global-config/.taskorchestrator:/project/.taskorchestrator:ro \
  -e AGENT_CONFIG_DIR=/project \
  -e DEGRADED_MODE_POLICY=reject \
  task-orchestrator:dev
```

To authenticate callers, configure the credential mode ([Enabling the REST API](#enabling-the-rest-api)) and, for JWKS, the [JWT Contract](#jwt-contract); the one credential covers REST and `/mcp`.

### What replaced the policy values

| Former value | Now |
|---|---|
| `accept-cached` | Gone. A token verified against a stale key set (within `API_JWKS_MAX_STALE_SECONDS`) still authenticates, with proof `verified_stale`; past the bound, or with no key set ever fetched, the answer is `503 unavailable`. A rejected token is `401`, never accepted. |
| `accept-self-reported` | Gone. The caller is always the transport principal (`api:<id>`, or `local` in local mode); no request carries an identity of its own. |
| `reject` | The only behavior: nothing is trusted that was not proven. Claim ownership (`claim`, `release`, `heartbeat`, and the ownership check of `transition`) is enforced against the principal. |

### `actor_authentication` removed in 4.0

The `actor_authentication:` block in the global `.taskorchestrator/config.yaml` is **removed**. Nothing authenticated or attributed a call through it, since no request carries an `actor` or an `actor.proof` any more, so its only remaining effect was an informational field on `GET /api/v1/info`, which is gone too. What to know when upgrading:

- A global config with an `actor_authentication:` key, whatever its value (a mapping, `{}`, a scalar or a bare `null`), stops the server with an `IllegalArgumentException` naming the key and the config path, before the readiness marker is written (the container exits non-zero). The legacy 3.x `auditing:` key and a set `DEGRADED_MODE_POLICY` variable fail the same way.
- Delete the block. Caller authentication is configured with `API_AUTH_MODE` and the `API_JWKS_*` variables, not in the config file. The 4.0 plugin no longer has an attribution hook, so there is no plugin-side attribution requirement to configure.
- A per-root push carrying the key is rejected at `/actor_authentication` as an unknown top-level section; nothing is stored.
- `GET /api/v1/info` no longer returns `actorAuthenticationEnabled`.
- Solo stdio: with no `AGENT_CONFIG_DIR` the server reads the workspace `.taskorchestrator/config.yaml` as its global file, so a hook-only `actor_authentication: {enabled: true}` block in it fails startup too.

## Rolling Out Claim Mode

Claim mode is opt-in. New deployments can start in any policy; existing deployments should ramp up policy strictness in stages so client-side actor wiring can catch up to server-side enforcement.

### Stages

Identity is no longer a policy dial: every call runs as the transport principal and claim ownership is always enforced against it. The remaining ramp is the credential mode of the deployment:

| Stage | Config | Server behavior |
|---|---|---|
| **0 — Local** | Unauthenticated mode (`API_AUTH_MODE=none`, or `/mcp` local mode) | Every call is the `local` principal. The `advance` tool's `claim` op works but is optional; a `transition` of an unclaimed item needs no claim. |
| **1 — Issued tokens** | `API_AUTH_MODE=bearer` | Each caller holds a token-file credential (proof `issued`); the principal id is the audited holder id (`api:<id>`). |
| **2 — Verified tokens** | `API_AUTH_MODE=jwks` | Each caller presents a JWT (proof `verified`, or `verified_stale` against a stale key set); an unavailable key set answers `503`, a bad token `401`. |

### Recommended Sequence

1. **Stage 0 → Stage 1.** Mint a token per agent in the token file and have clients send `Authorization: Bearer`. Claims then belong to `api:<id>` holders.
2. **Stage 1 → Stage 2.** Configure the JWKS source (`API_JWKS_*`) and have clients present JWTs. Use `GET /api/v1/info` (`verifier`, `maxStale`) to confirm the mode, and watch for `503 unavailable` answers to size `API_JWKS_MAX_STALE_SECONDS` against your issuer's outages.

### Rolling Back

Each stage is reversible by changing the credential mode. Existing claim records persist across the change: a claim placed by `api:<id>` can be heartbeated, released or transitioned only by that principal until it expires, so move holders between modes only when their claims have lapsed.

---

## JWT Contract

Under `API_AUTH_MODE=jwks` every credential on `/api/v1/*` and `/mcp*` is a JWT judged by the API JWT policy. This section documents the claims TO reads - not how to operate a JWT issuer.

### Claims TO Reads

| Claim | Required | Used for |
|---|---|---|
| `iss` | Yes (`API_JWKS_ISSUER` is required in jwks mode) | Must match `API_JWKS_ISSUER`; mismatch is `401 invalid_token`. |
| `aud` | Yes (`API_JWKS_AUDIENCE` is required in jwks mode) | Must contain `API_JWKS_AUDIENCE`; mismatch is `401 invalid_token`. |
| `sub` | Required | The principal id (`sub`); the audited holder id is `api:<sub>`. A token without a `sub` is rejected. |
| `exp` | Required | Enforced with a **60-second clock-skew allowance**; a token without `exp`, or past it, is rejected. Also bounds the lifetime cap below. |
| `nbf` | Optional | If present, enforced with a 60-second clock-skew allowance. |
| `iat` | Optional | When present: a token dated more than 60s in the future is rejected, and so is an `exp - iat` spread beyond `API_JWKS_MAX_TOKEN_LIFETIME_SECONDS` (+60s skew). Independent of `iat`, `exp - now` beyond the same cap is always rejected. |
| `to_kind` | Optional | `human`, `agent` or `service` (trimmed, case-insensitive); see [api-rest.md section 1](api-rest.md#1-authentication) for the default. A non-string or unknown value rejects the JWT. |
| `to_scope` | Optional | `root_ids` and `tags_include` scope; absent is unrestricted. A malformed value rejects the JWT (fail closed). |
| `to_capabilities` | Optional | The capability list; absent defaults to read-only. |

The signature algorithm must be on the `API_JWKS_ALGORITHMS` allowlist; `none` and symmetric algorithms (`HS256` etc.) are not supported. The API JWT policy has no `jti` replay protection, no DID binding and no `sub`/`actor.id` matching: the principal is `sub`, full stop. TO reads no other custom claims.

### Token Lifetime Cap

`API_JWKS_MAX_TOKEN_LIFETIME_SECONDS` (default `86400` = 24h; must be a positive integer or startup fails). A token is rejected once:

- `exp - now > cap + 60s` - always checked, independent of `iat` (bounds how long a stolen token stays usable from the moment it is presented, and cannot be defeated by a forged or absent `iat`), or
- `iat` is present and `iat > now + 60s` (a future-dated `iat`), or
- `iat` is present and `exp - iat > cap + 60s` (an honestly-declared over-long token).

**Upgrade note:** deployments that mint tokens with a lifetime longer than 24 hours see those tokens rejected. Raise `API_JWKS_MAX_TOKEN_LIFETIME_SECONDS` if longer-lived tokens are intentional.

### JWKS Source and Egress

`API_JWKS_URL` is the single key source. It must be `https`; plaintext `http` is accepted only for a literal loopback host with `API_JWKS_ALLOW_INSECURE_URL=true`. Every key fetch passes the egress guard, which resolves the host and refuses the fetch when **any** resolved address is non-public: loopback (unless `API_JWKS_ALLOW_INSECURE_URL=true`), link-local, private (RFC 1918), CGNAT, unique-local, unspecified, multicast and reserved ranges, and IPv6 forms that wrap a non-public IPv4 address. A name that resolves to a mix of public and private addresses is refused. In particular `host.docker.internal`, Docker-network service names and LAN hosts are refused, so a JWKS endpoint must be reachable at a public address. The guard is resolve-then-connect, so it is not a defence against an attacker who controls the authoritative DNS for the host.

### `API_JWKS_CACHE_TTL_SECONDS` and `API_JWKS_MAX_STALE_SECONDS`

JWKS key material is cached for `API_JWKS_CACHE_TTL_SECONDS` (default: 300). When a cached entry expires and a refresh fails, the prior cache is served only while it is younger than `API_JWKS_MAX_STALE_SECONDS` (default `max(3600, TTL)`), measured from its real fetch time:

| Cache + refresh state | Result |
|---|---|
| Fresh hit (within TTL) | JWT verified, identity = `sub`, proof `verified` |
| Expired, refresh succeeds | JWT verified against the fresh key set, proof `verified` |
| Expired, refresh fails, prior fetch younger than the stale bound | Stale key set served, JWT verified, proof `verified_stale` |
| Expired, refresh fails, and no prior fetch or a prior fetch older than the stale bound | The key set is unavailable: `503 unavailable` (`retryAfterMs` 15000), no `WWW-Authenticate`; the token is neither accepted nor called invalid |

Operators can alert on the `503` rate and on `verified_stale` in the call log's `proof_status` column to see prolonged JWKS outages.

Refresh behavior behind the table:

- **Backoff.** After a failed refresh the next attempt is deferred 15 s, doubling per consecutive failure up to 300 s, and reset by a success. Calls between attempts take the fast path and are served the stale set (inside the bound) instead of each re-fetching.
- **Unknown `kid`.** A token naming a `kid` the cached set lacks forces one refresh, at most once per 30 s per provider, and not at all while the source is in failure backoff.
- **Failed forced refresh.** If that forced refresh fails on an entry still inside its TTL, the entry is marked failed and backed off like any other, so a call can report `verifiedFromCache=true` before the TTL has elapsed.
- **Cold start during an outage.** With no cached entry there is nothing to serve or back off: concurrent callers serialize on one lock and each attempts a fetch, so every call fails `503 unavailable` until a fetch succeeds.
- **Redirects.** The fetch does not follow redirects; a `3xx` is a failed fetch.

### JWT `exp` vs Claim TTL

JWT lifetime and claim TTL are independent. A JWT whose `exp` passes mid-claim does not invalidate the claim record itself, but the holder's next call (`advance` `heartbeat`, `advance` `transition` on the claimed item) presents the credential again and is rejected (`401 invalid_token`) once it has expired. A holder must present a fresh token to heartbeat or transition a claimed item, so size JWT lifetime to comfortably exceed the heartbeat cadence.

The JWKS cache is separate from JWT lifetime - it caches the verifier's public key material, not the JWTs themselves.

### Proof handling

This section covers evidence written by earlier versions. A 4.0 server accepts no `actor.proof` (a body `actor` is `unknown_parameter`) and returns none: the `actor` objects that
`notes_read` and `items_read` `history` return carry only `id`, `kind` and `parent`, and the `verification`
object (`status`, `verifier`, `reason`) reports how the principal was established (`api-jwks`, `api-bearer` or `local`).

**Since migration `V17__Store_Actor_Proof_Evidence.sql`, the raw proof (the JWT) is not persisted
at all.** Only forensic *evidence about* the proof is stored on the `notes` rows and in the `item.transitioned`
event payload (`events.data`; before 4.0 it was the `role_transitions` table, whose rows migration V29 copied
into `events`):
- a lowercase hex SHA-256 hash of the proof's UTF-8 bytes, whenever a non-blank proof was
  supplied (independent of verification outcome), and
- the cryptographically verified JWT claims (`iss`, `sub`, `aud`, `jti`, `iat`, `exp`, `kid`,
  `alg`) — only when the proof was `VERIFIED`.

This evidence is stored for forensics only: no MCP tool or REST route returns it, `ADMIN` included
(3.x exposed it as `verification.proof` to `ADMIN`), and `ActorClaimDto` carries no
proof field at all; the historical opt-out mechanisms for this behavior were removed after their
one-release deprecation window (see CHANGELOG.md) and are now ignored like any unrecognized
include value or env variable.

**Pre-upgrade backups and pre-upgrade free space may still hold live tokens.** A SQLite database
file or backup (`docker cp`, volume snapshot) taken *before* upgrading to a build with V17 still
contains raw proofs from before the scrub — those remain replayable as a `VERIFIED` identity until
each token's own `exp`.

V17's migration sets every existing `actor_proof` value to `NULL` in the live database, with
`PRAGMA secure_delete = ON` so the cells that scrub frees are zeroed. This is partial: copies
SQLite had already moved or freed before the upgrade (page splits, notes deleted or re-upserted)
can survive in the file's free space, and no scrub reaches a backup taken earlier.

Historical rows scrubbed by V17 get no hash and no claims — there was no way to recompute a
SHA-256 of a value that is being erased without a code-executing migration, which was rejected as
out of scope; this is a known, accepted forensic gap for anything written before the upgrade.

#### Remediation

1. **Rotate first.** Rotate actor signing keys and reissue long-lived tokens. The only remedy
   reaching every copy; then purge pre-upgrade backups.
2. **Compaction now runs automatically.** The first start after upgrading to a build
   with this remediation runs a one-time startup compaction — `VACUUM`, a rebuild + `integrity-check`
   of all four FTS5 shadow tables, and a WAL checkpoint — gated on `PRAGMA user_version` so
   it runs exactly once per database file (a repeat boot is a no-op; a failed attempt is WARN-logged
   and retried on the next boot). It completes before the readiness marker is written and before
   the server starts serving. Set `DB_COMPACT_ON_UPGRADE=false` to opt out (e.g. to run the offline
   runbook manually on your own schedule instead); see the environment variable table below. **This
   is not the same check as step 3's `PRAGMA integrity_check`** — the automatic path runs only the
   FTS5 `integrity-check` special command against each of the four shadow tables in [FTS_TABLES],
   not a whole-database `PRAGMA integrity_check`; it is not a full substitute for step 3's offline
   check. The image ships no `sqlite3` binary (see step 2 below), so verify the automatic run with
   the server's own INFO log lines instead of trying to inspect `PRAGMA user_version` directly:
   `Startup compaction: starting one-time compaction of N bytes` followed by `Startup compaction
   complete: X bytes -> Y bytes in Zms` (or the DatabaseManager-level `Startup compaction outcome:
   COMPACTED` line) confirms it ran and finished.
3. **Offline compaction runbook (opt-out fallback).** Live file only; needs free
   disk ≥2× DB size. Note that `VACUUM`'s temporary copy is written to SQLite's own temp directory
   (`SQLITE_TMPDIR`/`TMPDIR`, typically `/var/tmp` or `/tmp` — the container's overlay filesystem
   in Docker, not the `mcp-task-data` volume), so the ≥2× headroom must exist there, not only on the
   data volume.
   1. Stop the server.
   2. Run
      `docker run --rm -v mcp-task-data:/data alpine:3.20 sh -c "apk add sqlite && sqlite3 /data/current-tasks.db"`.
      The image has no `sqlite3`.
   3. Execute in order:
      - `PRAGMA wal_checkpoint(TRUNCATE);`
      - `VACUUM;`
      - `INSERT INTO t(t) VALUES('rebuild');` and then `VALUES('integrity-check')`, for each `t` in
        `work_items_fts_trigram`, `work_items_fts_text`, `notes_fts_trigram`, `notes_fts_text`
      - `PRAGMA integrity_check;`
      - `PRAGMA wal_checkpoint(TRUNCATE);`
   4. `chown -R 1001:1001 /data`, then restart.

   Warn: skipping the rebuilds silently desyncs search; compaction misses backups and filesystem
   slack.

| Variable | Required when | Default | Description |
|----------|--------------|---------|-------------|
| `DB_COMPACT_ON_UPGRADE` | Opting out | `true` | Set `false` to skip the automatic one-time startup compaction described above (e.g. to run the offline runbook manually instead, or to avoid the extra startup time on a very large database). Ignored during a `FLYWAY_REPAIR` run, which never runs the automatic compaction regardless of this variable. |

### Schema management, locking and startup integrity

Flyway is the only schema path. Direct mode (`USE_FLYWAY=false`) has been removed and `USE_FLYWAY` is
ignored (one WARN if still set). A database with tables but no `flyway_schema_history` is baselined at
V17 only when its schema matches the V17 shape exactly; otherwise it is refused unmodified. Point
`DATABASE_PATH` at a new file, or restore a Flyway-mode backup.

| Variable | Default | Description |
|----------|---------|-------------|
| `FTS_INTEGRITY` | `startup` | `startup` runs the FTS5 `integrity-check` on every FTS table at each start and rebuilds a desynced one. `off` skips only that check and logs one WARN; the inventory check (every FTS table and trigger exists) still runs and still fails startup when an object is missing. Any other value falls back to `startup` with a WARN. |
| `UPGRADE_BACKUP` | `true` | Before the first 4.0 migration of a 3.x database (stored schema version 17 or lower), copy the database file to `<db>.pre-4.0.<yyyyMMddTHHmmssZ>.bak` (UTC) beside it and verify the copy; a copy that cannot be taken or verified fails startup as `UPGRADE_BACKUP` before any migration. `false` skips the copy with one WARN. Accepts `true`/`1`/`yes` and `false`/`0`/`no` (case-insensitive); an unset variable is `true` and any other value falls back to `true` with a WARN. A new database, one already at version 18 or later, `FLYWAY_REPAIR=true` and `SCHEMA_MODE=validate` never copy. See "Upgrading from 3.x". |
| `SCHEMA_MODE` | `migrate` | `migrate` applies pending migrations. `validate` runs no migrate and no baseline: a pending or future version, or an empty database, fails startup. Any other value fails startup. `FLYWAY_REPAIR` takes precedence, but only over a valid value: an invalid `SCHEMA_MODE` fails startup even with `FLYWAY_REPAIR=true`. `validate` is not read-only: it takes the `<db>.migrate.lock` file, may create an empty database file when `DATABASE_PATH` points at a missing path, and still runs the FTS integrity check, which may rebuild an index. |

- **Future versions fail closed.** A database whose history is ahead of the binary refuses to start. The remedy is to upgrade the binary to the release that wrote the schema; `FLYWAY_REPAIR` is not a way past it.
- **Migration lock.** The whole schema phase runs under an OS file lock `<dbfile>.migrate.lock` next to
  the database file, so the schema phase of concurrent boots serializes. One acquisition of the lock covers the `UPGRADE_BACKUP` copy, the schema phase, the parent-cycle check and the startup compaction, so a second first boot of a 3.x volume waits for the first one's copy and migration and then sees version 18 or later (one `.bak` in total; see "Upgrading from 3.x"). A second boot waits (INFO log every 10 s) for all of that and gives
  up after 300 s, naming the lock file; a first boot of a very large database can outlast that wait, and the waiter then fails startup closed as `SCHEMA_UPDATE` without taking a copy. The lock file is never deleted; leaving it in place is normal.
  A bounded retry on `SQLITE_BUSY` covers filesystems where the lock is advisory only.
- **Startup integrity.** After migrate, startup verifies that the six FTS5 tables (work items, notes and
  documents, two each) and the FTS and parent-cycle triggers exist (a missing one fails startup and nothing
  is re-created), runs the FTS5 `integrity-check` on each FTS table and rebuilds one that is desynced
  (unless `FTS_INTEGRITY=off`). It also logs WARN-only reports (never failing, never writing) for
  placement drift (depth or `root_id` inconsistent with the parent, orphans), mutually blocking
  dependency pairs, and links whose target no longer exists (links are provenance and outlive their
  targets), each with a count and the first 10 ids.
- **Backups.** Do not `VACUUM` or dump/restore without rebuilding the FTS indexes (the startup compaction and the offline runbook do both): they can renumber `rowid`s and desync the
  external-content FTS indexes. Use the sqlite3 `.backup` command, or copy the
  file with the server stopped (include the `-wal`/`-shm` files or checkpoint first).
- **Topology.** One HTTP server hub is the recommended shape; one stdio process per session against the
  same database file is supported (the migration lock serializes the `UPGRADE_BACKUP` copy and the schema phase of their boots, so concurrent first boots of a 3.x volume take one copy, see "Upgrading from 3.x"). Stop every 3.x process
  before upgrading: a 3.x binary does not fail closed on a newer database.

---

## Upgrading from 3.x

The procedure for the first 4.0 start on a volume that a 3.x server wrote. What changes in the data and the wire contract is listed under "Upgrading from 3.x" in [CHANGELOG.md](../../CHANGELOG.md); this section is the order of operations, what the server does for you, and how to undo it.

In the commands, `<volume>` is the Docker volume holding the database (`mcp-task-data` in the quick-start) and `<name>` is a backup file name of your choosing. The commands assume the image defaults: the database is `/app/data/current-tasks.db` (the image's `DATABASE_PATH=data/current-tasks.db`, relative to `/app`) and the server runs as uid 1001. If you set `DATABASE_PATH`, use that file name wherever `current-tasks.db` appears below.

### 1. Stop every 3.x process

Stop the 3.x container, and close every client that runs a 3.x stdio session against the same volume. A 3.x binary does not fail closed on a newer database, so a 3.x process left running can write to a file the 4.0 migrations are about to rewrite.

```bash
docker stop <3.x container>
```

### 2. Back up the whole volume

```bash
docker run --rm -v <volume>:/app/data -v "$(pwd)":/backup alpine tar czf /backup/<name>.tgz -C /app/data .
```

On PowerShell write `${PWD}` in place of `"$(pwd)"`. List the archive to confirm it is readable:

```bash
docker run --rm -v "$(pwd)":/backup alpine tar tzf /backup/<name>.tgz
```

Keep the archive off the volume. The `.bak` the server writes in step 3 sits on the same volume as the database, so it does not protect against losing the volume itself.

### 3. First 4.0 start

`UPGRADE_BACKUP` is on by default, so no flag is needed. The container must stay up for you to watch readiness, so start it detached over HTTP (a stdio container exits when its stdin closes). The example publishes the port on loopback only and uses the local, unauthenticated `/mcp` mode, which is acceptable only because of that loopback mapping; for a shared host start the container the way you will run it (see "`/mcp` Authentication - Read First").

```bash
docker run -d --name to-4.0 -v <volume>:/app/data -p 127.0.0.1:3001:3001 -e MCP_TRANSPORT=http -e MCP_ALLOW_UNAUTHENTICATED=true ghcr.io/jpicklyk/task-orchestrator:4.0.0
```

Wait for the readiness marker (`READINESS_FILE`, which the image's `HEALTHCHECK` tests; see "Startup, Readiness, and Health Checks"). The loop stops when the container is healthy, or prints its log and stops if it exits:

```bash
until [ "$(docker inspect --format '{{.State.Health.Status}}' to-4.0)" = healthy ]; do
  [ "$(docker inspect --format '{{.State.Running}}' to-4.0)" = true ] || { docker logs to-4.0; break; }
  sleep 5
done
```

The first start is slower than a normal boot: it runs the migrations, the startup compaction and the data steps before the marker is written. On a large database raise the container's `--start-period`, or the health will read `unhealthy` while the start is still progressing.

**What the server does before the first migration.** After the database opens and before Flyway runs, the server takes a safety copy, under the same migration lock as the migration that follows, when all of these hold:

- the highest successful version in `flyway_schema_history` is 17 or lower, or the file has user tables and no `flyway_schema_history` at all (a 3.x Direct-mode file; it counts as unversioned and is copied); and
- the binary's newest classpath migration is 18 or higher.

It then runs these steps in order, and the first one that fails stops the sequence:

1. Free space: the directory holding the database must have at least the size of the database file plus its `-wal` file free (exactly that much is enough).
2. `PRAGMA wal_checkpoint(TRUNCATE)`; a busy result is a failure, because a copy could miss WAL frames.
3. Copy the database file to `<db name>.pre-4.0.<yyyyMMddTHHmmssZ>.bak` in the same directory (for the default file, `current-tasks.db.pre-4.0.20261016T142700Z.bak`; the stamp is UTC). The file is created with `CREATE_NEW`: a path that already exists fails the run and is never overwritten or deleted.
4. Verify the copy: `PRAGMA integrity_check` must return `ok` and its highest successful `flyway_schema_history` version must equal the source's. A copy that fails verification is deleted.

On success the INFO log line `Pre-upgrade backup written: <path> (schema version <n>)` names the `.bak`, and Flyway then migrates. If any step fails, startup fails as `UPGRADE_BACKUP` before any migration runs, the process exits non-zero and the readiness marker is never written; the log (and the exception) carry the cause. Fix it (free space, a leftover `.bak` at the same-second path) and start again, or set `UPGRADE_BACKUP=false` to migrate without a copy.

When the server does not copy:

- `UPGRADE_BACKUP=false` skips the copy with one WARN naming the database and the versions involved. The WARN is logged only when a copy would otherwise have been taken.
- A new database (the file is missing or empty, or has no user tables) and a database already at version 18 or higher (every later boot, once the first one migrated it) are never copied.
- `FLYWAY_REPAIR=true` and `SCHEMA_MODE=validate` never migrate, so they never copy; neither does an invalid `SCHEMA_MODE`, which fails startup on its own. An in-memory database is never copied.

Limits to know about:

- Only the database file is copied, after the checkpoint. The `.bak` is not a copy of the volume: config files, the `.migrate.lock` file and any other file on the volume are not in it.
- The server never deletes a `.bak`, with one exception: a partial copy it created in this run is deleted when the write or the verification fails. Each boot that still finds a version of 17 or lower takes a new one with a new stamp, so a start that failed midway can leave several. Delete them once the 4.0 volume is accepted.
- A `.bak` holds the pre-upgrade data in full, including anything the 4.0 migrations later scrub. Protect and purge it like the database (see "Proof handling" -> Remediation).

### 4. Check the result

List the volume to see the `.bak`, and the log for the line above:

```bash
docker run --rm -v <volume>:/app/data alpine ls -l /app/data
docker logs to-4.0
```

**Data steps.** The `data_steps` table has one row per once step that has run: `name`, `applied_at` (UTC), `rows_affected` and `binary_version`. In Claude Code the `check_schema_version` command (`.claude/commands/check_schema_version.md`) prints the last ten `flyway_schema_history` rows and the `data_steps` rows; without it, this prints the latest three migrations and the `data_steps` rows:

```bash
docker run --rm -v <volume>:/data alpine sh -c "apk add --no-cache sqlite >/dev/null 2>&1 && sqlite3 -header -column /data/current-tasks.db 'SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 3; SELECT name, applied_at, rows_affected, binary_version FROM data_steps ORDER BY applied_at;'"
```

A missing row for a once step that is registered fails startup as `DATA_STEPS`, so every row you expect is there on a healthy start. An every-boot step leaves no row.

**Config warnings.** The global config file is imported at every boot; a legacy 3.x top-level key in it is accepted as a stored warning. `GET /api/v1/info` returns them as `configWarnings` (an empty array when there are none). The route needs the REST API enabled and an authenticated caller, and the step 3 example has the API off: to read it, start with `API_ENABLED=true` and a credential mode (see "Enabling the REST API" and [api-rest.md](api-rest.md)). A global file carrying 3.x plugin sections is expected to show them; move those settings under `extensions.claude-code`.

### 5. Roll back

Rolling back discards everything 4.0 wrote after the upgrade. A 3.x binary cannot open a database at version 26 or later, so the file must go back to its 3.x state first.

1. Stop and remove the 4.0 container:

   ```bash
   docker stop to-4.0 && docker rm to-4.0
   ```

2. Restore the database, either from the server's `.bak` or from your tar archive.

   From the `.bak` (use the file name `ls` showed in step 4). Removing the `-wal` and `-shm` files matters: the 4.0 database ran in WAL mode, and a leftover WAL from it must not be replayed onto the restored file.

   ```bash
   docker run --rm -v <volume>:/app/data alpine sh -c "cd /app/data && rm -f current-tasks.db-wal current-tasks.db-shm && cp <db>.pre-4.0.<yyyyMMddTHHmmssZ>.bak current-tasks.db && chown 1001:1001 current-tasks.db"
   ```

   Or from the archive, replacing the whole volume content:

   ```bash
   docker run --rm -v <volume>:/app/data -v "$(pwd)":/backup alpine sh -c "find /app/data -mindepth 1 -delete && tar xzf /backup/<name>.tgz -C /app/data"
   ```

   If you restore or copy the data into a NEW Docker volume, the data directory must be owned by uid 1001 before the first boot (the server runs as uid 1001 and cannot write a backup or the database otherwise): run `docker run --rm -v <volume>:/app/data alpine chown -R 1001:1001 /app/data`.

3. Start the 3.16 image with the flags you used before the upgrade. Use the exact tag; never `latest`, which follows the newest release and would start 4.0 again.

   ```bash
   docker run -d --name to-3.16 -v <volume>:/app/data -p 127.0.0.1:3001:3001 -e MCP_TRANSPORT=http ghcr.io/jpicklyk/task-orchestrator:3.16.0
   ```

To confirm the restored file is a 3.x database, run the step 4 query again: the latest `flyway_schema_history` row reads version 17, and the `data_steps` query fails with `no such table`.

<!-- Source citations for the behavioural claims in "Upgrading from 3.x" (docs seat, task ff08e631). Each citation names a symbol (class, function or heading); search for it, line numbers are deliberately not used. P = PreUpgradeBackup (current/src/main/kotlin/io/github/jpicklyk/mcptask/current/infrastructure/sqlite/PreUpgradeBackup.kt), D = DatabaseManager (infrastructure/sqlite/DatabaseManager.kt), S = CurrentMcpServer (bootstrap/CurrentMcpServer.kt), A = AppConfig (infrastructure/config/AppConfig.kt).
 1 eligibility, stored <= 17 and binary target >= 18: PreUpgradeBackup.runBackup (the skip checks) and maxSuccessfulVersion.
 2 a file with user tables and no flyway_schema_history is unversioned (null) and still copied: PreUpgradeBackup.hasUserTables and maxSuccessfulVersion (null).
 3 binary target = latest classpath migration: DatabaseManager.latestClasspathMigration; none found = SkippedNoMigration in DatabaseManager.runPreUpgradeBackup.
 4 step order free space -> checkpoint -> copy -> verify: the order of steps in PreUpgradeBackup.runBackup.
 5 free space needs db + -wal, equality proceeds: the `free < needed` check in PreUpgradeBackup.runBackup.
 6 busy checkpoint is a failure: PreUpgradeBackup.checkpointBusy and its use in runBackup.
 7 file name and UTC stamp: PreUpgradeBackup.STAMP and the target name in runBackup.
 8 CREATE_NEW, an existing path fails and is never overwritten or deleted: the NOFOLLOW_LINKS exists check, CREATE_NEW and FileAlreadyExistsException branch in PreUpgradeBackup.runBackup.
 9 verification (integrity_check = ok, same max version), failed copy deleted: PreUpgradeBackup.verify and the delete in runBackup.
10 INFO log line: "Pre-upgrade backup written" in PreUpgradeBackup.runBackup.
11 Failed maps to Reason.UPGRADE_BACKUP before updateSchema(), both under the migration lock: DatabaseManager.backupAndUpdateSchema, CurrentMcpServer (Failed(Reason.UPGRADE_BACKUP)), Reason in StartupOutcome.
12 non-zero exit, readiness marker not written: CurrentMain.main (non-zero exit on Failed); marker written only later by runStdioTransport and the HTTP transport runner in CurrentMcpServer; cleared first by ReadinessMarker.clear at the top of CurrentMcpServer startup.
13 failure detail carries the cause and the UPGRADE_BACKUP=false hint: PreUpgradeBackup.fail, DISABLE_HINT and RETRY_AFTER_FIX.
14 UPGRADE_BACKUP=false skips with one WARN, only when a copy was otherwise due: PreUpgradeBackup.runBackup (the `!enabled` branch); default true in AppConfig.upgradeBackup via EnvBoolean.parse in EnvBoolean.kt (unset = default, unrecognized = default with WARN).
15 new database never copied (missing, empty, no user tables): PreUpgradeBackup.runBackup (isFile/length check) and hasUserTables.
16 already >= 18 never copied (every later boot): PreUpgradeBackup.FIRST_UNPROTECTED_VERSION and the SkippedAlreadyUpgraded branch.
17 FLYWAY_REPAIR, SCHEMA_MODE=validate and an invalid SCHEMA_MODE never copy: DatabaseManager.updateSchema (repair, validate and invalid-mode branches).
18 in-memory or non-file URL never copied: DatabaseManager.runPreUpgradeBackup (file-URL resolution), StartupCompaction (file path helper).
19 only the database file is copied, after the checkpoint: PreUpgradeBackup.runBackup (a single Files.copy of dbFile).
20 the server never deletes a .bak (only a partial or unverifiable copy it just made); each boot still at <= 17 copies again with a new stamp: PreUpgradeBackup.runBackup (eligibility is re-evaluated every boot).
21 image defaults: Dockerfile (the WORKDIR, USER, VOLUME, HEALTHCHECK on READINESS_FILE and DATABASE_PATH=data/current-tasks.db instructions).
22 the database runs in WAL mode (stale -wal/-shm must not survive a restore): DatabaseManager (SQLiteConfig.JournalMode.WAL).
23 data_steps columns: current/src/main/resources/db/migration/sqlite/V23__Data_Steps.sql; an unapplied once step fails startup as DATA_STEPS: CurrentMcpServer (the DataStepRunner call and Failed(Reason.DATA_STEPS)) and the "Data steps" paragraph above.
24 configWarnings on GET /api/v1/info, authenticated: api-rest.md (configWarnings in the GET /api/v1/info section), ServiceRoutes (info route).
25 a 3.x binary cannot open a V26 or later database: CHANGELOG.md ("Upgrading from 3.x").
-->

---

## SQLite Version Requirement

FTS5 trigram tokenizer support requires **SQLite ≥ 3.45**. The Docker image bundles SQLite via
the `xerial/sqlite-jdbc` driver, so this requirement is automatically satisfied in containerized
deployments. If you run the JAR directly against a system-installed SQLite, verify:

```bash
sqlite3 --version  # must be ≥ 3.45
```

The V7 migration fails at startup if the trigram FTS5 table cannot be created.

---

## SQLite Tuning — connection pools, `DATABASE_MAX_CONNECTIONS`, `DATABASE_BUSY_TIMEOUT_MS`

SQLite is a single-writer database. The server keeps two HikariCP connection pools over the one database file:

| Pool | Size | Begins | Purpose |
|---|---|---|---|
| `to-writer` | 1 connection | `IMMEDIATE` (writer lock taken at `BEGIN`) | every write unit |
| `to-reader` | `DATABASE_MAX_CONNECTIONS` (default 10, clamped to 1-64) | `DEFERRED`, `PRAGMA query_only = 1` | reads outside a write unit |

Both pools use WAL, `foreign_keys = ON` and a fixed 1 s `busy_timeout`. Reads no longer take the writer lock, so a long write unit does not delay reads (they see the last committed snapshot).

Writes inside this process are serialized by an in-process lock, so concurrent callers queue fairly (and can be cancelled) instead of colliding in SQLite. Only OTHER processes touching the same file contend for the SQLite lock, which is why the pool `busy_timeout` is short. A unit that hits `SQLITE_BUSY` or `SQLITE_LOCKED` is retried as a whole with jittered backoff for up to 10 s (the same budget also bounds the wait for the in-process write lock), then fails with the `unavailable` error (`retryAfterMs` 1000). The 10 s is a soft bound: an attempt already running is not force-cancelled, so a unit can overrun by up to about 1.4 s (maximum backoff of 250 ms with up to 1.5x jitter, plus the 1 s pool `busy_timeout` on BEGIN, plus the attempt's own statement time). If no pool connection becomes available within 2 s the call fails at once with `unavailable` (`retryAfterMs` 2000). In-memory databases (`mode=memory`, `:memory:`) are refused at startup: two pools would open two different databases. Every store write runs inside a unit of work; a store write attempted outside one is a server bug and fails at once (`OutsideUnitWriteException`, logged and counted per operation) instead of running as its own implicit transaction.

`DATABASE_BUSY_TIMEOUT_MS` (default 5000 ms) now governs only the startup paths: the Flyway migration connection and the one-time startup compaction. It no longer sets the request-path timeout.

```bash
# A longer startup wait when several server processes may migrate the same file
DATABASE_BUSY_TIMEOUT_MS=15000
# A smaller reader pool for a constrained host
DATABASE_MAX_CONNECTIONS=4
```

### Recommended Values (`DATABASE_BUSY_TIMEOUT_MS`, startup paths only)

`DATABASE_BUSY_TIMEOUT_MS` no longer scales with fleet size: request-path contention is handled by the in-process writer lock and the fixed 1 s pool `busy_timeout`. Raise it only when several server processes may run the Flyway migration or startup compaction against the same database file at once.

| Situation | Recommended timeout |
|---|---|
| One server process (any number of agents) | 5000ms (default) |
| Several server processes may start together on one file | 10000-15000ms |
| Many processes migrate/compact one shared file concurrently | 15000-30000ms (see Capacity Planning below) |

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
  -v "$(pwd)"/deploy/global-config/.taskorchestrator:/project/.taskorchestrator:ro \
  -e AGENT_CONFIG_DIR=/project \
  -e DATABASE_BUSY_TIMEOUT_MS=15000 \
  task-orchestrator:dev
```

---

## Capacity Planning

SQLite is a single-writer database backed by a file on a single host. Understanding this constraint is essential for fleet sizing.

### Item Granularity — One Concurrent Actor Per Item

Before sizing the fleet, size the *items*. Both the claim mechanism and resource leasing assume
**one concurrent actor per work item**: claims bind one actor to an item, and resource leases are
item-keyed — two sessions working inside the same item share its lease and its claim, and the
server cannot detect the interference (it arbitrates transitions, not actions). Field pattern to
avoid: modelling "one conceptual task" as one item while multiple sessions (a watcher and an
extractor, say) operate inside it — the lease holds, and the sessions still collide on the shared
browser tab / compose window / rate budget underneath. Cut items at actor granularity: if two
sessions run concurrently, give each its own item (children of a shared parent work well), let
each claim its own item, and declare the shared resource on both so the lease serializes them.

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
- Agents frequently receive `unavailable` (kind `shedding`) errors from tool calls
- `advance` latency grows beyond 500ms per call
- `DATABASE_BUSY_TIMEOUT_MS` at 30s still produces timeout errors

These are signals to partition the work, not to increase the timeout further.

### Partitioning Strategy

The cleanest fleet partition is by work tree:
- Instance A handles feature containers A–M
- Instance B handles feature containers N–Z
- Each agent targets a specific instance based on its assignment

Agents use `parentId` scoping on `items_read` `next` (`parentId`) to restrict their search to their assigned subtree, avoiding cross-instance work stealing.

---

## Identity Model

### `claimedBy` as Opaque String

The `claimedBy` field on a `WorkItem` is an uninterpreted opaque string. The server treats it as a key for ownership comparison — it does not parse or validate its structure. Valid values include:

- Principal ids: `"api:worker-12"` (a token-file id or a JWT `sub`), or `"local"` in a local mode
- Rows written by earlier versions may hold any other string (session ids, hostnames, `did:web` identifiers)

### Identity From the Credential

The caller's identity is the principal its credential resolves to; nothing the caller puts in a request body can override it.

The identity resolution chain:
1. A valid JWT -> its `sub` claim is the principal id, and the audited holder id is `api:<sub>`
2. A valid token-file token -> the token's id is the principal id (audited `api:<id>`)
3. An invalid or expired credential -> `401`, the call does not run
4. An unavailable key set -> `503 unavailable`, the call does not run
5. No credential in a local mode (stdio, `API_AUTH_MODE=none`, `MCP_ALLOW_UNAUTHENTICATED=true`) -> the one `local` principal

There is no self-reported identity and no fallback to one. **In the local modes every session is the same `local` principal**, so concurrent stdio and local HTTP sessions share claims and note ownership: they cannot contend with each other, and separating them needs credentials or seat tokens.

### `claimedBy` and PII

`claimedBy` is whatever value the identity resolution chain produces — typically `api:<id>` (the JWT `sub` or token-file id), or `local` in local mode. If your issuer puts an email address, employee ID, or other personal identifier in `sub`, that value persists in the database on every claim and in audit log entries on every write.

| Surface | What gets persisted |
|---|---|
| `work_items.claimed_by` | Current claim holder identity |
| Audit notes (every write) | Actor claim object on every write — `id`, `kind`, `parent`, verification metadata |
| `notes_read` body content | Audit notes are readable via standard note reads |
| `notes.actor_proof_claims` / the `actorProofClaims` key of an `item.transitioned` event's `data` (V17; formerly `role_transitions.actor_proof_claims`) | Historical only: verified JWT claims JSON on a `VERIFIED` proof, written by earlier versions (see "Proof handling"); a 4.0 server accepts no proof and writes none. Pre-4.0 rows can hold `sub`/`iss`, so the same PII sensitivity as `claimedBy` applies to them |

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
| `items_read` `context` with an `itemId` | Full claim detail (`claimDetail`): `claimedBy`, `claimedAt`, `claimExpiresAt`, `originalClaimedAt`, `isExpired` |
| `advance` `claim` / `heartbeat` op success result | Own claim metadata only: `version` (the item row version), `claimedAt`, `expiresAt`, `originalClaimedAt`, and `released` (the caller's own other items the claim auto-released; omitted when none) |
| `not_claim_holder` on a fenced write, and an `ownership`-blocked cascade | `itemId` only - the holder is never named |
| `advance` `claim` op `claim_held` error | `detail.expiresAt` and `retryAfterMs` only - competing agent identity never disclosed |
| `items_read` `next` with `includeClaimed: true` | `isClaimed: boolean` per item only - no identity |
| `items_read` `list` with `claimStatus` | The filter only: entries carry no claim data |
| `items_read` `overview` (global) | `claimSummary: { active, expired, unclaimed }` per root - counts only |
| `items_read` `context` without an `itemId` (health view) | `claimSummary: { active, expired, unclaimed }` - counts only |

`items_read` `context` with an `itemId` is the operator diagnostic. All other surfaces use count-only or boolean signals.

---

## Resource Leasing

Resource leasing is a **separate primitive from the `advance` `claim` op**, deliberately: a claim leases a work
*item* to a single actor; a resource lease serializes access to a shared *external* resource
(a staging slot, a test database, a deploy credential, a fleet-wide deployment mutex) across
whichever items declare it. An agent can hold a work-item claim and one or more resource leases
simultaneously — the two systems do not interact, and one released is not implicitly released
alongside the other. See [Workflow Guide §11](workflow-guide.md#11-resource-leasing) for the
agent-facing model (declaration modes, contention/retry discipline, the full guarantees/non-
guarantees statement) and
[`config-format.md`](../../claude-plugins/task-orchestrator/skills/manage-schemas/references/config-format.md)
for the `resources:` trait/registry YAML syntax. This section covers the operator-facing runbook.

### Item-keyed holder model

The exclusivity subject is the **holding work item**, not the actor that triggered acquisition
(`acquired_by_actor_id` is audit metadata only, nullable). This is deliberate: a principal is often
shared across processes (ten worker processes all holding one credential, or all running as `local`,
would otherwise all be treated as "the same holder," reproducing the
exact multi-instance credential-collision failure this feature exists to prevent). Practical
consequence for operators: **you cannot release a lease by matching on actor identity** — the only
release paths are (1) the holding item's own transition out of WORK, (2) TTL expiry, or (3) an
explicit `DELETE /api/v1/resources/leases/{key}` force-release. There is no "kick this actor's
leases" operation.

### TTL guidance

Default TTL is **3600 seconds** (1 hour), configurable per resource key up to a hard maximum of
**86400 seconds** (24 hours) via the registry's `defaultTtlSeconds`, or per-declaration via a
trait's `ttlSeconds` (precedence: `requirement.ttlSeconds` > `registry.defaultTtlSeconds` > 3600,
clamped to `[1, 86400]`). This is a materially longer default than the work-item claim TTL (900s) —
size it to the resource, not the item: a resource lease should comfortably outlast the WORK phase
of the item holding it, since there is no lease heartbeat/renewal call (unlike a claim, which
agents heartbeat explicitly with `advance` `heartbeat`). A lease that expires while the holding item is still legitimately in
WORK does not get cleaned up automatically — the item keeps working, but a contending item could
then acquire the "expired" key out from under it (a lost-exclusivity edge case; size TTL generously
enough that this is a non-event in practice).

| Resource type | Guidance |
|---|---|
| Fast single-step operation (e.g. a schema migration against a shared test DB) | Default (3600s) is comfortable headroom |
| Multi-hour feature work touching a shared staging slot | Size `ttlSeconds` to 2-3x your realistic worst-case WORK duration |
| Fleet-wide deployment mutex | Size to the deployment window, not the calendar — a stuck deploy should free the mutex well before an operator would otherwise notice |

### Force-release runbook

When a holder crashes or hangs mid-WORK, the lease sits until TTL expiry unless an operator
intervenes:

```bash
curl -X DELETE https://orchestrator.internal:3001/api/v1/resources/leases/staging-db \
  -H "Authorization: Bearer <admin-token>"
```

Requires `ADMIN` capability. Responses (all response envelopes): `200` with `data: { resourceKey, releasedCount }` on success;
`404 not_found` (detail kind `lease`) if no active lease exists on that key (idempotent-safe — a repeat call after a
successful release 404s rather than double-counting); `400 invalid_request` if `{key}` fails the
`^[a-z0-9][a-z0-9\-_./]*$` / 128-char check. Every successful force-release is WARN-logged with the
acting principal's token id — check server logs to attribute the override after the fact. The same
token id is also threaded through to `releasedByActorId` on the closed `resource_lease_history`
interval(s) for that key (`releaseReason: "force_released"`), so the override is attributable from
`GET /api/v1/resources/leases/history?key=<key>` (`ADMIN` capability for actor identity) even after
server logs have rotated — see [Workflow Guide §11, "Lease-interval history"](workflow-guide.md#11-resource-leasing)
and [`api-rest.md`](api-rest.md) §14. There is **no equivalent MCP tool/verb** — force-release is
REST-only, by design (see Non-Goals in the implementation plan: no MCP acquire/release/renew verbs).

**Diagnosing before you force-release:** `GET /api/v1/resources/leases` (`READ` capability; add
`ADMIN` to see `acquiredByActorId`) lists every active lease with its `holderItemId` and
`expiresAt`. Cross-reference `holderItemId` against `items_read` `context` with that `itemId` (MCP) to
confirm the holder is actually dead (stale `roleChangedAt`, no recent transitions) before force-
releasing — a live holder that gets force-released will simply lose its lease silently; there is no
notification back to the holder.

### Enforcement kill switch

`RESOURCE_LEASES_ENFORCED=false` (see the env var table above) disables **acquisition** fleet-wide —
releases keep running regardless, so no lease is ever stranded by flipping the switch. Use this as
an emergency valve if the gate itself is suspected of misbehaving (e.g. falsely rejecting every WORK
entry) — it degrades the deployment to rung-1/rung-2 behavior (the audit field and the lease table
both still exist and are still readable/force-releasable via REST) without requiring a config edit.
It does, however, need a process/container restart to take effect: the var is read once at startup
(`AppConfig.resourceLeasesEnforced`), so a running process never sees a change. Accepted values are
`true`/`1`/`yes` (enforced) and `false`/`0`/`no` (disabled), case-insensitive and trimmed; anything else
logs a WARN and keeps enforcement on. Re-enable the same way, by unsetting the var (or setting it to
`true`/`1`/`yes`) and restarting.

---

## Run Budgets and Caps

Runs are bounded by their pinned budget (see [`runs:`](config-reference.md#runs-runs) in the config reference for the
ceilings an operator sets, and the [workflow guide](workflow-guide.md#12-runs-seats-and-decisions) for the run
lifecycle). What an operator sees:

- **Spend.** `runs_read` `get` shows `usage` (`reportedTokens`, `reportedCostUsd`, `observedTokens`). Reported figures
  are the SUM of the seats' absolute `heartbeat` reports (the highest per seat is kept); `observedTokens` is the server's
  own estimate, written off the request path by the call-log writer. It is an estimate: only rows actually stored
  count (a duplicate or refused row adds nothing), and a dropped or failed call-log batch (see "Call log") under-counts it.
- **Breach.** The next token-carrying write after a crossing pauses the run (`pauseReason: budget`) and raises a
  high-severity `run-budget` decision; the writer itself never pauses anything, so a run whose seats have all gone quiet
  is not paused by observed spend alone until some seat writes again. Only token-carrying writes run the check (a
  heartbeat, `assign`, `end_seat`, a transition or a `raise`, each under a seat or coordinator token); a call
  without a token never does.
- **Answering.** A director or human answers through `runs_write` `answer` (`/ops/director/runs_write` on REST):
  `extend` with an `amount` resumes the run with the raised limit; `stop` closes it `budget_exhausted` and ends its
  seats, clears their claims and releases the leases of its WORK members. A coordinator cannot lift its own cap: a
  manual `resume` of a budget or attempts pause is refused. The decisions the server raises for a limit
  (`run-budget`, `attempt-cap`) cannot be withdrawn.
- **Attempt cap.** An `assign` over `attempts_per_seat` is refused `run_paused`, pauses the run (`pauseReason:
  attempts`) and raises an `attempt-cap` decision. The refusal names that decision in `detail.pauseDecisionId`, but the id is
  chosen before the unit that writes the decision runs, so after a database fault, or when the write finds an
  existing decision for the same attempt (a dedupe hit), the id can be absent or differ from the one that stands.
  Read the run's decisions (`runs_read` `decisions`) rather than trusting the refusal alone.
- **While paused.** A `start` presented with any of the run's tokens, a seat token or the coordinator token, is
  refused `run_paused` while the run is paused for a limit (`budget` or `attempts`). A manual pause never refuses
  it, so in-flight seats can finish their work, and a `start` without a token is never refused for a pause.
- **Concurrency.** `max_active_seats` answers an extra `assign` with `409 run_at_capacity` (transient); the coordinator
  backs off and retries when a seat ends. No decision is raised.

## Observability Gaps

As of 4.0, the MCP Task Orchestrator ships with **no metrics endpoints**. There is no Prometheus/Micrometer integration and no `/metrics` HTTP path. Per-call telemetry lives in the `call_log` table and the durable `events` table (streamed over SSE, see [api-rest.md](api-rest.md) section 21), and the director-profile `report` tool turns both into aggregates (see [Reporting](reporting.md)).

Fleet operators should treat the missing metrics endpoint as a known gap when planning production rollouts.

### Available Signals Today

| Signal | How to access |
|---|---|
| Active claim count | `items_read` `context` (health view) -> `claimSummary.active` |
| Expired claim count | `items_read` `context` (health view) -> `claimSummary.expired` |
| Per-root claim breakdown | `items_read` `overview` -> `claimSummary` per root item |
| Stalled items (missing required notes) | `items_read` `context` (health view) -> `stalled` |
| Recent role transitions | `items_read` select `history` (`since`/`until`, optional `itemId`) |
| Tool usage, errors, latency and tokens | `report` select `usage` (`groupBy` tool, operation, host, surface, principalKind (`human`, `agent` or `service`; pre-fix rows read `external`, see Call log), errorCode, day, principal) |
| Gate rejections by note, type or trait | `report` select `gates` |
| Claim and lease contention, expiry and force-release rates | `report` select `contention` |
| Call-pattern waste | `report` select `waste` |
| One subtree or one run as a summary | `report` select `rollup` (`anchorId` or `runId`) |
| Per-operation attribution | Notes and events carry the principal that wrote them (`notes_read`, `items_read` `history`); every call is a `call_log` row with its principal, host and `reqId` |

### Known Gaps — Plan Accordingly

- No metrics endpoint and no scrape format; `report` is a pull query, not a time series
- No write latency histograms (`report` gives the mean and the maximum, not percentiles)
- No `SQLITE_BUSY` error rate counters (a call's retry count is in `call_log` and `report` `usage`)
- No alerting integration

Claim acquisition and rejection rates are `report` `contention`, and per-principal throughput is `report` `usage` with `groupBy: principal` (inside the raw call-log horizon only). If your fleet rollout requires real-time dashboards or alerting, plan to instrument them at the client side (agent telemetry) or proxy level until server-side metrics are added.

Metrics and observability infrastructure are explicitly deferred to a future release (see issue tracker).

---

## Operating a Live Fleet

Lifecycle and edge-case behaviors that operators encounter once a claim-mode fleet is running.

### Serialized Find-and-Claim (Selector Mode)

Fleet agents can eliminate the **user-facing** race window inherent in the two-call `items_read` `next` -> `advance` `claim` (by `itemId`) pattern by using selector mode instead. The selector resolves a filter+rank query and claims the top match in a single MCP call. The selector and the claim run in one serialized write unit, so concurrent selectors each get a different item.

```json
{
  "ops": [{
    "op": "claim",
    "select": { "orderBy": "oldest", "priority": "high" },
    "ttlSeconds": 900,
    "idempotencyKey": "<fresh key per iteration>"
  }]
}
```

`orderBy: "oldest"` (createdAt ascending) provides **FIFO (oldest-first) queue draining**: agents process items in FIFO order rather than competing for the same high-priority items simultaneously. When nothing matches the selector filters at all, the element is ok with `claimed: false` and no `reason` - the queue is genuinely drained, no point retrying the same filters soon. When items match but every one is claimed, ancestor-claimed, or dependency-blocked, the element is ok with `claimed: false`, a `reason` (`claim_held` or `dependency_unmet`), `retryAfterMs` 30000 and an aggregate `excluded` breakdown - signal to back off and retry rather than idle for good.

The `select` filters of the `claim` op are a subset of the `items_read` `next` filters, and both share the same eligibility rules.

### Graceful Drain

There is no built-in drain command. The intended sequence to stop a TO instance with active claims:

1. Stop dispatching new work to agents talking to this instance (orchestration-side, outside TO).
2. Let in-flight claims complete naturally. Agents call `advance` `release` after `advance` `transition` with `trigger: "complete"` (a transition into `terminal` also clears the claim), or skip the release and let TTL elapse.
3. Monitor `items_read` `context` (health view) -> `claimSummary.active` until it reaches zero, or until residual claims age past their TTL into `claimSummary.expired`.
4. Stop the server.

If you stop the server while claims are active, no data is lost — claim records persist in the database. On the next startup, expired claims are filtered at read time as usual; non-expired claims will reach their TTL and become reclaimable.

### Upgrade Behavior

Schema migrations (Flyway) run at server startup before any tool calls are accepted. Existing claim columns are preserved across migrations unless a migration explicitly modifies them — V6 reshaped the `claim_expires_at` index but claim row data was untouched. Future migrations that touch the four claim columns will be flagged in the release notes.

The first 4.0 start on a 3.x volume is a larger step than a routine migration: see "Upgrading from 3.x" for the backup, the checks and the rollback.

A migration that runs while no client is connected has no claim-state implications. A migration that runs immediately after a restart, with claim records already in place, applies normally — claim records survive because they are row data, not schema.

### Cross-Partition Scoping

Claims are per-instance. Agents do not cross-claim across partitioned TO instances (different SQLite databases). An agent talking to two instances holds two independent claims, one in each.

### Reopen and Claim TTL

A transition into `terminal` clears the claim (see "Does completing or cancelling release the claim?" below), so an item reopened with `advance` `transition` (`trigger: "reopen"`) always starts unclaimed: any agent can claim it, and there is no original holder to release first.

---

## Claims Troubleshooting

Common operator scenarios when running a multi-agent fleet against the claim mechanism.

### Repeated `claim_held` on the same item

An agent retrying a claim and getting `claim_held` back-to-back is **expected** behavior - another agent holds the item and retry will not change that until the existing TTL elapses or the holder explicitly releases.

| Symptom | Recommended response |
|---|---|
| `retryAfterMs` < ~10s | Pick a different unclaimed item via `items_read` `next`. Holder is actively working. |
| `retryAfterMs` close to full TTL | Holder either just claimed or just heartbeated. Pick a different item. |
| Same item, repeated retries, never resolves | Use `items_read` `context` with the `itemId` to read `claimDetail.originalClaimedAt`. If the value is hours old, suspect a crashed holder - see "Stale `originalClaimedAt`" below. |

`claim_held` never discloses the competing agent's identity by design (see Tiered Claim Disclosure above). Use `items_read` `context` with the `itemId` for full diagnostics.

### `unavailable` (HTTP 503) on every call under JWKS

This means the JWKS key set cannot be had: it was never fetched, or the refresh keeps failing and the cached set is older than `max_stale_seconds`. The answer carries `retryAfterMs` 15000 and no `WWW-Authenticate` header, and the same holds on REST, `/mcp` and the SSE `?token=` path.

**Recovery checklist:**

1. Check the key source: the `API_JWKS_URL` (or `jwks_uri` / `oidc_discovery`) must be reachable from the server, and not resolve to a non-public address (the egress guard refuses those).
2. Read the server log: one WARN names the reason (never the token) each time a call is refused.
3. Restore connectivity; the next call after the refresh backoff succeeds. To ride out longer outages raise `API_JWKS_MAX_STALE_SECONDS` (`GET /api/v1/info` shows the value as `maxStale`), accepting that a revoked key is honored for that long.
4. A `401 invalid_token` is a different problem: the JWT itself is invalid (`iss`, `aud`, expiry, or a signing key outside the `algorithms` allowlist).

### Stale `originalClaimedAt` — diagnosing crashed holders

`originalClaimedAt` records when the *current* agent first claimed the item. It is preserved across heartbeat re-claims and reset only when a different agent claims the same item.

| `originalClaimedAt` age | Interpretation |
|---|---|
| < TTL (default 900s) | Fresh claim; agent is most likely working. |
| 1–10× TTL | Heartbeat-renewed long-running work. Inspect `claimExpiresAt` — if in the future, the agent is alive. |
| Hours/days old, `claimExpiresAt` in the past | Agent crashed mid-work. Claim is passively expired; any agent can now claim it. |
| Hours/days old, `claimExpiresAt` still being refreshed | Agent is alive but stuck in a long-running operation, or the heartbeat cadence is too aggressive. Investigate the holder's logs. |

Expired claims are filtered at read time - `items_read` `next` will surface items whose holders crashed once their TTL has elapsed. Nothing is cleared: the claim columns stay as they were (so `claimStatus=expired` and the health-check expired counts keep their meaning), and an hourly sweep, an advance that evaluates the item and a contender's claim each record one `claim.expired` event for the lapsed claim (see [Fleet-wide expired-claim sweep](#fleet-wide-expired-claim-sweep)).

### Heartbeat scheduling — implementation patterns

The recommended cadence is **TTL/2** (450s for the 900s default). This matches the convention used by Consul, etcd, and other lease-based distributed systems.

**Where to put the heartbeat timer:**

- **Inside the agent's main work loop.** Check elapsed time at each natural checkpoint (note write, file change, tool call) and re-claim if past TTL/2.
- **As a coroutine/task scheduled at TTL/2.** Simpler to reason about, but the agent must guarantee the timer fires while it's actually progressing — a paused or blocked agent that lets the timer fire anyway is silently extending a stale claim.
- **Avoid** background-only timers that fire regardless of work progress. Tying the heartbeat to forward progress is what gives crash recovery its meaning.

**Cross-restart behavior:** A re-launched agent process does not preserve its prior claim. After restart, call `advance` `claim` again - if the prior TTL has not elapsed, the re-claim succeeds (refreshing TTL, preserving `originalClaimedAt`); if it has, the item may have been picked up by another agent and you'll receive `claim_held`.

### Does completing or cancelling release the claim?

**Yes.** `advance` `transition` with `trigger: "complete"` or `"cancel"` - and any other transition, path or cascade that lands the item in TERMINAL - clears `claimedBy`, `claimedAt`, `claimExpiresAt` and `originalClaimedAt` as part of the same transition (`items_write` `complete_tree` and `POST /api/v1/ops/advance` behave the same). A terminal item therefore carries no claim, and `reopen` always starts it unclaimed.

The clear is recorded as a `claim.released` event with reason `cleared`; if the claim had already lapsed it is recorded as `claim.expired` instead (a lapsed claim is never reported as released).

Calling `advance` `release` before completing is not needed for claim hygiene; it only makes an early release explicit in the audit trail.

### Fleet-wide expired-claim sweep

Operators who want to inventory expired claims (e.g., during incident review) can run:

```json
{ "select": "list", "claimStatus": "expired" }
```

(`items_read`.) Entries carry no claim data - identity remains hidden. To get holder identity for a specific stuck item, drill in with `items_read` `context` and its `itemId`, which is the only surface that exposes `claimDetail.claimedBy`.

No cleanup action is required for correctness. The data is informational — it tells you which agents likely crashed and which work items are now available for re-claim.

**Expiry events.** The server reports expiry in the `events` table (audit rows; they are not streamed over SSE). A claim whose TTL has elapsed (`claimExpiresAt <= now`) gets exactly one `claim.expired` row (`holder`, `expiresAt`), written by whichever of these notices it first: an agent that claims, releases or re-claims over it, a transition (`advance` `transition`, `items_write` `complete_tree`) of that item that commits, or the hourly sweep that also runs once at server start. The claim columns are **not** cleared by this, so the inventory above is unchanged. Lapsed resource leases are different: the sweep, or a contender's acquire, removes the lapsed row (its history interval closes as `expired`) and records one `lease.expired` row (`key`, `expiresAt`). When a lapsed claim or lease is overwritten, cleared or removed, the row is `*.expired` rather than `claim.released` / `lease.released`. Read-only tools never write, so they never record expiry; the sweep covers items nobody touches. The detector is whoever runs the sweep: rows written by the scheduled sweeper carry no principal, while a `sweepExpired` call made inside a request records that request's principal as the detector (the event is still `*.expired`, never `released`). A sweep failure is logged at WARN and never stops the server.

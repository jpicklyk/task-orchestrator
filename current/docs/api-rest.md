# REST API Reference — MCP Task Orchestrator

This document describes the 4.0 HTTP REST API (v1). It runs on the same Ktor server as the MCP transport when `API_ENABLED=true`. The canonical route is the RPC route `POST /api/v1/ops/{tool}` (section 20a), which serves every tool of [api-reference.md](api-reference.md); a few resource GETs, the lease admin routes, the config JSON Schema, service meta and the SSE stream complete the surface. Every 3.x route outside that set is removed (section 10).

**Base URL:** `/api/v1`
**Content-Type:** `application/json`.
**OpenAPI spec:** [`api/openapi.yaml`](api/openapi.yaml) — covers all routes for machine consumption. Note: the YAML is hand-maintained and may lag behind edge-case behavior; this document and the source are authoritative.

**HTTP-first policy.** New plugin-side infrastructure features — `config-sync.mjs`, SSE event
streaming, and the `plan-capture.mjs` hook (which stashes an approved plan as a plan document;
its `PUT /roots/{rootId}/plans/{slug}` route was removed in 4.0 in favour of `POST /ops/docs_write`, and the hook
follows in the plugin work) — are built HTTP-only, each fail-opening to a silent no-op when
its REST env var (`TASK_ORCHESTRATOR_API_URL`, etc.) is absent. STDIO deployments keep full MCP tool
functionality but do not gain these convenience features; STDIO is positioned as the local/evaluation
transport, while a persistent HTTP daemon with the REST API enabled is the recommended path for
ongoing fleet or multi-project work.

The plugin's SubagentStop phase guard (`phase-guard.mjs` / `phase-guard-record.mjs`) is another such
consumer: it decides whether to send a returning subagent back for missing notes from the item's gate state
(`items_read` `select: context`, section 10 maps the removed `/gate` route it used to poll), and fails open the same way when `TASK_ORCHESTRATOR_API_URL` is unset - see
[integration-guides/plugin-skills-hooks.md](integration-guides/plugin-skills-hooks.md).

---

## Table of Contents

1. [Authentication](#1-authentication)
2. [Capabilities (Authorization)](#2-capabilities-authorization)
3. [Scope Filtering](#3-scope-filtering)
4. [ETag Concurrency](#4-etag-concurrency)
5. [Idempotency](#5-idempotency)
6. [Error Codes](#6-error-codes)
7. [Pagination](#7-pagination)
8. [Data Transfer Objects (DTOs)](#8-data-transfer-objects-dtos)
9. [Endpoints — Items (Read)](#9-endpoints--items-read)
10. [Removed 3.x Routes](#10-removed-3x-routes)
11. [Endpoints - Notes (Read)](#11-endpoints---notes-read)
14. [Endpoints — Resource Leases](#14-endpoints--resource-leases)
17. [Endpoints — Config JSON Schema](#17-endpoints--config-json-schema)
20. [Endpoints — Service Meta](#20-endpoints--service-meta)
20a. [Endpoints - Ops (RPC)](#20a-endpoints---ops-rpc)
21. [Server-Sent Events (SSE)](#21-server-sent-events-sse)
22. [Audit Model](#22-audit-model)
23. [Status-Graph Caveat](#23-status-graph-caveat)
24. [Known Limitations](#24-known-limitations)

Section numbers are stable anchors cited from other documents and source comments. Sections 12, 13, 15, 16, 18, 19 and
19a were folded into section 10 and their numbers are not reused.

---

## 1. Authentication

> **A `Host` header allowlist runs before any of this.** Every HTTP request — `/mcp` and every `/api/v1` route alike — is checked against a `Host` allowlist (DNS-rebinding protection) before authentication is even evaluated. A request with a disallowed `Host` gets `403 forbidden` (the 4.0 envelope, `detail: {scope: "host", required: "an allowed Host header"}`) on every path, `/mcp` included, regardless of `Authorization` or auth mode. A request with no `Host` header at all is allowed, since browsers always send one. The always-allowed defaults are `localhost`/`127.0.0.1`/`[::1]` (any port); `MCP_ALLOWED_HOSTS` extends that set. See [fleet-deployment.md](fleet-deployment.md) and §6.

All `/api/v1/*` endpoints require authentication by default. The API supports three modes, selected by `API_AUTH_MODE`:

### Bearer Mode (`API_AUTH_MODE=bearer`)

Present a static token in the `Authorization` header:

```
Authorization: Bearer <token>
```

The `Bearer` scheme name is matched case-insensitively (`bearer`, `BEARER`, `bEaReR` all work — RFC 7235 §2.1) and requires at least one space before the token (RFC 6750 `1*SP`; a tab, or no separator, does not match: `Bearer<token>` and `Bearer\t<token>` are both rejected as missing). Only one `Bearer`/`bearer` prefix is ever stripped from the header value — a doubled prefix such as `Bearer bearer <token>` is passed through as `bearer <token>` and fails lookup as an invalid token, rather than being unwrapped down to `<token>`.

Tokens are defined in a YAML secret file (path: `API_TOKENS_PATH`, default `/run/secrets/api-tokens.yaml`). Each token is stored as a SHA-256 hex digest for security — the plaintext never touches disk.

**Token file format (version 1):**

```yaml
version: 1
tokens:
  - id: dashboard-reader
    description: "Read-only dashboard token"
    token_sha256: "e3b0c44298fc1c149afbf4c8996fb924..."  # 64 lowercase hex chars
    capabilities:
      - read
    scope:
      root_ids:
        - "550e8400-e29b-41d4-a716-446655440000"
    expires_at: "2027-01-01T00:00:00Z"   # optional ISO-8601
  - id: admin-token
    token_sha256: "..."
    capabilities:
      - admin
```

- `id` — stable identifier used in audit records (prefixed with `api:`)
- `token_sha256` — lowercase hex SHA-256 of the plaintext token
- `capabilities` — list of granted operations (see §2); absent or `[]` grants nothing
- `kind` — optional: `human`, `agent` or `service` (trimmed, case-insensitive; default `service`). What sort of
  caller holds the token; carried on the principal (§22), never taken from a request body `actor`. Any other value,
  or a non-string, fails startup naming the token id.
- `profile` — optional; the only value is `director` (trimmed, case-insensitive), which confines the token to
  `POST /api/v1/ops/director/{tool}` (§20a): on any other path, `GET /api/v1/events` included, it answers
  `403 forbidden` with `detail: {scope: "capability", required: "director"}`. Any other value fails startup.
- `scope` — optional; when present must be a mapping with only `root_ids` and/or `tags_include`
  keys (any other key, including a typo, fails startup)
  - `scope.root_ids` — a list of root-item UUID strings, or absent/`null` for unrestricted access. Every
    listed id must be a depth-0 project root: a token whose anchor names an existing item that is not a root
    fails startup, naming the token id and the anchor (`token '<id>' scope.root_ids anchor <uuid> is not a depth-0 root`).
    An anchor that does not exist yet is accepted and admits nothing until the root is created. A JWT whose
    `to_scope.root_ids`/scope claim names an existing non-root is unauthenticated (`401`), with one WARN per `sub`.
    An empty list `[]` is **rejected at startup** — root scope is the isolation boundary, so `[]`
    cannot mean "every root"; omit the key (or set it `null`) instead.
  - `scope.tags_include` — a list of non-blank tag strings, or absent/`null`/`[]` for no tag
    constraint (`[]` is the canonical unrestricted form here, unlike `root_ids`)
  - Any malformed shape (a scalar instead of a list, a non-string or blank element, an unquoted
    YAML boolean like `yes`/`on`) fails startup with `IllegalArgumentException` naming the token id
    and the key path — never silently falls back to unrestricted
  - A token entry itself only accepts the keys `id`, `description`, `token_sha256`, `expires_at`,
    `scope`, `capabilities`, `kind`, `profile` — an unrecognized entry key (e.g. a `scopes:` typo) also fails startup
- `expires_at` — optional token expiry; expired tokens are rejected at lookup time
- Token rotation requires a server restart (tokens are loaded once at startup).

**Generating `token_sha256`** — the digest must cover the token's exact bytes with **no trailing newline**:

```bash
# macOS / Linux / Git Bash
printf '%s' "$TOKEN" | openssl dgst -sha256 | awk '{print $NF}'
```
```powershell
# Windows PowerShell
([System.BitConverter]::ToString([System.Security.Cryptography.SHA256]::Create().ComputeHash(
  [System.Text.Encoding]::UTF8.GetBytes($token))) -replace '-','').ToLower()
```

> Do **not** use `openssl dgst -sha256 <<< "$TOKEN"` or `echo "$TOKEN" | …` — `<<<` and `echo` append a newline, so the digest won't match the token sent in requests (every call returns `401`). See the [quick-start REST API walkthrough](quick-start.md) for the full token-generation flow.

### JWKS Mode (`API_AUTH_MODE=jwks`)

Present a JWT in the `Authorization: Bearer` header. The server validates the JWT against the JWKS endpoint configured by `API_JWKS_URL`. Claims extracted: `iss`, `aud`, `sub`, `exp`, `nbf`, `iat` (when present). `exp` is **required** — a JWT with no `exp` claim is rejected with `401 invalid_token`. A token is also rejected once its remaining lifetime exceeds `API_JWKS_MAX_TOKEN_LIFETIME_SECONDS` (default `86400` = 24h, 60s clock-skew allowance): `exp - now` beyond the cap always rejects, and when `iat` is present a future-dated `iat` or an `exp - iat` spread beyond the cap also rejects. See [fleet-deployment.md § JWT Contract](fleet-deployment.md#jwt-contract) for the full rule.

The principal's id is the JWT's `sub` claim. Kind, scope and capabilities are derived from three
custom claims the issuer sets:

- `to_kind` — optional: `human`, `agent` or `service` (trimmed, case-insensitive). Absent (or `null`), the kind is
  `service`, except on the actor DID path (`sub` equals `iss` and starts with `did:`), where it is `agent`. A
  non-string or unknown value fails closed like `to_scope`: the whole JWT is rejected with `401 invalid_token` and a
  WARN names the claim.

- `to_scope` — optional; when present must be a JSON object with only `root_ids` and/or
  `tags_include` keys, following the same fail-closed rules as the bearer `scope` block above
  (absent/`null` means unrestricted; `root_ids: []` is malformed and rejected; `tags_include`
  absent/`null`/`[]` means no tag constraint; any other shape, or an unrecognized key inside
  `to_scope`, is malformed). A malformed `to_scope` makes verification fail closed — the whole
  JWT is rejected with `401 invalid_token`, not just the scope claim; a WARN is logged naming the
  claim path (never the JWT itself).
- `to_capabilities` — optional list of capability strings (see §2); absent or empty defaults to
  `[read]`. Unlike scope, an unknown capability value is dropped (not fail-closed) and logged at
  WARN naming `to_capabilities`; if the claim is present but is not a string list, it is treated as
  absent (WARN logged) and the default `[read]` applies.

Unrecognized top-level JWT claims (added by the IdP) are ignored — only unrecognized keys nested
inside `to_scope` are treated as malformed.

**The principal.** Every authenticated REST call carries one principal: `(id, kind, proofStatus, scope,
capabilities, host)`. `proofStatus` is `issued` for a token-file token, `verified` for a JWT (or `verified_stale`
when the key set that verified it was served from the stale cache after a failed refresh), and `none` in
unauthenticated mode. `host` is the `X-Client-Host` header, else the `User-Agent` product (§6a). Every credential,
the header and the SSE `?token=` alike, is resolved by one pipeline: the token-file lookup first (an expired entry
resolves to nothing), then JWT verification in JWKS mode.

**Failing requests receive** (bodies are the 4.0 error envelope, §6):
- `401 unauthenticated` + `WWW-Authenticate: Bearer error="invalid_request"` — missing `Authorization` header, a header that does not use the `Bearer` scheme (wrong scheme name, or no space between the scheme and the token — the scheme name itself is case-insensitive), or a present-but-empty Bearer credential
- `401 unauthenticated` + `WWW-Authenticate: Bearer error="invalid_token"` — bad/expired token
- `403 forbidden` with `detail: {scope: "capability", required: "<capability>"}` -- token valid but lacks the required capability

**`degradedModePolicy` interaction (JWKS mode):** a JWT reaching a route handler has already been validated by the auth plugin, so its verification status is always `VERIFIED` by the time `DegradedModePolicy` is applied to the synthesized audit actor — every policy, including `reject`, trusts a `VERIFIED` result. Write endpoints therefore never actually return `verification_failed` in practice; `DEGRADED_MODE_POLICY` only changes behavior for MCP tool calls carrying a self-reported `actor.id` under a degraded (non-`VERIFIED`) JWKS verification result. Bearer mode and unauthenticated mode are unaffected regardless (neither has a JWKS chain to degrade).

### Unauthenticated Mode (`API_AUTH_MODE=none`, opt-in)

**Two signals are required together** — this is a deliberate two-key opt-in, not a single flag:

```
API_AUTH_MODE=none
API_ALLOW_UNAUTHENTICATED=true
```

Setting `API_AUTH_MODE=none` alone still fails startup with an error naming the confirm flag. Setting `API_ALLOW_UNAUTHENTICATED=true` alone (with `bearer`/`jwks`) is silently ignored — it only takes effect combined with `none`.

When both are set, every request — with or without an `Authorization` header — is attached a synthetic principal with `ADMIN` capability (implies all others) and unrestricted scope (`root_ids: null`), kind `agent` and proof status `none`. No token is required or checked. Audit records attribute writes to the actor `api:local-unauth`.

> **SECURITY — loopback only.** This mode has NO authentication whatsoever: anyone who can reach the port has full read/write/delete access to every item, note, and per-root config, identical to the existing `/mcp` exposure. Use it only on a server bound to `127.0.0.1` (or otherwise network-fenced) for a single-user local setup — e.g. the config-sync hook talking to a local HTTP-transport server. Never set both keys on a server reachable from an untrusted network. The server logs a loud `SECURITY:` warning at startup when this mode is active.

Unauthenticated mode also covers `GET /api/v1/events` (SSE, §21) — the SSE route's dedicated
pre-flight auth plugin short-circuits with the synthetic unauthenticated principal exactly like
`ApiBearerAuth` does for every other route, so a caller does not need a bearer token to open an
event stream when both opt-in keys are set.

---

## 2. Capabilities (Authorization)

Each token grants a set of additive capabilities:

| Capability | Config string | Grants access to |
|------------|--------------|-----------------|
| `READ` | `read` | All GET endpoints under `/api/v1/` |
| `WRITE_ITEMS` | `write-items` | `items_write` ops through `POST /ops/items_write` (create, update, delete, and `create_tree` in the orchestration profile) |
| `WRITE_NOTES` | `write-notes` | `POST /ops/notes_write` ops `upsert` and `delete` (checked per op by the dispatcher, section 20a) |
| `ADVANCE` | `advance` | The `advance` tool's ops (`transition`, `claim`, `release`, `heartbeat`) through `POST /ops/advance`, and `items_write` `complete_tree` (orchestration profile) through `POST /ops/items_write` (section 20a) |
| `MANAGE_DEPENDENCIES` | `manage-dependencies` | `POST /ops/deps_write` ops `create`, `delete`, `linear`, `fan_out` and `fan_in` (checked per op by the dispatcher, section 20a) |
| `WRITE_CONFIG` | `write-config` | `POST /ops/config` ops `push`, `rollback` and `reset` (checked per op by the dispatcher, section 20a). Grants no `docs_write` op. |
| `WRITE_DOCS` | `write-docs` | `POST /ops/docs_write` ops `upsert`, `archive`, `unarchive`, `delete`, `attach` and `detach` (checked per op by the dispatcher, section 20a) |
| `RUNS` | `runs` | Reserved for the run tools; parsed and grantable, no endpoint checks it yet |
| `DECIDE` | `decide` | Reserved for decision answering; parsed and grantable, no endpoint checks it yet |
| `ADMIN` | `admin` | Implies all of the above; unlocks attribution fields in responses and the `principal` grouping of `POST /ops/report` `usage` |

**Breaking change (4.0):** `docs_write` needs `write-docs`. A token that wrote documents with `write-config` alone
now gets `403 forbidden` (`required: write-docs`) on every `docs_write` op; add `write-docs` to it.

**`ADMIN` unlocks:** When a caller has `ADMIN`, note `actor`/`verification` fields are included in responses (subject to `API_REDACT_*` env flags). Non-admin callers always receive `null` for these fields. The transition trail (`items_read` `history`, section 10) follows the same rule for its `actor`/`verification`.

---

## 3. Scope Filtering

Tokens with `scope.root_ids` set can only access items within those project trees. Roots only: an item is accessible when its stamped `root_id` is one of the listed roots (each a depth-0 item, validated at startup for bearer tokens and per credential for JWTs). There is no ancestor walk, so a listed id that is not a root admits nothing, and an item with no stamped `root_id` is never admitted.

Tokens with `scope.tags_include` set are additionally restricted to items carrying at least one of
the listed tags. Unlike `root_ids`, tag scope applies **item-level only** — there is no ancestor
walk; the item's own `tags` column is checked against the (CSV-trimmed) allowlist for exact
membership. An empty `tags_include` means no tag constraint. Both scope halves are enforced by the
one scope predicate (`ScopeGuard`) every tool, read route and the event stream use, so single-item and
collection checks cannot drift apart.

- `GET /items/{id}` and `GET /items/{id}/children` (section 9) - the `items_read` rules: a target item
  outside the scope is `403 forbidden` (envelope, `detail.scope` `root` or `tag`), and under a restricted
  scope an id that matches nothing the caller may see is `403 forbidden` too, never `404`, so scope is no
  existence oracle; children outside the scope are left out of the page and not counted
- Writes (`POST /ops/{tool}`) - `403 forbidden` (`detail.scope` `root` or `tag`) if the target item is outside scope
- Every tool resolves a caller-supplied id by the same scope-aware rule (Q32): under a restricted scope a prefix that
  matches nothing, or only items outside the scope, is the same `403 forbidden`, and an ambiguous prefix lists only the
  candidates the scope admits (one admitted candidate resolves)
- `POST /ops/items_read` (section 20a) - every select applies the same rules; `list`, `search` and
  `overview` apply the scope before paging, ranking and capping, so `page.total` counts only visible items
  and a restricted token gets a full page of in-scope hits (a note hit is checked on its owning item);
  `related` checks its subject item like `get` and drops every out-of-scope relative (from any signal:
  dependencies, links, tags, text) before fusion and the cap, so a dropped relative is never named and
  never counted (a document is visible exactly when its root item is);
  `ancestors` stop at the caller's scope root and drop ancestors that fail `tags_include`
- `items_read` `history` (the transition audit trail) - a root scope sees only its roots' rows and a `tags_include` token only rows for items it is allowed to see
- `POST /ops/deps_read` and `POST /ops/deps_write` (section 10) - the subject item of a read is
  checked (`403 forbidden`); an edge whose counterparty is out of scope is dropped from a read page
  and not counted; a write needs every named item (both stored ends for a delete) in scope
- `POST /ops/report` - `gates` and `contention` read events: a `tags_include` token sees only the events whose item
  currently carries an allowed tag (no `403`), a `root_ids` token only the events of its roots. `usage` and `waste` read
  `call_log`, whose rows carry no tags: a `tags_include` token is
  `403 forbidden` (`detail.scope` `tag`) before anything is read; a `root_ids` token aggregates only the calls
  recorded against one of its roots (calls with no root are never visible to it), filtered before grouping so
  `page.total` counts only visible groups; `groupBy: principal` needs the `admin` capability (`403 forbidden`,
  `detail.scope` `capability`)
- `GET /api/v1/events` (SSE) - a `tags_include` token is enforced per-event, identically for the
  live stream and Last-Event-ID replay (see section 21)

**Item writes are scope-checked too.** Item creates, updates and deletes are the `items_write` ops of
`POST /ops/items_write` (§20a); each element is checked against the token's scope before anything is written
(`forbidden`, `detail.scope` `root` or `tag`). A new depth-0 root founds a new project, and no restricted scope
owns a root that does not exist yet: creating a root (`items_write` `create` without `parentId`, or a new top-level
root in `create_work_tree`) and moving an item to the top level (`clear: ["parentId"]` on a non-root item) are
`forbidden` with `detail.scope` `root` for any token with `scope.root_ids` or `scope.tags_include`, unless it
holds `admin` (`detail.required` is the listed roots, empty for a tag-only token). Clearing the parent of an item
that already is a root stays a no-op. A reparent under another parent is checked against the new parent.

**A collection never turns a tag-scope mismatch into `403`.** A tag-scoped caller whose scope matches
nothing on a collection (an `items_read` `list`, `search`, `overview` or `related` page, `GET /items/{id}/children`,
`ancestors`, an `items_read` `history` page) gets `200 OK` with an empty or partially filtered result; `403` is
reserved for a single item named directly. The exceptions are `report` `usage` and `waste`, aggregates over call rows
that carry no tags at all: a tag-scoped caller is refused outright rather than shown a silently empty report.

**Pagination under a scope.** `items_read` applies root and tag scope in the store before paging, so every
caller gets full pages and an exact `page.total` of visible items; the 3.x bounded candidate window for
tag-scoped callers is gone.

---

## 4. ETag Concurrency

### Item and Note ETags

- Item reads: `GET /items/{id}` answers `ETag: "v{version}"` from the item version on a successful read.
  `GET /items/{id}/children` carries no `ETag`.
- Item writes: no REST item write route remains. `items_write` (`POST /api/v1/ops/items_write`) guards an
  update or delete with `expectedVersion` (`version_conflict`); no route takes an item `If-Match`, and the 3.x
  `"v1-<modifiedAtMillis>"` tag is no longer served.
- Notes: `GET /items/{id}/notes/{key}` answers `ETag: "v{version}"` from the note version (V28) and honors `If-None-Match` (`304`); there is no note `If-Match`: guard a write with `notes_write` `expectedVersion` (section 10)

**Conditional reads:** an `If-None-Match` naming the current tag (one tag or a comma-separated list; a
`W/` weak tag and `*` also match) answers `304 Not Modified` with no body. An error never carries an
`ETag` and is never a `304`, so the scope check comes first.

**Conditional writes:**
- None on item or note resources: `items_write` and `notes_write` guard a write with `expectedVersion` (`version_conflict`) instead of `If-Match`.

### Config ETags

Only `GET /config/schema` (§17) carries a config ETag: a strong validator over the published JSON Schema that changes
exactly when the schema does. The `"cfg-<fingerprint>"`, `"cfg-v<version>"` and `"eff-<fingerprint>"` ETags and the
`If-Match` / `If-None-Match` handling of the per-root config routes are retired together with those routes: config
reads and writes are ops of the `config` tool (`POST /ops/config`, section 20a), where a write guards with
`expectedVersion` (`version_conflict`) and a read always answers `200`.

**Normalization of config text:** before hashing, the config text has one leading UTF-8 BOM (U+FEFF) stripped if
present, then every CRLF (`\r\n`) is replaced with LF (`\n`) - nothing else. The stored/served `configYaml` bytes
are never rewritten; only the value fed into the SHA-256 fingerprint is normalized. A CRLF/BOM checkout (e.g.
`core.autocrlf=true` on Windows) of byte-identical content therefore produces the same fingerprint as an LF, BOM-less
checkout. See `infrastructure/security/Sha256Hex.kt`'s `configFingerprint` for the canonical implementation.

---

## 5. Idempotency

No REST route takes the 3.x `Idempotency-Key` header any more: `POST /items/{id}/advance` (moved to the `advance` tool) and
`POST /items`, `PATCH /items/{id}` and `DELETE /items/{id}` (moved to `items_write`) were its last users. The 4.0 write
tools served at `POST /api/v1/ops/{tool}` (`items_write`, `notes_write`, `deps_write`, `docs_write`, `advance`) take a per-element
`idempotencyKey` instead, recorded per `(principal, tool, key)` for 24 hours (section 20a); an `Idempotency-Key` header on
`/ops` is `400 invalid_request`.

- Every write route bounds its body read to a fixed byte limit BEFORE buffering it: a `Content-Length` over the limit is rejected with `413 payload_too_large` before any bytes are touched, and a chunked/understated-`Content-Length` body is caught by a channel read capped at `limit + 1` bytes, so an oversized body is never buffered in full either way. `POST /api/v1/ops/{tool}` has a 1 MiB limit (its `payload_too_large` is an envelope error, section 20a); a `docs_write` document body is capped by the document service instead (section 6).

---

## 6. Error Codes

Every JSON body a `/api/v1` route or plugin writes is the 4.0 response envelope (Q32), the same bytes MCP returns:

```json
{
  "ok": false,
  "error": { "code": "<catalog code>", "kind": "permanent|transient|shedding", "message": "<sentence>", "detail": { ... }, "fix": "<what to do next>" },
  "meta": { "reqId": "<8-char id>" }
}
```

The HTTP status is the catalog status of `error.code` (no route picks a status by hand), and the `X-Req-Id` header
repeats `meta.reqId` (§6a). The codes and their `detail` shapes are the error catalog of
[api-reference.md](api-reference.md) ("Error catalog"). The REST-specific answers:

| Situation | Status | `error.code` and `detail` |
|-----------|--------|---------------------------|
| `Host` header not allowed (any path, `/mcp` and `/.well-known` included; checked before authentication) | 403 | `forbidden`, `{scope: "host", required: "an allowed Host header"}`. Never echoes the rejected value or the allowlist. |
| Missing, non-Bearer or empty `Authorization` (also the SSE pre-flight with no token) | 401 | `unauthenticated`; `WWW-Authenticate: Bearer error="invalid_request"` |
| Unknown, expired or invalid token (bearer or JWKS) | 401 | `unauthenticated`; `WWW-Authenticate: Bearer error="invalid_token"` |
| The route's capability is missing (`GET /config/schema`, the lease routes, the SSE `read` check) | 403 | `forbidden`, `{scope: "capability", required: "<capability>"}` |
| An item, root or tag outside the token's scope | 403 | `forbidden`, `{scope: "root" or "tag", required: "<listed values>"}` |
| Request body over the route's limit | 413 | `payload_too_large`, `{max, actual}` |
| Unsupported `Content-Type` | 400 | `invalid_request` on field `Content-Type` (3.x answered `415`) |
| Malformed body or parameter, or a domain validation failure | 400 | `invalid_request` |
| A server config head that cannot be read (`GET /info`) | 503 | `unavailable`, `{retryAfterMs}` |
| A stored config head that cannot be loaded | 422 | `config_invalid` |
| An exception escaping a route | 500 | `internal`, the fixed message `Internal server error` (the exception text is logged at ERROR, never returned) |

**Bodies that are not envelopes** (an explicit, reasoned list, guarded by `SurfaceConformanceTest`): `GET /api/v1/health`
(the probe DTO), `GET /.well-known/mcp-task-orchestrator.json` (the discovery document), the `GET /api/v1/events`
stream body (its pre-flight answers are envelopes), the `200` body of `GET /api/v1/config/schema` (the raw JSON Schema,
a `$schema` target), any `304`, and Ktor's own `404`/`405` for a request no route matched.

**Safety net.** When the REST API is enabled, an exception that escapes a `/api/v1` route is answered by a Ktor
`StatusPages` handler scoped to `/api/v1` paths (other paths, `/mcp` included, keep Ktor's default) with the row of
the table above that matches it. A cancelled request is never answered. An API-disabled deployment installs nothing.

---

## 6a. Request correlation (`X-Req-Id`)

Every `/api/v1` response except the `GET /api/v1/events` stream carries an `X-Req-Id` header: the
call's 8-character correlation id (lowercase Crockford base32). It is always server-generated (an
inbound `X-Request-Id` only feeds the `requestId` log field), appears on success and error responses
alike (including `401`), is the `req_id` of every `events` row the request wrote, and keys the
request's row in the `call_log` table. Only `/api/v1` and paths under `/api/v1/` are covered (not
`/api/v10`). Every error row records its envelope's `error.code` (`unauthenticated`, `forbidden`,
`payload_too_large`, ...) as the row's `error_code`; `http_<status>` is the fallback for a body that is not an envelope. The row's `tool` is
the matched route template as declared (`POST /api/v1/ops/{tool}`); a call that matched no route is
`<METHOD> /api/v1/<first segment>` for a served top-level resource, else `<METHOD> unmatched`. `principal_id`
on REST rows is the authenticated API principal (`api:<id>`); only MCP rows can carry a self-reported actor id (up to 500 characters, with `proof_status` distinguishing verified ids). `X-Req-Id` is in the default
`CORS_EXPOSE_HEADERS`. See
`fleet-deployment.md` -> "Call log and `reqId`".

### Client host (`X-Client-Host`)

A request may carry the optional `X-Client-Host` header to name the client application making the call. It sets the
`host` recorded on the request's `call_log` row and on every `events` row the request writes (a `401` row included).
The host is data only: nothing branches on it and responses do not change. It is resolved in this order:

1. the first `X-Client-Host` value, when it is non-empty after normalization;
2. otherwise the first product token of the `User-Agent` header (the text before the first `/`, per RFC 9110
   section 10.1.5; a blank `User-Agent`, or one starting with `(`, has no product, and later products are never
   consulted);
3. otherwise the literal `rest`.

Whichever value is chosen is normalized the same way: control characters are dropped, the text is trimmed and
lowercased, and it is capped at 64 code points (a surrogate pair is never split, and trailing whitespace at the cut is
trimmed). A value that is empty after normalization counts as absent, so a blank `X-Client-Host` falls through to the
`User-Agent`. `X-Client-Host` is in the default `CORS_ALLOWED_HEADERS`, so browser clients can send it cross-origin. The value is
self-reported and unauthenticated: it labels telemetry only and is never used for authorization, scoping or identity.
MCP calls take their host from the client's `initialize` `clientInfo.name` instead.

## 7. Pagination

Every list a route or tool answers is the envelope's list data: `{"items": [...], "page": {"total", "truncated",
"capped"?, "next"?}}` (api-reference "List data"), paged with `limit` and the opaque `cursor` from `page.next`. The 3.x
`PageDto` (`page`/`pageSize`/`totalItems`/`hasMore`) and its `?page=`/`?pageSize=` parameters are gone.

---

## 8. Data Transfer Objects (DTOs)

The item and note views are the `items_read` / `notes_read` views (api-reference); `GET /items/{id}` and the note routes
answer them in the envelope. `role` is absent for a lifecycle-free item (its type's schema declares `lifecycle: none`),
so a client must not assume it is always present.

### ActorClaimDto

```json
{
  "id": "api:dashboard-editor",
  "kind": "orchestrator|subagent|user|external",
  "parent": "string|null"
}
```

For REST API writes, `id` is always `"api:<id>"` and `kind` is always `"external"` (server-synthesized; client-supplied actor fields are silently dropped).

### Config DTOs

These shapes are the members of the `effective` op's result (the `config` tool, `POST /ops/config`, section 20a;
[api-reference.md](api-reference.md#config)). They are no longer returned by any REST route.

**NoteSchemaEntryDto:**
```json
{
  "key": "implementation-notes",
  "role": "work",
  "required": true,
  "description": "string",
  "guidance": "string|null",
  "skill": "string|null",
  "maxLength": 3000
}
```

`maxLength` (integer, optional) is present only when a maximum note-body length is configured for
this entry; omitted otherwise. It appears wherever a `NoteSchemaEntryDto` is returned: in the `effective` result's
`schemas[].notes` and `traits[].notes`.

**DispatchProfileDto:**
```json
{
  "agent": "task-orchestrator:implementer",
  "effort": "medium"
}
```

`{agent?, model?, effort?}` — all three fields optional; each key is **omitted** (never emitted as
`null`) when the profile doesn't set it — at least one key is present on any profile the server
returns. Declared per trait per phase under `traits.<name>.dispatch.<phase>:`; see
[`config-format.md`](../../claude-plugins/task-orchestrator/skills/manage-schemas/references/config-format.md#dispatch-trait-dimension)
→ "Dispatch (Trait Dimension)".

**ResourceRequirementDto:**
```json
{
  "key": "staging-db",
  "mode": "exclusive",
  "ttlSeconds": 1800
}
```

`mode` is `"exclusive"` or `"advisory"`; `ttlSeconds` is present only when configured.

**TraitDto:**
```json
{
  "name": "needs-security-review",
  "notes": [<NoteSchemaEntryDto>],
  "dispatch": { "work": <DispatchProfileDto> },
  "resources": [<ResourceRequirementDto>]
}
```

`dispatch` (map of phase name to `DispatchProfileDto`, optional) and `resources` (array of
`ResourceRequirementDto`, optional) are omitted from the JSON (never `null`) rather than an empty map/array when
the trait declares neither. In the `effective` result a trait the per-root config declares is reported from the
per-root entry alone (see `EffectiveConfigDto` below).

**StatusGraphTypeDto:**
```json
{
  "type": "feature-task",
  "lifecycleMode": "auto",
  "hasReviewPhase": true,
  "transitions": {
    "queue": {"start": "work", "complete": "terminal", "cancel": "terminal"},
    "work": {"start": "review", "complete": "terminal", "block": "blocked", "hold": "blocked", "cancel": "terminal"},
    "review": {"start": "terminal", "complete": "terminal", "block": "blocked", "cancel": "terminal"},
    "blocked": {"resume": "<previousRole>", "complete": "terminal", "cancel": "terminal"},
    "terminal": {"reopen": "queue"}
  }
}
```

Note: `"<previousRole>"` is a literal sentinel string — dashboards must resolve it from the live item's `previousRole` field.

A type whose schema declares `lifecycle: none` is listed with `"lifecycleMode": "none"` and `"transitions": {}`: its
items are lifecycle-free and take no trigger.

**StatusGraphDto:**
```json
{
  "roles": ["queue", "work", "review", "blocked", "terminal"],
  "triggers": ["start", "complete", "block", "hold", "resume", "cancel", "reopen"],
  "types": [<StatusGraphTypeDto>]
}
```

**EffectiveSchemaDto** (an element of the `effective` result's `schemas`):
```json
{
  "type": "feature-task",
  "matchedType": "feature-task",
  "configSource": "per-root",
  "configFingerprint": "e3b0c44298fc1c14...",
  "lifecycleMode": "auto",
  "hasReviewPhase": true,
  "notes": [<NoteSchemaEntryDto>],
  "defaultTraits": ["needs-security-review"]
}
```
`type` is the queried type key; `matchedType` is the schema that actually answered it (`"default"`
when a default schema resolved the miss). `configSource` is `"per-root"` or `"global"` — the same
literal MCP `items_read(select="schema", ...)` uses. `configFingerprint` is the supplying
layer's fingerprint, omitted when that layer has none.

**EffectiveConfigDto** (the `effective` op's result, less the `ok` flag):
```json
{
  "rootId": "550e8400-e29b-41d4-a716-446655440000|null",
  "schemas": [<EffectiveSchemaDto>],
  "traits": [<TraitDto>],
  "types": ["bug", "feature-task"],
  "statusGraph": <StatusGraphDto>,
  "defaultSchema": <EffectiveSchemaDto>|null,
  "globalFingerprint": "e3b0c44298fc1c14...",
  "perRootFingerprint": "a94a8fe5cc..."
}
```
Every registered type (the union of this root's per-root `work_item_schemas` keys and the global
schema service's keys) resolved against `{rootId}`'s LAYERED config in one response — the same
per-root/global view `EffectiveConfigResolver` and MCP `items_read(select="schema", type=K, rootId=R)`
already compute, surfaced as one result instead of requiring a dashboard to probe per type. `rootId` is omitted
for the server-only view (`effective` without a `rootId`).
`types` lists every schema key (per-root and global, ascending natural `String` order) that
resolves to a schema for this root, and `schemas` follows that same order — `types` always equals
the `type` values of `schemas`. Each key resolves by the one precedence rule (per-root exact type,
then global exact type, then per-root `default`, then global `default`), so every listed key resolves.
`traits` lists every trait name visible to this root (per-root names first,
then global, distinct) with its resolved notes/dispatch/resources — a trait unknown to both layers
is skipped. `globalFingerprint`/`perRootFingerprint` are omitted when null (no global config loaded
/ no per-root config pushed for this root, respectively). `defaultSchema` is the resolved `"default"`
entry, omitted when no layer defines one. A trait the per-root config declares is reported from the
per-root entry alone (notes, dispatch and resources together; a dimension it does not declare is
empty). The `schemaResolution` field was removed in 4.0 (the 3.x `schema_resolution` modes are
gone). It performs no trait merging (same as the MCP
`items_read(select="schema")` type path): it reports each type's RESOLVED BASE schema only.

### Resource lease items (see section 14)

The lease admin routes answer the response envelope, so there are no lease DTO classes: a list item is the
plain JSON object described in section 14 (ISO-8601 instants).

### SSE / ApiEvent

```json
{
  "id": 42,
  "event": "item.updated",
  "itemId": "<uuid>|null",
  "modifiedAt": "ISO-8601|null",
  "newRole": "work|null",
  "actor": { "id": "api:dashboard-editor", "kind": "external", "parent": "..." },
  "rootId": "<uuid>"
}
```

`actor` and `rootId` are additive and are absent from the JSON when null (see §21 "Actor and rootId").

---

## 9. Endpoints — Items (Read)

All require `READ` capability.

The two item GETs below are thin translations to the 4.0 `items_read` tool through the shared dispatcher
and the same access builder as `POST /api/v1/ops/{tool}` (section 20a). They answer the 4.0 response
envelope, byte-equal to `POST /api/v1/ops/items_read` for the same principal and arguments apart from
`meta.reqId`, with the HTTP status taken from the envelope's error code. `{id}` is a full UUID or 8 to 32 hex characters of one. Capability and scope are enforced by
the dispatcher and the select exactly as on `/ops`: an out-of-scope item, or under a restricted scope an
id that matches nothing the caller may see, is `403 forbidden`. A request without an authenticated
principal is `401 unauthenticated`. Fields, views and error codes are described in `api-reference.md` ->
`items_read`.

The 3.x item list, tree, breadcrumbs, schema, gate, search and write routes are removed (section 10). Everything else about
items is a call to `items_read` or `items_write` over `POST /api/v1/ops/{tool}` (section 20a).

### GET /items/{id}

`items_read select=get` with `itemId` = `{id}`. Takes no query parameter: any is `400 unknown_parameter`.

**Response:** `200 OK`, `data` = the full item view (full UUIDs, lean: empty members omitted, `tags` an
array, `version` always present; a pinned item adds `schemaVersion` `{server?, root?, key?, layer?}` and, only
when a pinned config version is no longer the head, `stale: true`), with `ETag: "v{version}"` (the item version). An `If-None-Match` naming
that tag (a comma-separated list, a `W/` weak tag or `*` also match) answers `304 Not Modified` with no
body. An error never carries an `ETag` and is never a `304`, so the scope check comes first. `stale` can change
without a version change (a config push moves the head), so a `304` does not prove `stale` unchanged. Errors:
`400 invalid_request`, `403 forbidden`, `404 not_found` / `ambiguous_id`, `503 unavailable` (the pin view's config
read failed with nothing cached).

### GET /items/{id}/children

`items_read select=list` with `parentId` = `{id}`: one page of the item's direct children as compact views
(8-hex short ids), newest first.

**Query parameters:** `limit` (1-200, default 50; a non-integer is `400 invalid_request`), `cursor` (from
`data.page.next`). Any other parameter is `400 unknown_parameter`.

**Response:** `200 OK`, `data: {items: [compact view], page: {total, truncated, next?}}`. Paging follows the
envelope rules as amended by U22: `total` is the exact count of visible children on every page,
`truncated` is true exactly when children exist after this page, and `next` is present exactly when
`truncated`. No `ETag`. A parent outside the scope selects nothing (the filter id is not scope-checked, see
`api-reference.md` -> `items_read` "Ids"); under a restricted scope a parent id that matches nothing visible
is `403 forbidden`. Errors: `400 invalid_request` / `invalid_cursor`, `403 forbidden`, `404 not_found` /
`ambiguous_id`.

---

## 10. Removed 3.x Routes

Q32 deleted the 3.x per-resource routes. Each is now an op of `POST /api/v1/ops/{tool}` (section 20a), served by the same
handler MCP uses, or one of the two resource GETs kept in sections 9 and 11. A removed route is not routed: it answers
`404` (or a `4xx` such as `405` where the path still matches another route), never a success. Request fields, answers and
error codes live in [api-reference.md](api-reference.md); this table only maps old to new.

| Removed route | 4.0 replacement | Capability |
|---|---|---|
| `GET /items` | `items_read` `{select: "list", ...filters}` | `read` |
| `GET /items/roots` | `items_read` `{select: "list", depth: 0}`, or `{select: "overview"}` for counts. The path now reads as `GET /items/{id}` with id `roots` (a `4xx`, never a list) | `read` |
| `GET /items/{id}/tree` | `items_read` `{select: "list", ancestorId: id}` | `read` |
| `GET /items/{id}/breadcrumbs` | `items_read` `{select: "get", itemId: id, fields: ["ancestors"]}` | `read` |
| `GET /items/{id}/schema` | `items_read` `{select: "schema", itemId: id}` (add `fields: ["guidance"]` for guidance text) | `read` |
| `GET /items/{id}/gate` | `items_read` `{select: "context", itemId: id}` (orchestration profile); the gate is `data.gateStatus`, with `guidanceKey` and `skillPointer` as siblings in `data` | `read` |
| `GET /items/{id}?include=notes\|deps\|children` | `notes_read` `{select: "list"}`, `deps_read` `{select: "list"}`, `GET /items/{id}/children`; the `include` parameter is gone | `read` |
| `POST /items`, `PATCH /items/{id}`, `DELETE /items/{id}` | `items_write` ops `create`, `update`, `delete`; `expectedVersion` replaces `If-Match`, `recursive` guards a parent delete, `idempotencyKey` replaces the `Idempotency-Key` header. `create_tree` and `complete_tree` are orchestration-profile ops (neither had a 3.x route) | `write-items` (`advance` for `complete_tree`) |
| `POST /items/{id}/advance` | `advance` ops `transition`, `claim`, `release`, `heartbeat`. `transition` enforces claim ownership on REST exactly as on MCP (an item claimed by another actor is `not_claim_holder`), resource leases are always enforced (no override flag), and `DELETE /resources/leases/{key}` (section 14) recovers a stuck lease | `advance` |
| `PUT /items/{id}/notes/{key}`, `DELETE /items/{id}/notes/{key}` | `notes_write` ops `upsert`, `delete`; `expectedVersion` guards a write (`0` = must not exist, `n` = current version must be `n`, else `409 version_conflict`). `bodyFromFile` is MCP-only: over REST an element that sets it fails alone with `invalid_request` on `/bodyFromFile`, no file is opened, and the other elements of a non-atomic batch still run | `write-notes` |
| `GET /items/{id}/dependencies`, `GET /items/{id}/backlinks` | `deps_read` `{select: "list", item: id}`; backlinks are `direction: incoming`; `{select: "graph"}` walks the blocking graph. A subject item outside the scope is `403 forbidden`; an out-of-scope counterparty is dropped and not counted | `read` |
| `POST /dependencies`, `DELETE /dependencies/{id}` | `deps_write` ops `create`, `delete` (by `id`, by `from`/`to`, or every edge of an `item`), `linear`, `fan_out`, `fan_in` (each pattern is one element, all or nothing). Every named item must be in scope, else the element is `forbidden` and nothing is written | `manage-dependencies` |
| `GET /search`, `GET /notes/search` | `items_read` `{select: "search"}`; `kinds` picks item content, note bodies and/or documents, and the token scope applies before ranking and capping | `read` |
| `GET /items/{id}/transitions`, `GET /transitions` | `items_read` `{select: "history"}` with an `itemId`, or `since` for every item the token may see. Rows that predate 4.0 were copied from `role_transitions` by migration V29 and carry `backfilled: true`; `actor` and `verification` follow the attribution rule of the notes reads | `read` |
| `GET /roots/{rootId}/config[?version=&fingerprint=]` | `config` op `get` | `read` |
| `PUT /roots/{rootId}/config[?force=]` | `config` op `push`; `force` and `expectedVersion` replace `?force=` and `If-Match` | `write-config` |
| `DELETE /roots/{rootId}/config` | `config` op `reset` (a root with no config, or one already reset, answers `appended: false` instead of `404`) | `write-config` |
| `GET /roots/{rootId}/config/history[?limit=&before=]` | `config` op `history` | `read` |
| `POST /roots/{rootId}/config/rollback` | `config` op `rollback`; `expectedVersion` replaces `If-Match` | `write-config` |
| `GET /roots/{rootId}/config/effective` | `config` op `effective` | `read` |
| `GET /config`, `/config/schemas[/{type}]`, `/config/traits`, `/config/types`, `/config/status-graph` | `config` op `effective` without a `rootId` returns the same server-set view. Only `GET /config/schema` stays (section 17) | `read` |
| none | `config` op `validate` (a dry run of `push`) | `read` |
| `GET /roots/{rootId}/rules`, `GET /roots/{rootId}/rules/{key}` | `config` op `rules`; `itemId` with `noteKey` follows a note's skill pointer (now also available on REST). An absent rule is `not_found` kind `document`, no longer `rule_not_found` | `read` |
| `PUT /roots/{rootId}/plans/{slug}` | `docs_write` op `upsert`; a `rule/<key>` slug is `kind` `rule`, slug `<key>`. A write over an adopted plan is `duplicate` (`409`); `bodyFromFile` is not accepted over REST | `write-docs` (was `write-config`) |
| `GET /roots/{rootId}/plans/{slug}`, `GET /roots/{rootId}/plans` | `docs_read` selects `get`, `list`; `kind` is required, the `status` filter and field are gone (an adopted plan reports `adoptedBy`) | `read` |

The config ops check `rootId` scope first, so a root outside the caller's scope is `forbidden`, not `not_found`; status
mapping of an element error follows the envelope table in section 20a (`config_invalid` `422`, `version_conflict` `409`,
`payload_too_large` `413`, `not_found` `404`). The `Idempotency-Key` header and `If-Match` / `If-None-Match` handling of
the retired config routes are gone with them (section 4). Plugin consumers of the removed routes (`config-sync`,
`plan-capture`, `phase-guard`) are re-pointed by the plugin work.

---

## 11. Endpoints - Notes (Read)

Both routes are thin translations to the 4.0 `notes_read` tool through the shared dispatcher (see
`api-reference.md` -> `notes_read`). They answer the 4.0 response envelope, byte-equal to
`POST /api/v1/ops/notes_read` (section 20a) for the same arguments apart from `meta.reqId`, with the HTTP status
taken from the envelope's error code. Both require `READ` capability and
honor the token's root and tag scope (`403 forbidden` with `detail.scope`). Attribution fields (`actor`,
`verification`) are present only when the caller has `ADMIN` or `API_REDACT_NOTE_ATTRIBUTION=false`; proof
evidence is never shown. A query parameter a route does not list is `400 invalid_request`.

### GET /items/{id}/notes

`notes_read select=list`: the item's notes without bodies, ordered by key.

**Query parameters:** `role` (`queue`, `work`, `review`), `keys` (comma-separated keys to keep), `limit`
(1-200, default 50), `cursor` (from `data.page.next`). The 3.x `key` parameter is gone (use `keys`).

**Response:** `200 OK`, `data: {items: [{id, key, role, version, bodyLength, modifiedAt, actor?,
verification?}], page: {total, truncated, next?}}`. `id` is the first 8 hex characters of the note id.
Paging follows the envelope rules as amended by U22: `total` is the full count on every page,
`truncated` is true exactly when notes exist after this page, and `next` is present exactly when
`truncated`. Errors: `400 invalid_request` / `invalid_cursor`, `403 forbidden`, `404 not_found` /
`ambiguous_id` for the item id.

### GET /items/{id}/notes/{key}

`notes_read select=get`: one note with its body. Takes no query parameter.

**Response:** `200 OK`, `data: {id, key, role, version, body, modifiedAt, actor?, verification?}`, with
`ETag: "v{version}"` (the note version, V28). An `If-None-Match` naming that tag (or `*`) answers
`304 Not Modified` with no body. A missing note is `404 not_found` (id `<itemUuid>/<key>`).

---

## 14. Endpoints — Resource Leases

Server-wide primitive backing the resource-lease gate of the `advance` tool's `transition` op and the
`resources:` trait dimension (see `config-format.md`). **Cross-project, not per-root** — unlike
every other `/api/v1` resource in this document, these routes carry no `rootId` scoping; a lease key
is a server-wide namespace (see the "global-wins" registry precedence note in `config-format.md`).

All three routes answer the **response envelope** (`ok`, `data`/`error`, `meta.reqId`), with the status of the error code.
A token lacking the needed capability gets `forbidden` (`detail.scope = "capability"`, `detail.required` the capability name).
A `401` from the authentication plugin is the `unauthenticated` envelope too (section 6).

### GET /api/v1/resources/leases

Lists all currently active (unexpired) resource leases. Requires `READ`.

**Response `200 OK`:** a list page. `data.items` is ordered by (`resourceKey`, `holderItemId`) and each item is
`{resourceKey, holderItemId, acquiredByActorId?, acquiredAt, expiresAt, originalAcquiredAt}`; `data.page` is
`{total, truncated: false}` with `total == items.length`. `acquiredByActorId` is present only for callers with
`ADMIN` capability (omitted, never `null`, for others). No cursor: lease cardinality is bounded by design (one row per
declared resource key per active holder, TTL-bounded).

### DELETE /api/v1/resources/leases/{key}

Force-releases the active lease(s) held on `{key}`. Requires `ADMIN`. This is the operator recovery
path for a crashed holder — it turns "wait out the TTL (up to 24h) or disable enforcement
server-wide" into a one-call fix, and it is the only one now that the advance `overrideResourceLeases` flag is gone.

**Path parameter:** `{key}` — validated against `^[a-z0-9][a-z0-9\-_./]*$`, max 128 chars, checked
**before** any repository access.

**Responses:**
- `200 OK` → `{"ok": true, "data": {"resourceKey": "...", "releasedCount": 1}, "meta": {...}}`
- `400 invalid_request` — `{key}` fails the pattern/length check (field `key`)
- `403 forbidden` — caller lacks `ADMIN` (`scope: capability`, `required: admin`)
- `404 not_found` — no active lease exists on `{key}` (`detail.kind = "lease"`, `detail.id` the key; a repeat call
  after a successful release returns `404`, not a second `200`)

Every successful force-release is WARN-logged with the acting principal's token id, the key, and the
released count — sufficient to attribute the action to a specific bearer token from server logs
alone. The acting principal's token id is also threaded through to `released_by_actor_id` on the closed history interval(s).

### GET /api/v1/resources/leases/history

Append-only lease-interval audit log — answers "who held resource R at time T" after a lease has
already been released, stolen, or force-released, which `GET /resources/leases` (current holders
only) cannot. Requires `READ`.

**Query parameters** (all optional):
- `key` — restrict to one resource key. Same validation as `{key}` on the DELETE route above (`invalid_request`, field `key`).
- `at` — an ISO-8601 instant. When present, returns every interval held at that instant (per
  `acquiredAt <= at < coalesce(releasedAt, expiresAt)`), open or closed; an unparsable value is `invalid_request`
  on field `at`. When absent, returns the most recent intervals (open or closed). Either way the order is
  newest-first `(acquiredAt DESC, id ASC)`; the id tiebreak makes it total, so paging is stable.
- `limit` — max rows, an integer from `1` to `200`, default `50` (any other value is `invalid_request` on field `limit`).
  Applied in SQL after the newest-first ordering, so a large history is never loaded to be truncated.
- `cursor` — the previous page's `page.next` (an opaque offset, not bound to `key`/`at`). Absent is the first page;
  a malformed or empty value is `invalid_cursor` (400). Field faults (`key`, `at`, `limit`) are answered first.

A lease is active while its expiry is strictly after "now": an expiry equal to now is expired, so a release at exactly the expiry instant records `expired`. All lease timestamps are canonical UTC text from the server's one bound clock.

**Response `200 OK`:** a list page; each item is `{resourceKey, holderItemId, acquiredByActorId?, acquiredAt, expiresAt,
releasedAt?, releaseReason?, releasedByActorId?}` (`releaseReason` is `released`, `expired` or `force_released`; the
last three are absent while the interval is open), `page.total` is the count of ALL matching intervals on every page, `page.truncated` is true while intervals remain after this page, and `page.next` is then the cursor for the next page (absent on the last page); walking `next` visits every interval exactly once only while no lease starts or ends during the walk (history is newest-first, so a walk during lease activity can repeat or skip rows). `acquiredByActorId` / `releasedByActorId`
are present only for `ADMIN` callers.

No pruning/retention in v1 — the table is append-only and grows with lease-event cardinality
(documented, accepted behavior; see `current/src/main/resources/db/migration/sqlite/V16__Resource_Lease_History.sql`).

---

## 17. Endpoints — Config JSON Schema

### GET /config/schema

The JSON Schema (draft 2020-12) every config document is validated against, byte for byte. Requires `READ`.

**Response:** `200 OK`, `Content-Type: application/schema+json`, the schema document itself (not an
envelope), with a strong `ETag` that changes exactly when the schema does; `If-None-Match` with that ETag
returns `304`. See the [config reference](config-reference.md).

It is the only config route. `GET /config`, `/config/schemas`, `/config/schemas/{type}`, `/config/traits`,
`/config/types` and `/config/status-graph` are gone (they answer `404`): `effective` without a `rootId` returns the
same server-set view (schemas, traits, types and status graph) from the `config` tool, section 20a.

---

## 20. Endpoints — Service Meta

### GET /api/v1/info

Requires an authenticated caller. Returns server metadata and the caller's resolved capabilities as the `data` of an
ok envelope (`{"ok": true, "data": {...}, "meta": {"reqId": ...}}`, Q32).

**Response `200 OK` `data`:**
```json
{
  "serverName": "mcp-task-orchestrator-current",
  "version": "4.0.0",
  "apiVersion": "v1",
  "capabilities": ["read", "write-items"],
  "claimModeAvailable": true,
  "actorAuthenticationEnabled": false,
  "features": ["seats", "dispatchBySeat", "independent_of", "rules"],
  "configWarnings": []
}
```

`configWarnings` (array of strings, additive) is the stored SERVER config head's warnings: the reasons recorded when the
global config file was imported at boot (for example one per legacy top-level key such as `orchestration`), in the
same string form push warnings use. Empty when there is no server config or it imported clean. A server-head read
fault answers `503 unavailable` and an unloadable head `422 config_invalid` (the `config` tool's typed head faults);
`/health` never reads it.

`features` (array, A1) advertises server-wide optional-response capabilities this server version
can serve, **unconditionally** — regardless of whether the resolved config for any given root
actually declares `seats:`/`independent_of:`. `"independent_of"` (A2) joined this list once the
independence attestation gate started enforcing it, and `"rules"` (A3) advertises the per-root
rule text served by the `config` tool's `rules` op (section 10) - see [api-reference.md](api-reference.md)
→ "Server feature advertisement" for the full rationale and the current list.

### GET /api/v1/health

**No authentication required.** Lightweight DB probe.

**Responses:**
- `200 OK` → `{"status": "ok", "dbReachable": true}`
- `503 Service Unavailable` → `{"status": "degraded", "dbReachable": false}`

### GET /.well-known/mcp-task-orchestrator.json

**No authentication required.** Service discovery document. Mounted at the root (not under `/api/v1`).

**Response `200 OK`:**
```json
{
  "name": "mcp-task-orchestrator-current",
  "version": "4.0.0",
  "apiVersion": "v1",
  "apiUrl": "/api/v1",
  "features": ["seats", "dispatchBySeat", "independent_of", "rules"]
}
```

`features` (array, A1) — same server-wide feature advertisement as `GET /api/v1/info`'s `features`
above.

---

## 20a. Endpoints - Ops (RPC)

### POST /api/v1/ops/{tool}

The canonical RPC route for the 4.0 tool surface: one route that calls any tool in the tool registry, the
same tools MCP serves. `{tool}` is the registry tool name. It serves every registered select and op, whatever its
profile set, including REST-only members (an empty set): tool profiles (`/mcp`, `/mcp/knowledge`, `/mcp/director`,
`MCP_TOOL_PROFILE`) are an MCP-connection concept. The
machine-readable form is in [`api/openapi.yaml`](api/openapi.yaml) (path `/ops/{tool}`).

**Request body:** the tool's envelope request, i.e. the same JSON object an MCP `tools/call` carries as
`arguments` (`select` for a read tool, `ops[]` for a write tool, `describe: true` for the tool's schema). The
body must be a JSON object, at most 1 MiB. The `Content-Type` header may be absent or `application/json` with
any parameters (`application/json; charset=utf-8`); anything else is rejected. This check is not the CSRF
protection on its own (an absent `Content-Type` is accepted); cross-site calls are stopped by the CORS plugin
and the host allowlist, as on every other route.

**Response:** the response envelope, byte-equal to what MCP returns for the same arguments and access, apart
from `meta.reqId`. The HTTP status comes from the envelope's error code (`200` for ok, `207` for a partial
batch, otherwise the code's own status, e.g. `404 not_found`, `403 forbidden`); no route picks a status by
hand. Errors use the envelope, as on every other route (§6).

**Principal.** The caller's principal comes from the bearer token (audited as `api:<id>`, kind `external`). A body
`actor` is ignored, never decoded, so a token holder cannot spoof attribution (MCP, which has no bearer
credential, still lifts the body `actor`). Idempotency keys (`ops[].idempotencyKey`) are therefore namespaced
per token principal: the same key and payload sent from two different tokens is two different calls, not a
replay. A reused key with a different payload from the same token is `idempotency_mismatch`, as in §5.

**Capabilities.** There is no route-level capability wrapper; the dispatcher checks capability per select and
per op with the shared capability predicate (`ADMIN` grants every capability, otherwise the capability must
be listed on the token; see §2). Each select and op declares the capability it needs (`READ` for a read
select, the matching write capability for an op).
- A read `select` the token lacks the capability for fails the whole call: `403 forbidden` with
  `detail.scope: "capability"` and `detail.required` the kebab-case capability name (`read`, `write-items`,
  ...). The handler does not run. `describe` needs no capability.
- A write op the token lacks the capability for fails that `ops[]` element alone with `forbidden`; it is never
  executed and never stored. The capability check runs before the element's `idempotencyKey` and field
  checks, so a forbidden element with invalid fields reports `forbidden`, not `invalid_request`. In a
  non-atomic batch the other elements still run and the call answers `207 partial_failure`; in an atomic batch
  the call answers `403` with `error.index` naming the forbidden element.

**Scope.** The token's `scope` (§3) reaches every tool handler. An item outside the token's `root_ids` or
`tags_include` is reported `forbidden` with `detail.scope` `root` or `tag` and `detail.required` the sorted
listed root ids or tags joined by `,`. The check is the transport-neutral `ScopeGuard`, the one scope predicate:
an unrestricted token is admitted with no database read; with a
root restriction the item or one of its ancestors must be a listed root; with a tag restriction the item must
carry one of the listed tags (exact, case-sensitive match on the trimmed comma-separated tags). A missing item
and a lookup failure under a restricted scope are `forbidden` too (fail closed), so scope is never an
existence oracle; the one exception is an id that is itself one of the token's listed roots, which is admitted
under a root restriction (as in 3.x) and then answers the tool's own `not_found`, revealing nothing beyond
the token's own scope. List reads filter out-of-scope entities instead of failing, except `report` `usage` and `waste`, which
refuse a `tags_include` token outright (`forbidden`, `detail.scope` `tag`; call rows carry no tags) and aggregates only
a `root_ids` token's own roots (§3).

**Reports.** `POST /ops/report` serves the `report` tool (a director-profile tool on MCP). Its selects are `usage`
(windows at most 400 days), `gates` and `contention` (at most 31 days) and `waste` (at most 7 days). The `usage` select
aggregates `call_log` per `groupBy` group over a half-open `[since, until)` window (default the last 7 days, at most
400; a window of at most 31 days inside the raw horizon (an empty `call_log` with a fold present counts as before it) reads `call_log` with `basis` `raw`, any other reads the per-day
summaries `usage_daily` plus the raw tail with `basis` `daily`, `since` floored and a folded `until` ceiled to UTC midnight;
`groupBy` `principal` is raw-only): `{items: [{key, calls, errors, responseBytes, responseTokensEst, avgLatencyMs, maxLatencyMs, retries, replays}],
page, window: {since, until, retainedSince?, basis, truncatedByRetention?}}`. `key` is `{<groupBy>: value}`, or `{}`
when the group value is missing (never a JSON `null`). Grouping by `principal` needs `admin`. The
field reference is in [`api-reference.md`](api-reference.md#report). The `gates` and `contention` selects aggregate the `events` table (rejected transitions, claim and lease activity) and apply a tag scope per event instead of refusing it. The `waste` select answers one object of call-pattern waste signals over `call_log` (no grouping, no page) with the visibility of `usage`.

**Faults.** Every fault answers an envelope and is checked in this order:

| Condition | Status | Envelope |
|-----------|--------|----------|
| No authenticated principal on the call | 401 | `unauthenticated` |
| `Content-Type` present and not `application/json[;params]` | 400 | `invalid_request`, field `Content-Type` |
| An `Idempotency-Key` header (use `ops[].idempotencyKey`) | 400 | `invalid_request`, field `Idempotency-Key` |
| Body over 1 MiB (the declared `Content-Length`, or the bytes read, capped at 1 MiB + 1) | 413 | `payload_too_large` with `max` and `actual` |
| Body is not a JSON object (empty, malformed, array, scalar) | 400 | `invalid_request`, field `$` |
| `{tool}` unknown, or not visible in the served profile | 400 | `invalid_request`, field `tool`, reason listing the valid tool names |

Anything a tool or the dispatcher reports (`not_found`, `idempotency_mismatch`, `forbidden`, ...) is the
tool's own envelope with its own status.

**Authentication failures.** A missing or invalid bearer token is rejected by the shared `ApiBearerAuth` plugin
before this route runs: `401 unauthenticated` with the `WWW-Authenticate` challenge (§6). A token with
`profile: director` (§1) is refused here with `403 forbidden` (`scope: capability`, `required: director`). With the
REST API disabled (`API_ENABLED=false`) the route is not mounted and answers `404` like every other `/api/v1` route.

**Request correlation.** The `X-Req-Id` response header (§6a) equals `meta.reqId` in the envelope, on success
and on every fault above.

**Call log.** The `call_log` row of an `/ops` call (§6a, `fleet-deployment.md` -> "Call log and `reqId`")
records the registry tool name as `tool` (not the route template), the select or op name as `operation`, and
`batch_size`, `failed_count` and `result_count` from the dispatch, with `surface` `rest` and `host` captured
like any other REST row. A fault answered before dispatch (the table above) keeps the route template
`POST /api/v1/ops/{tool}` as `tool` and no `operation`.

**Parity with MCP.** MCP and this route share one tool registry and one dispatcher, so a tool behaves the same
on both surfaces, with one deliberate exception: `notes_write` `bodyFromFile` is MCP-only and answers
`invalid_request` (`/bodyFromFile`) here (section 10). A test-side parity harness runs every registered tool over both transports with identically
seeded databases and compares the envelope bytes (minus `meta.reqId`), the HTTP status against the error
code, and the resulting database state; a coverage guard requires every registered select and op to carry a
happy-path and a scope-forbidden scenario.

### POST /api/v1/ops/director/{tool}

The director tool profile over REST: the same dispatcher, request body, envelopes, statuses, capability and
scope checks as `POST /api/v1/ops/{tool}`, serving only the selects of the director profile (the set
`/mcp/director` serves: `items_read` `get`, `list`, `overview` and `blocked`; `deps_read` `list` and `graph`). For a
member of that set the envelope is byte-equal to the `/ops/{tool}` answer, apart from `meta.reqId`.

- A registered tool with no member in the director set answers `403 forbidden` with `detail: {scope: "capability",
  required: "director"}`.
- A name that is not a registered tool answers `400 invalid_request` (field `tool`, listing the director tools).
- A select hidden inside a visible tool (e.g. `items_read` `history`) answers the dispatcher's `400 invalid_request`
  (`/select`, listing the visible selects).

Any credential may call it with the capability the member needs (`read`). It is the only path a token with
`profile: director` may call (§1); the path is matched exactly (`/api/v1/ops/director/<tool>`, lowercase, no
trailing slash, no encoding), and anything else is refused for such a token. The machine-readable form is in
[`api/openapi.yaml`](api/openapi.yaml) (path `/ops/director/{tool}`).

---

## 21. Server-Sent Events (SSE)

### GET /api/v1/events

Real-time event stream. Requires `READ` or `ADMIN` capability.

**Delivery guarantee:** the stream is a projection of the durable `events` table (4.0, migration V20). Every write records its event rows in the same database transaction as the change, so a rolled-back write records (and streams) nothing. Events reach subscribers in commit order (`id` = the row's `seq`). A write made by this process streams as soon as it commits; a write made by another process sharing the database (for example an MCP `stdio` server) streams within about one second, picked up by a 1 s poll while any subscriber is connected. A caller observing a `200`/`201` response is guaranteed the corresponding event is stored and replayable.

**Authentication — pre-flight plugin (important):**

Ktor's `sse {}` handler runs inside the response-body phase — after the HTTP 200 status is committed. Auth cannot be performed inside the handler itself. The SSE route uses a dedicated pre-flight plugin that checks authentication in the `Plugins` phase, before streaming begins. A failed auth check sends `401`/`403` before any SSE content is produced.

This route is exempted from the global `AuthenticationPlugin` via `publicPaths`/`publicPrefixes`
(matched against the request path only, not the full URI — so a `?token=` or any other query
string on the exempted path still matches). `CurrentMcpServer.kt` registers `/mcp`, `/mcp/knowledge`,
`/mcp/director` and `/api/v1/events` as public paths; a Ktor application plugin like `AuthenticationPlugin` is never
route-scoped by nesting alone, so `publicPaths` is the only lever that keeps the global plugin from
401'ing this route before the SSE pre-flight plugin gets a chance to run its own check.

**Auth resolution order:**
1. `Authorization: Bearer <token>` header — always accepted
2. `?token=<plaintext>` query parameter — only accepted when `API_ALLOW_QUERY_TOKEN_FOR_SSE=true`.
   Because `publicPaths` matching is path-only (see above), this works correctly even though the
   query string is present on the request.
3. `API_AUTH_MODE=none` + `API_ALLOW_UNAUTHENTICATED=true` (§1) — the pre-flight plugin short-circuits
   with the synthetic unauthenticated principal, mirroring `ApiBearerAuth`'s `Unauthenticated` branch;
   no token is required or checked
4. If none of the above apply → `401 unauthenticated` (challenge `error="invalid_request"`) when no token is presented (no `Authorization` header and no allowed `?token=`), or `401 unauthenticated` (challenge `error="invalid_token"`) for an unknown, expired, or invalid token; a valid token lacking the `read` capability gets `403 forbidden` (`detail.scope` `capability`). Every pre-flight body is the error envelope.

**Tag-scope guard:** Per-event `tags_include` filtering (below) needs the route's `WorkItemRepository`
wiring to resolve an event's item tags. If a principal's `scope.tags_include` is non-empty but no
repository is wired for the route, the pre-flight plugin rejects the connection with
`403 forbidden` (`detail.scope` `tag`) rather than silently serving an unfiltered stream — a fail-closed
connection-time check, distinct from the per-event filtering described below.

**Browser SSE note:** The native browser `EventSource` API cannot set custom headers. Browsers must use a fetch-based SSE client (e.g., `@microsoft/fetch-event-source`) to provide the `Authorization: Bearer` header, or enable `API_ALLOW_QUERY_TOKEN_FOR_SSE=true` to use the query-parameter path.

**Query parameters:**
- `root` (repeatable) — filter to events whose stamped `root_id` is this root (the root's whole tree; roots only, no ancestor walk). Effective subscription = intersection of `?root=` values with `principal.scope.rootIds`. Both rejections below are enforced by the pre-flight plugin — a normal HTTP status + JSON body, not an SSE stream:
  - `403 forbidden` (`detail: {scope: "root", required: "<the token's roots>"}`) — the principal is root-scoped (`scope.rootIds` non-null) and its `?root=` values do not intersect that scope at all.
  - `400 invalid_request` on field `root` — any `?root=` value is not a UUID (e.g. `?root=` with no value, `?root=garbage`, or `?root=<uuid>&root=garbage`; Q32 ended the 3.x leniency that dropped the bad values of a mix). This check also fires under `API_AUTH_MODE=none`.
- `types` — comma-separated event type filter (e.g., `types=item.created,item.advanced`). Exempt from this filter: `sync.lost` and `auth.expired` (the control events, see Event Types below) are always delivered regardless of `types` — they report the state of the stream itself, and a filtered-out client would otherwise keep operating on incomplete state (or an expired credential) with no signal.

`tags_include` is not a query parameter — it is enforced from the principal's own token scope, per event, as described next.

**`Last-Event-ID` replay:** Event ids are the `seq` of the `events` table: monotonic, durable across restarts, shared by every process writing the database, and (since V20) above 10^12. On reconnect, the stored events with `id > Last-Event-ID` are replayed before live streaming resumes, deduplicated against the live stream by id (no event is delivered twice or skipped at the boundary). `API_SSE_BUFFER_SIZE` (default 1000) is the **replay window**: a resume at most that many ids behind the newest event replays everything after the cursor. Every row carries its `root_id`, so the replay path applies the **same root-intersection filter** as the live fan-out -- a client reconnecting with `?root=<uuid>` receives only replayed events for roots within its subscription (and scope). Replay is consistent with the live stream. Control events (`sync.lost`, `auth.expired`) are not stored, so they are never replayed. A connection without `Last-Event-ID` starts at the newest event: it receives only events committed after it connected (a backlog written while nobody listened is replayed only on a resume).

**Unreplayable cursor -> `sync.lost`:** When a reconnecting client's `Last-Event-ID` cannot be satisfied -- the id is more than the replay window behind the newest event (`buffer_evicted`), above the newest event (`unknown_event_id`), below the 10^12 seq floor (`unknown_event_id`: every id issued by the pre-4.0 in-memory ring buffer, so a cursor saved before the upgrade is reported, never silently matched), or the header was present but not parseable as a number (`unknown_event_id`) -- the bus emits a `sync.lost` event as the **first frame of the connection**, before any replayed or live event, carrying the cause in `reason`. Detection compares the cursor with the newest event of the whole log, not the caller's root-scoped view, so a root-scoped client can occasionally receive a `sync.lost` for a gap that didn't affect its own roots (a false positive costing one extra re-fetch -- the alternative, a scoped check, risks a false *negative*, i.e. silent loss, which is what this exists to prevent). A blank or absent `Last-Event-ID` header is not treated as a resume attempt and never produces `sync.lost`.

**Replay resumes from the sentinel, not the client's cursor:** After either `sync.lost` reason, the same connection immediately replays the WHOLE replay window -- not just events past the client's original `Last-Event-ID` -- because the replay cursor becomes the sentinel's own `id` (`max(10^12, newest id - API_SSE_BUFFER_SIZE)`, below every event that follows it) rather than the unusable client cursor. No second reconnect is needed, and reconnecting with the sentinel's id yields no second sentinel. `API_SSE_BUFFER_SIZE=0` is a legal, explicit "no replay" setting: every resume attempt behind the newest event yields `sync.lost` (`buffer_evicted`, sentinel id = the newest id) with no events to replay after it.

**Writes made while no subscriber was connected are replayed in full:** every write is stored with its root whether or not anyone is connected, so a later `Last-Event-ID` resume recovers it with the same root filtering as a live delivery. (Before 4.0 such writes were buffered without a root and replayed only to unrestricted subscribers.)

**Tag scope (`tags_include`) filtering:** Root scope is applied at the bus level (above); tag scope
is enforced per-event on top of it, identically for the live stream and for `Last-Event-ID` replay,
via the same `allowsItemTags` predicate the REST collection endpoints use (§3). A connection whose
principal has no `tags_include` (or an event with no `itemId`, i.e. a bus-level event) is unaffected.
Otherwise each event's `itemId` is resolved to its current tags and checked against the allowlist;
the result is cached per connection for `item.*`/`scope.*` events (which also refresh the cache, so a
mid-stream tag change on an item takes effect immediately) and reused for `note.*`/`dependency.*`
events on the same item. See §24 for the `item.deleted` fail-closed gap this scheme has.

**Event ID namespace:** The ids of `/api/v1/events` (the `events` table's `seq`) are **independent** from the `/mcp` SSE channel's `EventStore`. Do NOT reuse `Last-Event-ID` values across the two channels.

**Token expiry:** The SSE handler periodically checks token expiry (interval: `API_SSE_AUTH_CHECK_INTERVAL_SECONDS`, default 30s). When a token expires, an `auth.expired` event is sent and the stream closes. The client must reconnect with a fresh token. This watchdog runs for **every** authenticated JWKS SSE session — JWKS tokens are required to carry `exp` (see §1), so every JWKS session has a real expiry to watch. It does not run, and no `auth.expired` event is ever sent, for the two cases with no expiry to watch: `API_AUTH_MODE=none` unauthenticated sessions, and bearer-token sessions whose token entry has no `expires_at`.

### Event Types

| Event type | `itemId` | `modifiedAt` | `newRole` | Description |
|-----------|----------|--------------|-----------|-------------|
| `item.created` | set | set | null | Work item created |
| `item.updated` | set | set | null | Work item field updated |
| `item.deleted` | set | set | null | Work item deleted |
| `item.advanced` | set | set | set | Role transition occurred; `newRole` is the target role |
| `note.upserted` | set | set | null | Note created or updated |
| `note.deleted` | set | set | null | Note deleted |
| `dependency.added` | set | set | null | Dependency edge created |
| `dependency.removed` | set | set | null | Dependency edge removed |
| `scope.entered` | set | set | null | Item reparented into this root's subtree |
| `scope.left` | set | set | null | Item reparented out of this root's subtree |
| `sync.lost` | null | null | null | Unrecoverable gap in the stream; re-fetch full state. Carries `reason`: `queue_overflow` (the connection's per-connection queue overflowed and events were dropped mid-stream), `buffer_evicted` (resumed `Last-Event-ID` is further behind the newest event than the replay window), or `unknown_event_id` (resumed `Last-Event-ID` is above the newest event, below the 10^12 seq floor -- a pre-4.0 cursor -- or was not parseable as a number) |
| `auth.expired` | null | null | null | Connection's token has expired; reconnect with fresh credential |

Both `sync.lost` and `auth.expired` are **control events** — they always bypass the `?types=` filter (see Query parameters above). All other event types additionally carry a `reason` field of `null`, and (because the SSE payload is encoded with `explicitNulls = false`) it is absent from their JSON entirely rather than present as `null`.

**Actor and rootId (domain events only).** Every domain event (`item.*`, `note.*`, `dependency.*`, `scope.*`) may carry two additive fields; the control events (`sync.lost`, `auth.expired`) never do.

- `actor` - `{id, kind, parent?}`: who performed the write. Never `proof` or `verification`. Resolution order at the moment the event is queued: the note's own `actorClaim` (`note.upserted`), then the actor of the enclosing write (the MCP tool's `actor`, per transition for `advance` -- a cascaded parent advance carries the triggering transition's actor - or the synthesized `api:<tokenId>` / `external` claim for REST writes), then none. A write with no actor (a tool call without `actor`, a background sweep) emits no `actor` field.
- `rootId` -- the item's depth-0 ancestor (`scope.left` carries the OLD root, `scope.entered` the NEW root). Always present on a domain event since 4.0 (a root item uses its own id), live and on replay alike. `rootId` is scope metadata and is never redacted.

**Redaction.** Applied per connection, on egress only, identically for live delivery and `Last-Event-ID` replay: `actor` is omitted when `API_REDACT_NOTE_ATTRIBUTION=true` and the caller lacks `ADMIN`; otherwise it is delivered (`API_AUTH_MODE=none` callers are ADMIN).

**Stored `events` row data:** each domain event's `events`-table row carries a type-specific `data` object that the SSE `ApiEvent` does not expose. For `note.upserted` it is `{itemId, key, role, bodyLength, bodyFromFile}` (plus `actorParent` when the writing actor names a parent): `bodyFromFile` is the server-side path a `notes_write` MCP caller supplied (since 4.0), or null for an inline body; the file contents are never recorded.

**`item.updated` note:** since 4.0 an update that changes no field emits nothing, and a role change that also edits other fields emits only `item.advanced`. A lifecycle flip (an update that changes `type`, or a `repin`, moving the item into or out of lifecycle-free) is not a transition: it emits `item.updated`, whose stored changed fields include `role`, and no `item.advanced`.

**`item.advanced` note:** This event is the projection of the `item.transitioned` event a role change records (via the `advance` tool, `items_write` `complete_tree`, including cascaded parent transitions). It carries the `newRole` field. This is distinct from `item.updated` -- a role change emits `item.advanced` (not `item.updated`).

**Claim/release note:** A successful claim or release through the `advance` tool's `claim` and `release` ops (MCP or `POST /ops/advance`) emits `item.updated` for the claimed/released item -- and, for a claim that auto-releases the agent's other held items, one additional `item.updated` per auto-released item. A transition that ends a held claim (for example completing a claimed item) also emits `item.updated` for that item since 4.0.

**Bulk-write note:** `items_write` `create_tree` emits `item.created` for each newly created item (root first; an attach-mode pre-existing root emits nothing), then `dependency.added` per edge, `note.upserted` per note and `document.adopted` for a `docRef`, all after the element's unit commits -- a rolled-back tree emits nothing. Deleting all of an item's notes at once emits one `note.deleted` per note (since 4.0; was one per call); removing all of an item's dependencies emits one `dependency.removed` per edge. Deleting an item also emits one `note.deleted` per note and one `dependency.removed` per edge the database cascade removes, before the `item.deleted`, and one `document.deleted` per document anchored to the item (archived and adopted ones included), before those.

**Table-only events:** the `events` table also records rejections (`transition.rejected`, `claim.rejected`, `lease.rejected`), expiry (`claim.expired`, `lease.expired`), resource-lease acquire/release, config version creation (`config.version_created`), document writes and adoption (`document.created`, `document.updated`, `document.archived`, `document.deleted`, `document.adopted`) and config pins (`item.pinned`). These are audit rows and are not streamed; the stream carries only the event types listed above.

**Who records what (4.0).** Each write service records its own rows, in its own transaction: the
item command service (`item.created`, `item.updated`, the two `scope.*` rows of a reparent plus one
`item.updated` per restamped descendant, `item.deleted` and the cascade `note.deleted` /
`dependency.removed` rows of a delete; the `document.deleted` rows of the documents a delete removes
come from the document service, joining the same transaction), the note, dependency, project-config and plan-document
services their own rows, the advance pipeline `item.advanced` (including a cascade a reparent or a
delete triggers), and the claim service claim and lease rows. No store is decorated. The bus is the
recorder's commit listener directly; a fan-out failure is logged and never fails the committed write
(the 1 s poll picks the rows up).

---

## 22. Audit Model

Every write (`POST /ops/{tool}`) synthesizes an actor server-side from the authenticated principal. Client-supplied `actor.*` fields in the request body are **silently dropped** - callers cannot override audit attribution.

**Synthesized actor:**
- `id` = `"api:<id>"`, the principal id (e.g., `"api:dashboard-editor"`)
- `kind` = `"external"` for every REST principal, whatever its own kind (`human`, `agent`, `service`; §1), which
  travels with the call instead of the audit actor
- `parent` = `null`
- `verification` = `verified` (verifier `api-jwks`) for a JWT, fresh or stale; `unchecked` for a token-file token
  (`api-bearer`) and for unauthenticated mode (`api-unauthenticated`)

**Attribution redaction (applies to notes and the `items_read` `history` select):**
- Non-admin callers: `actor` and `verification` fields are omitted from responses
- Admin callers: fields are visible subject to `API_REDACT_NOTE_ATTRIBUTION`
- Proof evidence (a SHA-256 hash of the proof, and the verified JWT claims `iss`/`sub`/`aud`/`jti`/`iat`/`exp`/`kid`/`alg`
  when the proof was `verified`) is stored but never returned: `verification` is `{status, verifier?, reason?}` for every
  caller, `ADMIN` included (3.x showed `verification.proof` to `ADMIN`).

**Redaction env vars:**
- `API_REDACT_NOTE_ATTRIBUTION` (default `true`) — when `true`, non-admin callers see no attribution
  (this includes the `actor` field on SSE events; see §21)

**Independence violations (A2) are redacted by construction, not by policy.** An independence violation
carries only seat names and note keys (`key`, `seat`, `constraint`, `conflictingSeat`, `waived`) —
never an actor id, proof, or verification claim — on every surface (`items_read` `context` `gateStatus.violations`,
the `violations` of an `advance` `transition` result, and the `detail.violations` of a `409 gate_blocked`). This is unconditional: it does not vary with `API_REDACT_NOTE_ATTRIBUTION`,
caller capability tier, or admin status, because there is no attribution-bearing field on the DTO for
`AttributionRedactor` to redact in the first place.

---

## 23. Status-Graph Caveat

The `statusGraph` member of the `config` tool's `effective` result is the **structural** (schema-defined) transition graph — which triggers are valid for each role per type.

This graph does NOT reflect:
- Runtime gate enforcement (note gates — required notes that must be filled before `start` succeeds)
- Dependency blockers (an item blocked by an unsatisfied dependency cannot advance)
- Claim ownership (a transition on an item another actor holds is `not_claim_holder`, on MCP and `POST /ops/advance` alike)
- Per-item lifecycle exceptions

Dashboard UI: do not use the status graph to pre-compute which buttons to enable. Always call the `advance` tool's `transition` op and surface its error if the transition is blocked at runtime — `gate_blocked` (unfilled required note), `dependency_unmet` (dependency blocker), or `invalid_transition` (invalid state), each a `409`.

The `"<previousRole>"` sentinel in `blocked.resume` is a literal string — resolve it from the live item's `previousRole` field.

The graph of a rooted `effective` call is built over that root's resolved schema set, so it is equally structural-only: it does
not reflect note gates, dependency blockers, claim ownership, or per-item lifecycle exceptions for the root's items
either.

---

## 24. Known Limitations

**SSE root scoping is per row.** Since 4.0 every event row is stored with its root (the item's
depth-0 ancestor; a reparent stores one row under the old root and one under the new), so live
delivery and `Last-Event-ID` replay are root-filtered identically whether or not a subscriber was
connected at write time. Bus-level control events (`sync.lost`, `auth.expired`) always broadcast to
every subscriber, root-scoped included, because they report the state of the stream itself.

**SSE tag-scope filtering has an `item.deleted` fail-closed gap.** Per-event `tags_include`
filtering (§21) resolves an event's tags by looking up its `itemId` at delivery time. For
`item.deleted`, the item is already gone by the time the event is filtered, so its tags cannot be
resolved — the event is dropped for any tag-scoped connection rather than risk showing (or hiding)
it incorrectly. There is no live row left to query for `item.deleted`, so the fail-closed drop for
tag-scoped subscribers is unconditional. The `note.deleted` and `dependency.removed` events an item
delete cascades (emitted before its `item.deleted`) share the gap: they are dropped for a tag-scoped
connection unless it already resolved the item's tags from an earlier event on the same connection.

**SSE honors bearer, JWKS, and unauthenticated modes.** The pre-flight auth plugin for the SSE route resolves `Authorization: Bearer` (and, when enabled, `?token=`) using the same shared Bearer-scheme parser as `ApiBearerAuth`, and explicitly short-circuits for `API_AUTH_MODE=none` (§1/§21) exactly like the bearer route. JWKS-mode JWT authentication for SSE is exercised by the automated expiry-watchdog test suite, which sends JWKS-signed tokens through this plugin.

**FTS5 requires SQLite.** `items_read` `search` runs on the SQLite FTS5 virtual tables created by the Flyway migrations.

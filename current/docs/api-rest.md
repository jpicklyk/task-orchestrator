# REST API Reference — MCP Task Orchestrator

This document describes the HTTP REST API layer (v1) added alongside the MCP transport. The REST API runs on the same Ktor server when `API_ENABLED=true`.

**Base URL:** `/api/v1`
**Content-Type:** `application/json` (responses); see PATCH for request-body negotiation.
**OpenAPI spec:** [`api/openapi.yaml`](api/openapi.yaml) — covers all routes for machine consumption. Note: the YAML is hand-maintained and may lag behind edge-case behavior; this document and the source are authoritative.

**HTTP-first policy.** New plugin-side infrastructure features — `config-sync.mjs`, SSE event
streaming, and the `plan-capture.mjs` hook (which stashes an approved plan as a `plan_document` via
`PUT /roots/{rootId}/plans/{slug}`) — are built HTTP-only, each fail-opening to a silent no-op when
its REST env var (`TASK_ORCHESTRATOR_API_URL`, etc.) is absent. STDIO deployments keep full MCP tool
functionality but do not gain these convenience features; STDIO is positioned as the local/evaluation
transport, while a persistent HTTP daemon with the REST API enabled is the recommended path for
ongoing fleet or multi-project work.

The plugin's SubagentStop phase guard (`phase-guard.mjs` / `phase-guard-record.mjs`) is another such
consumer: it polls `GET /items/{id}/gate` (§9) to decide whether to send a returning subagent back
for missing notes, and fails open the same way when `TASK_ORCHESTRATOR_API_URL` is unset — see
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
10. [Endpoints — Items (Write)](#10-endpoints--items-write)
11. [Endpoints — Notes (Read)](#11-endpoints--notes-read)
12. [Endpoints — Notes (Write)](#12-endpoints--notes-write)
13. [Endpoints — Dependencies](#13-endpoints--dependencies)
14. [Endpoints — Resource Leases](#14-endpoints--resource-leases)
15. [Endpoints — Transitions (Audit)](#15-endpoints--transitions-audit)
16. [Endpoints — Search](#16-endpoints--search)
17. [Endpoints — Config / Schema Discovery](#17-endpoints--config--schema-discovery)
18. [Endpoints — Project Config (Per-Root)](#18-endpoints--project-config-per-root)
19. [Endpoints — Plan Documents (Per-Root)](#19-endpoints--plan-documents-per-root)
20. [Endpoints — Service Meta](#20-endpoints--service-meta)
21. [Server-Sent Events (SSE)](#21-server-sent-events-sse)
22. [Audit Model](#22-audit-model)
23. [Merge Patch Semantics](#23-merge-patch-semantics)
24. [Status-Graph Caveat](#24-status-graph-caveat)
25. [Known Limitations](#25-known-limitations)

---

## 1. Authentication

> **A `Host` header allowlist runs before any of this.** Every HTTP request — `/mcp` and every `/api/v1` route alike — is checked against a `Host` allowlist (DNS-rebinding protection) before authentication is even evaluated. A request with a disallowed `Host` gets `403 host_not_allowed` on `/api/v1` routes (`/mcp` returns a JSON-RPC error instead) regardless of `Authorization` or auth mode. A request with no `Host` header at all is allowed, since browsers always send one. The always-allowed defaults are `localhost`/`127.0.0.1`/`[::1]` (any port); `MCP_ALLOWED_HOSTS` extends that set. See [fleet-deployment.md](fleet-deployment.md) and §6.

All `/api/v1/*` endpoints require authentication by default. The API supports three modes, selected by `API_AUTH_MODE`:

### Bearer Mode (`API_AUTH_MODE=bearer`)

Present a static token in the `Authorization` header:

```
Authorization: Bearer <token>
```

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
- `capabilities` — list of granted operations (see §2)
- `scope` — optional; when present must be a mapping with only `root_ids` and/or `tags_include`
  keys (any other key, including a typo, fails startup)
  - `scope.root_ids` — a list of root-item UUID strings, or absent/`null` for unrestricted access.
    An empty list `[]` is **rejected at startup** — root scope is the isolation boundary, so `[]`
    cannot mean "every root"; omit the key (or set it `null`) instead.
  - `scope.tags_include` — a list of non-blank tag strings, or absent/`null`/`[]` for no tag
    constraint (`[]` is the canonical unrestricted form here, unlike `root_ids`)
  - Any malformed shape (a scalar instead of a list, a non-string or blank element, an unquoted
    YAML boolean like `yes`/`on`) fails startup with `IllegalArgumentException` naming the token id
    and the key path — never silently falls back to unrestricted
  - A token entry itself only accepts the keys `id`, `description`, `token_sha256`, `expires_at`,
    `scope`, `capabilities` — an unrecognized entry key (e.g. a `scopes:` typo) also fails startup
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

Present a JWT in the `Authorization: Bearer` header. The server validates the JWT against the JWKS endpoint configured by `API_JWKS_URL`. Claims extracted: `iss`, `aud`, `sub`, `exp`, `nbf`. `exp` is **required** — a JWT with no `exp` claim is rejected with `401 invalid_token`; there is no max-lifetime knob to accept exp-less tokens instead.

The principal's `tokenId` is the JWT's `sub` claim. Scope and capabilities are derived from two
custom claims the issuer sets:

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

**Failing requests receive:**
- `401 Unauthorized` + `WWW-Authenticate: Bearer error="invalid_request"` — missing token
- `401 Unauthorized` + `WWW-Authenticate: Bearer error="invalid_token"` — bad/expired token
- `403 Forbidden` — token valid but lacks required capability

**`degradedModePolicy` interaction (JWKS mode):** When `DEGRADED_MODE_POLICY=reject` and JWKS verification fails, write endpoints return `401` with error `verification_failed`. Read endpoints and the bearer mode are unaffected (bearer auth has no JWKS chain).

### Unauthenticated Mode (`API_AUTH_MODE=none`, opt-in)

**Two signals are required together** — this is a deliberate two-key opt-in, not a single flag:

```
API_AUTH_MODE=none
API_ALLOW_UNAUTHENTICATED=true
```

Setting `API_AUTH_MODE=none` alone still fails startup with an error naming the confirm flag. Setting `API_ALLOW_UNAUTHENTICATED=true` alone (with `bearer`/`jwks`) is silently ignored — it only takes effect combined with `none`.

When both are set, every request — with or without an `Authorization` header — is attached a synthetic principal with `ADMIN` capability (implies all others) and unrestricted scope (`root_ids: null`). No token is required or checked. Audit records attribute writes to the actor `api:local-unauth`.

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
| `WRITE_ITEMS` | `write-items` | `POST /items`, `PATCH /items/{id}`, `DELETE /items/{id}` |
| `WRITE_NOTES` | `write-notes` | `PUT /items/{id}/notes/{key}`, `DELETE /items/{id}/notes/{key}` |
| `ADVANCE` | `advance` | `POST /items/{id}/advance` |
| `MANAGE_DEPENDENCIES` | `manage-dependencies` | `POST /dependencies`, `DELETE /dependencies/{id}` |
| `WRITE_CONFIG` | `write-config` | `PUT /roots/{rootId}/config`, `DELETE /roots/{rootId}/config`, `PUT /roots/{rootId}/plans/{slug}` |
| `ADMIN` | `admin` | Implies all of the above; unlocks attribution fields in responses |

**`ADMIN` unlocks:** When a caller has `ADMIN`, note and transition `actor`/`verification` fields are included in responses (subject to `API_REDACT_*` env flags). Non-admin callers always receive `null` for these fields.

---

## 3. Scope Filtering

Tokens with `scope.root_ids` set can only access items within those root subtrees. Scope enforcement walks the item's full ancestor chain — an item is accessible if any ancestor (including itself) is in the scope set.

Tokens with `scope.tags_include` set are additionally restricted to items carrying at least one of
the listed tags. Unlike `root_ids`, tag scope applies **item-level only** — there is no ancestor
walk; the item's own `tags` column is checked against the (CSV-trimmed) allowlist for exact
membership. An empty `tags_include` means no tag constraint. Both scope halves are enforced by the
same `allowsItemTags`/`enforceScopeForItem` predicates, so single-item and collection checks cannot
drift apart.

- `GET /items` — scope applied at SQL level via `findInScope` for `root_ids`; a `tags_include`
  filter is then applied on top (see the pagination caveat below)
- `GET /items/{id}` — `403 scope_forbidden` if item is outside scope (either `root_ids` or
  `tags_include`)
- `GET /items/{id}/gate` — same `enforceScopeForItem` check as `GET /items/{id}`: `403
  scope_forbidden` if the item is outside scope (either `root_ids` or `tags_include`)
- Write endpoints — `403 scope_forbidden` if the target item is outside scope
- `GET /items/{id}/breadcrumbs` — chain is truncated at the caller's scope root (ancestors above the
  scope root are hidden); ancestors that fail `tags_include` are dropped from the returned chain
- `GET /items/{id}/tree` — paginated flat list; scope check applies on the root item only
- `GET /notes/search` and `GET /search` — `?ancestorId` is validated against the principal's scope;
  a `tags_include` token also has its search hits filtered to items carrying an allowed tag
- `GET /transitions` and `GET /items/{id}/breadcrumbs` — a `tags_include` token sees only rows for
  items it is allowed to see; `GET /items/{id}?include=children` is filtered the same way
- `GET /items/{id}/dependencies` and `GET /items/{id}/backlinks` — the subject item's own scope
  check is unchanged (`403 scope_forbidden` if the subject itself is out of scope); the
  *counterparty* item on each edge/backlink is additionally checked against `root_ids` and
  `tags_include`, and a row whose counterparty is out of scope is dropped from the response body
  (`200 OK` with a filtered, possibly-empty collection — never `403` for the counterparty side)
- `GET /api/v1/events` (SSE) — a `tags_include` token is enforced per-event, identically for the
  live stream and Last-Event-ID replay (see §21)
- `GET /items/{id}/children` — the parent gets the same `enforceScopeForItem` check as any other
  single item; a `tags_include` token additionally has the returned children filtered to items
  carrying an allowed tag (see the pagination caveat below — filtering happens before paging)

**Creating or moving an item to root level is scope-checked too.** A newly created item's ancestor
chain is only its own (server-generated) id, so it can never already be listed in a `root_ids`
allowlist — `POST /items` with `parentId` absent or `null` returns `403 scope_forbidden` for any
token with a non-null `scope.root_ids`. A `tags_include`-only token may create a root item only if
the tags it is creating that root *with* satisfy its own `tags_include` allowlist (the new root is
its own anchor, the same way an existing parent's tags anchor a non-root create) — otherwise `403
scope_forbidden`, and nothing is persisted. Symmetrically, `PATCH /items/{id}` that changes
`parentId` from non-null to `null` (move to root) returns `403 scope_forbidden` when the caller has
a non-null `scope.root_ids` and `id` itself is not in that set — after the move the item's chain is
just itself. (When `id` is itself a listed root the move is allowed; such a caller may already
`DELETE` that item, so this grants nothing new.) The tag half of a move-to-root is the existing
entry-point check against the item's tags as they are before the patch.

**A collection endpoint never turns a tag-scope mismatch into `403`.** Unlike the single-item case,
a tag-scoped caller whose scope matches nothing on a collection response (`GET /items`,
`GET /items/roots`, breadcrumb ancestors, search hits, `GET /transitions`, inline `children`) gets
`200 OK` with an empty (or partially filtered) result — `403 scope_forbidden` is reserved for the
single-item case where an out-of-scope item is named directly.

**Pagination under `tags_include`.** The filter has no SQL form, so `GET /items`,
`GET /items/roots` and `GET /items/{id}/children` read a bounded candidate window (1000 rows, offset
0) for a tag-scoped caller, filter it by `tags_include`, and paginate the *filtered* result in
memory. `totalItems` in that case counts only the visible (post-filter) items, not the raw candidate
window or an unfiltered DB count — a tag-scoped caller with more than 1000 matching candidates will
see a short/incomplete page. This mirrors the existing `GET /items/{id}/tree` shape and only applies
to tag-scoped principals; unscoped and `root_ids`-only callers keep SQL-level `LIMIT`/`OFFSET` and a
DB `COUNT` and are unaffected.

---

## 4. ETag Concurrency

### Item and Note ETags

- Format: `"v1-<modifiedAtMillis>"` (quoted string per HTTP spec)
- Items: ETag is derived from `item.modifiedAt`
- Notes: ETag is derived from `note.modifiedAt`
- Returned in `ETag` response header on all successful reads and writes

**Conditional reads:** `If-None-Match: <etag>` → `304 Not Modified` when the resource has not changed.

**Conditional writes:**
- `PATCH /items/{id}` — **requires** `If-Match` header. Missing header → `400 precondition_required`. Mismatch → `412` with error `etag_mismatch`.
- `DELETE /items/{id}` — **optional** `If-Match`. When supplied and mismatched → `412 etag_mismatch`.
- `PUT /items/{id}/notes/{key}` — `If-Match` accepted on the update path (when the note already exists). Missing on update is allowed; mismatch → `412 etag_mismatch`. On create (note does not exist), `If-Match` is ignored.

**`GET /items/{id}/gate` carries no `ETag` and ignores `If-None-Match`.** This is deliberate, not an
omission: gate status depends on the item's notes and its resolved schema/config, and neither is
versioned by `item.modifiedAt` — an `ETag` derived from the item alone would go stale the moment a
note is upserted or the config changes, without the item itself being touched.

### Config ETags

Config/schema endpoints (`/config`, `/config/schemas`, etc.) use a fingerprint-based ETag:
- Format: `"cfg-<fingerprint>"` where fingerprint is a SHA-256 hex digest computed once, at process startup, over the exact bytes parsed from the global config file — not a fresh re-read of the file on each request, so it is stable for the life of the process even if the file changes on disk (restart to pick up new bytes; there is no lastModified/size fallback)
- Stable across reads when the config has not changed
- `If-None-Match` → `304` when fingerprint matches

The per-root project config endpoints (§18, `/roots/{rootId}/config`) use the SAME `"cfg-<fingerprint>"`
format, but the fingerprint is a SHA-256 over the stored `configYaml`'s raw UTF-8 bytes (see
`SQLiteProjectConfigRepository.computeFingerprint`) rather than the global config file. `PUT` additionally
accepts `If-Match` for optimistic-concurrency writes (see §18).

---

## 5. Idempotency

`POST /items`, `PATCH /items/{id}`, and `PUT /items/{id}/notes/{key}` support idempotency via the `Idempotency-Key` header:

```
Idempotency-Key: <UUID>
```

- Must be a valid UUID
- Malformed key → `400 bad_request`
- On retry with the same key: the cached response (status + body) is returned verbatim without re-executing the operation
- Cache is keyed by `(actor-id, idempotency-key)`; TTL is ~10 minutes
- ETag pre-conditions are evaluated and stored as part of the cached response — a replay does NOT re-evaluate the ETag against the now-mutated resource
- **Replay contract is pinned to the key alone — there is no body hash.** A retry with the *same* key returns the first request's captured response verbatim even if the retry's body differs; only the `(actor-id, idempotency-key)` pair distinguishes requests. Concurrent requests carrying the same key coalesce onto one in-flight computation — the first caller executes it, later callers block on and receive the same result, and different keys never serialize against each other. If the computation throws, nothing is cached and the next request with that key computes again.
- This cache instance is shared with the idempotent MCP tools (keyed by `requestId`, e.g.
  `advance_item` — see `api-reference.md`) as well as these REST routes — the blast radius of the
  cache is cross-surface, though a collision needs a matching `(actor-id, key)` pair on both sides.
- `POST /items` and `PUT /items/{id}/notes/{key}` additionally require `Content-Type: application/json` (an absent header is treated as `*/*` and accepted); any other value → `415 unsupported_media_type` before the idempotency key or body is read.
- Every write route now bounds its body read to a fixed byte limit BEFORE buffering it: a `Content-Length` over the limit is rejected with `413 payload_too_large` before any bytes are touched, and a chunked/understated-`Content-Length` body is caught by a channel read capped at `limit + 1` bytes, so an oversized body is never buffered in full either way. `POST /items`, `PATCH /items/{id}`, `POST /items/{id}/advance`, `PUT /items/{id}/notes/{key}`, and `POST /dependencies` share a 1 MiB limit (previously unbounded); `PUT /roots/{rootId}/config` (128 KiB) and `PUT /roots/{rootId}/plans/{slug}` (64 KiB) keep their existing numeric limits, now enforced at the same pre-buffer point instead of after a full read. See §6 for the `payload_too_large` error shape.

---

## 6. Error Codes

All error responses use:

```json
{
  "error": "<machine-readable-code>",
  "message": "<human-readable description>",
  "details": { ... }  // optional structured context
}
```

| `error` value | Typical HTTP status | Description |
|--------------|---------------------|-------------|
| `host_not_allowed` | 403 | The request's `Host` header isn't `localhost`/`127.0.0.1`/`[::1]` (any port) or listed in `MCP_ALLOWED_HOSTS` — DNS-rebinding protection, checked ahead of authentication on every route. Never discloses the rejected `Host` value; see §1. |
| `bad_request` | 400 | Missing or malformed path/query parameter |
| `validation_error` | 400 | Invalid field value or deserialization failure; or (SSE-specific) `GET /api/v1/events` was called with a `?root=` query parameter that yields no valid UUID (see §21) |
| `precondition_required` | 400 | `PATCH` missing required `If-Match` header |
| `not_found` | 404 | Item, note, or dependency not found |
| `scope_forbidden` | 403 | Item exists but is outside the caller's scope; also returned for `POST /items` creating a root item, or `PATCH /items/{id}` moving an item to root, when the resulting root-level item would be outside the caller's scope (see §3) |
| `field_not_patchable` | 400 | PATCH attempted on a server-owned field |
| `cycle_detected` | 400 | Dependency would create a cycle |
| `unsupported_media_type` | 415 | Wrong `Content-Type` for PATCH (see §23), or a non-JSON `Content-Type` on `POST /items`, `PUT /items/{id}/notes/{key}`, `POST /items/{id}/advance`, or `POST /dependencies` (see §5) |
| `etag_mismatch` | 412 | `If-Match` header does not match current ETag |
| `payload_too_large` | 413 | Request body exceeds its route's byte limit — the `Content-Length` header alone if it declares a size over the limit (body untouched), otherwise the actual bytes read, capped at `limit + 1` so an oversized body is never buffered in full. `POST /items`, `PATCH /items/{id}`, `POST /items/{id}/advance`, `PUT /items/{id}/notes/{key}`, and `POST /dependencies` share a 1 MiB limit; `PUT /roots/{rootId}/config` is 128 KiB; `PUT /roots/{rootId}/plans/{slug}` is 64 KiB (see §18, §19). |
| `version_conflict` | 409 | `PATCH /items/{id}`: `If-Match` matched at read time, but a concurrent writer's update won the version race before this write committed — optimistic-lock loss, distinct from `etag_mismatch`. Retry with a fresh `If-Match` ETag. |
| `unauthenticated` | 401 | No authenticated principal (missing/invalid token) |
| `verification_failed` | 401 | JWKS verification failed under `reject` policy |
| `insufficient_capability` | 403 | Caller's token lacks a capability required by the request itself (distinct from `scope_forbidden`'s root-scope check) — e.g. a non-ADMIN caller sets `overrideResourceLeases: true` on `POST /items/{id}/advance`, or calls `DELETE /api/v1/resources/leases/{key}` without `ADMIN` |
| `insufficient_scope` | 403 | A generic `requireCapability` check failed for the plugin's configured capability; (SSE-specific) a `GET /api/v1/events` connection carries a `tags_include` scope but the route has no `WorkItemRepository` wired to filter by it — fail-closed rather than serving an unfiltered stream; or (SSE-specific) a root-scoped principal's `?root=` values do not intersect its token's `scope.rootIds` — the requested roots are entirely outside scope (see §21) |
| `transition_failed` | 422 | Role transition rejected (invalid trigger, gate failure, dependency blocker) |
| `resource_unavailable` | 409 | Resource-lease gate contention on `POST /items/{id}/advance` into WORK — transient, retryable. Carries a `Retry-After` header and `details.contendedResources`/`details.retryAfterMs`. Never discloses the current holder. |
| `config_unavailable` | 503 | Per-root config read failed (a transient database error) and there was no last-known-good cached config to serve for that root — transient, retryable; the caller applies its own backoff (no `Retry-After` header). Returned by `POST /items/{id}/advance` and `GET /items/{id}/gate` (see §9, §10). |
| `db_error` | 500 | Database query failed |

---

## 7. Pagination

List endpoints return a `PageDto<T>`:

```json
{
  "items": [...],
  "page": 1,
  "pageSize": 50,
  "totalItems": 42,   // may be null when count is expensive
  "hasMore": true,
  "skipped": 1         // omitted when 0/null — see below
}
```

Query parameters: `?page=<int>` (default 1, must be an integer in `1..100000`) and `?pageSize=<int>` (default 50, must be a positive integer; values above 200 are silently capped at 200). A missing or blank `page`/`pageSize` falls back to its default; a non-integer, `page < 1`, or `page > 100000` value returns `400 validation_error` (`{"error":"validation_error","message":"page must be an integer between 1 and 100000"}` or the equivalent `pageSize must be a positive integer` message) instead of being silently clamped.

`totalItems` may be `null` for endpoints where computing an exact count is expensive; use `hasMore` for continuation.

`skipped` counts rows in this page's underlying query window that were dropped because they failed domain validation (e.g. a corrupt/legacy row) — the row is excluded from `items` but still counted in `totalItems`. The field is omitted entirely when nothing was skipped. Invariant: `items.size == min(pageSize, totalItems - offset) - skipped` (clamped to the actual window), so truncation is always derivable from `totalItems`/`pageSize`/`page` without a separate `truncated` field. Currently populated on `GET /items` and `GET /items/roots`; other list endpoints report `skipped: null`.

---

## 8. Data Transfer Objects (DTOs)

### ItemDto

```json
{
  "id": "<uuid>",
  "parentId": "<uuid>|null",
  "title": "string",
  "description": "string|null",
  "summary": "string",
  "type": "string|null",
  "role": "queue|work|review|terminal|blocked",
  "previousRole": "queue|work|review|null",
  "statusLabel": "string|null",
  "priority": "HIGH|MEDIUM|LOW|CRITICAL|BACKLOG",
  "complexity": 5,
  "requiresVerification": false,
  "tags": ["string"],
  "properties": {},
  "createdAt": "ISO-8601",
  "modifiedAt": "ISO-8601",
  "roleChangedAt": "ISO-8601",
  "etag": "\"v1-<millis>\"",
  "depth": 0,
  "isClaimed": false,
  "notes": null,       // populated when ?include=notes
  "children": null,    // populated when ?include=children
  "dependencies": null // populated when ?include=deps
}
```

**Notes on `tags`:** The domain stores tags as a comma-separated string. The REST API expands this to a `List<String>` in `ItemDto` (GET responses). However, `POST /items` accepts `tags` as a JSON array (`List<String>`), while `PATCH /items/{id}` requires `tags` as a **comma-separated string** (e.g., `"feature,auth"`) — this mirrors the domain storage format. Sending a JSON array in a PATCH body will result in a `400 validation_error`.

### NoteDto

```json
{
  "key": "implementation-notes",
  "role": "queue|work|review",
  "body": "string",
  "createdAt": "ISO-8601",
  "modifiedAt": "ISO-8601",
  "etag": "\"v1-<millis>\"",
  "actor": null,        // null unless caller has ADMIN + redaction disabled
  "verification": null  // null unless caller has ADMIN + redaction disabled
}
```

### ActorClaimDto

```json
{
  "id": "api:dashboard-editor",
  "kind": "orchestrator|subagent|user|external",
  "parent": "string|null",
  "proof": null  // null unless caller has ADMIN and ?include=proof
}
```

For REST API writes, `id` is always `"api:<tokenId>"` and `kind` is always `"external"` (server-synthesized; client-supplied actor fields are silently dropped).

### VerificationDto

```json
{
  "status": "unverified|verified|unavailable|unchecked",
  "verifier": "api-bearer|api-jwks|null",
  "reason": "string|null"
}
```

### RoleTransitionDto

```json
{
  "id": "<uuid>",
  "itemId": "<uuid>",
  "fromRole": "queue|null",
  "toRole": "work",
  "trigger": "start",
  "statusLabel": "string|null",
  "occurredAt": "ISO-8601",
  "actor": null,        // redacted unless ADMIN
  "verification": null, // redacted unless ADMIN
  "consumedCredentials": ["vault:prod-db-password"] // nullable; omitted when empty
}
```

`consumedCredentials` is the audit trail of opaque credential/resource-lease labels this transition
consumed — caller-supplied `credentialRefs` from the advance request, unioned with any keys the
resource-lease gate auto-derived (held exclusive leases + advisory-mode declarations) when the item
declares `resources:`. **Not subject to `API_REDACT_NOTE_ATTRIBUTION`** — unlike `actor`/`verification`
on this same DTO, it is visible to any caller with `READ` capability, regardless of `ADMIN` status;
the field's purpose is external verifiability, so it is deliberately not redacted.

### DependenciesDto

```json
{
  "blocks": [<DependencyEdgeDto>],
  "blockedBy": [<DependencyEdgeDto>],
  "related": [<DependencyEdgeDto>]
}
```

### DependencyEdgeDto

```json
{
  "id": "<uuid>",
  "fromItemId": "<uuid>",
  "toItemId": "<uuid>",
  "type": "blocks|is_blocked_by|relates_to",
  "unblockAt": "queue|work|review|terminal|null",
  "createdAt": "ISO-8601"
}
```

`type` includes `is_blocked_by` on reads (a row created via MCP `manage_dependencies` can be stored
with that type; REST create only accepts `blocks`/`relates_to` — see POST /dependencies below).
`unblockAt` is the effective unblock-role threshold: `null` only for `relates_to` (no blocking
semantics); for `blocks` and `is_blocked_by` it is the stored value, defaulting to `"terminal"` when
unset — never a raw possibly-null passthrough.

### BacklinkDto

```json
{
  "fromItemId": "<uuid>",
  "fromTitle": "string",
  "type": "blocks|relates_to"
}
```

### GateStatusDto

```json
{
  "canAdvance": false,
  "phase": "work",
  "missing": ["implementation-notes"]
}
```

`phase` is the item's CURRENT role, lowercased. `missing` is the required-note KEY strings (schema
order) still unfilled for `phase` — plain strings, never `{key, description, ...}` objects.

### ItemGateDto

```json
{
  "itemId": "<uuid>",
  "title": "string",
  "role": "work",
  "gateStatus": <GateStatusDto>,
  "guidanceKey": "implementation-notes",
  "skillPointer": "migration-review"
}
```

Response DTO for `GET /items/{id}/gate` (§9) — field-for-field identical to `get_context` item
mode's `gateStatus`/`guidanceKey`/`skillPointer` (see `api-reference.md:1458-1475`). `guidanceKey`
and `skillPointer` are the FIRST missing required note's guidance key / skill pointer for the
current phase — omitted from JSON (not `null`) when there is none, same `explicitNulls=false` rule
as every other DTO in this document.

### PageDto\<T\>

See §7.

### ErrorDto

See §6.

### SearchHitDto

```json
{
  "itemId": "<uuid>",
  "field": "title|summary|body",
  "snippet": "<marked snippet>",
  "score": 0.032,
  "noteKey": "implementation-notes"  // null for item hits, set for note hits
}
```

### Config DTOs

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
this entry; omitted otherwise. It appears wherever a `NoteSchemaEntryDto` is returned — `GET
/config`, `/config/schemas`, `/config/schemas/{type}`, and `/config/traits`.

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

**SchemaDto:**
```json
{
  "type": "feature-task",
  "lifecycleMode": "auto",
  "hasReviewPhase": true,
  "notes": [<NoteSchemaEntryDto>],
  "defaultTraits": ["needs-security-review"]
}
```

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
the trait declares neither. **Global config only:** every `/config*` route resolves against the
server-wide schema service, not any per-root pushed config, so a per-root `dispatch`/`resources`
override on a trait of the same name is not reflected here — resolve the per-root-aware value via
`query_items(operation="schema", itemId=...)` (MCP) instead; that path applies the item's own
`rootId` automatically, so no `rootId` parameter is needed (or honored) there.

**ConfigSnapshotDto:**
```json
{
  "schemas": [<SchemaDto>],
  "traits": [<TraitDto>],
  "types": ["feature-task", "bug"],
  "statusGraph": <StatusGraphDto>,
  "defaultSchema": <SchemaDto>|null
}
```

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

**StatusGraphDto:**
```json
{
  "roles": ["queue", "work", "review", "blocked", "terminal"],
  "triggers": ["start", "complete", "block", "hold", "resume", "cancel", "reopen"],
  "types": [<StatusGraphTypeDto>]
}
```

**ProjectConfigResponseDto** (see §18):
```json
{
  "rootId": "550e8400-e29b-41d4-a716-446655440000",
  "fingerprint": "e3b0c44298fc1c14...",
  "updatedAt": "2026-07-15T19:00:00Z",
  "configYaml": "work_item_schemas:\n  ...",
  "warning": "string|null",
  "relation": "current|superseded|unknown|null",
  "ignoredSections": ["actor_authentication"],
  "schemaWarnings": ["Schema 'feature-task' entry[2] (key='spec') has invalid role 'plan' (valid: queue, work, review); skipping"]
}
```
`configYaml` is populated on `GET` only (omitted on `PUT`). `warning` is populated only when the
root's `type` is not `"project"` (non-fatal — the push still succeeds). `relation` is populated on
`GET` only, and only when `?fingerprint=` was supplied — see §18. `ignoredSections` is populated on
`PUT` only, and only when the pushed document contains top-level keys the per-root resolution layer
does not honor (e.g. `actor_authentication`, which stays global-only) — omitted entirely when empty.
`schemaWarnings` is populated on `PUT` only, and only when `YamlSchemaParser` produced per-entry
warnings while parsing the pushed document (e.g. an invalid note `role`) — omitted entirely when
empty; the push still succeeds and the config is still stored regardless of `schemaWarnings`. See
§18 for the full list of honored per-root keys.

**PlanDocumentResponseDto** (see §19):
```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "rootId": "550e8400-e29b-41d4-a716-446655440000",
  "slug": "auth-redesign",
  "contentHash": "e3b0c44298fc1c14...",
  "status": "pending",
  "adoptedByItemId": "string|null",
  "createdAt": "2026-07-15T19:00:00Z",
  "updatedAt": "2026-07-15T19:00:00Z",
  "body": "string|null"
}
```
`body` is populated on `GET` only (omitted on `PUT`). `adoptedByItemId` is populated once `status`
is `"adopted"`; null while `"pending"`, and null again if the adopting item is later deleted
(`ON DELETE SET NULL` — the document's own `status` does not revert).

**PlanDocumentSummaryDto** — one row of `PlanDocumentListResponseDto.plans` (see §19); same shape as
`PlanDocumentResponseDto` minus `body`, which is never included in list responses.

**PlanDocumentListResponseDto** (see §19):
```json
{
  "rootId": "550e8400-e29b-41d4-a716-446655440000",
  "plans": [<PlanDocumentSummaryDto>]
}
```

### ResourceLeaseDto (see §14)

```json
{
  "resourceKey": "staging-db",
  "holderItemId": "<uuid>",
  "acquiredByActorId": "agent-worker-42",  // ADMIN callers only — omitted (not null) otherwise
  "acquiredAt": "ISO-8601",
  "expiresAt": "ISO-8601",
  "originalAcquiredAt": "ISO-8601"
}
```

`acquiredByActorId` is present only when the caller has `ADMIN` capability; non-admin `READ` callers
receive the object without this field at all (server-wide `explicitNulls=false` — omitted, not
serialized as `null`). `holderItemId` (the exclusivity subject — see §14) is visible to any `READ`
caller; it carries no more information than an item ID already readable via `GET /items/{id}`.

### ResourceLeaseListResponseDto

```json
{ "leases": [<ResourceLeaseDto>] }
```

### ResourceLeaseReleaseResponseDto

```json
{ "resourceKey": "staging-db", "releasedCount": 1 }
```

### ResourceLeaseIntervalDto (see §14)

```json
{
  "resourceKey": "staging-db",
  "holderItemId": "<uuid>",
  "acquiredByActorId": "agent-worker-42",  // ADMIN callers only — omitted (not null) otherwise
  "acquiredAt": "ISO-8601",
  "expiresAt": "ISO-8601",
  "releasedAt": "ISO-8601",                // null (omitted) while the interval is still open
  "releaseReason": "released",             // "released" | "expired" | "force_released"; null while open
  "releasedByActorId": "admin-token-id"    // ADMIN callers only; null for a passive "expired" close
}
```

One row per lease hold INTERVAL, not per lease event — append-only, never pruned in v1. See §14.

### ResourceLeaseHistoryResponseDto

```json
{ "intervals": [<ResourceLeaseIntervalDto>] }
```

### SSE / ApiEvent

```json
{
  "id": 42,
  "event": "item.updated",
  "itemId": "<uuid>|null",
  "modifiedAt": "ISO-8601|null",
  "newRole": "work|null"
}
```

---

## 9. Endpoints — Items (Read)

All require `READ` capability.

### GET /items

Paginated list of work items with optional filters.

**Query parameters:**

| Parameter | Type | Description |
|-----------|------|-------------|
| `page` | int | Page number (default 1, `1..100000`) |
| `pageSize` | int | Items per page (default 50, max 200) |
| `role` | string | Filter by role: `queue`, `work`, `review`, `terminal`, `blocked` |
| `priority` | string | Filter by priority: `HIGH`, `MEDIUM`, `LOW`, `CRITICAL`, `BACKLOG` |
| `tag` | string | Comma-separated tags; all listed tags must be present (AND match) |
| `tagAny` | string | Comma-separated tags; any listed tag must be present (OR match). Overrides `tag` when both present. |
| `type` | string | Filter by item type |
| `parentId` | UUID | Filter to direct children of this parent |
| `rootId` | UUID | Filter to items within this root's subtree (intersected with principal scope) |
| `modifiedAfter` | ISO-8601 | Modified after this timestamp |
| `modifiedBefore` | ISO-8601 | Modified before this timestamp |
| `createdAfter` | ISO-8601 | Created after this timestamp |
| `createdBefore` | ISO-8601 | Created before this timestamp |
| `claimStatus` | string | Filter by claim state: `claimed`, `unclaimed`, `expired` |
| `orderBy` | string | Sort field |
| `orderDir` | string | Sort direction: `asc`, `desc` |

**Response:** `200 OK` → `PageDto<ItemDto>` (see §7 for `skipped` semantics; populated on the unscoped branch)

### GET /items/roots

Root-level items (depth=0) accessible to the caller.

- Scoped tokens: returns only the specific root items within scope (efficient, not capped); `totalItems` is the exact scoped root count.
- Unscoped/admin: fully paginated via standard `page`/`pageSize` params — `totalItems` comes from an exact `COUNT` query, unaffected by `pageSize` or by rows dropped for failing domain validation. There is no cap; page through all roots with repeated requests.

**Query parameters:** Standard pagination params (`page`, `pageSize`).

**Response:** `200 OK` → `PageDto<ItemDto>` (see §7 for `skipped` semantics; populated on the unscoped branch)

### GET /items/{id}

Single item by UUID.

**Query parameters:**
- `include` — comma-separated list: `notes`, `deps`, `children` — inline related data

**Responses:**
- `200 OK` → `ItemDto`
- `304 Not Modified` — when `If-None-Match` matches current ETag
- `400 bad_request` — invalid UUID
- `403 scope_forbidden`
- `404 not_found`

### GET /items/{id}/tree

Descendant tree, paginated as a flat list (root item always included as first element).

**Query parameters:**
- `depth` — maximum relative depth from the root item (optional)
- Standard pagination params

**Response:** `200 OK` → `PageDto<ItemDto>`

### GET /items/{id}/breadcrumbs

Ancestor chain from root to the target item (inclusive). Chain is truncated at the caller's scope root for scoped tokens.

**Response:** `200 OK` → `List<ItemDto>` (root-first, target last)

### GET /items/{id}/children

Direct children of an item, paginated.

For a `tags_include`-scoped caller, children are filtered by tag before being paginated (bounded
candidate window; see §3's pagination caveat) — `totalItems` is the filtered count, not a raw DB
`COUNT`. Unscoped and `root_ids`-only callers keep SQL-level `LIMIT`/`OFFSET` and an exact `COUNT`.

**Response:** `200 OK` → `PageDto<ItemDto>`
- `403 scope_forbidden` — parent item outside scope

### GET /items/{id}/gate

Gate status for the item's current phase — field-for-field identical to the MCP `get_context`
item mode's `gateStatus`/`guidanceKey`/`skillPointer` (see
[api-reference.md](api-reference.md):1458-1475), computed via the same `resolveSchema` +
`computePhaseNoteContext` path. No dependency/blocker info and no dispatch field. Consumed by the
plugin's SubagentStop phase guard (see
[integration-guides/plugin-skills-hooks.md](integration-guides/plugin-skills-hooks.md)).

If the notes read itself fails (repository error), every required note for the current phase is
reported missing — the same fail-safe `computePhaseNoteContext` gives `get_context` when notes
cannot be loaded.

**Responses:**
- `200 OK` → `ItemGateDto` (§8) — no `ETag` header (§4)
- `400 bad_request` — invalid UUID
- `403 scope_forbidden`
- `404 not_found`
- `503 config_unavailable` — the item's per-root config could not be read and there was no
  last-known-good cached config for that root (see §6); transient, no `Retry-After` header

---

## 10. Endpoints — Items (Write)

### POST /items

Create a work item. Requires `WRITE_ITEMS`.

**Request body:**

```json
{
  "title": "string",           // required
  "description": "string",     // optional
  "summary": "string",         // optional (default empty)
  "parentId": "<uuid>",        // optional; null = root item
  "type": "string",            // optional
  "priority": "HIGH",          // optional (default MEDIUM)
  "complexity": 5,             // optional
  "requiresVerification": false, // optional (default false)
  "tags": ["feature", "auth"], // optional — JSON array
  "statusLabel": "string",     // optional
  "properties": {},            // optional — JSON object
  "metadata": "string"         // optional
}
```

**Responses:**
- `201 Created` → `ItemDto` + `ETag` header
- `400 validation_error` — invalid field values
- `400 not_found` — parentId not found
- `403 scope_forbidden` — parent outside scope; or, when `parentId` is absent/`null` (a root-level
  create), a `root_ids`-scoped token is always denied (a new item's chain can never already be in
  `root_ids`), and a `tags_include`-only token is denied unless the tags it creates the root
  *with* satisfy its own `tags_include` (see §3)
- `413 payload_too_large` — body exceeds the shared 1 MiB write-body limit (see §5, §6)
- `415 unsupported_media_type` — `Content-Type` is present and is not `application/json` (an absent header is accepted as `*/*`); checked before the `Idempotency-Key` header or body is read

Supports `Idempotency-Key` header.

**Audit:** actor is synthesized as `api:<tokenId>` / kind `external`; client-supplied `actor.*` is dropped.

### PATCH /items/{id}

JSON Merge Patch update. Requires `WRITE_ITEMS`, `If-Match`, and `Content-Type: application/merge-patch+json` (or `application/json`).

**Request body:** A partial JSON object following RFC 7396 merge-patch semantics (see §23).

**Tags deviation:** In PATCH, `tags` must be a comma-separated **string** (not a JSON array). Example: `"tags": "feature,auth"`. Sending a JSON array in PATCH → `400 validation_error`.

**Server-owned fields that cannot be patched** (→ `400 field_not_patchable`):
`id`, `role`, `previousRole`, `roleChangedAt`, `depth`, `createdAt`, `modifiedAt`, `version`, `claimedBy`, `claimedAt`, `claimExpiresAt`, `originalClaimedAt`

**Responses:**
- `200 OK` → `ItemDto` + `ETag` header
- `400 precondition_required` — `If-Match` missing
- `400 field_not_patchable` — attempted to patch server-owned field
- `403 scope_forbidden` — new parent outside scope. When the patch re-parents the item (`parentId`
  changed to a non-null value), the target parent is scope-checked the same way `POST /items`
  checks a create-time `parentId` — an existence check alone is not authorization. A `parentId`
  patch to a non-existent parent still returns `400 not_found` first; scope is checked only after
  the parent is confirmed to exist, so it never becomes an existence oracle. When the patch instead
  moves the item TO root (`parentId` changed from non-null to `null`), a `root_ids`-scoped token
  gets `403 scope_forbidden` unless the item's own id is itself in `root_ids` (see §3). The tag
  half is the entry-point `enforceScopeForItem` check, made against the item's tags as they are
  before the patch.
- `400 validation_error` — re-parent would create a cycle: `parentId` equals the item's own id
  (message: `"An item cannot be its own parent"`) or names one of the item's own descendants
  (message: `"Cannot re-parent an item under its own descendant"`). Checked with an identity
  comparison plus a full walk of the proposed parent's real ancestor chain via a single batched
  `findAncestorChains` lookup — not bounded by the parent row's denormalized `depth`, so a stale or
  incorrect `depth` value can no longer let a cycle through. **Ordering:** `404 not_found` (unknown
  parent) → `403 scope_forbidden` (parent outside scope) → this `400 validation_error`
  (self/descendant cycle) — each check runs only after the previous one passes, and nothing is
  written until all three clear.
- `500 db_error` — the ancestor-chain lookup for the cycle check failed (repository error); the
  patch fails closed with no write, rather than silently treating the failure as "no cycle found."
- `409 version_conflict` — a concurrent writer's update won the version race between the `If-Match`
  check and this request's own write. Distinct from `412 etag_mismatch` below: the ETag matched at
  read time, but the underlying row changed before this write committed. Retry with a fresh
  `If-Match` ETag.
- `412 etag_mismatch` — `If-Match` does not match
- `413 payload_too_large` — body exceeds the shared 1 MiB write-body limit (see §5, §6)
- `415 unsupported_media_type` — wrong Content-Type + `Accept-Patch: application/merge-patch+json, application/json` response header

Supports `Idempotency-Key` header.

### DELETE /items/{id}

Cascade delete (removes item and all descendants, notes, and dependencies). Requires `WRITE_ITEMS`.

**Optional:** `If-Match` header — when supplied, mismatched ETag → `412 etag_mismatch`.

**Responses:**
- `204 No Content`
- `404 not_found`
- `412 etag_mismatch`

### POST /items/{id}/advance

Trigger a role transition. Requires `ADVANCE`.

This route runs the **same advance pipeline as the MCP `advance_item` tool**, unified behind `AdvanceService`: resolve → validate dependencies → **required-note gate** → **resource-lease gate** → apply → cascade detection → unblock detection. Previously the REST path skipped the gate, cascades, and unblock detection; those are now enforced and reported.

**Request body:**
```json
{
  "trigger": "start|complete|block|hold|resume|cancel|reopen",
  "credentialRefs": ["vault:prod-db-password"],     // optional; audit labels, never secret values
  "overrideResourceLeases": false                    // optional; ADMIN-only, see below
}
```

`credentialRefs`: opaque audit labels for credentials/resources this transition consumed — max 8
entries, each 1-128 chars matching `^[a-z0-9][a-z0-9\-_./]*$`. Validated with the identical
`CredentialRefValidation` object the MCP `advance_item` tool uses, so the two surfaces reject the
same inputs. **Closed-set rule:** once the target item declares at least one resource via its traits'
`resources:` (registry state alone does not trigger this), each supplied ref must name a
declared/registered key
— an unrecognized ref is rejected with `400 validation_error` naming the ref and the known-key list.
Items with no declared resources are unaffected (an open, format-only check, unchanged from rung 1).
Declared exclusive/advisory keys are auto-recorded into `consumedCredentials` on work entry — you do
not need to repeat them.

`overrideResourceLeases`: **ADMIN-only.** When `true`, skips the resource-lease gate for this
transition entirely — no lease is taken, the gate is bypassed, not satisfied. A non-admin caller
setting this field to `true` gets **`403 insufficient_capability`**, checked before the item is even
loaded — the flag is never silently ignored. A successful override is WARN-logged (principal token
id, item id, trigger) and the persisted transition's `summary` is stamped `"(resource leases
overridden)"`. Omitted, `null`, or `false` are indistinguishable — the gate stays enforced.

**Claimed-item behavior:** The REST API bypasses MCP claim ownership — a claimed item advances successfully even if a different MCP agent holds the claim. A WARN is emitted to the server log (`API_WARN_ON_CLAIMED_ADVANCE=false` to suppress). The response does NOT disclose `claimedBy` (tiered-disclosure principle). If this (or any) transition lands the item in TERMINAL, its claim fields (`claimedBy`/`claimedAt`/`claimExpiresAt`/`originalClaimedAt`) are cleared as part of the same transition — a subsequent `reopen` always starts the item unclaimed.

**Note-gate enforcement:** When the item's schema declares required notes, the gate is enforced exactly as in MCP — a `start` requires the current phase's required notes; a `complete` requires all required notes across every phase. An advance that leaves a required note unfilled is **rejected with `422 gate_blocked`** (it no longer silently advances). The missing notes are returned in `details.missingNotes`.

**Resource-lease gate — the ownership/lease asymmetry.** REST bypasses MCP claim *ownership* for
every caller unconditionally (see above), but it does **not** bypass the resource-lease gate the
same way: leases stay enforced for every REST caller by default, and only an explicit ADMIN
`overrideResourceLeases: true` opts out. The rationale: a claim is one agent's own bookkeeping, which
an operator may reasonably overrule; a lease protects a shared *external* resource, which operator
authority over the item does not make safe to double-acquire. This is the single most surprising
behavior on this route — read it twice if you're building an operator dashboard.

Entry into WORK is gated on **both** the `start` and `resume` triggers (`resume` re-enters WORK from
BLOCKED and re-resolves/re-acquires leases against config as it stands at resume time — it is not
exempt just because the note gate's `start`/`complete` check is). `complete`, `cancel`, `block`,
`hold`, and `reopen` never acquire; every trigger that leaves WORK releases held leases inside the
same transition (best-effort — a release failure is WARN-logged and never fails the transition; the
lease TTL is the backstop).

**Contention response (`409 resource_unavailable`):**
```json
{
  "error": "resource_unavailable",
  "message": "Resource lease contended: staging-db",
  "details": {
    "targetRole": "work",
    "contendedResources": ["staging-db"],
    "retryAfterMs": 30000
  }
}
```
A `Retry-After` response header accompanies the body — whole seconds, **rounded up** from
`retryAfterMs` with a floor of 1, so a client never retries before the lease can possibly have
expired. `CORS_EXPOSE_HEADERS` includes `Retry-After` by default (see `fleet-deployment.md`) so
browser-based dashboards behind CORS can read it directly; `details.retryAfterMs` is present as a
fallback regardless. **No holder identity is ever disclosed** on this path — neither the holding
item's id nor its actor. Use `GET /api/v1/resources/leases` (§14, `ADMIN` capability for actor
identity) to diagnose a stuck lease, or `get_context(itemId=...)` on the MCP side.

An item that declares no resources (no `resources:` trait, whether or not a registry exists) can
never produce `resource_unavailable` — the gate short-circuits before any lease-repository query, so
non-adopters see byte-identical behavior to the feature's absence.

**Known REST/MCP parity gap:** a start-cascade suppressed by resource contention is recorded in
`cascadeEvents` with `"applied": false` (same as a gate-blocked cascade), but — unlike the MCP
surface, which adds `resourceBlocked`/`contendedResources` to the cascade JSON — the REST
`CascadeEventDto` does not yet carry those fields. A REST caller currently cannot distinguish a
resource-suppressed cascade from any other unapplied one by field alone; this is flagged as a small
additive follow-up, not a blocking gap (the child item's own transition still fully succeeds either
way).

**Response `200 OK`:** `AdvanceResponseDto`
```json
{
  "itemId": "<uuid>",
  "previousRole": "queue",
  "newRole": "work",
  "trigger": "start",
  "statusLabel": "string|null",
  "cascadeEvents": [
    {
      "itemId": "<uuid>",
      "title": "Parent",
      "previousRole": "work",
      "targetRole": "terminal",
      "applied": true,
      "statusLabel": "done"
    }
  ],
  "unblockedItems": [
    { "itemId": "<uuid>", "title": "Downstream" }
  ],
  "expectedNotes": [
    { "key": "implementation-notes", "role": "work", "required": true, "description": "...", "exists": false }
  ]
}
```

The `cascadeEvents`, `unblockedItems`, and `expectedNotes` fields are **additive** — they were added when the REST and MCP advance paths were unified. A gate-blocked cascade carries `"applied": false`, `"gateBlocked": true`, and a `missingNotes` array.

**Responses:**
- `200 OK` → `AdvanceResponseDto`
- `400 validation_error` — invalid trigger string, or a `credentialRefs` entry fails format/closed-set validation
- `403 insufficient_capability` — `overrideResourceLeases: true` sent by a non-ADMIN caller
- `413 payload_too_large` — body exceeds the shared 1 MiB write-body limit (see §5, §6)
- `415 unsupported_media_type` — `Content-Type` is present and is not `application/json` (an absent header is accepted as `*/*`); checked before the body is read
- `409 resource_unavailable` — resource-lease gate contention; `Retry-After` header + `details.contendedResources`/`details.retryAfterMs` (see above)
- `409 not_claim_holder` — pre-existing, defensive-only on this route (REST bypasses claim ownership by default — see "Claimed-item behavior" above); not expected to occur in normal REST usage
- `422 gate_blocked` — a required-note gate failed; `details.missingNotes` lists the unfilled required notes
- `422 transition_blocked` — a dependency blocker prevents the transition; `details.blockers` lists the blocking edges
- `422 transition_failed` — invalid state transition (resolution/apply failure)
- `503 config_unavailable` — the item's per-root config could not be read and there was no
  last-known-good cached config for that root (see §6); transient, no `Retry-After` header — the
  transition was NOT applied

**Gate-rejection example (`422`):**
```json
{
  "error": "gate_blocked",
  "message": "Gate check failed: required notes not filled for queue phase: spec",
  "details": {
    "targetRole": "work",
    "missingNotes": [
      { "key": "spec", "description": "Problem statement and approach", "guidance": "..." }
    ]
  }
}
```

The `hasReviewPhase` is resolved from the item's schema (type + tags + traits) to match `AdvanceItemTool` behavior — an advance from `work` goes to `review` when the schema has a review phase, or directly to `terminal` when it does not.

---

## 11. Endpoints — Notes (Read)

All require `READ` capability. Attribution fields (`actor`, `verification`) are `null` by default; set to non-null only when the caller has `ADMIN` and `API_REDACT_NOTE_ATTRIBUTION=false`.

### GET /items/{id}/notes

List all notes for an item.

**Query parameters:**
- `role` — filter by phase: `queue`, `work`, `review`
- `key` — filter by note key (exact match)

**Response:** `200 OK` → `List<NoteDto>`

### GET /items/{id}/notes/{key}

Single note by key.

**Response header:** `ETag: "v1-<millis>"` (for use as `If-Match` on subsequent PUT)

**Responses:**
- `200 OK` → `NoteDto`
- `404 not_found` — note not found on item

---

## 12. Endpoints — Notes (Write)

All require `WRITE_NOTES` capability.

### PUT /items/{id}/notes/{key}

Upsert (create or replace) a note. `role` and `body` are always replaced on update.

**Request body:**
```json
{
  "role": "queue|work|review",   // required
  "body": "string",              // required
  "properties": {}               // optional — reserved, currently ignored
}
```

**`If-Match` behavior:** Optional on create. On update (note already exists), supplied `If-Match` is validated; mismatch → `412 etag_mismatch`.

**Responses:**
- `201 Created` → `NoteDto` + `ETag` header (note was new)
- `200 OK` → `NoteDto` + `ETag` header (note was updated)
- `412 etag_mismatch`
- `413 payload_too_large` — body exceeds the shared 1 MiB write-body limit (see §5, §6)
- `415 unsupported_media_type` — `Content-Type` is present and is not `application/json` (an absent header is accepted as `*/*`); checked before the `Idempotency-Key` header or body is read

Supports `Idempotency-Key` header.

### DELETE /items/{id}/notes/{key}

Delete a note.

**Responses:**
- `204 No Content`
- `404 not_found` — note not found

---

## 13. Endpoints — Dependencies

### GET /items/{id}/dependencies

Returns the combined dependency view split by direction and type. Requires `READ`.

**Response `200 OK`:** `DependenciesDto` with `blocks`, `blockedBy`, and `related` arrays.

### GET /items/{id}/backlinks

Items that reference this item via any dependency edge. Requires `READ`.

**Response `200 OK`:** `List<BacklinkDto>`

### POST /dependencies

Create a dependency edge. Requires `MANAGE_DEPENDENCIES`.

**Request body:** `DependencyCreateDto`
```json
{
  "fromItemId": "<uuid>",
  "toItemId": "<uuid>",
  "type": "blocks|relates_to",
  "unblockAt": "queue|work|review|terminal|null"
}
```

Validation:
- `fromItemId` ≠ `toItemId` — `400 validation_error`
- `unblockAt` must be absent or null for `relates_to` edges — `400 validation_error`
- Both items must exist — `400 not_found`
- Both items must be in scope — `403 scope_forbidden`
- Cycle detection — `400 cycle_detected`. Runs only for `blocks` (the item that would block, `fromItemId`); `relates_to` has no blocking semantics and skips the check entirely. `is_blocked_by` is not an accepted create type over REST (see `type` above), so its reverse-direction cycle check is not exercised here — only via MCP `manage_dependencies`.

**Responses:**
- `201 Created` → `DependencyEdgeDto`
- `413 payload_too_large` — body exceeds the shared 1 MiB write-body limit (see §5, §6)
- `415 unsupported_media_type` — `Content-Type` is present and is not `application/json` (an absent header is accepted as `*/*`); checked before the body is read

### DELETE /dependencies/{id}

Remove a dependency edge by its UUID. Requires `MANAGE_DEPENDENCIES`.

Scope check: both `fromItemId` and `toItemId` of the edge must be accessible to the caller.

**Responses:**
- `204 No Content`
- `404 not_found`
- `403 scope_forbidden`

---

## 14. Endpoints — Resource Leases

Server-wide primitive backing the resource-lease gate on `POST /items/{id}/advance` (§10) and the
`resources:` trait dimension (see `config-format.md`). **Cross-project, not per-root** — unlike
every other `/api/v1` resource in this document, these routes carry no `rootId` scoping; a lease key
is a server-wide namespace (see the "global-wins" registry precedence note in `config-format.md`).

### GET /api/v1/resources/leases

Lists all currently active (unexpired) resource leases. Requires `READ`.

**Response `200 OK`:** `ResourceLeaseListResponseDto` → `{ "leases": [<ResourceLeaseDto>] }`.
`acquiredByActorId` is present per-entry only for callers with `ADMIN` capability — non-admin `READ`
callers see every other field (including `holderItemId`, which is not considered sensitive; see
`ResourceLeaseDto` in §8). No pagination — lease cardinality is bounded by design (one row per
declared resource key per active holder, TTL-bounded), not a user-facing high-cardinality collection.

### DELETE /api/v1/resources/leases/{key}

Force-releases the active lease(s) held on `{key}`. Requires `ADMIN`. This is the operator recovery
path for a crashed holder — it turns "wait out the TTL (up to 24h) or disable enforcement
server-wide" into a one-call fix.

**Path parameter:** `{key}` — validated against `^[a-z0-9][a-z0-9\-_./]*$`, max 128 chars, checked
**before** any repository access.

**Responses:**
- `200 OK` → `ResourceLeaseReleaseResponseDto` → `{ "resourceKey": "...", "releasedCount": 1 }`
- `400 validation_error` — `{key}` fails the pattern/length check
- `403 insufficient_capability` — caller lacks `ADMIN`
- `404 not_found` — no active lease exists on `{key}` (idempotent-safe: a repeat call after a
  successful release returns `404`, not a second `200`)
- `500 db_error` — repository failure

Every successful force-release is WARN-logged with the acting principal's token id, the key, and the
released count — sufficient to attribute the action to a specific bearer token from server logs
alone, without needing to correlate against the response body. The acting principal's token id is
also threaded through to `released_by_actor_id` on the closed history interval(s) — see below.

### GET /api/v1/resources/leases/history

Append-only lease-interval audit log — answers "who held resource R at time T" after a lease has
already been released, stolen, or force-released, which `GET /resources/leases` (current holders
only) cannot. Requires `READ`.

**Query parameters** (all optional):
- `key` — restrict to one resource key. Same validation as `{key}` on the DELETE route above (400
  `validation_error` on mismatch).
- `at` — an ISO-8601 instant. When present, returns every interval held at that instant (per
  `acquiredAt <= at < coalesce(releasedAt, expiresAt)`), open or closed. **400 `validation_error`**
  if the value does not parse as an instant. When absent, returns the most recent intervals
  (open or closed), newest-first by `acquiredAt`.
- `limit` — max rows, default `100`, clamped to `[1, 500]`.

**Response `200 OK`:** `ResourceLeaseHistoryResponseDto` → `{ "intervals": [<ResourceLeaseIntervalDto>] }`.
`acquiredByActorId` / `releasedByActorId` are present per-entry only for callers with `ADMIN`
capability — same inline redaction rule `GET /resources/leases` applies to `acquiredByActorId`.

No pruning/retention in v1 — the table is append-only and grows with lease-event cardinality
(documented, accepted behavior; see `current/src/main/resources/db/migration/V16__Resource_Lease_History.sql`).

---

## 15. Endpoints — Transitions (Audit)

All require `READ`. `actor` and `verification` fields are redacted (null) for non-admin callers; admin callers see them subject to `API_REDACT_NOTE_ATTRIBUTION` and `API_REDACT_ACTOR_PROOF` env vars. Proof requires `ADMIN` + `?include=proof`.

### GET /items/{id}/transitions

Per-item role-transition history (append-only audit log), paginated.

**Response:** `200 OK` → `PageDto<RoleTransitionDto>`

### GET /transitions

Recent transitions across all items. Default window: last 24 hours.

**Query parameters:**
- `since` — ISO-8601 timestamp; default: 24 hours ago
- Standard pagination params

Scope-filtered: scoped tokens only see transitions for items within their scope (ancestor-chain check).

**Scan cap:** the underlying fetch is bounded at 1000 rows (`minOf(offset + pageSize + 1, 1000)`)
regardless of how many transitions actually occurred since `since`. If more than 1000 transitions
match the window, pages beyond that cap come back truncated — `hasMore` reads `false` once the scan
limit is hit even though older matching transitions exist beyond it. This is not reflected in
`totalItems`/`hasMore` as a distinct signal, so a caller paging deep into a busy window can silently
stop short of the true history. **Narrow `since` rather than paging deeper** — a tighter time window
keeps the match count under the cap instead of walking a truncated scan.

**Response:** `200 OK` → `PageDto<RoleTransitionDto>`

---

## 16. Endpoints — Search

All require `READ`.

### GET /search

FTS5 full-text search over item titles and summaries.

**Query parameters:**
- `q` (required) — search query; special characters are auto-sanitized
- `ancestorId` — scope results to a subtree
- `role` — filter by item role
- `tag` — comma-separated tag filter

Results are ranked by RRF-fused relevance (trigram + porter tokenizer). Returns up to 50 hits.

**Response:** `200 OK` → `List<SearchHitDto>`

**H2 caveat:** Returns an empty list in test environments where the repository is H2-backed (FTS5 requires SQLite).

### GET /notes/search

FTS5 full-text search over note bodies.

**Query parameters:**
- `q` (required) — search query
- `ancestorId` — scope results to a subtree

Returns up to 50 hits. `noteKey` is populated on every hit (note-body search always has a key).

**Response:** `200 OK` → `List<SearchHitDto>`

---

## 17. Endpoints — Config / Schema Discovery

All require `READ`. All config endpoints emit a fingerprint-based ETag (`"cfg-<fingerprint>"`) and support `If-None-Match` → `304 Not Modified`.

### GET /config

Full config snapshot: all schemas, traits, types, and the status-transition graph.

**Response:** `200 OK` → `ConfigSnapshotDto`

### GET /config/schemas

All schema definitions.

**Response:** `200 OK` → `List<SchemaDto>`

### GET /config/schemas/{type}

Single schema by type name.

**Responses:**
- `200 OK` → `SchemaDto`
- `404 not_found` — unknown type

### GET /config/traits

All trait definitions.

**Response:** `200 OK` → `List<TraitDto>`

### GET /config/types

Sorted list of registered type name strings.

**Response:** `200 OK` → `List<String>`

### GET /config/status-graph

Structural role-transition graph across all schema types.

**Response:** `200 OK` → `StatusGraphDto`

See §23 for the important caveat about what the status graph does and does not reflect.

---

## 18. Endpoints — Project Config (Per-Root)

Per-root config YAML documents pushed by a client (or the fail-open SessionStart config-sync hook,
in a later phase) and layered over the global `.taskorchestrator/config.yaml` on every
schema-resolving read — see [`ProjectConfigPushService`](../src/main/kotlin/io/github/jpicklyk/mcptask/current/application/service/ProjectConfigPushService.kt)
and the MCP `manage_project_config` tool, which share the exact same validate-then-persist
pipeline as these routes (both converge on identical DB state for the same payload).

All three verbs additionally require `ApiScope.rootIds` (when scoped) to contain `{rootId}` —
`403 scope_forbidden` otherwise. `{rootId}` must be a depth-0 WorkItem UUID.

### PUT /roots/{rootId}/config

Validates and stores raw `configYaml` for `{rootId}`. Requires `WRITE_CONFIG`.

**Request body:** raw YAML text (`Content-Type: application/yaml` or `text/plain`); max 128 KiB.

**Query parameter:** `force` (boolean, default `false`) — set `?force=true` to bypass push guards;
skips both the embedded `project.rootId` mismatch check (guard 5 below) and the fast-forward
fingerprint guard (guard 6 below).

**Validation pipeline (in order, stops at first failure — nothing is written on failure):**
1. Body size ≤ 128 KiB
2. `{rootId}` resolves to an existing WorkItem
3. That WorkItem is depth-0 (configs anchor to project roots only)
4. `configYaml` parses under a `SafeConstructor` YAML load (rejects `!!`-tagged arbitrary Java
   type construction — CWE-502 — as well as ordinary syntax errors)
5. Unless `?force=true`: if the parsed document embeds a top-level `project.rootId` that parses as
   a UUID and differs from `{rootId}`, the push is rejected (an absent or non-UUID `project.rootId`
   is not an error — the push proceeds as if it were absent)
6. Unless `?force=true`: the incoming `configYaml`'s fingerprint is classified against `{rootId}`'s
   stored fingerprint history — a fast-forward (known-old) guard. A fingerprint that is
   **superseded** (present in history but not current) is rejected, since writing it would silently
   revert a later push made from elsewhere. **current** (idempotent re-push) and **unknown**
   (divergent edit, or no row/history yet) both proceed normally.
7. Optional `If-Match` (see below), evaluated against the CURRENT stored fingerprint

On success, the parsed document's top-level keys are checked against the honored allowlist —
`work_item_schemas`, `note_schemas`, `traits`, `project`, `note_limits`, `status_labels`,
`resources` — and any other key present (e.g. `actor_authentication`, which stays global-only — see
[`config-format.md`](../../claude-plugins/task-orchestrator/skills/manage-schemas/references/config-format.md))
is reported in the response's `ignoredSections` array so a push is never silently partial.
`resources` is honored with an inverted precedence versus the other six keys — global wins on a
registry key collision, not per-root — see "Per-root honorable settings" in `config-format.md`.

**Responses:**
- `200 OK` → `ProjectConfigResponseDto` (no `configYaml` field on this verb; `ignoredSections`
  and `schemaWarnings` present only when non-empty). `schemaWarnings` carries per-entry parse
  warnings from `YamlSchemaParser` (e.g. an invalid note `role` value) — the push still succeeds
  and the config is still stored even when warnings are present; only a hard parse/shape failure
  (guards 1-6 above) short-circuits the push. `ETag: "cfg-<fingerprint>"`
- `404 not_found` — `{rootId}` does not resolve to an existing WorkItem
- `422 validation_error` — `{rootId}` is not depth-0
- `422 parse_error` — `configYaml` failed SafeConstructor parse-validation
- `422 rootid_mismatch` — `configYaml` embeds a `project.rootId` differing from `{rootId}` (message
  names both ids); retry with `?force=true` to bypass
- `409 superseded` — `configYaml`'s fingerprint is known-old (guard 6 above); message names the
  server's current `updatedAt`; retry with `?force=true` to overwrite anyway. Distinct from
  `412 etag_mismatch` below — this is a known-old-**content** guard, not a concurrent-write guard.
- `412 etag_mismatch` — `If-Match` supplied and mismatched against an EXISTING row's ETag (a
  first push to a root with no prior row ignores `If-Match` — there is nothing to match yet)
- `413 payload_too_large` — body exceeds 128 KiB; enforced before the body is fully buffered (see §5)
- `403 scope_forbidden` — capability present but `{rootId}` outside token scope

### GET /roots/{rootId}/config

Reads back the stored config for `{rootId}`. Requires `READ`.

**Query parameter:** `fingerprint` (optional, SHA-256 hex digest) — when supplied, classifies it
against `{rootId}`'s stored fingerprint history and adds a `relation` field
(`"current"|"superseded"|"unknown"`) to the response body. Omitted entirely when the query
parameter is absent.

**Responses:**
- `200 OK` → `ProjectConfigResponseDto` (includes `configYaml`); `ETag: "cfg-<fingerprint>"`
- `304 Not Modified` — `If-None-Match` matches the current fingerprint ETag
- `404 not_found` — no config has ever been pushed for `{rootId}` (the WorkItem itself is not
  validated to exist — mirrors the MCP tool's `get` semantics)

### DELETE /roots/{rootId}/config

Removes the stored config row for `{rootId}`. Requires `WRITE_CONFIG`.

**Responses:**
- `204 No Content` — deleted
- `404 not_found` — no config row existed for `{rootId}`

---

## 19. Endpoints — Plan Documents (Per-Root)

Per-root plan documents — free-floating planning docs an agent stashes ahead of adoption into a
real WorkItem. See [`PlanDocumentService`](../src/main/kotlin/io/github/jpicklyk/mcptask/current/application/service/PlanDocumentService.kt)
and the MCP `manage_plan_documents` tool's `stash`/`get`/`list` operations, which share the exact
same validate-then-persist pipeline as these routes (both converge on identical DB state, including
`contentHash`, for the same payload).

All verbs additionally require `ApiScope.rootIds` (when scoped) to contain `{rootId}` —
`403 scope_forbidden` otherwise. `{rootId}` must be a depth-0 WorkItem UUID. `{slug}` is a
caller-chosen identifier, unique within `{rootId}`.

### PUT /roots/{rootId}/plans/{slug}

Validates and stores the raw request body as the `pending` document at `{rootId}`+`{slug}`.
Requires `WRITE_CONFIG`.

**Request body:** raw document text (markdown or plain text); max 64 KiB.

**Validation pipeline (in order, stops at first failure — nothing is written on failure):**
1. Body size ≤ 64 KiB
2. `{rootId}` resolves to an existing WorkItem
3. That WorkItem is depth-0 (plan documents anchor to project roots only)
4. If a document already exists at `{rootId}`+`{slug}` and its `status` is `adopted`, the stash is
   rejected — adoption is a one-way transition and cannot be overwritten. A `pending` document at
   that slug is overwritten in place (`contentHash`, `body`, and `updatedAt` are replaced; `id` and
   `createdAt` are preserved).

**Responses:**
- `200 OK` → `PlanDocumentResponseDto` (no `body` field on this verb — the caller already has it)
- `404 not_found` — `{rootId}` does not resolve to an existing WorkItem
- `422 validation_error` — `{rootId}` is not depth-0
- `409 adopted_conflict` — the slug is already `adopted` (message names the adopting item when known)
- `413 payload_too_large` — body exceeds 64 KiB; enforced before the body is fully buffered (see §5)
- `403 scope_forbidden` — capability present but `{rootId}` outside token scope

### GET /roots/{rootId}/plans/{slug}

Reads back the stored document (including its body) for `{rootId}`+`{slug}`. Requires `READ`.

**Responses:**
- `200 OK` → `PlanDocumentResponseDto` (includes `body`)
- `404 not_found` — no document exists at that slug

### GET /roots/{rootId}/plans

Lists metadata-only summaries (never the body) for every document under `{rootId}`. Requires `READ`.

**Query parameter:** `status` (optional, `pending` | `adopted`) — filters the list to a single status.
`400 bad_request` on any other value.

**Responses:**
- `200 OK` → `PlanDocumentListResponseDto` — `plans` ordered by `slug` ascending

---

## 20. Endpoints — Service Meta

### GET /api/v1/info

Requires `READ`. Returns server metadata and the caller's resolved capabilities.

**Response `200 OK`:**
```json
{
  "serverName": "mcp-task-orchestrator-current",
  "version": "3.8.0",
  "apiVersion": "v1",
  "capabilities": ["read", "write-items"],
  "claimModeAvailable": true,
  "actorAuthenticationEnabled": false
}
```

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
  "version": "3.8.0",
  "apiVersion": "v1",
  "apiUrl": "/api/v1"
}
```

---

## 21. Server-Sent Events (SSE)

### GET /api/v1/events

Real-time event stream. Requires `READ` or `ADMIN` capability.

**Delivery guarantee:** every domain event (`item.*`, `note.*`, `dependency.*`, `scope.*`) is published after the write's database transaction commits, and is dropped (never published) if that transaction rolls back. Ordering on the success path is unchanged — an event still reaches subscribers in commit order, and a caller observing a `200`/`201` response is guaranteed the corresponding event was (or imminently will be) published.

**Authentication — pre-flight plugin (important):**

Ktor's `sse {}` handler runs inside the response-body phase — after the HTTP 200 status is committed. Auth cannot be performed inside the handler itself. The SSE route uses a dedicated pre-flight plugin that checks authentication in the `Plugins` phase, before streaming begins. A failed auth check sends `401`/`403` before any SSE content is produced.

This route is exempted from the global `AuthenticationPlugin` via `publicPaths`/`publicPrefixes`
(matched against the request path only, not the full URI — so a `?token=` or any other query
string on the exempted path still matches). `CurrentMcpServer.kt` registers both `/mcp` and
`/api/v1/events` as public paths; a Ktor application plugin like `AuthenticationPlugin` is never
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
4. If none of the above apply → `401`

**Tag-scope guard:** Per-event `tags_include` filtering (below) needs the route's `WorkItemRepository`
wiring to resolve an event's item tags. If a principal's `scope.tags_include` is non-empty but no
repository is wired for the route, the pre-flight plugin rejects the connection with
`403 insufficient_scope` rather than silently serving an unfiltered stream — a fail-closed
connection-time check, distinct from the per-event filtering described below.

**Browser SSE note:** The native browser `EventSource` API cannot set custom headers. Browsers must use a fetch-based SSE client (e.g., `@microsoft/fetch-event-source`) to provide the `Authorization: Bearer` header, or enable `API_ALLOW_QUERY_TOKEN_FOR_SSE=true` to use the query-parameter path.

**Query parameters:**
- `root` (repeatable) — filter to events for items in this root's subtree. Effective subscription = intersection of `?root=` values with `principal.scope.rootIds`. Both rejections below are enforced by the pre-flight plugin — a normal HTTP status + JSON body, not an SSE stream:
  - `403 insufficient_scope` (`{"error":"insufficient_scope","error_description":"Requested roots are outside this token's scope"}`) — the principal is root-scoped (`scope.rootIds` non-null) and its `?root=` values do not intersect that scope at all.
  - `400 validation_error` (`{"error":"validation_error","error_description":"root query parameter must be a valid UUID"}`) — `?root=` is present but yields zero valid UUIDs (e.g. `?root=` with no value, or `?root=garbage`). A mix of valid and invalid values is accepted and simply narrows to the valid ones — dropping invalid entries can only narrow the subscription, never widen it. This check also fires under `API_AUTH_MODE=none`.
- `types` — comma-separated event type filter (e.g., `types=item.created,item.advanced`). Exempt from this filter: `sync.lost` and `auth.expired` (the control events, see Event Types below) are always delivered regardless of `types` — they report the state of the stream itself, and a filtered-out client would otherwise keep operating on incomplete state (or an expired credential) with no signal.

`tags_include` is not a query parameter — it is enforced from the principal's own token scope, per event, as described next.

**`Last-Event-ID` replay:** The bus maintains a ring buffer of recent events (size: `API_SSE_BUFFER_SIZE`, default 1000). On reconnect, events with `id > Last-Event-ID` are replayed before live streaming resumes. Ring-buffer entries carry `affectedRoots` metadata, so the replay path applies the **same root-intersection filter** as the live fan-out — a client reconnecting with `?root=<uuid>` receives only replayed events for roots within its subscription (and scope). Replay is consistent with the live stream.

**Unreplayable cursor → `sync.lost`:** When a reconnecting client's `Last-Event-ID` can no longer be satisfied from the ring buffer — the id is older than the oldest retained event (`buffer_evicted`), above the current high-water mark (`unknown_event_id`; typically a pre-restart cursor, since the id counter restarts at 0 on restart), or the header was present but not parseable as a number (`unknown_event_id`) — the bus emits a `sync.lost` event as the **first frame of the connection**, before any replayed or live event, carrying the cause in `reason`. Detection reads the global ring buffer, not the caller's root-scoped view, so a root-scoped client can occasionally receive a `sync.lost` for an eviction that didn't affect its own roots (a false positive costing one extra re-fetch — the alternative, a scoped check, risks a false *negative*, i.e. silent loss, which is what this exists to prevent). A blank or absent `Last-Event-ID` header is not treated as a resume attempt and never produces `sync.lost`.

**Replay resumes from the sentinel, not the client's cursor:** After either `sync.lost` reason, the same connection immediately replays the FULL retained ring buffer — not just events past the client's original `Last-Event-ID` — because the replay cursor becomes the sentinel's own `id` (always one below the oldest retained event, or the current high-water mark on an empty buffer) rather than the unusable client cursor. No second reconnect is needed to recover the buffered history. `API_SSE_BUFFER_SIZE=0` is a legal, explicit "retain nothing" setting: the buffer never accumulates entries, so every resume attempt that carries a `Last-Event-ID` yields `sync.lost` (`buffer_evicted`, sentinel id = the current high-water mark) with no events to replay after it.

**Tag scope (`tags_include`) filtering:** Root scope is applied at the bus level (above); tag scope
is enforced per-event on top of it, identically for the live stream and for `Last-Event-ID` replay,
via the same `allowsItemTags` predicate the REST collection endpoints use (§3). A connection whose
principal has no `tags_include` (or an event with no `itemId`, i.e. a bus-level event) is unaffected.
Otherwise each event's `itemId` is resolved to its current tags and checked against the allowlist;
the result is cached per connection for `item.*`/`scope.*` events (which also refresh the cache, so a
mid-stream tag change on an item takes effect immediately) and reused for `note.*`/`dependency.*`
events on the same item. See §25 for the `item.deleted` fail-closed gap this scheme has.

**Event ID namespace:** The monotonic ID counter for `/api/v1/events` is **independent** from the `/mcp` SSE channel's `EventStore`. Do NOT reuse `Last-Event-ID` values across the two channels.

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
| `sync.lost` | null | null | null | Unrecoverable gap in the stream; re-fetch full state. Carries `reason`: `queue_overflow` (the connection's per-connection queue overflowed and events were dropped mid-stream), `buffer_evicted` (resumed `Last-Event-ID` is older than the oldest event still retained in the ring buffer), or `unknown_event_id` (resumed `Last-Event-ID` is above the current high-water mark — typically pre-restart — or was not parseable as a number) |
| `auth.expired` | null | null | null | Connection's token has expired; reconnect with fresh credential |

Both `sync.lost` and `auth.expired` are **control events** — they always bypass the `?types=` filter (see Query parameters above). All other event types additionally carry a `reason` field of `null`, and (because the SSE payload is encoded with `explicitNulls = false`) it is absent from their JSON entirely rather than present as `null`.

**`item.advanced` note:** This event is emitted on role change (via `POST /items/{id}/advance` or any write path that triggers `RoleTransitionHandler`). It carries the `newRole` field. This is distinct from `item.updated` — a role change emits `item.advanced` (not `item.updated`).

---

## 22. Audit Model

All write endpoints (POST, PATCH, PUT, DELETE) synthesize an actor server-side from the authenticated principal. Client-supplied `actor.*` fields in the request body are **silently dropped** — callers cannot override audit attribution.

**Synthesized actor:**
- `id` = `"api:<tokenId>"` (e.g., `"api:dashboard-editor"`)
- `kind` = `"external"` (distinguishes API writes from MCP agent writes)
- `parent` = `null`
- `proof` = `null` (bearer token is in the HTTP header; it is not echoed into stored audit records)

**Attribution redaction (applies to notes and transitions):**
- Non-admin callers: `actor` and `verification` fields are `null` in responses
- Admin callers: fields are visible subject to `API_REDACT_NOTE_ATTRIBUTION` and `API_REDACT_ACTOR_PROOF` env vars
- `proof` within `actor`: requires `ADMIN` capability AND `?include=proof` in the request

**Redaction env vars:**
- `API_REDACT_NOTE_ATTRIBUTION` (default `true`) — when `true`, non-admin callers see no attribution
- `API_REDACT_ACTOR_PROOF` (default `true`) — when `true`, `proof` is redacted even from admin callers unless `?include=proof`

---

## 23. Merge Patch Semantics

`PATCH /items/{id}` implements [RFC 7396 JSON Merge Patch](https://datatracker.ietf.org/doc/html/rfc7396).

**Rules:**
- Fields present in the patch object with a non-null value → replace that field
- Fields absent from the patch object → unchanged
- `null` value in the patch → delete that field (clears to null/empty)
- Nested objects merge recursively; arrays replace wholesale

**Content-Type:** Must be `application/merge-patch+json` or `application/json`. Wrong type → `415` with `Accept-Patch` response header.

**Patchable fields:** `title`, `description`, `summary`, `statusLabel`, `priority`, `complexity`, `requiresVerification`, `tags` (CSV string), `type`, `properties`, `metadata`, `parentId`

**`properties` null-delete/nested-merge:** The `properties` field in the patch is merged recursively when both the base and the patch value are JSON objects. Setting a key to `null` removes it from `properties`. Setting `properties` itself to `null` clears the entire properties object.

**`tags` deviation — see §8 ItemDto section.** POST accepts a JSON array; PATCH requires a CSV string.

---

## 24. Status-Graph Caveat

`GET /config/status-graph` returns the **structural** (schema-defined) transition graph — which triggers are valid for each role per type.

This graph does NOT reflect:
- Runtime gate enforcement (note gates — required notes that must be filled before `start` succeeds)
- Dependency blockers (an item blocked by an unsatisfied dependency cannot advance)
- Claim ownership (MCP callers without claim ownership are blocked; REST API callers bypass claim ownership)
- Per-item lifecycle exceptions

Dashboard UI: do not use the status graph to pre-compute which buttons to enable. Always call `POST /items/{id}/advance` and surface the `422` error if the transition is blocked at runtime — `gate_blocked` (unfilled required note), `transition_blocked` (dependency blocker), or `transition_failed` (invalid state).

The `"<previousRole>"` sentinel in `blocked.resume` is a literal string — resolve it from the live item's `previousRole` field.

---

## 25. Known Limitations

**SSE dependency-event root resolution falls back to the database on a cold cache.** Live
root-filtering and `Last-Event-ID` replay are both correctly root-scoped — ring-buffer entries
carry `affectedRoots` metadata and the replay path applies the same root-intersection filter as the
live fan-out. `dependency.added` / `dependency.removed` resolve their affected roots from an
in-memory ancestor-root cache populated by prior item create/update writes; on a cache miss (e.g.,
a dependency change with no preceding item write on that subtree during the connection's lifetime)
root resolution now queries `findAncestorChains` directly instead of failing closed, so root-scoped
subscribers correctly receive the event as long as at least one subscriber is connected at the
moment of the dependency write (with zero subscribers connected, resolution is skipped entirely as
a performance guard — moot, since there is no one to receive it). Bus-level control events
(`sync.lost`, `auth.expired`) always broadcast to every subscriber, root-scoped included, because
they report the state of the stream itself.

**SSE tag-scope filtering has an `item.deleted` fail-closed gap.** Per-event `tags_include`
filtering (§21) resolves an event's tags by looking up its `itemId` at delivery time. For
`item.deleted`, the item is already gone by the time the event is filtered, so its tags cannot be
resolved — the event is dropped for any tag-scoped connection rather than risk showing (or hiding)
it incorrectly. Unlike the dependency-event root resolution above, which now falls back to a live
DB query on a cache miss, there is no live row left to query here for `item.deleted` — the
fail-closed drop for tag-scoped subscribers is unconditional.

**SSE honors bearer and unauthenticated modes; JWKS is untested on this route.** The pre-flight auth plugin for the SSE route resolves `Authorization: Bearer` (and, when enabled, `?token=`) the same way `ApiBearerAuth` does, and explicitly short-circuits for `API_AUTH_MODE=none` (§1/§21) exactly like the bearer route. JWKS-mode JWT authentication for SSE has not been separately verified — the pre-flight plugin's bearer-token path is what's exercised; treat JWKS+SSE as unconfirmed rather than assuming parity until it's tested.

**FTS5 requires SQLite.** Search endpoints (`GET /search`, `GET /notes/search`) return empty results when the repository is H2-backed (test/embedded environments). FTS5 is only available against the production SQLite database.

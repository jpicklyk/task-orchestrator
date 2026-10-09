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
19a. [Endpoints — Rules (Per-Root)](#19a-endpoints--rules-per-root)
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

Present a JWT in the `Authorization: Bearer` header. The server validates the JWT against the JWKS endpoint configured by `API_JWKS_URL`. Claims extracted: `iss`, `aud`, `sub`, `exp`, `nbf`, `iat` (when present). `exp` is **required** — a JWT with no `exp` claim is rejected with `401 invalid_token`. A token is also rejected once its remaining lifetime exceeds `API_JWKS_MAX_TOKEN_LIFETIME_SECONDS` (default `86400` = 24h, 60s clock-skew allowance): `exp - now` beyond the cap always rejects, and when `iat` is present a future-dated `iat` or an `exp - iat` spread beyond the cap also rejects. See [fleet-deployment.md § JWT Contract](fleet-deployment.md#jwt-contract) for the full rule.

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
- `401 Unauthorized` + `WWW-Authenticate: Bearer error="invalid_request"` — missing `Authorization` header, a header that does not use the `Bearer` scheme (wrong scheme name, or no space between the scheme and the token — the scheme name itself is case-insensitive), or a present-but-empty Bearer credential
- `401 Unauthorized` + `WWW-Authenticate: Bearer error="invalid_token"` — bad/expired token
- `403 Forbidden` (`insufficient_scope`) -- token valid but lacks the required capability (body shapes: see §6)

**`degradedModePolicy` interaction (JWKS mode):** a JWT reaching a route handler has already been validated by the auth plugin, so its verification status is always `VERIFIED` by the time `DegradedModePolicy` is applied to the synthesized audit actor — every policy, including `reject`, trusts a `VERIFIED` result. Write endpoints therefore never actually return `verification_failed` in practice; `DEGRADED_MODE_POLICY` only changes behavior for MCP tool calls carrying a self-reported `actor.id` under a degraded (non-`VERIFIED`) JWKS verification result. Bearer mode and unauthenticated mode are unaffected regardless (neither has a JWKS chain to degrade).

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

**`GET /items/{id}` with a recognized `include` (`notes`, `deps`, `children`) carries no `ETag` and ignores `If-None-Match`.** Same rationale as `/gate`: inlined notes, dependencies and (per-principal tag-filtered) children are not versioned by `item.modifiedAt`, so an item-only validator would serve a stale `304` after a note upsert or child creation. An absent, empty or unrecognized-only `include` keeps the normal `ETag`/`304` behavior. The `etag` field inside the body is still `etagFor(item.modifiedAt)` and remains the `If-Match` validator for `PATCH`/`DELETE`; it is not a conditional-read validator.

### Config ETags

Config/schema endpoints (`/config`, `/config/schemas`, etc.) use a fingerprint-based ETag:
- Format: `"cfg-<fingerprint>"` where fingerprint is a SHA-256 hex digest computed once, at process startup, over the exact bytes parsed from the global config file (normalized first — see below) — not a fresh re-read of the file on each request, so it is stable for the life of the process even if the file changes on disk (restart to pick up new bytes; there is no lastModified/size fallback)
- Stable across reads when the config has not changed
- `If-None-Match` → `304` when fingerprint matches

The per-root project config endpoints (§18, `/roots/{rootId}/config`) use the SAME `"cfg-<fingerprint>"`
format, but the fingerprint is a SHA-256 over the stored `configYaml`'s UTF-8 bytes (see
`SQLiteProjectConfigRepository.computeFingerprint`) rather than the global config file. `PUT` additionally
accepts `If-Match` for optimistic-concurrency writes (see §18).

The per-root effective config endpoint (§18, `GET /roots/{rootId}/config/effective`) uses a DIFFERENT
prefix, `"eff-<fingerprint>"`, where the fingerprint is a SHA-256 over BOTH layers' fingerprints (global
and per-root), so it changes when either layer changes. It supports `If-None-Match` → `304` like the
`cfg-` endpoints, but an `eff-` ETag is never accepted as a per-root config `If-Match`.

**Normalization (both endpoints, identical rule):** before hashing, the config text has one leading
UTF-8 BOM (U+FEFF) stripped if present, then every CRLF (`\r\n`) is replaced with LF (`\n`) — nothing
else. The stored/served `configYaml` bytes are never rewritten; only the value fed into the SHA-256
hash is normalized. This means a CRLF/BOM checkout (e.g. `core.autocrlf=true` on Windows) of
byte-identical content produces the same fingerprint/ETag as an LF, BOM-less checkout. See
`infrastructure/security/Sha256Hex.kt`'s `configFingerprint` for the canonical implementation.

---

## 5. Idempotency

`POST /items`, `PATCH /items/{id}`, `POST /items/{id}/advance`, `PUT /items/{id}/notes/{key}`, and `POST /dependencies` support idempotency via the `Idempotency-Key` header:

```
Idempotency-Key: <UUID>
```

- Must be a valid UUID
- Malformed key → `400 validation_error`
- The write and its record commit in the same transaction, so a record exists only if the effects committed. Records are durable (they survive a restart and are shared by every process on the database) and live for 24 hours, scoped to the caller and the route (`rest.<METHOD> <route template>`).
- **Replay.** A retry with the same key and the same request (method, path, `If-Match`, canonical body) returns the stored response (status, body and `ETag`) verbatim with the header `Idempotent-Replayed: true`, without re-executing. ETag pre-conditions were evaluated on the first call and are not re-evaluated against the now-mutated resource.
- **Mismatch.** The same key with a different request is `409 idempotency_mismatch`; nothing is executed. The body is compared canonically (key order and whitespace do not matter), so a retry must send the same content.
- **What is recorded.** Only a 2xx response and a pure payload rejection (a `400 validation_error` for a malformed body or value) are recorded. A response that depends on state or is transient (`404`, `403`, `409`, `412`, `500`, `503`) rolls the write back and is **not** recorded, so a retry with the same key runs again.
- Concurrent requests with the same key serialize on the database writer: the first executes, the others replay its result.
- The same record store backs the MCP tools (`requestId`, keyed per element — see `api-reference.md`); a REST key is `"<uuid>:0"` under the REST route, so the two surfaces never collide.
- `POST /items` and `PUT /items/{id}/notes/{key}` additionally require `Content-Type: application/json` (an absent header is treated as `*/*` and accepted); any other value → `415 unsupported_media_type` before the idempotency key or body is read.
- Every write route now bounds its body read to a fixed byte limit BEFORE buffering it: a `Content-Length` over the limit is rejected with `413 payload_too_large` before any bytes are touched, and a chunked/understated-`Content-Length` body is caught by a channel read capped at `limit + 1` bytes, so an oversized body is never buffered in full either way. `POST /items`, `PATCH /items/{id}`, `POST /items/{id}/advance`, `PUT /items/{id}/notes/{key}`, and `POST /dependencies` share a 1 MiB limit (previously unbounded); `PUT /roots/{rootId}/config` (128 KiB) and `PUT /roots/{rootId}/plans/{slug}` (64 KiB) keep their existing numeric limits, now enforced at the same pre-buffer point instead of after a full read. See §6 for the `payload_too_large` error shape.

---

## 6. Error Codes

Most error responses use the `ErrorDto` envelope:

```json
{
  "error": "<machine-readable-code>",
  "message": "<human-readable description>",
  "details": { ... }  // optional structured context
}
```

Authentication and authorization rejections from the auth plugins (`401 invalid_request`,
`401 invalid_token`, `403 insufficient_scope`) and the SSE pre-flight `400 validation_error` (§21)
instead use an OAuth/RFC 6750-style envelope with `error_description` and **no** `message` field:

```json
{
  "error": "<machine-readable-code>",
  "error_description": "<human-readable description>"
}
```

The 401 responses also carry a `WWW-Authenticate: Bearer error="<code>"` header (§1). Rows below
marked "`error_description` body" use this second shape; every other row uses `ErrorDto`. The
other 403 codes (`host_not_allowed`, `scope_forbidden`, `insufficient_capability`) use `ErrorDto`.

| `error` value | Typical HTTP status | Description |
|--------------|---------------------|-------------|
| `host_not_allowed` | 403 | The request's `Host` header isn't `localhost`/`127.0.0.1`/`[::1]` (any port) or listed in `MCP_ALLOWED_HOSTS` — DNS-rebinding protection, checked ahead of authentication on every route. Never discloses the rejected `Host` value; see §1. |
| `bad_request` | 400 | Missing or malformed path/query parameter |
| `validation_error` | 400 | Invalid field value or deserialization failure (`ErrorDto` body); or (SSE-specific, `error_description` body) `GET /api/v1/events` was called with a `?root=` query parameter that yields no valid UUID (see §21) |
| `precondition_required` | 400 | `PATCH` missing required `If-Match` header |
| `not_found` | 404 | Item, note, or dependency not found |
| `rule_not_found` | 404 | `GET /roots/{rootId}/rules/{key}` (§19a): the root resolves and is depth-0, but no `rule/<key>` plan document exists there — distinct from `not_found`, which covers an unknown `{rootId}` itself |
| `scope_forbidden` | 403 | Item exists but is outside the caller's scope; also returned for `POST /items` creating a root item, or `PATCH /items/{id}` moving an item to root, when the resulting root-level item would be outside the caller's scope (see §3) |
| `field_not_patchable` | 400 | PATCH attempted on a server-owned field |
| `cycle_detected` | 400 | Dependency would create a cycle |
| `has_children` | 409 | `DELETE /items/{id}` refused: item has one or more direct children and `?recursive=true` was not given; `details.childCount` is the direct child count |
| `idempotency_mismatch` | 409 | An `Idempotency-Key` was reused with a different request (see §5) |
| `duplicate_dependency` | 409 | `POST /dependencies`: an edge with the same `fromItemId`/`toItemId`/`type` already exists |
| `unsupported_media_type` | 415 | Wrong `Content-Type` for PATCH (see §23), or a non-JSON `Content-Type` on `POST /items`, `PUT /items/{id}/notes/{key}`, `POST /items/{id}/advance`, or `POST /dependencies` (see §5) |
| `etag_mismatch` | 412 | `If-Match` header does not match current ETag |
| `note_body_too_long` | 422 | `PUT /items/{id}/notes/{key}`: the body exceeds the schema `maxLength` for the key and `note_limits.mode` is `reject` |
| `payload_too_large` | 413 | Request body exceeds its route's byte limit (and, for `PUT /items/{id}/notes/{key}`, a note `body` over 65536 UTF-8 bytes) — the `Content-Length` header alone if it declares a size over the limit (body untouched), otherwise the actual bytes read, capped at `limit + 1` so an oversized body is never buffered in full. `POST /items`, `PATCH /items/{id}`, `POST /items/{id}/advance`, `PUT /items/{id}/notes/{key}`, and `POST /dependencies` share a 1 MiB limit; `PUT /roots/{rootId}/config` is 128 KiB; `PUT /roots/{rootId}/plans/{slug}` is 64 KiB, except a `{slug}` starting with `rule/` (e.g. `rule%2Fcommit-discipline`), which is capped tighter at 16384 bytes (16 KiB) — the single enforcement point `query_rules`/§19a rely on, so those read surfaces never re-check size themselves (see §18, §19, §19a). |
| `version_conflict` | 409 | `PATCH /items/{id}`: `If-Match` matched at read time, but a concurrent writer's update won the version race before this write committed — optimistic-lock loss, distinct from `etag_mismatch`. Retry with a fresh `If-Match` ETag. |
| `invalid_request` | 401 | `error_description` body. Missing `Authorization` header, a non-Bearer scheme, or an empty Bearer credential (also the SSE pre-flight when no header or allowed `?token=` is presented). Carries `WWW-Authenticate: Bearer error="invalid_request"`. |
| `invalid_token` | 401 | `error_description` body. Unknown, expired, or otherwise invalid token (bearer or JWKS). Carries `WWW-Authenticate: Bearer error="invalid_token"`. |
| `verification_failed` | 401 | Not currently reachable via REST — a JWT passing `ApiBearerAuth` is always `VERIFIED`, which every `degradedModePolicy` trusts. Reserved for the same audit-policy check used by MCP tool calls, where a self-reported actor under a degraded JWKS result can still be rejected. |
| `insufficient_capability` | 403 | Caller's token lacks a capability required by the request itself (distinct from `scope_forbidden`'s root-scope check) — e.g. a non-ADMIN caller sets `overrideResourceLeases: true` on `POST /items/{id}/advance`, or calls `DELETE /api/v1/resources/leases/{key}` without `ADMIN` |
| `insufficient_scope` | 403 | `error_description` body. A generic `requireCapability` check failed for the plugin's configured capability; (SSE-specific) a `GET /api/v1/events` connection presents a valid token that lacks the `read` capability (see §21); (SSE-specific) a `GET /api/v1/events` connection carries a `tags_include` scope but the route has no `WorkItemRepository` wired to filter by it -- fail-closed rather than serving an unfiltered stream; or (SSE-specific) a root-scoped principal's `?root=` values do not intersect its token's `scope.rootIds` -- the requested roots are entirely outside scope (see §21) |
| `transition_failed` | 422 | Role transition rejected (invalid trigger, gate failure, dependency blocker) |
| `resource_unavailable` | 409 | Resource-lease gate contention on `POST /items/{id}/advance` into WORK — transient, retryable. Carries a `Retry-After` header and `details.contendedResources`/`details.retryAfterMs`. Never discloses the current holder. |
| `config_unavailable` | 503 | Per-root config read failed (a transient database error) and there was no last-known-good cached config to serve for that root — transient, retryable; the caller applies its own backoff (no `Retry-After` header). Returned by `POST /items/{id}/advance`, `GET /items/{id}/gate` (see §9, §10), `PUT /items/{id}/notes/{key}` (note write policy needs the per-root schema and `note_limits` mode), and `GET /roots/{rootId}/config/effective` (see §18). REST and the MCP tools now read per-root config through the same `EffectiveConfigResolver`/last-known-good cache (one shared instance, built once in `ServerComposition`) — a transient DB error on one surface is absorbed by a cache warmed by the other, so this error is rarer than it was when each surface kept its own cache. Exception: `POST /items/{id}/advance` reads the item's config (and every cascade target's) inside the advance's unit of work, `GET /items/{id}/gate` inside its preview's read unit, and `PUT /items/{id}/notes/{key}` inside the write's unit of work; a unit has no last-known-good fallback, so on those three routes a read fault answers `config_unavailable` even when the cache is warm. |
| `db_error` | 500 | A store fault: a read route responds `Database query failed` (or the route's own read text), a write route its existing text (e.g. `Failed to create item`). A store fault on a read is never reported as `404 not_found`; `404` means only that the row does not exist. |

---

## 6a. Request correlation (`X-Req-Id`)

Every `/api/v1` response except the `GET /api/v1/events` stream carries an `X-Req-Id` header: the
call's 8-character correlation id (lowercase Crockford base32). It is always server-generated (an
inbound `X-Request-Id` only feeds the `requestId` log field), appears on success and error responses
alike (including `401`), is the `req_id` of every `events` row the request wrote, and keys the
request's row in the `call_log` table. Only `/api/v1` and paths under `/api/v1/` are covered (not
`/api/v10`). A `401` from the auth layer records its body's `error` code (`invalid_request`,
`invalid_token`, ...) as the row's `error_code`; `http_<status>` is the fallback. The row's `tool` is
the matched route template as declared (`GET /api/v1/items/{id}/schema`); a call that matched no route is
`<METHOD> /api/v1/<first segment>` for a served top-level resource, else `<METHOD> unmatched`. `principal_id`
on REST rows is the authenticated API principal (`api:<tokenId>`); only MCP rows can carry a self-reported actor id (up to 500 characters, with `proof_status` distinguishing verified ids). `X-Req-Id` is in the default
`CORS_EXPOSE_HEADERS`. See
`fleet-deployment.md` -> "Call log and `reqId`".

## 7. Pagination

List endpoints return a `PageDto<T>`:

```json
{
  "items": [...],
  "page": 1,
  "pageSize": 50,
  "totalItems": 42,   // may be null when count is expensive
  "hasMore": true,
  "skipped": null      // always omitted — see below
}
```

Query parameters: `?page=<int>` (default 1, must be an integer in `1..100000`) and `?pageSize=<int>` (default 50, must be a positive integer; values above 200 are silently capped at 200). A missing or blank `page`/`pageSize` falls back to its default; a non-integer, `page < 1`, or `page > 100000` value returns `400 validation_error` (`{"error":"validation_error","message":"page must be an integer between 1 and 100000"}` or the equivalent `pageSize must be a positive integer` message) instead of being silently clamped.

`totalItems` may be `null` for endpoints where computing an exact count is expensive; use `hasMore` for continuation.

`skipped` is retained for wire compatibility and is **always omitted**: a stored row that fails domain validation (e.g. a corrupt/legacy row) is returned like any other (a WARN log identifies it) instead of being dropped. Invariant: `items.size == min(pageSize, totalItems - offset)` (clamped to the actual window), so truncation is always derivable from `totalItems`/`pageSize`/`page` without a separate `truncated` field.

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
  "priority": "high|medium|low",
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
  "parent": "string|null"
}
```

For REST API writes, `id` is always `"api:<tokenId>"` and `kind` is always `"external"` (server-synthesized; client-supplied actor fields are silently dropped).

### VerificationDto

```json
{
  "status": "unverified|verified|unavailable|unchecked",
  "verifier": "api-bearer|api-jwks|null",
  "reason": "string|null",
  "proof": {                    // present only for ADMIN callers, and only when a proof was
                                 // supplied; omitted otherwise (explicitNulls=false) — independent
                                 // of API_REDACT_NOTE_ATTRIBUTION
    "sha256": "ba7816bf...",    // SHA-256 hex digest of the proof; present whenever a proof was supplied
    "iss": "string|null",       // verified JWT claims below — present only when status was "verified"
    "sub": "string|null",
    "aud": ["string"] | null,
    "jti": "string|null",
    "iat": 1700000000,
    "exp": 1700003600,
    "kid": "string|null",
    "alg": "string|null"
  }
}
```

`VerificationDto.proof` is the forensic-evidence replacement for the raw proof: a SHA-256 hash
plus, when the proof was cryptographically `verified`, the JWT claims that were checked. It has
its own `ADMIN`-only gate, independent of `API_REDACT_NOTE_ATTRIBUTION` — any `ADMIN` caller sees
it whenever `verification` itself is shown; a non-admin caller never sees it.

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
  "type": "blocks|relates_to",
  "unblockAt": "queue|work|review|terminal|null",
  "createdAt": "ISO-8601"
}
```

`type` is `blocks` or `relates_to`: blocking dependencies are stored in one orientation (`fromItemId`
blocks `toItemId`), and `IS_BLOCKED_BY`, accepted as an input type by MCP `manage_dependencies`, is
normalized to `blocks` with the ends swapped before it is stored. REST create only accepts
`blocks`/`relates_to` — see POST /dependencies below. `unblockAt` is the effective unblock-role
threshold: `null` only for `relates_to` (no blocking semantics); for `blocks` it is the stored value,
defaulting to `"terminal"` when unset — never a raw possibly-null passthrough.

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
  "missing": ["implementation-notes"],
  "missingBySeat": { "implementer": ["implementation-notes"] },
  "violations": [
    { "key": "test-manifest", "seat": "test-author", "constraint": "same_actor", "conflictingSeat": "implementer" }
  ]
}
```

`phase` is the item's CURRENT role, lowercased. `missing` is the required-note KEY strings (schema
order) still unfilled for `phase` — plain strings, never `{key, description, ...}` objects.

`canAdvance` is "`POST /items/{id}/advance` with `start` would be allowed right now", claim ownership
excluded: the same transition-policy evaluation the advance runs. It is `false` when the transition
table rejects `start` (terminal or blocked item), a blocking dependency is unmet, a current-phase
required note is unfilled (or a `reject`-mode violation blocks), or an exclusive resource the item
declares is held by another item. `blockedBy` (string, optional) names that gate — `table`,
`dependency`, `note` or `lease` — and is present only when `canAdvance` is `false` and the item is not
terminal.

`missingBySeat` (object, optional, A1) buckets `missing`'s keys by owning seat —
`{<seat>: [keys], ..., "unowned": [keys]}`, non-empty buckets only, in merged-seat order with
`unowned` always last. Present only when the item's resolved schema is seat-aware AND the item is
not `TERMINAL` (an empty object `{}` when seat-aware but nothing is missing); omitted entirely
(`explicitNulls=false`) for a seat-less schema or a terminal item — see
[`config-format.md`](../../claude-plugins/task-orchestrator/skills/manage-schemas/references/config-format.md#seats-trait--schema-dimension)
→ "Seats" for how a note's owning seat is determined and the `unowned` bucket rule.

`violations` (array, optional, A2) reports independence-attestation findings for `phase` —
`IndependenceViolationDto` objects, present (possibly `[]`) whenever independence `mode` is not
`off` and the resolved schema declares `independent_of` somewhere; omitted entirely otherwise,
including for a terminal item. `canAdvance` already accounts for a `reject`-mode block from a
non-waived violation, the same way it accounts for missing required notes — see
[`config-format.md`](../../claude-plugins/task-orchestrator/skills/manage-schemas/references/config-format.md#independence-a2)
→ "Independence (A2)".

### IndependenceViolationDto

```json
{
  "key": "test-manifest",
  "seat": "test-author",
  "constraint": "same_actor",
  "conflictingSeat": "implementer",
  "waived": true
}
```

One A2 independence-attestation finding — field-for-field identical to the MCP JSON shape
(`GatePredicate`/`NoteSchemaJsonHelpers.buildViolationsArray`). `key` is the declaring note's key;
`seat` its owning seat (omitted when the schema is not seat-aware); `constraint` is
`"same_actor"` | `"missing_actor"` | `"unverified"`; `conflictingSeat` the seat the finding was
raised against (omitted for a `missing_actor`/`unverified` finding raised against the declaring
note itself); `waived` present (`true`) only when the `independence: temporal-only` waiver applies
— a waived entry never blocks a `reject`-mode transition. **Actor-free by construction: never an
actor id, proof, or claim.** `AttributionRedactor` is not involved — there is nothing
attribution-bearing here to redact; the same body is served to every caller regardless of
`API_REDACT_NOTE_ATTRIBUTION` or capability tier.

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
mode's `gateStatus`/`guidanceKey`/`skillPointer` (see
[api-reference.md → get_context](api-reference.md#get_context), "Response (item mode)"). `guidanceKey`
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

**EffectiveSchemaDto** (see §18, `GET /roots/{rootId}/config/effective`):
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
literal MCP `query_items(operation="schema", ...)` uses. `configFingerprint` is the supplying
layer's fingerprint, omitted when that layer has none.

**EffectiveConfigDto** (see §18, `GET /roots/{rootId}/config/effective`):
```json
{
  "rootId": "550e8400-e29b-41d4-a716-446655440000",
  "schemas": [<EffectiveSchemaDto>],
  "traits": [<TraitDto>],
  "types": ["bug", "feature-task"],
  "statusGraph": <StatusGraphDto>,
  "defaultSchema": <EffectiveSchemaDto>|null,
  "globalFingerprint": "e3b0c44298fc1c14...",
  "perRootFingerprint": "a94a8fe5cc...",
  "schemaResolution": "legacy"
}
```
Every registered type (the union of this root's per-root `work_item_schemas` keys and the global
schema service's keys) resolved against `{rootId}`'s LAYERED config in one response — the same
per-root/global view `EffectiveConfigResolver` and MCP `query_items(schema, type=K, rootId=R)`
already compute, surfaced as one REST resource instead of requiring a dashboard to probe per type.
`types` lists every schema key (per-root and global, ascending natural `String` order) that
resolves to a schema for this root, and `schemas` follows that same order — `types` always equals
the `type` values of `schemas`. A key that does not resolve under the root's mode is omitted from
both (e.g. under `schema_resolution: isolated`, a global-only type with no per-root `default`). `traits` lists every trait name visible to this root (per-root names first,
then global, distinct) with its resolved notes/dispatch/resources — a trait unknown to both layers
is skipped. `globalFingerprint`/`perRootFingerprint` are omitted when null (no global config loaded
/ no per-root config pushed for this root, respectively). `defaultSchema` is the resolved `"default"`
entry, omitted when no layer defines one. `schemaResolution` (AR-39) is this root's effective
`schema_resolution` mode — `"legacy"`, `"layered"`, or `"isolated"` — always populated on a `200`
(`"legacy"` when the key is absent everywhere); see
[`config-format.md`](../../claude-plugins/task-orchestrator/skills/manage-schemas/references/config-format.md)
→ "Global vs Per-Project Config". Distinct from `ConfigSnapshotDto` (GLOBAL-only, `/config`) and the
raw stored YAML `GET /roots/{rootId}/config` returns — this route performs no trait merging (same
as `SchemaDto`/the MCP type-path): it reports each type's RESOLVED BASE schema only.

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

**RuleResponseDto** (see §19a) — §19a's per-key rule read; mirrors the MCP `query_rules` tool's
`get` data payload minus `resolvedFrom` (REST has no item/skill-pointer mode):
```json
{
  "rootId": "550e8400-e29b-41d4-a716-446655440000",
  "key": "commit-discipline",
  "rulesVersion": "e3b0c44298fc1c14...",
  "body": "string"
}
```
`rulesVersion` is the backing `rule/<key>` plan document's `contentHash` (same value
`PlanDocumentResponseDto`/`PlanDocumentSummaryDto` report for that slug). `body` is served
byte-for-byte as stored.

**RuleSummaryDto** — one row of `RuleListResponseDto.rules` (see §19a); metadata only, never the
body:
```json
{
  "key": "commit-discipline",
  "rulesVersion": "e3b0c44298fc1c14...",
  "updatedAt": "2026-09-28T13:00:00Z"
}
```

**RuleListResponseDto** (see §19a):
```json
{
  "rootId": "550e8400-e29b-41d4-a716-446655440000",
  "rules": [<RuleSummaryDto>]
}
```
`rules` is ordered by `key` ascending.

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
  "newRole": "work|null",
  "actor": { "id": "api:dashboard-editor", "kind": "external", "parent": "..." },
  "rootId": "<uuid>"
}
```

`actor` and `rootId` are additive and are absent from the JSON when null (see §21 "Actor and rootId").

---

## 9. Endpoints — Items (Read)

All require `READ` capability.

### GET /items

Paginated list of work items with optional filters.

Any supplied but unparsable filter value is rejected with `400 validation_error` (`{"error":"validation_error","message":"Invalid parentId 'xyz': must be a UUID"}`), never silently ignored, so a malformed filter cannot widen the result set. A present but blank value (`?parentId=`) is treated as absent. The same rule applies to `GET /items/{id}/tree` `depth`, `GET /transitions` `since`, and `ancestorId`/`role` on `GET /search` and `GET /notes/search`. Validation runs before any scope check, so an invalid `ancestorId` yields `400` rather than `403 scope_forbidden`. (`orderBy`/`orderDir` keep their `400 bad_request` code.)

**Query parameters:**

| Parameter | Type | Description |
|-----------|------|-------------|
| `page` | int | Page number (default 1, `1..100000`) |
| `pageSize` | int | Items per page (default 50, max 200) |
| `role` | string | Filter by role: `queue`, `work`, `review`, `terminal`, `blocked` (case-insensitive). Invalid value -> `400 validation_error`. |
| `priority` | string | Filter by priority: `high`, `medium`, `low` (case-insensitive; output is lower-case). Invalid value -> `400 validation_error`. |
| `tag` | string | Comma-separated tags; all listed tags must be present (AND match) |
| `tagAny` | string | Comma-separated tags; any listed tag must be present (OR match). Overrides `tag` when both present. |
| `type` | string | Filter by item type |
| `parentId` | UUID | Filter to direct children of this parent. Invalid value -> `400 validation_error`. |
| `rootId` | UUID | Filter to items within this root's subtree (intersected with principal scope). Invalid value -> `400 validation_error`. |
| `modifiedAfter` | ISO-8601 | Modified after this timestamp. Invalid value -> `400 validation_error`. |
| `modifiedBefore` | ISO-8601 | Modified before this timestamp. Invalid value -> `400 validation_error`. |
| `createdAfter` | ISO-8601 | Created after this timestamp. Invalid value -> `400 validation_error`. |
| `createdBefore` | ISO-8601 | Created before this timestamp. Invalid value -> `400 validation_error`. |
| `claimStatus` | string | Filter by claim state: `claimed`, `unclaimed`, `expired` (case-insensitive). Invalid value -> `400 validation_error`. |
| `orderBy` | string | Sort field: `title`, `priority`, `complexity`, `createdAt`, `modifiedAt` (also accepts legacy `created`/`modified` aliases). Unknown value → `400 bad_request`. |
| `orderDir` | string | Sort direction: `asc`, `desc` (default: `desc`). Unknown value → `400 bad_request`. |

**Response:** `200 OK` → `PageDto<ItemDto>` (see §7: `skipped` is always omitted)

### GET /items/roots

Root-level items (depth=0) accessible to the caller.

- Scoped tokens: returns only the specific root items within scope (efficient, not capped); `totalItems` is the exact scoped root count.
- Unscoped/admin: fully paginated via standard `page`/`pageSize` params — `totalItems` comes from an exact `COUNT` query, unaffected by `pageSize` or by rows dropped for failing domain validation. There is no cap; page through all roots with repeated requests.

**Query parameters:** Standard pagination params (`page`, `pageSize`).

**Response:** `200 OK` → `PageDto<ItemDto>` (see §7: `skipped` is always omitted)

### GET /items/{id}

Single item by UUID.

**Query parameters:**
- `include` — comma-separated list: `notes`, `deps`, `children` — inline related data

**Responses:**
- `200 OK` → `ItemDto`
- `304 Not Modified` -- when `If-None-Match` matches the current ETag; only possible when no recognized `include` (`notes`, `deps`, `children`) is requested. With a recognized `include` the response is always `200 OK` (no `ETag` header, `If-None-Match` ignored)
- `400 bad_request` — invalid UUID
- `403 scope_forbidden`
- `404 not_found`

### GET /items/{id}/tree

Descendant tree, paginated as a flat list (root item always included as first element).

**Query parameters:**
- `depth` — maximum relative depth from the root item (optional; non-negative integer, otherwise `400 validation_error`)
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
[api-reference.md → get_context](api-reference.md#get_context), "Response (item mode)"), computed via the same `resolveSchema` +
`computePhaseNoteContext` path; `canAdvance`/`blockedBy` come from the same transition-policy preview
`get_context` uses (`start`, claim ownership excluded). No blocker list and no dispatch field. Consumed by the
plugin's SubagentStop phase guard (see
[integration-guides/plugin-skills-hooks.md](integration-guides/plugin-skills-hooks.md)).

If the notes read itself fails (repository error), every required note for the current phase is
reported missing — the same fail-safe `computePhaseNoteContext` gives `get_context` when notes
cannot be loaded.

**Independence (A2):** `gateStatus.violations` (see `GateStatusDto` in §8) is computed against the
same `independence:` policy and `independent_of` declarations as the MCP `get_context`/`advance_item`
paths — see [`config-format.md`](../../claude-plugins/task-orchestrator/skills/manage-schemas/references/config-format.md#independence-a2)
→ "Independence (A2)". This is also the route the plugin's SubagentStop phase-guard hook polls to
decide whether a `reject`-mode violation should block a subagent's stop.

**Responses:**
- `200 OK` → `ItemGateDto` (§8) — no `ETag` header (§4)
- `400 bad_request` — invalid UUID
- `403 scope_forbidden`
- `404 not_found`
- `503 config_unavailable` — the item's per-root config could not be read inside the preview's read
  unit, where the last-known-good fallback is disabled (see §6); transient, no `Retry-After` header

### GET /items/{id}/schema

The item's resolved schema view — body **exactly equal** to MCP's
`query_items(operation="schema", itemId=...)` `data` object (see
[api-reference.md](api-reference.md) → "Key Parameters — schema"): both this route and that MCP
operation call the SAME `ItemSchemaView.buildItemSchemaJson` builder, so the two response bodies
stay identical by construction, never by separately-maintained convention. `{ type,
configFingerprint, configSource, notes: [...], dispatch?, resources?, seats?, dispatchBySeat?,
features }` — `seats`/`dispatchBySeat` present only for a seat-aware schema, `features` always
present. Id handling mirrors `GET /items/{id}` and `GET /items/{id}/gate`: full UUID only (a hex
prefix is rejected), checked in the order below. Same `configResolver` and per-root config cache as
`GET /items/{id}/gate` (§6), read outside any unit so the last-known-good fallback applies here — no
separate cache, no `ETag`/`If-None-Match` handling
(§4's rationale applies here too: neither notes nor config version `item.modifiedAt`).

**Responses:**
- `200 OK` → the resolved schema view JSON (unwrapped — no envelope DTO)
- `400 bad_request` — invalid UUID (including a hex prefix; this route, unlike list/search
  endpoints, requires a full UUID)
- `403 scope_forbidden`
- `404 not_found` — item does not exist
- `404 no_schema` — the item is schema-free (no type/tag match, and no `default` schema resolves)
- `503 config_unavailable` — same meaning and no-`Retry-After` shape as `GET /items/{id}/gate` above

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

Deletes the item. Requires `WRITE_ITEMS`. A parent item (one with direct children) is refused
with `409 has_children` unless `?recursive=true` is given, in which case it and every descendant
(notes and dependencies cascade with each row) are deleted, leaves-first, inside one
transaction — all-or-nothing, matching the MCP `manage_items` delete operation's semantics
(`WorkItemDeletion`, shared by both surfaces).

**Query parameter:** `recursive` — case-insensitive `"true"`/`"false"`; absent means `"false"`.
Any other value → `400 validation_error`.

**Optional:** `If-Match` header — when supplied, mismatched ETag → `412 etag_mismatch`.

**Responses:**
- `200 OK` — recursive delete succeeded: `{"id", "deleted", "descendantsDeleted"}` (`deleted` is the
  total row count including the target item; `descendantsDeleted` is descendant-only)
- `204 No Content` — non-recursive delete of a leaf item succeeded
- `400 validation_error` — invalid `recursive` value
- `404 not_found`
- `409 has_children` — item has direct children and `?recursive=true` was not given;
  `details.childCount` is the direct child count
- `412 etag_mismatch`
- `500 db_error`

### POST /items/{id}/advance

Trigger a role transition. Requires `ADVANCE`. Supports `Idempotency-Key` header (see [section 5](#5-idempotency)).

This route runs the **same advance pipeline as the MCP `advance_item` tool**, unified behind `AdvanceService`: ONE transaction in which the transition policy evaluates the transition table, **blocking dependencies**, the **required-note gate** and the **resource-lease gate** (claim ownership is not enforced on REST), then the role change, its audit row and event, the lease acquire/release, and every cascade commit together or not at all; unblocked downstream items are then reported.

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
same transition. A lease-store fault while acquiring or releasing fails the whole advance with
`422 transition_failed` and nothing is persisted.

**Contention response (`409 resource_unavailable`):**
```json
{
  "error": "resource_unavailable",
  "message": "Cannot enter work phase: resource(s) currently held by another work item: staging-db",
  "details": {
    "targetRole": "work",
    "contendedResources": ["staging-db"],
    "retryAfterMs": 30000
  }
}
```
`retryAfterMs` is the time until the soonest contended lease expires. A `Retry-After` response
header accompanies the body — whole seconds, **rounded up** from `retryAfterMs` with a floor of 1, so a client never retries before the lease can possibly have
expired. `CORS_EXPOSE_HEADERS` includes `Retry-After` and `X-Req-Id` by default (see `fleet-deployment.md`) so
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

`CascadeEventDto` still carries `error` (string, optional, omitted when null), but it is never
populated: cascades commit in the same transaction as the child, so a cascade apply fault fails the
whole advance with `422 transition_failed` (the child's transition is rolled back too).

`CascadeEventDto` also carries `roleBlocked` (boolean, default `false`) — a terminal cascade
suppressed because the parent is `blocked` — and `dependencyBlocked` (boolean, default `false`)
plus `blockers` (array of `{fromItemId, currentRole, requiredRole}`, non-null only when
`dependencyBlocked`; `currentRole` is `"unknown"` for an unreadable blocker) — a terminal, start or
reopen cascade suppressed by an unmet blocking dependency on the parent. Both apply to
cancel-originated cascades too. Start and reopen cascades do not happen at all for a parent whose
lifecycle is `manual` or `permanent`.

**Response `200 OK`:** `AdvanceResponseDto`
```json
{
  "itemId": "<uuid>",
  "previousRole": "queue",
  "newRole": "work",
  "trigger": "start",
  "statusLabel": "string|null",
  "violations": [
    { "key": "test-manifest", "seat": "test-author", "constraint": "same_actor", "conflictingSeat": "implementer" }
  ],
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

The `cascadeEvents`, `unblockedItems`, and `expectedNotes` fields are **additive** — they were added when the REST and MCP advance paths were unified. A gate-blocked cascade carries `"applied": false`, `"gateBlocked": true`, and a `missingNotes` array; a role-suppressed one carries `"roleBlocked": true`, and a dependency-suppressed one `"dependencyBlocked": true` plus a `blockers` array.

`violations` (array, optional, A2) — on the top-level response and on each `cascadeEvents` entry — reports independence-attestation findings for that transition's target schema, `IndependenceViolationDto` objects mirroring `GateStatusDto.violations` above, but under a stricter presence rule: present ONLY when the list is non-empty. It is omitted (not `[]`) both when independence checking applies but finds nothing, and when independence mode is `off` or the target schema declares no `independent_of`. Populated in `warn` mode too, whenever there is something to report (a `warn`-mode transition still applies and still reports what it found).

**Responses:**
- `200 OK` → `AdvanceResponseDto`
- `400 validation_error` — invalid trigger string, or a `credentialRefs` entry fails format/closed-set validation
- `403 insufficient_capability` — `overrideResourceLeases: true` sent by a non-ADMIN caller
- `413 payload_too_large` — body exceeds the shared 1 MiB write-body limit (see §5, §6)
- `415 unsupported_media_type` — `Content-Type` is present and is not `application/json` (an absent header is accepted as `*/*`); checked before the body is read
- `409 resource_unavailable` — resource-lease gate contention; `Retry-After` header + `details.contendedResources`/`details.retryAfterMs` (see above)
- `409 not_claim_holder` — pre-existing, defensive-only on this route (REST bypasses claim ownership by default — see "Claimed-item behavior" above); not expected to occur in normal REST usage
- `422 gate_blocked` — a required-note gate failed; `details.missingNotes` lists the unfilled required notes, and `details.missingBySeat` (A1, optional) buckets those keys by owning seat when the target schema is seat-aware
- `422 transition_blocked` — a dependency blocker prevents the transition; `details.blockers` lists the blocking edges
- `422 transition_failed` — invalid state transition, or a persistence fault anywhere in the advance
  (including a cascade or a lease release); nothing was applied
- `503 config_unavailable` — the item's per-root config could not be read inside the advance's unit
  of work, where the last-known-good fallback is disabled (see §6); transient, no `Retry-After`
  header — the transition was NOT applied (a cascade parent whose own root's config cannot be read is
  skipped instead; the transition still commits)

**Gate-rejection example (`422`):**
```json
{
  "error": "gate_blocked",
  "message": "Gate check failed: required notes not filled for queue phase: spec",
  "details": {
    "targetRole": "work",
    "missingNotes": [
      { "key": "spec", "description": "Problem statement and approach", "guidance": "..." }
    ],
    "missingBySeat": { "planner": ["spec"] },
    "violations": [
      { "key": "test-manifest", "seat": "test-author", "constraint": "same_actor", "conflictingSeat": "implementer" }
    ]
  }
}
```

`details.missingBySeat` (object, optional, A1) appears immediately after `details.missingNotes` —
same shape, ordering, and omission rule as `GateStatusDto.missingBySeat` above
(`{<seat>: [keys], ..., "unowned": [keys]}`, non-empty buckets only, `unowned` last): present only
when the target schema is seat-aware, omitted entirely for a seat-less schema.

`details.violations` (array, optional, A2) appears after `details.missingBySeat` — same shape as
`GateStatusDto.violations`, but the same non-empty-only presence rule as `AdvanceResponseDto.violations`
above (present only when the list is non-empty; omitted, not `[]`, when independence mode is `off`,
the target schema declares no `independent_of`, or the check applies and finds nothing). A
`gate_blocked` rejection can be caused by missing required notes, by a non-waived independence
violation in `reject` mode, or both; `details.violations` reports whichever independence findings
exist regardless of which condition actually triggered the 422 (it is also present, non-empty, when
the block was violations-only — an empty `missingNotes` alongside a populated `violations`).

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
  "role": "queue|work|review",   // required; any letter case, stored lowercase
  "body": "string",              // required; at most 65536 UTF-8 bytes
  "properties": {}               // optional — reserved, currently ignored
}
```

**`If-Match` behavior:** Optional on create. On update (note already exists), supplied `If-Match` is validated; mismatch → `412 etag_mismatch`.

**Responses:**
- `201 Created` → `NoteDto` + `ETag` header (note was new)
- `200 OK` → `NoteDto` + `ETag` header (note was updated)
- `201 Created` / `200 OK` also carry a `warning` string in the body when the note body exceeded the schema `maxLength` under `note_limits.mode: warn` (absent otherwise)
- `400 validation_error` — invalid `role`, or the key is declared in the item's resolved schema with a different role
- `412 etag_mismatch`
- `413 payload_too_large` — body exceeds the shared 1 MiB write-body limit (see §5, §6), or the note `body` itself exceeds 65536 UTF-8 bytes
- `415 unsupported_media_type` — `Content-Type` is present and is not `application/json` (an absent header is accepted as `*/*`); checked before the `Idempotency-Key` header or body is read
- `422 note_body_too_long` — the body exceeds the schema `maxLength` for the key and `note_limits.mode` is `reject`
- `503 config_unavailable` — the item's per-root config could not be read

The write policy (role normalization, 64 KiB body cap, CRLF-to-LF normalization, schema-role and `maxLength` checks) is the same one `manage_notes` and `create_work_tree` apply; the `If-Match` check runs before it. A schema-role or `maxLength` rejection is not recorded for `Idempotency-Key` replay (it depends on config), so a retry after a config change runs again.

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
- Cycle detection — `400 cycle_detected`. Runs only for `blocks` (the item that would block, `fromItemId`), over the normalized blocker-to-blocked graph (including edges created through MCP `manage_dependencies` with `IS_BLOCKED_BY`); `relates_to` has no blocking semantics and skips the check entirely.
- Duplicate edge — `409 duplicate_dependency` when an edge with the same `fromItemId`/`toItemId`/`type` already exists. A `blocks` edge that restates a stored one the other way round (a former `IS_BLOCKED_BY` input) is the same stored edge and is rejected the same way.

**Responses:**
- `201 Created` → `DependencyEdgeDto`
- `400 cycle_detected` / `validation_error` / `not_found`
- `409 duplicate_dependency`
- `413 payload_too_large` — body exceeds the shared 1 MiB write-body limit (see §5, §6)
- `415 unsupported_media_type` — `Content-Type` is present and is not `application/json` (an absent header is accepted as `*/*`); checked before the body is read

Supports `Idempotency-Key` header (see [section 5](#5-idempotency)); the same key with a different body is `409 idempotency_mismatch`.

### DELETE /dependencies/{id}

Remove a dependency edge by its UUID. Requires `MANAGE_DEPENDENCIES`.

Scope check: both `fromItemId` and `toItemId` of the edge must be accessible to the caller.

**Responses:**
- `204 No Content`
- `404 not_found`
- `403 scope_forbidden`
- `500 db_error` - a store fault: `Database query failed` on the edge lookup, `Failed to delete
  dependency` on the delete itself (the SQL text is logged, not returned)

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
- `limit` — max rows, default `100`, clamped to `[1, 500]`. Applied in SQL after the newest-first ordering (for both the `at` and the no-`at` views), so a large history is never loaded to be truncated.

A lease is active while its expiry is strictly after "now": an expiry equal to now is expired, so a release at exactly the expiry instant records `expired`. All lease timestamps are canonical UTC text from the server's one bound clock.

**Response `200 OK`:** `ResourceLeaseHistoryResponseDto` → `{ "intervals": [<ResourceLeaseIntervalDto>] }`.
`acquiredByActorId` / `releasedByActorId` are present per-entry only for callers with `ADMIN`
capability — same inline redaction rule `GET /resources/leases` applies to `acquiredByActorId`.

No pruning/retention in v1 — the table is append-only and grows with lease-event cardinality
(documented, accepted behavior; see `current/src/main/resources/db/migration/sqlite/V16__Resource_Lease_History.sql`).

---

## 15. Endpoints — Transitions (Audit)

All require `READ`. `actor` and `verification` fields are redacted (null) for non-admin callers; admin callers see them subject to `API_REDACT_NOTE_ATTRIBUTION`. `verification.proof` (forensic hash + verified claims) requires `ADMIN`.

### GET /items/{id}/transitions

Per-item role-transition history (append-only audit log), paginated. Newest first (`transitionedAt` descending); accepts the standard `page` and `pageSize` parameters.

**Response:** `200 OK` → `PageDto<RoleTransitionDto>`

### GET /transitions

Recent transitions across all items. Default window: last 24 hours.

**Query parameters:**
- `since` — ISO-8601 timestamp; default: 24 hours ago when absent or blank; an unparsable value returns `400 validation_error`
- Standard pagination params

Scope-filtered: scoped tokens only see transitions for items within their scope (ancestor-chain check). If the scope lookup fails, every scoped row in that scan is dropped (fail closed) rather than returned unfiltered.

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
- `ancestorId` — scope results to a subtree (invalid UUID -> `400 validation_error`)
- `role` — filter by item role (invalid value -> `400 validation_error`)
- `tag` — comma-separated tag filter

Results are ranked by RRF-fused relevance (trigram + porter tokenizer). Returns up to 50 hits.

**Response:** `200 OK` → `List<SearchHitDto>`

### GET /notes/search

FTS5 full-text search over note bodies.

**Query parameters:**
- `q` (required) — search query
- `ancestorId` — scope results to a subtree (invalid UUID -> `400 validation_error`)

Returns up to 50 hits. `noteKey` is populated on every hit (note-body search always has a key).

**Response:** `200 OK` → `List<SearchHitDto>`

---

## 17. Endpoints — Config / Schema Discovery

All require `READ`. All config endpoints emit a fingerprint-based ETag (`"cfg-<fingerprint>"`) and support `If-None-Match` → `304 Not Modified`.

These endpoints describe the **global** config only. For what a specific project root actually resolves to (its per-root config layered over the global one, per its `schema_resolution` mode), use `GET /roots/{rootId}/config/effective` (§18).

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

Structural role-transition graph across all schema types, derived from the transition table the
advance policy uses (one cell per role and user trigger; an invalid cell is omitted).

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

**Query parameter:** `force` (boolean, default `false`) - set `?force=true` to bypass push guards;
skips both the embedded `project.rootId` mismatch check (guard 5 below) and the fast-forward
fingerprint guard (guard 6 below). It does NOT skip guard 7 (`If-Match`) - an explicit
compare-and-set precondition the caller supplied for this request is still enforced even under
`?force=true`.

**Validation pipeline (in order, stops at first failure - nothing is written on failure):**
1. Body size <= 128 KiB
2. `{rootId}` resolves to an existing WorkItem
3. That WorkItem is depth-0 (configs anchor to project roots only)
4. `configYaml` parses under a `SafeConstructor` YAML load (rejects `!!`-tagged arbitrary Java
   type construction - CWE-502 - as well as ordinary syntax errors)
5. Unless `?force=true`: if the parsed document embeds a top-level `project.rootId` that parses as
   a UUID and differs from `{rootId}`, the push is rejected (an absent or non-UUID `project.rootId`
   is not an error - the push proceeds as if it were absent)
6. Unless `?force=true`: the incoming `configYaml`'s fingerprint is classified against `{rootId}`'s
   fingerprint history, and
7. optional `If-Match` (see below) is compared against `{rootId}`'s fingerprint -

   guards 6 and 7 are evaluated **atomically with the write itself**, inside the SAME transaction
   `ProjectConfigStore.upsertGuarded` uses to persist the row: both guards read the row once,
   decide, and the write applies to that SAME row version, closing the read-then-write race a
   separate guard-read followed by a separate write would leave open (a concurrent writer between
   the read and the write can no longer cause a silently lost update or a silently reverted push -
   the loser is retried internally, bounded, re-evaluating both guards against the winner's row).
   Guard 6 rejects a fingerprint that is **superseded** (present in history but not current), since
   writing it would silently revert a later push made from elsewhere; **current** (idempotent
   re-push) and **unknown** (divergent edit, or no row/history yet) both proceed normally. Guard 7
   rejects a supplied `If-Match` that does not match the row's fingerprint AT THE POINT the write
   would occur; `If-Match` is ignored when no row exists yet (a first push is a create with nothing
   to compare against).

On success, the parsed document's top-level keys are checked against the honored allowlist -
`work_item_schemas`, `note_schemas`, `traits`, `project`, `note_limits`, `status_labels`,
`resources`, `schema_resolution` (AR-39) - and any other key present (e.g. `actor_authentication`, which stays global-only - see
[`config-format.md`](../../claude-plugins/task-orchestrator/skills/manage-schemas/references/config-format.md))
is reported in the response's `ignoredSections` array so a push is never silently partial.
`resources` is honored with an inverted precedence versus the other six keys - global wins on a
registry key collision, not per-root - see "Per-root honorable settings" in `config-format.md`.

**Responses:**
- `200 OK` -> `ProjectConfigResponseDto` (no `configYaml` field on this verb; `ignoredSections`
  and `schemaWarnings` present only when non-empty). `schemaWarnings` carries per-entry parse
  warnings from `YamlSchemaParser` (e.g. an invalid note `role` value) - the push still succeeds
  and the config is still stored even when warnings are present; only a hard parse/shape failure
  (guards 1-6 above) short-circuits the push. `ETag: "cfg-<fingerprint>"`
- `404 not_found` - `{rootId}` does not resolve to an existing WorkItem
- `422 validation_error` - `{rootId}` is not depth-0
- `422 parse_error` - `configYaml` failed SafeConstructor parse-validation
- `422 rootid_mismatch` - `configYaml` embeds a `project.rootId` differing from `{rootId}` (message
  names both ids); retry with `?force=true` to bypass
- `409 superseded` - `configYaml`'s fingerprint is known-old (guard 6 above); message names the
  server's current `updatedAt`; retry with `?force=true` to overwrite anyway. Distinct from
  `412 etag_mismatch` below - this is a known-old-**content** guard, not a concurrent-write guard.
- `412 etag_mismatch` - `If-Match` supplied and mismatched against an EXISTING row's fingerprint AT
  THE POINT the write would occur (guard 7 above; a first push to a root with no prior row ignores
  `If-Match` - there is nothing to match yet); response carries a refreshed `ETag` header for the
  row's actual current fingerprint
- `413 payload_too_large` - body exceeds 128 KiB; enforced before the body is fully buffered (see §5)
- `403 scope_forbidden` - capability present but `{rootId}` outside token scope
- `500 db_error` - a repository failure surfaces here rather than silently skipping a guard -
  guard evaluation is fail-closed, not fail-open. The guarded write runs once inside its unit of
  work (single writer), so there is no compare-and-set retry budget to exhaust. SQLITE_BUSY is
  retried by the unit; a BUSY that outlasts the unit deadline is reported like any other store
  fault, as `500 db_error` with `Failed to store project config` (not `unavailable`)

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

### GET /roots/{rootId}/config/effective

Additive route (AR-39/C5): every registered type resolved against `{rootId}`'s LAYERED
(per-root-over-global) config in one response — the same view `EffectiveConfigResolver` and MCP
`query_items(operation="schema", type=K, rootId=R)` already compute. Distinct from `GET /config`
(§17, GLOBAL-only) and `GET /roots/{rootId}/config` above (the RAW stored YAML): this route reports
the RESOLVED base schema per type, with no trait merging — same as `SchemaDto`/the MCP type-path.
Purely additive: no existing `/config*` or `/roots/{rootId}/config` route, body, or ETag changes.
Requires `READ`.

**Authorization/validation order:** `READ` capability → parse `{rootId}` as a UUID (`400
bad_request` on failure) → `ApiScope.rootIds` scope check (`403 scope_forbidden`) → root WorkItem
exists (`404 not_found`) → root is depth-0 (`422 validation_error`) → exactly one per-root config
read, wrapped in the same `config_unavailable` 503 envelope `POST /items/{id}/advance` and `GET
/items/{id}/gate` use (see §6, §9, §10) — see §4/§5's note above on the shared REST/MCP cache.

**Responses:**
- `200 OK` → `EffectiveConfigDto`; `ETag: "eff-<fingerprint>"` — a composite fingerprint over
  BOTH layers (`"eff-" + sha256Hex("global:" + (globalFingerprint ?: "-") + "\nper-root:" +
  (perRootFingerprint ?: "-"))`), distinct from `/config*`'s and `/roots/{rootId}/config`'s
  `"cfg-"` prefix so an effective ETag can never be mistaken for a per-root-config `If-Match`
  fingerprint value
- `304 Not Modified` — `If-None-Match` matches the current effective ETag
- `400 bad_request` — `{rootId}` is missing or not a valid UUID
- `403 scope_forbidden` — capability present but `{rootId}` outside token scope
- `404 not_found` — `{rootId}` does not resolve to an existing WorkItem
- `422 validation_error` — `{rootId}` resolves to a WorkItem that is not depth-0
- `500 db_error` — the root-lookup read itself failed with a repository error other than
  not-found (a transient DB failure surfaces here, distinct from the 404 case above)
- `503 config_unavailable` — the per-root config read failed and there was no last-known-good
  cached config for `{rootId}` (see §6); transient, no `Retry-After` header

`schemaResolution` on the response body is `{rootId}`'s effective `schema_resolution` mode —
`"legacy"`, `"layered"`, or `"isolated"` — and is **always present** on a `200` response (never
omitted; `"legacy"` when the key is absent everywhere). See
[`config-format.md`](../../claude-plugins/task-orchestrator/skills/manage-schemas/references/config-format.md)
→ "Global vs Per-Project Config" for the mode precedence chains and how the effective mode is
chosen, and `EffectiveConfigDto` above for the full response shape.

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

## 19a. Endpoints — Rules (Per-Root)

Read-only REST counterpart of the MCP `query_rules` tool's DIRECT (`rootId`+`key`) lookup -- git-
tracked, client-neutral operating rules (the blind-authorship protocol, commit discipline,
forbidden test patterns, review scoping, and so on), stored as `rule/<key>` plan documents (§19)
and served here verbatim. Both surfaces converge on the same `RuleService` -- a pure read-only view
over the same `rule/<key>` rows `manage_plan_documents`/`PUT /roots/{rootId}/plans/{slug}` write, so
MCP and REST always agree on `rulesVersion` (the backing document's `contentHash`) and `body` for
the same key. REST has **no item/skill-pointer mode** -- that mode resolves an item's effective
(trait-merged) schema, which REST config-adjacent routes never read; use the MCP `query_rules` tool
with `itemId`+`noteKey` for skill-pointer resolution.

All verbs require `ApiScope.rootIds` (when scoped) to contain `{rootId}` -- `403 scope_forbidden`
otherwise. `{rootId}` must resolve to an existing, depth-0 WorkItem. `{key}` must match the rule-key
grammar `^[a-z0-9][a-z0-9._-]{0,99}$` -- lowercase alphanumeric, `.`, `_`, `-`; starting
alphanumeric; max 100 characters; never `/`, uppercase, or `:` (see §8's `RuleResponseDto`). The
route takes the BARE key, not a `rule/`-prefixed slug -- the server derives the `rule/<key>` slug
internally; stashing or reading the underlying plan document directly still goes through §19's
`{slug}` routes, which need a literal `%2F` in the path for a `rule/`-prefixed slug (e.g. `PUT
/roots/{rootId}/plans/rule%2Fcommit-discipline`).

**Validation/authorization order** (mirrors `EffectiveConfigRoutes`'s documented order, §18):
parse `{rootId}` as a UUID (`400 bad_request`) → parse/validate `{key}` grammar, `{key}` route
only (`400 bad_request`) → `enforceScopeForItem` (`403 scope_forbidden`) → root WorkItem exists
(`404 not_found`) → root is depth-0 (`422 validation_error`) → `rule/<key>` document exists,
`{key}` route only (`404 rule_not_found`) → `200 OK`. A repository failure at any read step is
`500 db_error`.

### GET /roots/{rootId}/rules/{key}

Reads back one rule's body and `rulesVersion`. Requires `READ`.

**Responses:**
- `200 OK` → `RuleResponseDto`
- `400 bad_request` — `{rootId}` is missing or not a valid UUID, or `{key}` fails the rule-key
  grammar
- `403 scope_forbidden` — capability present but `{rootId}` outside token scope
- `404 not_found` — `{rootId}` does not resolve to an existing WorkItem
- `422 validation_error` — `{rootId}` resolves to a WorkItem that is not depth-0
- `404 rule_not_found` — `{rootId}` is a valid depth-0 root, but no `rule/{key}` document exists
  there
- `500 db_error` — the root-lookup or document read itself failed with a repository error

### GET /roots/{rootId}/rules

Lists `{key, rulesVersion, updatedAt}` for every valid rule key under `{rootId}`, sorted by key
ascending, never the body. A `rule/<key>` document whose key fails the grammar (stashable via
`manage_plan_documents`, which does not itself enforce it, but never served here) is silently
excluded, as are non-`rule/` slugs. `status` (`pending` vs `adopted`) is ignored -- both are listed.
Requires `READ`.

**Responses:**
- `200 OK` → `RuleListResponseDto`
- `400 bad_request` — `{rootId}` is missing or not a valid UUID
- `403 scope_forbidden` — capability present but `{rootId}` outside token scope
- `404 not_found` — `{rootId}` does not resolve to an existing WorkItem
- `422 validation_error` — `{rootId}` resolves to a WorkItem that is not depth-0
- `500 db_error` — the root-lookup or list read itself failed with a repository error

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
  "actorAuthenticationEnabled": false,
  "features": ["seats", "dispatchBySeat", "independent_of", "rules"]
}
```

`features` (array, A1) advertises server-wide optional-response capabilities this server version
can serve, **unconditionally** — regardless of whether the resolved config for any given root
actually declares `seats:`/`independent_of:`. `"independent_of"` (A2) joined this list once the
independence attestation gate started enforcing it, and `"rules"` (A3) advertises the per-root
rule text served by §19a — see [api-reference.md](api-reference.md)
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
  "version": "3.8.0",
  "apiVersion": "v1",
  "apiUrl": "/api/v1",
  "features": ["seats", "dispatchBySeat", "independent_of", "rules"]
}
```

`features` (array, A1) — same server-wide feature advertisement as `GET /api/v1/info`'s `features`
above.

---

## 21. Server-Sent Events (SSE)

### GET /api/v1/events

Real-time event stream. Requires `READ` or `ADMIN` capability.

**Delivery guarantee:** the stream is a projection of the durable `events` table (4.0, migration V20). Every write records its event rows in the same database transaction as the change, so a rolled-back write records (and streams) nothing. Events reach subscribers in commit order (`id` = the row's `seq`). A write made by this process streams as soon as it commits; a write made by another process sharing the database (for example an MCP `stdio` server) streams within about one second, picked up by a 1 s poll while any subscriber is connected. A caller observing a `200`/`201` response is guaranteed the corresponding event is stored and replayable.

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
4. If none of the above apply → `401 invalid_request` when no token is presented (no `Authorization` header and no allowed `?token=`), or `401 invalid_token` for an unknown, expired, or invalid token; a valid token lacking the `read` capability gets `403 insufficient_scope`

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
events on the same item. See §25 for the `item.deleted` fail-closed gap this scheme has.

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

- `actor` — `{id, kind, parent?}`: who performed the write. Never `proof` or `verification`. Resolution order at the moment the event is queued: the note's own `actorClaim` (`note.upserted`), then the actor of the enclosing write (the MCP tool's `actor`, per transition for `advance_item` — a cascaded parent advance carries the triggering transition's actor — or the synthesized `api:<tokenId>` / `external` claim for REST writes), then none. A write with no actor (a tool call without `actor`, a background sweep) emits no `actor` field.
- `rootId` -- the item's depth-0 ancestor (`scope.left` carries the OLD root, `scope.entered` the NEW root). Always present on a domain event since 4.0 (a root item uses its own id), live and on replay alike. `rootId` is scope metadata and is never redacted.

**Redaction.** Applied per connection, on egress only, identically for live delivery and `Last-Event-ID` replay: `actor` is omitted when `API_REDACT_NOTE_ATTRIBUTION=true` and the caller lacks `ADMIN`; otherwise it is delivered (`API_AUTH_MODE=none` callers are ADMIN).

**`item.updated` note:** since 4.0 an update that changes no field emits nothing, and a role change that also edits other fields emits only `item.advanced`.

**`item.advanced` note:** This event is the projection of the transition row a role change records (via `advance_item`, `complete_tree`, `POST /items/{id}/advance`, including cascaded parent transitions). It carries the `newRole` field. This is distinct from `item.updated` -- a role change emits `item.advanced` (not `item.updated`).

**Claim/release note:** A successful claim or release through the MCP `claim_item` tool (its `claims` and `releases` arrays) emits `item.updated` for the claimed/released item -- and, for a claim that auto-releases the agent's other held items, one additional `item.updated` per auto-released item. A transition that ends a held claim (for example completing a claimed item) also emits `item.updated` for that item since 4.0.

**Bulk-write note:** `create_work_tree` emits `item.created` for each newly created item (root first; an attach-mode pre-existing root emits nothing), then `dependency.added` per edge and `note.upserted` per note, all after the enclosing transaction commits -- a rolled-back tree emits nothing. Deleting all of an item's notes at once emits one `note.deleted` per note (since 4.0; was one per call); removing all of an item's dependencies emits one `dependency.removed` per edge. Deleting an item also emits one `note.deleted` per note and one `dependency.removed` per edge the database cascade removes, before the `item.deleted`.

**Table-only events:** the `events` table also records rejections (`transition.rejected`, `claim.rejected`, `lease.rejected`), expiry (`claim.expired`, `lease.expired`), resource-lease acquire/release, per-root config pushes and plan-document stash/adopt. These are audit rows and are not streamed; the stream carries only the event types listed above.

---

## 22. Audit Model

All write endpoints (POST, PATCH, PUT, DELETE) synthesize an actor server-side from the authenticated principal. Client-supplied `actor.*` fields in the request body are **silently dropped** — callers cannot override audit attribution.

**Synthesized actor:**
- `id` = `"api:<tokenId>"` (e.g., `"api:dashboard-editor"`)
- `kind` = `"external"` (distinguishes API writes from MCP agent writes)
- `parent` = `null`

**Attribution redaction (applies to notes and transitions):**
- Non-admin callers: `actor` and `verification` fields are `null` in responses
- Admin callers: fields are visible subject to `API_REDACT_NOTE_ATTRIBUTION`
- `verification.proof`: the evidence that replaced the raw proof — a SHA-256 hash of the proof
  (whenever one was supplied) plus, only when the proof was cryptographically `verified`, the
  verified JWT claims (`iss`/`sub`/`aud`/`jti`/`iat`/`exp`/`kid`/`alg`). Requires `ADMIN`
  capability; independent of `API_REDACT_NOTE_ATTRIBUTION`.

**Redaction env vars:**
- `API_REDACT_NOTE_ATTRIBUTION` (default `true`) — when `true`, non-admin callers see no attribution
  (this includes the `actor` field on SSE events; see §21)

**Independence violations (A2) are redacted by construction, not by policy.** `IndependenceViolationDto`
carries only seat names and note keys (`key`, `seat`, `constraint`, `conflictingSeat`, `waived`) —
never an actor id, proof, or verification claim — on every surface (`GateStatusDto.violations`,
`AdvanceResponseDto.violations`, `CascadeEventDto.violations`, `422 gate_blocked`'s
`details.violations`). This is unconditional: it does not vary with `API_REDACT_NOTE_ATTRIBUTION`,
caller capability tier, or admin status, because there is no attribution-bearing field on the DTO for
`AttributionRedactor` to redact in the first place.

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

This same caveat applies to the `statusGraph` field `GET /roots/{rootId}/config/effective` (§18)
returns: it is built by the same status-graph construction over that root's resolved schema set, so
it is equally structural-only — it does not reflect note gates, dependency blockers, claim
ownership, or per-item lifecycle exceptions for `{rootId}`'s items either.

---

## 25. Known Limitations

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

**FTS5 requires SQLite.** Search endpoints (`GET /search`, `GET /notes/search`) run on the SQLite FTS5 virtual tables created by the Flyway migrations.

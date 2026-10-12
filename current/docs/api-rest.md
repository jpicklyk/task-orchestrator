# REST API Reference — MCP Task Orchestrator

This document describes the HTTP REST API layer (v1) added alongside the MCP transport. The REST API runs on the same Ktor server when `API_ENABLED=true`.

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
consumer: it polled `GET /items/{id}/gate`, which no longer exists (it is `POST /ops/items_read` with `select: context`, section 20a; the hook is re-pointed in W8), to decide whether to send a returning subagent back
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
11. [Endpoints - Notes (Read)](#11-endpoints---notes-read)
12. [Endpoints - Notes (Write)](#12-endpoints---notes-write)
13. [Endpoints — Dependencies](#13-endpoints--dependencies)
14. [Endpoints — Resource Leases](#14-endpoints--resource-leases)
15. [Endpoints — Transitions (Audit), moved to `items_read` `history`](#15-endpoints--transitions-audit-moved-to-items_read-history)
16. [Endpoints — Search](#16-endpoints--search)
17. [Endpoints — Config JSON Schema](#17-endpoints--config-json-schema)
18. [Project Config (Per-Root), moved to the `config` tool](#18-project-config-per-root-moved-to-the-config-tool)
19. [Endpoints — Plan Documents (Per-Root)](#19-endpoints--plan-documents-per-root)
19a. [Rules (Per-Root), moved to the `config` tool](#19a-rules-per-root-moved-to-the-config-tool)
20. [Endpoints — Service Meta](#20-endpoints--service-meta)
20a. [Endpoints - Ops (RPC)](#20a-endpoints---ops-rpc)
21. [Server-Sent Events (SSE)](#21-server-sent-events-sse)
22. [Audit Model](#22-audit-model)
23. [Status-Graph Caveat](#23-status-graph-caveat)
24. [Known Limitations](#24-known-limitations)

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
| `WRITE_ITEMS` | `write-items` | `items_write` ops through `POST /ops/items_write` (create, update, delete, and `create_tree` in the orchestration profile) |
| `WRITE_NOTES` | `write-notes` | `POST /ops/notes_write` ops `upsert` and `delete` (checked per op by the dispatcher, section 20a) |
| `ADVANCE` | `advance` | The `advance` tool's ops (`transition`, `claim`, `release`, `heartbeat`) through `POST /ops/advance`, and `items_write` `complete_tree` (orchestration profile) through `POST /ops/items_write` (section 20a) |
| `MANAGE_DEPENDENCIES` | `manage-dependencies` | `POST /ops/deps_write` ops `create`, `delete`, `linear`, `fan_out` and `fan_in` (checked per op by the dispatcher, section 20a) |
| `WRITE_CONFIG` | `write-config` | `POST /ops/config` ops `push`, `rollback` and `reset`, `POST /ops/docs_write` ops `upsert`, `archive`, `unarchive`, `delete`, `attach` and `detach` (checked per op by the dispatcher, section 20a) |
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

- `GET /items/{id}` and `GET /items/{id}/children` (section 9) - the `items_read` rules: a target item
  outside the scope is `403 forbidden` (envelope, `detail.scope` `root` or `tag`), and under a restricted
  scope an id that matches nothing the caller may see is `403 forbidden` too, never `404`, so scope is no
  existence oracle; children outside the scope are left out of the page and not counted
- Write endpoints - `403 scope_forbidden` if the target item is outside scope
- `POST /ops/items_read` (section 20a) - every select applies the same rules; `list`, `search` and
  `overview` apply the scope before paging, ranking and capping, so `page.total` counts only visible items
  and a restricted token gets a full page of in-scope hits (a note hit is checked on its owning item);
  `related` checks its subject item like `get` and drops every out-of-scope relative (from any signal:
  dependencies, links, tags, text) before fusion and the cap, so a dropped relative is never named and
  never counted (a document is visible exactly when its root item is);
  `ancestors` stop at the caller's scope root and drop ancestors that fail `tags_include`
- `items_read` `history` (the transition audit trail) - a root scope sees only its roots' rows and a `tags_include` token only rows for items it is allowed to see
- `POST /ops/deps_read` and `POST /ops/deps_write` (section 13) - the subject item of a read is
  checked (`403 forbidden`); an edge whose counterparty is out of scope is dropped from a read page
  and not counted; a write needs every named item (both stored ends for a delete) in scope
- `GET /api/v1/events` (SSE) - a `tags_include` token is enforced per-event, identically for the
  live stream and Last-Event-ID replay (see section 21)

**Item writes are scope-checked too.** Item creates, updates and deletes are the `items_write` ops of
`POST /ops/items_write` (§20a); each element is checked against the token's scope before anything is written
(`forbidden`, `detail.scope` `root` or `tag`). A newly created item's ancestor chain is only its own
(server-generated) id, so it can never already be listed in a `root_ids` allowlist: a root create with a non-null
`scope.root_ids` is `forbidden`. A `tags_include`-only token may create a root item only if the tags it is creating
that root *with* carry a listed tag. A reparent is checked against the new parent, and `clear: ["parentId"]` (move
to root) under `root_ids` is allowed only when the item itself is a listed root.

**A collection never turns a tag-scope mismatch into `403`.** A tag-scoped caller whose scope matches
nothing on a collection (an `items_read` `list`, `search`, `overview` or `related` page, `GET /items/{id}/children`,
`ancestors`, an `items_read` `history` page) gets `200 OK` with an empty or partially filtered result; `403` is
reserved for a single item named directly.

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
- Notes: `GET /items/{id}/notes/{key}` answers `ETag: "v{version}"` from the note version (V28) and honors `If-None-Match` (`304`); there is no note `If-Match`: guard a write with `notes_write` `expectedVersion` (section 12)

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

- Every write route bounds its body read to a fixed byte limit BEFORE buffering it: a `Content-Length` over the limit is rejected with `413 payload_too_large` before any bytes are touched, and a chunked/understated-`Content-Length` body is caught by a channel read capped at `limit + 1` bytes, so an oversized body is never buffered in full either way. `POST /api/v1/ops/{tool}` has a 1 MiB limit (its `payload_too_large` is an envelope error, section 20a); a `docs_write` document body is capped by the document service instead (section 6). See §6 for the legacy `payload_too_large` error shape.

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
| `not_found` | 404 | Item, note, or dependency not found |
| `scope_forbidden` | 403 | Item exists but is outside the caller's scope (see §3) |
| `etag_mismatch` | 412 | `If-Match` header does not match current ETag |
| `payload_too_large` | 413 | Request body exceeds its route's byte limit — the `Content-Length` header alone if it declares a size over the limit (body untouched), otherwise the actual bytes read, capped at `limit + 1` so an oversized body is never buffered in full. `POST /api/v1/ops/{tool}` is 1 MiB (an envelope error, section 20a); a document body written through `docs_write` is capped by the document service, not by a route limit: 65536 bytes, or 16384 for `kind` `rule` (a `payload_too_large` element), the single enforcement point the `config` tool's `rules` op relies on (see §18, §19, §19a). |
| `invalid_request` | 401 | `error_description` body. Missing `Authorization` header, a non-Bearer scheme, or an empty Bearer credential (also the SSE pre-flight when no header or allowed `?token=` is presented). Carries `WWW-Authenticate: Bearer error="invalid_request"`. |
| `invalid_token` | 401 | `error_description` body. Unknown, expired, or otherwise invalid token (bearer or JWKS). Carries `WWW-Authenticate: Bearer error="invalid_token"`. |
| `verification_failed` | 401 | Not currently reachable via REST — a JWT passing `ApiBearerAuth` is always `VERIFIED`, which every `degradedModePolicy` trusts. Reserved for the same audit-policy check used by MCP tool calls, where a self-reported actor under a degraded JWKS result can still be rejected. |
| `insufficient_capability` | 403 | Caller's token lacks a capability required by the request itself (distinct from `scope_forbidden`'s root-scope check) — e.g. a caller without `ADMIN` calls a legacy route that needs it (the lease admin routes answer the envelope `forbidden` instead, see section 14) |
| `insufficient_scope` | 403 | `error_description` body. A generic `requireCapability` check failed for the plugin's configured capability; (SSE-specific) a `GET /api/v1/events` connection presents a valid token that lacks the `read` capability (see §21); (SSE-specific) a `GET /api/v1/events` connection carries a `tags_include` scope but the route has no `WorkItemRepository` wired to filter by it -- fail-closed rather than serving an unfiltered stream; or (SSE-specific) a root-scoped principal's `?root=` values do not intersect its token's `scope.rootIds` -- the requested roots are entirely outside scope (see §21) |
| `not_claim_holder` | 403 | Not an `ErrorDto` route error: the `advance` tool (`POST /ops/advance`, section 20a) returns it in its envelope for a `transition` on an item another actor holds, or a `release`/`heartbeat` of an item the caller does not hold. Claim ownership applies on REST as on MCP since 4.0 |
| `internal` | 500 | An exception escaped a `/api/v1` route. The message is always `Internal server error`: the exception text is logged at ERROR and never returned. Produced by the REST safety net (see below) |
| `config_unavailable` | 503 | Per-root config read failed (a transient database error) and there was no last-known-good cached config to serve for that root — transient, retryable. No REST route returns it any more: the config reads moved to `POST /ops/config` and the gate to `items_read` `select: context`, which answer the envelope error `unavailable` (`retryAfterMs` 1000) instead (section 20a). Both read inside a unit of work, which has no last-known-good fallback, so a read fault there answers `unavailable` even when the cache is warm. |
| `config_invalid` | 422 | A stored config head (the server head, or a root's) has no canonical form or cannot be decoded. Fail closed: the config is never treated as absent and never falls through to another layer. No REST route returns it any more: item writes, the item schema view, the gate and the config reads moved to `/ops/items_write`, `/ops/items_read` (the gate is `select: context`) and `/ops/config`, which answer it in the 4.0 envelope; permanent, a fresh config push restores the root. |
| `db_error` | 500 | A store fault: a read route responds `Database query failed` (or the route's own read text), a write route its existing text (e.g. `Failed to create item`). A store fault on a read is never reported as `404 not_found`; `404` means only that the row does not exist. |

**One mapper, one safety net.** Every `ErrorDto` body is built by one mapper (`LegacyRestErrorMapper`): each
code above is bound to its HTTP status and to the error catalog code that classifies it, so a route names a
code instead of a free string and the status cannot drift from the wire code. REST carries no `kind` and no
catalog `details` in this release; the bodies, statuses, `details` objects and `Retry-After` headers are the
3.x ones. When the REST API is enabled, an exception that still escapes a `/api/v1` route is answered by a
Ktor `StatusPages` handler scoped to `/api/v1` paths (other paths, `/mcp` included, keep Ktor's default):
Ktor's own status exceptions keep their status (`400 bad_request` for a malformed body or parameter,
`404 not_found`, `415 unsupported_media_type`, `413 payload_too_large`), a domain validation failure is
`400 validation_error`, and anything else is `500 internal` with the fixed message above. A cancelled request
is never answered. An API-disabled deployment installs nothing.

---

## 6a. Request correlation (`X-Req-Id`)

Every `/api/v1` response except the `GET /api/v1/events` stream carries an `X-Req-Id` header: the
call's 8-character correlation id (lowercase Crockford base32). It is always server-generated (an
inbound `X-Request-Id` only feeds the `requestId` log field), appears on success and error responses
alike (including `401`), is the `req_id` of every `events` row the request wrote, and keys the
request's row in the `call_log` table. Only `/api/v1` and paths under `/api/v1/` are covered (not
`/api/v10`). A `401` from the auth layer records its body's `error` code (`invalid_request`,
`invalid_token`, ...) as the row's `error_code`; `http_<status>` is the fallback. The row's `tool` is
the matched route template as declared (`POST /api/v1/ops/{tool}`); a call that matched no route is
`<METHOD> /api/v1/<first segment>` for a served top-level resource, else `<METHOD> unmatched`. `principal_id`
on REST rows is the authenticated API principal (`api:<tokenId>`); only MCP rows can carry a self-reported actor id (up to 500 characters, with `proof_status` distinguishing verified ids). `X-Req-Id` is in the default
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
  "traits": ["string"],
  "properties": {},
  "createdAt": "ISO-8601",
  "modifiedAt": "ISO-8601",
  "roleChangedAt": "ISO-8601",
  "etag": "\"v1-<millis>\"",
  "depth": 0,
  "isClaimed": false,
  "notes": null,       // always null since the include parameter was removed
  "children": null,    // always null since the include parameter was removed
  "dependencies": null // always null since the include parameter was removed
}
```

**Notes on `tags`:** The domain stores tags as a comma-separated string. The REST API expands this to a `List<String>` in `ItemDto` (GET responses); the `items_write` tool takes `tags` as a JSON array.

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
blocks `toItemId`); edges are written through `deps_write` (section 13), which accepts only
`blocks`/`relates_to`. `unblockAt` is the effective unblock-role
threshold: `null` only for `relates_to` (no blocking semantics); for `blocks` it is the stored value,
defaulting to `"terminal"` when unset — never a raw possibly-null passthrough.

### PageDto\<T\>

See §7.

### ErrorDto

See §6.

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
`meta.reqId`, with the HTTP status taken from the envelope's error code (not the `ErrorDto` body of
section 6). `{id}` is a full UUID or 8 to 32 hex characters of one. Capability and scope are enforced by
the dispatcher and the select exactly as on `/ops`: an out-of-scope item, or under a restricted scope an
id that matches nothing the caller may see, is `403 forbidden`. A request without an authenticated
principal is `401 unauthenticated`. Fields, views and error codes are described in `api-reference.md` ->
`items_read`.

The 3.x `GET /items`, `GET /items/roots`, `GET /items/{id}/tree`, `GET /items/{id}/breadcrumbs` and
`GET /items/{id}/schema` routes are removed, together with the `include` parameter of `GET /items/{id}`.
`GET /items/{id}/tree`, `/breadcrumbs`, `/schema` and `/gate` are no longer routed (`404`). `GET /items` is a method
the path no longer serves and `GET /items/roots` is read as `GET /items/{id}` with the id `roots`; both
answer a `4xx` client error, never a list. Use `POST /api/v1/ops/items_read`:

| Removed route | `items_read` request |
|---|---|
| `GET /items` | `{select: "list", ...filters}` |
| `GET /items/roots` | `{select: "list", depth: 0}`, or `{select: "overview"}` for counts |
| `GET /items/{id}/tree` | `{select: "list", ancestorId: id}` |
| `GET /items/{id}/breadcrumbs` | `{select: "get", itemId: id, fields: ["ancestors"]}` |
| `GET /items/{id}/schema` | `{select: "schema", itemId: id}` (add `fields: ["guidance"]` for guidance text) |
| `GET /items/{id}/gate` | `{select: "context", itemId: id}`; the gate is `data.gateStatus` (orchestration profile) |
| `GET /items/{id}?include=notes` | `notes_read` `{select: "list", itemId: id}` |
| `GET /items/{id}?include=deps` | `deps_read` `{select: "list", item: id}` |
| `GET /items/{id}?include=children` | `GET /items/{id}/children` |

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

### Gate status

`GET /items/{id}/gate` is removed. The gate state of an item (`canAdvance`, `phase`, `blockedBy`, `blockers`, `contended`,
`missing`, `missingBySeat`, `violations`) is `data.gateStatus` of
`POST /api/v1/ops/items_read` with `{"select": "context", "itemId": "<id>"}` (section 20a), the same answer as MCP
`items_read(select="context")`; `guidanceKey` and `skillPointer` are siblings of `gateStatus` in `data`, not inside it; see
[api-reference.md](api-reference.md#items_read). A removed route answers `404`.

---

## 10. Endpoints — Items (Write)

Item creates, updates and deletes are no longer separate routes: `POST /items`, `PATCH /items/{id}` and
`DELETE /items/{id}` never succeed or write: they answer a client error (`404` or `405`). Use the `items_write` tool, `POST /api/v1/ops/items_write` (§20a), with
`ops[]` elements `create`, `update` and `delete`, and in the orchestration profile only (amendment U23) `create_tree`
(a root with its children, dependencies and notes as one write unit; capability `write-items`) and `complete_tree`
(complete or cancel a tree or an id list through the `advance` pipeline; capability `advance`), which replace the 3.x
`create_work_tree` and `complete_tree` MCP tools (neither had a REST route); the fields, the optimistic lock (`expectedVersion`, replacing
`If-Match`), the soft delete rules (`recursive`), the scope checks and the errors are in
[`api-reference.md`](./api-reference.md#items_write). Write elements take an `idempotencyKey` in place of the
`Idempotency-Key` header.

### Advancing items (moved)

`POST /items/{id}/advance` was removed in 4.0. Phase transitions and claim ownership are the `advance` tool, called
over MCP or `POST /api/v1/ops/advance` (section 20a) with the `advance` capability. Its ops are `transition`,
`claim`, `release` and `heartbeat`; see the `advance` section of `api-reference.md` for the element shapes and error codes.
Unlike the removed route, `transition` enforces claim ownership on REST exactly as on MCP (an item claimed by another actor is
`not_claim_holder`), resource leases are always enforced (there is no override flag), and every answer is the response
envelope. `DELETE /resources/leases/{key}` (section 14) is the recovery path for a stuck lease.

---

## 11. Endpoints - Notes (Read)

Both routes are thin translations to the 4.0 `notes_read` tool through the shared dispatcher (see
`api-reference.md` -> `notes_read`). They answer the 4.0 response envelope, byte-equal to
`POST /api/v1/ops/notes_read` (section 20a) for the same arguments apart from `meta.reqId`, with the HTTP status
taken from the envelope's error code (not the `ErrorDto` body of section 6). Both require `READ` capability and
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

## 12. Endpoints - Notes (Write)

The 3.x `PUT /items/{id}/notes/{key}` and `DELETE /items/{id}/notes/{key}` routes are removed and no longer
served. Notes are written through
`POST /api/v1/ops/notes_write` (section 20a) with `ops[]` elements `upsert` and `delete`, each with an optional
`expectedVersion` guard (`0` = must not exist, `n` = current version must be `n`; otherwise
`409 version_conflict`, nothing written) and an optional per-element `idempotencyKey` (an
`Idempotency-Key` header is rejected on `/ops`). Requires `WRITE_NOTES`. The write policy (role
normalization, 65536-byte body cap, CRLF to LF, schema role, `maxLength` under `note_limits`) is
described in `api-reference.md` -> `notes_write`.

**`bodyFromFile` is MCP-only.** It reads a file on the server's host, so REST takes the note text inline in
`body` only (as the 3.x REST note writes did). An `upsert` element that sets `bodyFromFile` on
`POST /api/v1/ops/notes_write` fails alone with `invalid_request` on field `/bodyFromFile` (reason: use the
inline `body`); no file is opened and nothing is stored for it, and the other elements of a non-atomic batch
still run.

---

## 13. Endpoints — Dependencies

The 3.x `GET /items/{id}/dependencies`, `GET /items/{id}/backlinks`, `POST /dependencies` and
`DELETE /dependencies/{id}` routes are removed and no longer served. Dependencies are read and written
through the 4.0 tools over `POST /api/v1/ops/{tool}` (section 20a):

- `POST /api/v1/ops/deps_read` - `select: list` (the edges touching an item, filtered by `direction`
  `incoming` / `outgoing` / `all` and `type`; the old backlinks are `direction: incoming`) or
  `select: graph` (the blocking graph around an item in topological order). Requires `READ`. A subject
  item outside the token's scope is `403 forbidden`; an edge whose counterparty is out of scope is
  dropped from the page and not counted.
- `POST /api/v1/ops/deps_write` - `ops[]` elements `create`, `delete` (by `id`, by `from`/`to`, or every
  edge of an `item`), and the patterns `linear`, `fan_out` and `fan_in`, each one element created all or
  nothing. Requires `MANAGE_DEPENDENCIES`, checked per op. Every named item (both stored ends for a
  delete) must be in scope, else the element is `forbidden` and nothing is written. A duplicate edge is
  `duplicate`, a cycle `cycle_detected`; per-element `idempotencyKey` replaces the `Idempotency-Key`
  header.

Fields, answers and error codes are described in `api-reference.md` -> `deps_read` and `deps_write`.
`GET /items/{id}` no longer takes `include`: `deps_read` `select: list` replaces `include=deps`.

---

## 14. Endpoints — Resource Leases

Server-wide primitive backing the resource-lease gate of the `advance` tool's `transition` op and the
`resources:` trait dimension (see `config-format.md`). **Cross-project, not per-root** — unlike
every other `/api/v1` resource in this document, these routes carry no `rootId` scoping; a lease key
is a server-wide namespace (see the "global-wins" registry precedence note in `config-format.md`).

All three routes answer the **response envelope** (`ok`, `data`/`error`, `meta.reqId`), with the status of the error code.
A token lacking the needed capability gets `forbidden` (`detail.scope = "capability"`, `detail.required` the capability name).
A `401` from the authentication plugin keeps the legacy body.

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
  on field `at`. When absent, returns the most recent intervals (open or closed), newest-first by `acquiredAt`.
- `limit` — max rows, an integer from `1` to `200`, default `50` (any other value is `invalid_request` on field `limit`).
  Applied in SQL after the newest-first ordering, so a large history is never loaded to be truncated.

A lease is active while its expiry is strictly after "now": an expiry equal to now is expired, so a release at exactly the expiry instant records `expired`. All lease timestamps are canonical UTC text from the server's one bound clock.

**Response `200 OK`:** a list page; each item is `{resourceKey, holderItemId, acquiredByActorId?, acquiredAt, expiresAt,
releasedAt?, releaseReason?, releasedByActorId?}` (`releaseReason` is `released`, `expired` or `force_released`; the
last three are absent while the interval is open), `page.total == items.length`. `acquiredByActorId` / `releasedByActorId`
are present only for `ADMIN` callers.

No pruning/retention in v1 — the table is append-only and grows with lease-event cardinality
(documented, accepted behavior; see `current/src/main/resources/db/migration/sqlite/V16__Resource_Lease_History.sql`).

---

## 15. Endpoints — Transitions (Audit), moved to `items_read` `history`

`GET /items/{id}/transitions` and `GET /transitions` were removed in 4.0 (they answer `404`). The transition audit
trail is the `history` select of `items_read`, called as `POST /ops/items_read` (section 20a) with
`{"select": "history", "itemId": "<id>"}` for one item or `{"select": "history", "since": "<ISO-8601>"}` across
every item the token may see. It reads the `item.transitioned` rows of the event log, newest first, with an exact
`page.total`, `limit` 1 to 200 (default 50), and optional inclusive `since`/`until` bounds; there is no default time
window and no scan cap. Rows that predate 4.0 were copied from `role_transitions` when the server first started on
the new schema (migration V29) and carry `backfilled: true`. `actor` and `verification` follow the attribution rule
of the notes reads (hidden unless the caller may see attribution); the proof hash and verified claims are never
returned on any surface. See `api-reference.md`, `items_read`, for the entry shape.

---

## 16. Endpoints — Search

All require `READ`.

The 3.x `GET /search` and `GET /notes/search` routes are removed (`404`). Search items and notes with
`items_read` `select: search` over `POST /api/v1/ops/items_read` (section 20a): `kinds` picks item content,
note bodies and/or document title and body (default: every kind the `role`, `tags` and `traits` filters apply
to, so all three without them), ranked in one list, and the token's root and tag scope applies before ranking and
capping. Fields and the hit shape are in `api-reference.md` -> `items_read`.

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

## 18. Project Config (Per-Root), moved to the `config` tool

`GET`, `PUT` and `DELETE /roots/{rootId}/config`, `GET /roots/{rootId}/config/history`,
`POST /roots/{rootId}/config/rollback` and `GET /roots/{rootId}/config/effective` are gone (they answer `404`). Their
replacements are ops of the `config` tool, called as `POST /api/v1/ops/config` (section 20a) and over MCP:

| Removed route | `config` op |
|---|---|
| `GET /roots/{rootId}/config[?version=&fingerprint=]` | `get` |
| `PUT /roots/{rootId}/config[?force=]` | `push` (`force`, `expectedVersion` replace `?force=` and `If-Match`) |
| `DELETE /roots/{rootId}/config` | `reset` (a root with no config, or one already reset, now answers `appended: false` instead of `404`) |
| `GET /roots/{rootId}/config/history[?limit=&before=]` | `history` |
| `POST /roots/{rootId}/config/rollback` | `rollback` (`expectedVersion` replaces `If-Match`) |
| `GET /roots/{rootId}/config/effective` | `effective` |
| none | `validate` (a dry run of `push`) |

Capabilities: `get`, `history`, `validate`, `effective` and `rules` need `read`; `push`, `rollback` and `reset` need
`write-config`; the dispatcher checks each element. The scope check runs on `rootId` first, so a root outside the
caller's scope is `forbidden`, not `not_found`. Status mapping of an element error follows the envelope table in
section 20a: `config_invalid` is `422`, `version_conflict` `409`, `payload_too_large` `413`, `not_found` `404`.

The shapes of the ops, the push pipeline, the strict validation, the fast-forward guard (now `version_conflict`) and
the last-known-good behavior are documented once, in [api-reference.md](api-reference.md#config) and the
[config reference](config-reference.md).

---

## 19. Endpoints — Plan Documents (Per-Root) — removed in 4.0

`PUT /roots/{rootId}/plans/{slug}`, `GET /roots/{rootId}/plans/{slug}` and `GET /roots/{rootId}/plans` are removed
(the paths answer `404`), together with the MCP `manage_plan_documents` tool and the `PlanDocumentResponseDto` family
of responses. Documents (kinds `plan`, `rule` and `page`, addressed by `(rootId, kind, slug)`) are read and written
through the 4.0 envelope tools, served over MCP and over the RPC route (section 20a):

| 3.x | 4.0 |
|---|---|
| `PUT /roots/{rootId}/plans/{slug}` | `POST /ops/docs_write` op `upsert` (`WRITE_CONFIG`); a `rule/<key>` slug is `kind` `rule`, slug `<key>` |
| `GET /roots/{rootId}/plans/{slug}` | `POST /ops/docs_read` select `get` (`READ`) |
| `GET /roots/{rootId}/plans` | `POST /ops/docs_read` select `list` (`READ`) |

Behaviour that changed: the `status` filter and field are gone (an adopted plan reports `adoptedBy`); a write over an
adopted plan is `duplicate` (HTTP 409); `bodyFromFile` is not accepted over REST; `kind` is required. Every op checks the
caller's root and tag scope on `rootId`. See `api-reference.md` (`docs_read`, `docs_write`).

---

## 19a. Rules (Per-Root), moved to the `config` tool

`GET /roots/{rootId}/rules` and `GET /roots/{rootId}/rules/{key}` are gone (they answer `404`). The `config` tool's
`rules` op serves the same git-tracked rule text (`rule` documents, `kind` `rule` and slug `<key>`,
written through `docs_write`): `rootId` lists keys, `rootId` with `key` reads
one rule, and `itemId` with `noteKey` follows a note's skill pointer (now also available on REST). An absent rule is
`not_found` kind `document`, no longer the `rule_not_found` code. See [api-reference.md](api-reference.md#config).

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
  "features": ["seats", "dispatchBySeat", "independent_of", "rules"],
  "configWarnings": []
}
```

`configWarnings` (array of strings, additive) is the stored SERVER config head's warnings: the reasons recorded when the
global config file was imported at boot (for example one per legacy top-level key such as `orchestration`), in the
same string form push warnings use. Empty when there is no server config or it imported clean. A server-head read
fault answers `503 config_unavailable`; `/health` never reads it.

`features` (array, A1) advertises server-wide optional-response capabilities this server version
can serve, **unconditionally** — regardless of whether the resolved config for any given root
actually declares `seats:`/`independent_of:`. `"independent_of"` (A2) joined this list once the
independence attestation gate started enforcing it, and `"rules"` (A3) advertises the per-root
rule text served by the `config` tool's `rules` op (§19a) — see [api-reference.md](api-reference.md)
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

## 20a. Endpoints - Ops (RPC)

### POST /api/v1/ops/{tool}

The canonical RPC route for the 4.0 tool surface: one route that calls any tool in the tool registry, the
same tools MCP serves. `{tool}` is the registry tool name. It always serves the orchestration profile: tool
profiles (`/mcp`, `/mcp/knowledge`, `MCP_TOOL_PROFILE`) are an MCP-connection concept. The
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
hand. Errors use the envelope, not the `ErrorDto` body of the other routes (§6), with the one exception of
`401` below.

**Principal.** The caller's principal comes from the bearer token (`api:<tokenId>`, kind `external`). A body
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
listed root ids or tags joined by `,`. The check is the transport-neutral `ScopeGuard`, the twin of the
`scope_forbidden` check on the other routes: an unrestricted token is admitted with no database read; with a
root restriction the item or one of its ancestors must be a listed root; with a tag restriction the item must
carry one of the listed tags (exact, case-sensitive match on the trimmed comma-separated tags). A missing item
and a lookup failure under a restricted scope are `forbidden` too (fail closed), so scope is never an
existence oracle; the one exception is an id that is itself one of the token's listed roots, which is admitted
under a root restriction (as in 3.x) and then answers the tool's own `not_found`, revealing nothing beyond
the token's own scope. List reads filter out-of-scope entities instead of failing.

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

**Authentication failures keep the legacy body.** A missing or invalid bearer token is rejected by the shared
`ApiBearerAuth` plugin before this route runs, so it answers `401` with the OAuth-style
`error`/`error_description` body described in §1 and §6, not an envelope. Envelope conformance of the `401` is
planned for the end of the 4.0 line (Q32). With the REST API disabled (`API_ENABLED=false`) the route is not
mounted and answers `404` like every other `/api/v1` route.

**Request correlation.** The `X-Req-Id` response header (§6a) equals `meta.reqId` in the envelope, on success
and on every fault above.

**Call log.** The `call_log` row of an `/ops` call (§6a, `fleet-deployment.md` -> "Call log and `reqId`")
records the registry tool name as `tool` (not the route template), the select or op name as `operation`, and
`batch_size`, `failed_count` and `result_count` from the dispatch, with `surface` `rest` and `host` captured
like any other REST row. A fault answered before dispatch (the table above) keeps the route template
`POST /api/v1/ops/{tool}` as `tool` and no `operation`.

**Parity with MCP.** MCP and this route share one tool registry and one dispatcher, so a tool behaves the same
on both surfaces, with one deliberate exception: `notes_write` `bodyFromFile` is MCP-only and answers
`invalid_request` (`/bodyFromFile`) here (section 12). A test-side parity harness runs every registered tool over both transports with identically
seeded databases and compares the envelope bytes (minus `meta.reqId`), the HTTP status against the error
code, and the resulting database state; a coverage guard requires every registered select and op to carry a
happy-path and a scope-forbidden scenario.

---

## 21. Server-Sent Events (SSE)

### GET /api/v1/events

Real-time event stream. Requires `READ` or `ADMIN` capability.

**Delivery guarantee:** the stream is a projection of the durable `events` table (4.0, migration V20). Every write records its event rows in the same database transaction as the change, so a rolled-back write records (and streams) nothing. Events reach subscribers in commit order (`id` = the row's `seq`). A write made by this process streams as soon as it commits; a write made by another process sharing the database (for example an MCP `stdio` server) streams within about one second, picked up by a 1 s poll while any subscriber is connected. A caller observing a `200`/`201` response is guaranteed the corresponding event is stored and replayable.

**Authentication — pre-flight plugin (important):**

Ktor's `sse {}` handler runs inside the response-body phase — after the HTTP 200 status is committed. Auth cannot be performed inside the handler itself. The SSE route uses a dedicated pre-flight plugin that checks authentication in the `Plugins` phase, before streaming begins. A failed auth check sends `401`/`403` before any SSE content is produced.

This route is exempted from the global `AuthenticationPlugin` via `publicPaths`/`publicPrefixes`
(matched against the request path only, not the full URI — so a `?token=` or any other query
string on the exempted path still matches). `CurrentMcpServer.kt` registers `/mcp`, `/mcp/knowledge` and
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

- `actor` — `{id, kind, parent?}`: who performed the write. Never `proof` or `verification`. Resolution order at the moment the event is queued: the note's own `actorClaim` (`note.upserted`), then the actor of the enclosing write (the MCP tool's `actor`, per transition for `advance_item` — a cascaded parent advance carries the triggering transition's actor — or the synthesized `api:<tokenId>` / `external` claim for REST writes), then none. A write with no actor (a tool call without `actor`, a background sweep) emits no `actor` field.
- `rootId` -- the item's depth-0 ancestor (`scope.left` carries the OLD root, `scope.entered` the NEW root). Always present on a domain event since 4.0 (a root item uses its own id), live and on replay alike. `rootId` is scope metadata and is never redacted.

**Redaction.** Applied per connection, on egress only, identically for live delivery and `Last-Event-ID` replay: `actor` is omitted when `API_REDACT_NOTE_ATTRIBUTION=true` and the caller lacks `ADMIN`; otherwise it is delivered (`API_AUTH_MODE=none` callers are ADMIN).

**Stored `events` row data:** each domain event's `events`-table row carries a type-specific `data` object that the SSE `ApiEvent` does not expose. For `note.upserted` it is `{itemId, key, role, bodyLength, bodyFromFile}` (plus `actorParent` when the writing actor names a parent): `bodyFromFile` is the server-side path a `notes_write` MCP caller supplied (since 4.0), or null for an inline body; the file contents are never recorded.

**`item.updated` note:** since 4.0 an update that changes no field emits nothing, and a role change that also edits other fields emits only `item.advanced`.

**`item.advanced` note:** This event is the projection of the `item.transitioned` event a role change records (via the `advance` tool, `items_write` `complete_tree`, including cascaded parent transitions). It carries the `newRole` field. This is distinct from `item.updated` -- a role change emits `item.advanced` (not `item.updated`).

**Claim/release note:** A successful claim or release through the `advance` tool's `claim` and `release` ops (MCP or `POST /ops/advance`) emits `item.updated` for the claimed/released item -- and, for a claim that auto-releases the agent's other held items, one additional `item.updated` per auto-released item. A transition that ends a held claim (for example completing a claimed item) also emits `item.updated` for that item since 4.0.

**Bulk-write note:** `items_write` `create_tree` emits `item.created` for each newly created item (root first; an attach-mode pre-existing root emits nothing), then `dependency.added` per edge, `note.upserted` per note and `document.adopted` for a `docRef`, all after the element's unit commits -- a rolled-back tree emits nothing. Deleting all of an item's notes at once emits one `note.deleted` per note (since 4.0; was one per call); removing all of an item's dependencies emits one `dependency.removed` per edge. Deleting an item also emits one `note.deleted` per note and one `dependency.removed` per edge the database cascade removes, before the `item.deleted`.

**Table-only events:** the `events` table also records rejections (`transition.rejected`, `claim.rejected`, `lease.rejected`), expiry (`claim.expired`, `lease.expired`), resource-lease acquire/release, per-root config pushes, plan-document stash/adopt and config pins (`item.pinned`). These are audit rows and are not streamed; the stream carries only the event types listed above.

**Who records what (4.0).** Each write service records its own rows, in its own transaction: the
item command service (`item.created`, `item.updated`, the two `scope.*` rows of a reparent plus one
`item.updated` per restamped descendant, `item.deleted` and the cascade `note.deleted` /
`dependency.removed` rows of a delete), the note, dependency, project-config and plan-document
services their own rows, the advance pipeline `item.advanced` (including a cascade a reparent or a
delete triggers), and the claim service claim and lease rows. No store is decorated. The bus is the
recorder's commit listener directly; a fan-out failure is logged and never fails the committed write
(the 1 s poll picks the rows up).

---

## 22. Audit Model

All write endpoints (POST, PATCH, PUT, DELETE) synthesize an actor server-side from the authenticated principal. Client-supplied `actor.*` fields in the request body are **silently dropped** — callers cannot override audit attribution.

**Synthesized actor:**
- `id` = `"api:<tokenId>"` (e.g., `"api:dashboard-editor"`)
- `kind` = `"external"` (distinguishes API writes from MCP agent writes)
- `parent` = `null`

**Attribution redaction (applies to notes and the `items_read` `history` select):**
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
never an actor id, proof, or verification claim — on every surface (`items_read` `context` `gateStatus.violations`,
`AdvanceResponseDto.violations`, `CascadeEventDto.violations`, `422 gate_blocked`'s
`details.violations`). This is unconditional: it does not vary with `API_REDACT_NOTE_ATTRIBUTION`,
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

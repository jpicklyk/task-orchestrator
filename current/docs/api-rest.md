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
14. [Endpoints — Transitions (Audit)](#14-endpoints--transitions-audit)
15. [Endpoints — Search](#15-endpoints--search)
16. [Endpoints — Config / Schema Discovery](#16-endpoints--config--schema-discovery)
17. [Endpoints — Project Config (Per-Root)](#17-endpoints--project-config-per-root)
18. [Endpoints — Plan Documents (Per-Root)](#18-endpoints--plan-documents-per-root)
19. [Endpoints — Service Meta](#19-endpoints--service-meta)
20. [Server-Sent Events (SSE)](#20-server-sent-events-sse)
21. [Audit Model](#21-audit-model)
22. [Merge Patch Semantics](#22-merge-patch-semantics)
23. [Status-Graph Caveat](#23-status-graph-caveat)
24. [Known Limitations](#24-known-limitations)

---

## 1. Authentication

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
- `scope.root_ids` — optional list of root-item UUIDs; null/empty means unrestricted
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

Present a JWT in the `Authorization: Bearer` header. The server validates the JWT against the JWKS endpoint configured by `API_JWKS_URL`. Claims extracted: `iss`, `aud`, `sub`, `exp`, `nbf`.

Capabilities and scope are derived from the JWT's `sub` claim (mapped to a principal) or from the token store if applicable — the exact mapping is deployment-specific; consult your JWKS issuer configuration.

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

- `GET /items` — scope applied at SQL level via `findInScope`
- `GET /items/{id}` — `403 scope_forbidden` if item is outside scope
- Write endpoints — `403 scope_forbidden` if the target item is outside scope
- `GET /items/{id}/breadcrumbs` — chain is truncated at the caller's scope root (ancestors above the scope root are hidden)
- `GET /items/{id}/tree` — paginated flat list; scope check applies on the root item only
- `GET /notes/search` and `GET /search` — `?ancestorId` is validated against the principal's scope

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

### Config ETags

Config/schema endpoints (`/config`, `/config/schemas`, etc.) use a fingerprint-based ETag:
- Format: `"cfg-<fingerprint>"` where fingerprint is derived from the config file content
- Stable across reads when the config has not changed
- `If-None-Match` → `304` when fingerprint matches

The per-root project config endpoints (§17, `/roots/{rootId}/config`) use the SAME `"cfg-<fingerprint>"`
format, but the fingerprint is a SHA-256 over the stored `configYaml`'s raw UTF-8 bytes (see
`SQLiteProjectConfigRepository.computeFingerprint`) rather than the global config file. `PUT` additionally
accepts `If-Match` for optimistic-concurrency writes (see §17).

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
| `bad_request` | 400 | Missing or malformed path/query parameter |
| `validation_error` | 400 | Invalid field value or deserialization failure |
| `precondition_required` | 400 | `PATCH` missing required `If-Match` header |
| `not_found` | 404 | Item, note, or dependency not found |
| `scope_forbidden` | 403 | Item exists but is outside the caller's scope |
| `field_not_patchable` | 400 | PATCH attempted on a server-owned field |
| `cycle_detected` | 400 | Dependency would create a cycle |
| `unsupported_media_type` | 415 | Wrong `Content-Type` for PATCH (see §21) |
| `etag_mismatch` | 412 | `If-Match` header does not match current ETag |
| `unauthenticated` | 401 | No authenticated principal (missing/invalid token) |
| `verification_failed` | 401 | JWKS verification failed under `reject` policy |
| `transition_failed` | 422 | Role transition rejected (invalid trigger, gate failure, dependency blocker) |
| `db_error` | 500 | Database query failed |

---

## 7. Pagination

List endpoints return a `PageDto<T>`:

```json
{
  "items": [...],
  "page": 1,
  "pageSize": 20,
  "totalItems": 42,   // may be null when count is expensive
  "hasMore": true,
  "skipped": 1         // omitted when 0/null — see below
}
```

Query parameters: `?page=<int>` (default 1) and `?pageSize=<int>` (default 20, max typically 100).

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
  "verification": null  // redacted unless ADMIN
}
```

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

### BacklinkDto

```json
{
  "fromItemId": "<uuid>",
  "fromTitle": "string",
  "type": "blocks|relates_to"
}
```

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
  "skill": "string|null"
}
```

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
  "notes": [<NoteSchemaEntryDto>]
}
```

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

**ProjectConfigResponseDto** (see §17):
```json
{
  "rootItemId": "550e8400-e29b-41d4-a716-446655440000",
  "fingerprint": "e3b0c44298fc1c14...",
  "updatedAt": "2026-07-15T19:00:00Z",
  "configYaml": "work_item_schemas:\n  ...",
  "warning": "string|null",
  "relation": "current|superseded|unknown|null",
  "ignoredSections": ["actor_authentication"]
}
```
`configYaml` is populated on `GET` only (omitted on `PUT`). `warning` is populated only when the
root's `type` is not `"project"` (non-fatal — the push still succeeds). `relation` is populated on
`GET` only, and only when `?fingerprint=` was supplied — see §17. `ignoredSections` is populated on
`PUT` only, and only when the pushed document contains top-level keys the per-root resolution layer
does not honor (e.g. `actor_authentication`, which stays global-only) — omitted entirely when empty.
See §17 for the full list of honored per-root keys.

**PlanDocumentResponseDto** (see §18):
```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "rootItemId": "550e8400-e29b-41d4-a716-446655440000",
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

**PlanDocumentSummaryDto** — one row of `PlanDocumentListResponseDto.plans` (see §18); same shape as
`PlanDocumentResponseDto` minus `body`, which is never included in list responses.

**PlanDocumentListResponseDto** (see §18):
```json
{
  "rootItemId": "550e8400-e29b-41d4-a716-446655440000",
  "plans": [<PlanDocumentSummaryDto>]
}
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
| `page` | int | Page number (default 1) |
| `pageSize` | int | Items per page (default 20) |
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

**Response:** `200 OK` → `PageDto<ItemDto>`

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
- `403 scope_forbidden` — parent outside scope

Supports `Idempotency-Key` header.

**Audit:** actor is synthesized as `api:<tokenId>` / kind `external`; client-supplied `actor.*` is dropped.

### PATCH /items/{id}

JSON Merge Patch update. Requires `WRITE_ITEMS`, `If-Match`, and `Content-Type: application/merge-patch+json` (or `application/json`).

**Request body:** A partial JSON object following RFC 7396 merge-patch semantics (see §21).

**Tags deviation:** In PATCH, `tags` must be a comma-separated **string** (not a JSON array). Example: `"tags": "feature,auth"`. Sending a JSON array in PATCH → `400 validation_error`.

**Server-owned fields that cannot be patched** (→ `400 field_not_patchable`):
`id`, `role`, `previousRole`, `roleChangedAt`, `depth`, `createdAt`, `modifiedAt`, `version`, `claimedBy`, `claimedAt`, `claimExpiresAt`, `originalClaimedAt`

**Responses:**
- `200 OK` → `ItemDto` + `ETag` header
- `400 precondition_required` — `If-Match` missing
- `400 field_not_patchable` — attempted to patch server-owned field
- `412 etag_mismatch` — `If-Match` does not match
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

This route runs the **same advance pipeline as the MCP `advance_item` tool**, unified behind `AdvanceService`: resolve → validate dependencies → **required-note gate** → apply → cascade detection → unblock detection. Previously the REST path skipped the gate, cascades, and unblock detection; those are now enforced and reported.

**Request body:**
```json
{
  "trigger": "start|complete|block|hold|resume|cancel|reopen"
}
```

**Claimed-item behavior:** The REST API bypasses MCP claim ownership — a claimed item advances successfully even if a different MCP agent holds the claim. A WARN is emitted to the server log (`API_WARN_ON_CLAIMED_ADVANCE=false` to suppress). The response does NOT disclose `claimedBy` (tiered-disclosure principle).

**Note-gate enforcement:** When the item's schema declares required notes, the gate is enforced exactly as in MCP — a `start` requires the current phase's required notes; a `complete` requires all required notes across every phase. An advance that leaves a required note unfilled is **rejected with `422 gate_blocked`** (it no longer silently advances). The missing notes are returned in `details.missingNotes`.

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
- `400 validation_error` — invalid trigger string
- `422 gate_blocked` — a required-note gate failed; `details.missingNotes` lists the unfilled required notes
- `422 transition_blocked` — a dependency blocker prevents the transition; `details.blockers` lists the blocking edges
- `422 transition_failed` — invalid state transition (resolution/apply failure)

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
- Cycle detection — `400 cycle_detected`

**Response:** `201 Created` → `DependencyEdgeDto`

### DELETE /dependencies/{id}

Remove a dependency edge by its UUID. Requires `MANAGE_DEPENDENCIES`.

Scope check: both `fromItemId` and `toItemId` of the edge must be accessible to the caller.

**Responses:**
- `204 No Content`
- `404 not_found`
- `403 scope_forbidden`

---

## 14. Endpoints — Transitions (Audit)

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

**Response:** `200 OK` → `PageDto<RoleTransitionDto>`

---

## 15. Endpoints — Search

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

## 16. Endpoints — Config / Schema Discovery

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

See §22 for the important caveat about what the status graph does and does not reflect.

---

## 17. Endpoints — Project Config (Per-Root)

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
`work_item_schemas`, `note_schemas`, `traits`, `project`, `note_limits`, `status_labels` — and any
other key present (e.g. `actor_authentication`, which stays global-only — see
[`config-format.md`](../../claude-plugins/task-orchestrator/skills/manage-schemas/references/config-format.md))
is reported in the response's `ignoredSections` array so a push is never silently partial.

**Responses:**
- `200 OK` → `ProjectConfigResponseDto` (no `configYaml` field on this verb; `ignoredSections`
  present only when non-empty); `ETag: "cfg-<fingerprint>"`
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
- `413 payload_too_large` — body exceeds 128 KiB
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

## 18. Endpoints — Plan Documents (Per-Root)

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
- `413 payload_too_large` — body exceeds 64 KiB
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

## 19. Endpoints — Service Meta

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

## 20. Server-Sent Events (SSE)

### GET /api/v1/events

Real-time event stream. Requires `READ` or `ADMIN` capability.

**Authentication — pre-flight plugin (important):**

Ktor's `sse {}` handler runs inside the response-body phase — after the HTTP 200 status is committed. Auth cannot be performed inside the handler itself. The SSE route uses a dedicated pre-flight plugin that checks authentication in the `Plugins` phase, before streaming begins. A failed auth check sends `401`/`403` before any SSE content is produced.

**Auth resolution order:**
1. `Authorization: Bearer <token>` header — always accepted
2. `?token=<plaintext>` query parameter — only accepted when `API_ALLOW_QUERY_TOKEN_FOR_SSE=true`
3. If neither present → `401`

**Browser SSE note:** The native browser `EventSource` API cannot set custom headers. Browsers must use a fetch-based SSE client (e.g., `@microsoft/fetch-event-source`) to provide the `Authorization: Bearer` header, or enable `API_ALLOW_QUERY_TOKEN_FOR_SSE=true` to use the query-parameter path.

**Query parameters:**
- `root` (repeatable) — filter to events for items in this root's subtree. Effective subscription = intersection of `?root=` values with `principal.scope.rootIds`.
- `types` — comma-separated event type filter (e.g., `types=item.created,item.advanced`)

**`Last-Event-ID` replay:** The bus maintains a ring buffer of recent events (size: `API_SSE_BUFFER_SIZE`, default 1000). On reconnect, events with `id > Last-Event-ID` are replayed before live streaming resumes. Ring-buffer entries carry `affectedRoots` metadata, so the replay path applies the **same root-intersection filter** as the live fan-out — a client reconnecting with `?root=<uuid>` receives only replayed events for roots within its subscription (and scope). Replay is consistent with the live stream.

**Event ID namespace:** The monotonic ID counter for `/api/v1/events` is **independent** from the `/mcp` SSE channel's `EventStore`. Do NOT reuse `Last-Event-ID` values across the two channels.

**Token expiry:** The SSE handler periodically checks token expiry (interval: `API_SSE_AUTH_CHECK_INTERVAL_SECONDS`, default 30s). When a token expires, an `auth.expired` event is sent and the stream closes. The client must reconnect with a fresh token.

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
| `sync.lost` | null | null | null | Client's per-connection queue overflowed; re-fetch full state |
| `auth.expired` | null | null | null | Connection's token has expired; reconnect with fresh credential |

**`item.advanced` note:** This event is emitted on role change (via `POST /items/{id}/advance` or any write path that triggers `RoleTransitionHandler`). It carries the `newRole` field. This is distinct from `item.updated` — a role change emits `item.advanced` (not `item.updated`).

---

## 21. Audit Model

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

## 22. Merge Patch Semantics

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

## 23. Status-Graph Caveat

`GET /config/status-graph` returns the **structural** (schema-defined) transition graph — which triggers are valid for each role per type.

This graph does NOT reflect:
- Runtime gate enforcement (note gates — required notes that must be filled before `start` succeeds)
- Dependency blockers (an item blocked by an unsatisfied dependency cannot advance)
- Claim ownership (MCP callers without claim ownership are blocked; REST API callers bypass claim ownership)
- Per-item lifecycle exceptions

Dashboard UI: do not use the status graph to pre-compute which buttons to enable. Always call `POST /items/{id}/advance` and surface the `422` error if the transition is blocked at runtime — `gate_blocked` (unfilled required note), `transition_blocked` (dependency blocker), or `transition_failed` (invalid state).

The `"<previousRole>"` sentinel in `blocked.resume` is a literal string — resolve it from the live item's `previousRole` field.

---

## 24. Known Limitations

**SSE dependency-event scoping depends on a warm ancestor cache.** Live root-filtering and `Last-Event-ID` replay are both correctly root-scoped — ring-buffer entries carry `affectedRoots` metadata and the replay path applies the same root-intersection filter as the live fan-out. One residual caveat remains for dependency events: `dependency.added` / `dependency.removed` resolve their affected roots from an in-memory ancestor cache populated by prior item create/update writes. On a cache miss (e.g., a dependency change with no preceding item write on that subtree during the connection's lifetime), the event falls back to an unscoped broadcast. This is a deliberate tradeoff; root-scoped subscribers that require exact dependency-event scoping should re-fetch state rather than relying solely on the event stream.

**SSE is bearer-mode only.** The pre-flight auth plugin for the SSE route only supports bearer token authentication. JWKS JWT authentication for SSE is not implemented (the pre-flight plugin does not invoke the JWKS verifier).

**FTS5 requires SQLite.** Search endpoints (`GET /search`, `GET /notes/search`) return empty results when the repository is H2-backed (test/embedded environments). FTS5 is only available against the production SQLite database.

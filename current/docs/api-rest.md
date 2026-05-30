# REST API Reference — MCP Task Orchestrator

This document describes the HTTP REST API layer (v1) added alongside the MCP transport. The REST API runs on the same Ktor server when `API_ENABLED=true`.

**Base URL:** `/api/v1`
**Content-Type:** `application/json` (responses); see PATCH for request-body negotiation.
**OpenAPI spec:** [`api/openapi.yaml`](api/openapi.yaml) — covers all routes for machine consumption. Note: the YAML is hand-maintained and may lag behind edge-case behavior; this document and the source are authoritative.

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
17. [Endpoints — Service Meta](#17-endpoints--service-meta)
18. [Server-Sent Events (SSE)](#18-server-sent-events-sse)
19. [Audit Model](#19-audit-model)
20. [Merge Patch Semantics](#20-merge-patch-semantics)
21. [Status-Graph Caveat](#21-status-graph-caveat)
22. [Known Limitations](#22-known-limitations)

---

## 1. Authentication

All `/api/v1/*` endpoints require authentication. The API supports two modes, selected by `API_AUTH_MODE`:

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

### JWKS Mode (`API_AUTH_MODE=jwks`)

Present a JWT in the `Authorization: Bearer` header. The server validates the JWT against the JWKS endpoint configured by `API_JWKS_URL`. Claims extracted: `iss`, `aud`, `sub`, `exp`, `nbf`.

Capabilities and scope are derived from the JWT's `sub` claim (mapped to a principal) or from the token store if applicable — the exact mapping is deployment-specific; consult your JWKS issuer configuration.

**Failing requests receive:**
- `401 Unauthorized` + `WWW-Authenticate: Bearer error="invalid_request"` — missing token
- `401 Unauthorized` + `WWW-Authenticate: Bearer error="invalid_token"` — bad/expired token
- `403 Forbidden` — token valid but lacks required capability

**`degradedModePolicy` interaction (JWKS mode):** When `DEGRADED_MODE_POLICY=reject` and JWKS verification fails, write endpoints return `401` with error `verification_failed`. Read endpoints and the bearer mode are unaffected (bearer auth has no JWKS chain).

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
| `unsupported_media_type` | 415 | Wrong `Content-Type` for PATCH (see §20) |
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
  "hasMore": true
}
```

Query parameters: `?page=<int>` (default 1) and `?pageSize=<int>` (default 20, max typically 100).

`totalItems` may be `null` for endpoints where computing an exact count is expensive; use `hasMore` for continuation.

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

**Response:** `200 OK` → `PageDto<ItemDto>`

### GET /items/roots

Root-level items (depth=0) accessible to the caller.

- Scoped tokens: returns only the specific root items within scope (efficient, not capped)
- Unscoped/admin: scans up to 200 root items (documented cap)

**Response:** `200 OK` → `PageDto<ItemDto>`

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

**Request body:** A partial JSON object following RFC 7396 merge-patch semantics (see §20).

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

**Request body:**
```json
{
  "trigger": "start|complete|block|hold|resume|cancel|reopen"
}
```

**Claimed-item behavior:** The REST API bypasses MCP claim ownership — a claimed item advances successfully even if a different MCP agent holds the claim. A WARN is emitted to the server log (`API_WARN_ON_CLAIMED_ADVANCE=false` to suppress). The response does NOT disclose `claimedBy` (tiered-disclosure principle).

**Response `200 OK`:**
```json
{
  "itemId": "<uuid>",
  "previousRole": "queue",
  "newRole": "work",
  "trigger": "start",
  "statusLabel": "string|null"
}
```

**Responses:**
- `200 OK` → `AdvanceResponseDto`
- `400 validation_error` — invalid trigger string
- `422 transition_failed` — gate failure, dependency blocker, or invalid state transition

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

See §21 for the important caveat about what the status graph does and does not reflect.

---

## 17. Endpoints — Service Meta

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

## 18. Server-Sent Events (SSE)

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

**`Last-Event-ID` replay:** The bus maintains a ring buffer of recent events (size: `API_SSE_BUFFER_SIZE`, default 1000). On reconnect, events with `id > Last-Event-ID` are replayed before live streaming resumes.

**KNOWN LIMITATION — replay is unfiltered by root:** Ring-buffer entries do not carry the per-publisher root metadata used for live filtering. A reconnecting client with `?root=<uuid>` may receive buffered event metadata from other roots during replay. Only the live stream path is correctly root-filtered. Clients should treat replayed events as potentially broader than their live subscription filter and re-fetch full item state when needed.

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

## 19. Audit Model

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

## 20. Merge Patch Semantics

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

## 21. Status-Graph Caveat

`GET /config/status-graph` returns the **structural** (schema-defined) transition graph — which triggers are valid for each role per type.

This graph does NOT reflect:
- Runtime gate enforcement (note gates — required notes that must be filled before `start` succeeds)
- Dependency blockers (an item blocked by an unsatisfied dependency cannot advance)
- Claim ownership (MCP callers without claim ownership are blocked; REST API callers bypass claim ownership)
- Per-item lifecycle exceptions

Dashboard UI: do not use the status graph to pre-compute which buttons to enable. Always call `POST /items/{id}/advance` and surface the `422 transition_failed` error if the transition is blocked at runtime.

The `"<previousRole>"` sentinel in `blocked.resume` is a literal string — resolve it from the live item's `previousRole` field.

---

## 22. Known Limitations

**SSE Last-Event-ID replay is unfiltered by root.** When a client reconnects with `?root=<uuid>` and a `Last-Event-ID`, buffered events replayed from the ring buffer are not filtered by root. The client may receive metadata for events from other roots during replay. The live stream path is correctly filtered. This is a known limitation of the current ring-buffer design; root metadata is not stored with buffered events. Mitigation: after reconnecting, re-fetch the full item list to reconcile state rather than relying solely on replayed events.

**SSE is bearer-mode only.** The pre-flight auth plugin for the SSE route only supports bearer token authentication. JWKS JWT authentication for SSE is not implemented (the pre-flight plugin does not invoke the JWKS verifier).

**`GET /items/roots` unscoped cap.** Unscoped/admin callers on `GET /items/roots` receive at most 200 root items. Scoped callers are not subject to this cap.

**FTS5 requires SQLite.** Search endpoints (`GET /search`, `GET /notes/search`) return empty results when the repository is H2-backed (test/embedded environments). FTS5 is only available against the production SQLite database.

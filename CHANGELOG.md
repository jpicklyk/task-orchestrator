# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Upgrading from 3.x

4.0 changes the wire contract (tools, request and response shapes, REST routes), the database schema (migrations V18 to V37 plus startup data steps) and the config format. Read this before the first 4.0 start on an existing volume.

- **Back up the volume before the first 4.0 start.** Migrations V22 (dependency rewrite), V24 (`plan_documents` into `documents`), V25 (`project_config` into `config_versions`), V26 (`work_items` recreation) and V29 (`role_transitions` into `events`, then dropped) and the `type-backfill`, `traits-from-properties` and `pin-backfill` data steps rewrite data one way. The backup is the supported rollback, and a 3.x binary cannot open a V26 or later database.
- **Stop every 3.x process first**, stdio sessions included. A 3.x binary does not fail closed on a newer database, and a 3.x writer that inserts an `IS_BLOCKED_BY` row fails the V22 CHECK.
- **The first start is slower.** It runs the data steps (`type-backfill`, `traits-from-properties`, `config-canonicalize`, `pin-backfill`) and the item FTS rebuild of V26 before the readiness marker is written; a failed step fails startup as `DATA_STEPS` and the container reports unhealthy. Raise the Docker `--start-period` on a large database.
- **The mounted global config boots with warnings.** The file is imported at every boot and then served from the database. The 3.x top-level keys `schema_resolution`, `orchestration`, `retrospective` and `actor_attribution` are accepted at boot as stored warnings (returned as `configWarnings` by `GET /api/v1/info`); a push of the same text is rejected. Move plugin settings under `extensions.claude-code`. Any other strict error, a file over 128 KiB, or a `project.rootId` that is not a depth-0 item of this database fails startup as `DATA_STEPS`.
- **Untyped items get a type.** `type` is required in 4.0. `type-backfill` gives every item whose `type` is NULL or blank the schema key the 3.x tag step resolved it to, or `untyped` when none matched, and `traits-from-properties` moves `properties.traits` into the typed `traits` list.
- **In-flight items keep their 3.x schema; new items follow the new precedence.** `pin-backfill` pins every item at the first start to the base schema 3.x gave it, so no gate moves for existing items. New items resolve by `type` only: per-root exact type, server exact type, per-root `default`, server `default`. Tags no longer select a schema, a server exact type now beats a per-root `default`, a root that used `schema_resolution: isolated` now sees server entries, and duplicate trait note keys resolve item-first with `required` OR'd.
- **Stored data moves.** Each `project_config` row becomes version 1 of its root's config (V25, ids preserved); the 3.x transition trail moves into `events` as backfilled `item.transitioned` rows (V29); `plan_documents` become `documents` (V24; a CRLF body keeps its 3.x hash until it is rewritten); `IS_BLOCKED_BY` rows are rewritten as `BLOCKS` (V22); every timestamp is normalized to UTC text (V18).
- **Notes written before 4.0 have no owner.** 4.0 enforces note ownership (see Added: note ownership), and a 3.x actor id was declared by the caller, so none is trusted as an owner. V34 adds `notes.owner_id`, NULL for every existing note, and changes no other column: each note keeps its 3.x `actor` and `verification`. The first 4.0 `upsert` of such a note, by any caller allowed to write the item (in its scope, holding `write-notes`, and the claim holder while the item is claimed), makes that caller the owner and records an audit `note.ownership_transferred` with `from: null` and the 3.x actor id as `legacyActor`; after that, another principal is refused `note_owned_by_other` unless the key is `shared: true` or an admin passes `takeover: true`. In-flight items need no `takeover`. Until its first 4.0 `upsert`, such a note can be deleted by any caller allowed to write the item, as in 3.x; this includes notes a 3.x REST token wrote.
- **Saved SSE cursors re-fetch once.** Event ids are now the durable `events` seq, above 10^12; a `Last-Event-ID` saved before the upgrade is answered with `sync.lost` (`unknown_event_id`).
- **Short ids need 8 hex characters.** Every id field takes a full UUID or 8 to 32 hex characters; 3.x accepted shorter prefixes.
- **Environment.** `USE_FLYWAY` is removed and ignored (one WARN if still set) and `API_WARN_ON_CLAIMED_ADVANCE` is removed. `SCHEMA_MODE`, `FTS_INTEGRITY`, `MCP_TOOL_PROFILE`, `MCP_ALLOW_UNAUTHENTICATED`, the four retention settings (`RETENTION_CALL_LOG_DAYS`, `RETENTION_EVENTS_DAYS`, `RETENTION_AUDIT_EVENTS_DAYS` and `RETENTION_LEASE_HISTORY_DAYS`) and `API_JWKS_MAX_STALE_SECONDS` are new, and `DATABASE_BUSY_TIMEOUT_MS` now governs only the startup paths. The default `CORS_ALLOWED_HEADERS` gains `X-Client-Host` and the default `CORS_EXPOSE_HEADERS` gains `X-Req-Id`; a deployment that sets either explicitly keeps its own list.
- **Clients and the plugin.** Every 3.x tool call shape and every 3.x REST resource route is gone (see Removed), and every `/api/v1` body is the envelope. The 3.x plugin (config-sync, phase-guard, the hook matchers, the skills) does not work against a 4.0 server until the 4.0 plugin ships: its hooks fail open, so nothing is blocked, but nothing syncs or gates either.
- **Key fetches refuse non-public addresses.** Every JWKS and OIDC discovery fetch now passes an egress guard: a key source that resolves to a loopback, link-local, private (RFC 1918), CGNAT, unique-local or unspecified address fails, and verification reports `unavailable` naming the refused address (REST fails the request closed). A private identity provider or a corporate JWKS on `10.0.0.0/8` stops working on upgrade; loopback is admitted only with `API_JWKS_ALLOW_INSECURE_URL=true` (REST), and other private ranges have no opt-in in 4.0.
- **`/mcp` over HTTP authenticates, and `actor` is gone.** With the REST API on in a credential mode (`API_AUTH_MODE=bearer` or `jwks`), every `/mcp`, `/mcp/knowledge` and `/mcp/director` request needs the same `Authorization: Bearer` credential as `/api/v1`; without one it answers `401 unauthenticated` (the error envelope, never JSON-RPC). `MCP_TRANSPORT=http` with the API disabled now fails startup (`MCP_AUTH`) unless `MCP_ALLOW_UNAUTHENTICATED=true` is set on purpose. Clients stop sending `actor`: the caller is the transport principal, and a body `actor` is `unknown_parameter`. Audit ids written in REST unauthenticated mode change from `api:local-unauth` to `local`.
- **Document writes need `write-docs`.** Every `docs_write` op now requires the new `write-docs` capability, and `write-config` no longer grants any of them. A token that only had `write-config` (for example a config-sync token that also pushes plans or rules) is refused with `forbidden` until `write-docs` is added to its `capabilities` list.
- **Principal root scope is roots-only.** A scope anchor (`scope.root_ids`) admits exactly the items stamped with that root, so a mid-tree anchor admits nothing. A REST bearer token whose `scope.root_ids` names an existing non-root fails startup, naming the token; a JWT anchored on a non-root resolves to no principal (401). Creating a root, or moving an item to a root, needs an unrestricted scope or ADMIN.
- **`DEGRADED_MODE_POLICY` is removed and fails startup.** Verification fails closed, so there is no policy to choose: unset `DEGRADED_MODE_POLICY` (any value) before the first 4.0 start, and delete `actor_authentication.degraded_mode_policy` together with the rest of the `actor_authentication` block (next entry). A leftover variable stops the server with an error naming it, before the readiness marker is written, because ignoring a security setting an operator believes is in force would be worse than refusing to start. A deployment that relied on `accept-cached` or `accept-self-reported` to keep working through a key-set outage now gets `503 unavailable` instead (see Changed). Wire clients that read a note's attribution read `verification.proofStatus`, not `verification.status` (see Changed).
- **`actor_authentication` is removed, and a leftover block fails startup.** No request carries an `actor` or an `actor.proof` any more, so the verifier the block configured was consulted by no call, and `require_sub_match`, `jti_replay_protection`, DID trust (`did_allowlist`, `did_pattern`) and the loose-kid match had no effect. A global config with an `actor_authentication:` key, whatever its value (a mapping, `{}`, a scalar or a bare `null`), now stops the server with an error naming it, before the readiness marker is written; the legacy `auditing:` key still fails startup too. A per-root push carrying it is rejected at `/actor_authentication` as an unknown top-level section. **REST break:** `GET /api/v1/info` no longer returns `actorAuthenticationEnabled`; clients that read it must stop. **Solo stdio:** a server with no `AGENT_CONFIG_DIR` reads the workspace `.taskorchestrator/config.yaml` as its global file, so a hook-only `actor_authentication: {enabled: true}` block there now fails startup too; delete it and use `actor_attribution.required` for the plugin hook's attribution requirement. A call's identity comes from `API_AUTH_MODE` alone: the credential's principal (`api:<id>`), or the single `local` principal for stdio, `/mcp` in a local mode and `API_AUTH_MODE=none`. Move any actor-proof JWKS setup to `API_AUTH_MODE=jwks` and the `API_JWKS_*` variables; delete the block before the first 4.0 start.
- **The first start folds and prunes.** The retention pruner runs right after startup. It folds each completed UTC day of `call_log` into `usage_daily`, then deletes `call_log` rows older than 90 days (folded days only), activity-class events older than 180 days and closed lease intervals older than 365 days; audit events are kept. To keep any of that data, set the matching `RETENTION_CALL_LOG_DAYS`, `RETENTION_EVENTS_DAYS` or `RETENTION_LEASE_HISTORY_DAYS` to `0` before the first start. The database file does not shrink without an offline `VACUUM` (see Data Retention in `current/docs/fleet-deployment.md`).
- **Credentials without a kind are `service`.** A token-file entry with no `kind` and a JWT with no `to_kind` load as kind `service` (a self-issued `did:` token is `agent`). Answering a decision whose class is `human-only` needs a credential of kind `human` (`kind: human` in the token file, `to_kind: human` in a JWT).
- **Stdio and local sessions are one principal.** Every stdio session, `/mcp` in a local mode and `API_AUTH_MODE=none` run as the single `local` principal, which owns the notes and claims they write, so separate sessions share them unless they hold seat tokens (see run seats under Added). Separating them otherwise takes credentials.

### Added

#### Identity

- **Added: `max_stale_seconds` and `API_JWKS_MAX_STALE_SECONDS`.** How old a cached key set may be, from its real fetch time, and still be served after a failed refresh (`stale_on_error`); past it the key set is unavailable and every token fails as `unavailable` instead of trusting a possibly revoked key forever. Default `max(3600, cache TTL)`; a value that is not a positive integer or is below the cache TTL fails startup (the `API_JWKS_MAX_STALE_SECONDS` env var for REST).
- **Added: the `write-docs` capability.** Every `docs_write` op (upsert, archive, unarchive, delete, attach, detach) needs `write-docs`; `write-config` keeps the `config` tool and no longer grants documents, because rule documents are served to agents as instructions. This is breaking for tokens that only had `write-config` (see Upgrading).
- **Added: `runs` and `decide` capabilities.** Both parse in a token file's `capabilities` list and in the JWT `to_capabilities` claim. `runs` is checked per op by every `runs_write` op except `ack` and `answer`, which check `decide`; `runs_read` needs only `read` (see the run tools under Storage and operations).
- **Added: token-file `kind` and `profile` entry fields, and the JWT `to_kind` claim.** `kind` (`agent`, `service` or `human`; default `service`) and `to_kind` (same values; default `service`, or `agent` for a self-issued `did:` token) set the principal kind; an unknown value fails startup for a token file and rejects the JWT. No request body can claim `human`: the principal comes only from a credential. `profile: director` confines a token to the director path.
- **Added: `POST /api/v1/ops/director/{tool}`.** The director tool profile over REST, through the same dispatcher, envelopes and checks as `POST /api/v1/ops/{tool}`; a tool with no member in the director set answers `403 forbidden` (`scope: capability`, `required: director`).
- **Added: director-credential confinement.** A token-file credential with `profile: director` may call only `POST /api/v1/ops/director/{tool}`; on every other REST path, `GET /api/v1/events` included, it answers `403 forbidden` (`scope: capability`, `required: director`), for both bearer and `?token=` SSE authentication.
- **Added: `/mcp` bearer authentication and session principals.** `McpBearerAuth` authenticates every `/mcp` request (POST, GET and DELETE, every profile path) after the Host guard and before the MCP SDK, through the same credential resolver as REST: a missing or malformed credential is `401 unauthenticated` with `WWW-Authenticate: Bearer error="invalid_request"`, an unknown or expired one `error="invalid_token"`, and a `profile: director` credential is admitted on `/mcp/director` only (`403 forbidden`, `required: director`, elsewhere). An MCP session is bound to the principal that opened it; a request in that session resolving to another principal is `401 unauthenticated` and runs nothing, and `DELETE /mcp` forgets the binding. Only a session id the SDK issued is bound; bindings are bounded (24 hours idle, 1024 sessions, least recently used of the largest holder first) and an evicted session is closed so its next request is `404`. A session the SDK opened for a `POST` without a session id that was not `initialize` (and then refused) is closed by a later such `POST` once it has been seen without an id for 30 seconds. A tool call runs under its principal's scope and capabilities, with the REST note-attribution rule (`API_REDACT_NOTE_ATTRIBUTION`). `ApiBearerAuth` delegates `/mcp` instead of listing it public.
- **Added: `MCP_ALLOW_UNAUTHENTICATED`.** The opt-in that lets `MCP_TRANSPORT=http` serve `/mcp` with the REST API disabled, every caller as the local principal (one startup WARN). Default `false`, which fails startup as `MCP_AUTH`; an unrecognized value fails startup.
- **Added: the `local` principal.** One principal (`local`, kind `agent`, proof `none`, unrestricted, ADMIN) for stdio, for `/mcp` in local mode and for REST `API_AUTH_MODE=none`, audited as the bare id `local` (verifier `local`), so ownership, claims and idempotency keys meet across those hosts. A dispatched call with no principal is `unauthenticated` before anything in it is read.
- **Added: principal kind and proof status in answer provenance.** The provenance attached to an answer now carries the caller's principal id, kind (`human`, `agent` or `service`) and proof status (`verified`, `verified_stale`, `issued` or `none`).

#### Tools, profiles and the envelope

- **Added: the 4.0 tools on one registry.** `items_read`, `items_write`, `notes_read`, `notes_write`, `deps_read`, `deps_write`, `advance`, `docs_read`, `docs_write` and `config` replace the 3.x tools. A read tool takes `{select, ...fields}`; a write tool takes `{ops: [{op, ...fields}], atomic?}`. Each tool is a `ToolSpec` of selects or ops registered in `buildToolSpecs()`, and the request classes are the single source of strict decoding, the JSON Schema, `describe` and the documentation. MCP and `POST /api/v1/ops/{tool}` serve the same registry. `readOnlyHint` and `destructiveHint` annotations come from the visible ops, `describe: true` on any call returns the tool's schema, each operation's parameters and its error codes, and the MCP `initialize` `instructions` is one sentence naming the profile. Per-tool schema goldens (`regenerateToolGoldens`), token budgets and the generated field tables and error catalog of `current/docs/api-reference.md` (`regenerateApiReference`, `ApiReferenceFreshnessTest`) keep the surface and its documentation in step.
- **Added: tool profiles over MCP.** The server builds one MCP `Server` per profile. Over HTTP, `/mcp` serves the orchestration profile (every tool except the director-only `report`, with all its selects and ops) and `/mcp/knowledge` serves the knowledge profile (`items_read`, `items_write`, `notes_read`, `notes_write`, `deps_read`, `deps_write`, `docs_read`, `docs_write`: no `context`, `next`, `blocked` or `history` select, no `create_tree`, `complete_tree` or `repin` op, no `advance` and no `config`). The path is exact and case-sensitive and any other `/mcp/<x>` is `404`, never the orchestration profile; a session keeps the profile it was initialised with. `/mcp/knowledge` is exempt from the REST bearer exactly like `/mcp`, and the Host allowlist and CORS cover it the same. Over stdio, `MCP_TOOL_PROFILE` (`knowledge` or `orchestration`, case-insensitive, default `orchestration`) picks the profile; an unknown value fails startup (`UNKNOWN_TOOL_PROFILE`) on either transport and is applied to stdio only. Every MCP `call_log` row's `request_shape` carries `profile`. A third profile, `director` (`/mcp/director`, `MCP_TOOL_PROFILE=director`), serves only `items_read` `get`, `list`, `overview`, `blocked`, `deps_read` and the director-only `report`: no read that can return a note, document or transition-summary body, and no write. Each select and op declares the set of profiles it appears in; an empty set is a REST-only member. REST (`/api/v1/ops` and the item and note resource routes) serves every registered select and op whatever its set.
- **Added: the response envelope.** Every tool and every `/api/v1` body except the documented exceptions is `{ok, data | error, meta: {reqId, replayed?}}`, byte-equal on MCP (`structuredContent`) and REST apart from `meta.reqId`. An error is `{code, kind, message, detail?, fix?, retryAfterMs?, index?}` from one closed catalog: `kind` (`transient`, `permanent`, `shedding`) is decided by the code, `detail` has a fixed shape per code, `fix` says what to change, and the HTTP status is the code's status. An `internal` error always carries the fixed message `Internal server error` (the exception text stays in the server log and never reaches a client, including a SQL fault inside a batch element). A batch answers `data: {results, summary: {ok, failed}}`; elements run as their own write units by default, `atomic: true` makes the array one unit and returns only the failing element's error with `error.index`, and a non-atomic batch with failures also carries a `partial_failure` error (REST `207`). The only bodies that are not envelopes are `GET /api/v1/health`, `/.well-known/mcp-task-orchestrator.json`, the event stream, the 200 body of `GET /api/v1/config/schema`, a `304`, and Ktor's own unmatched-route `404`/`405`. New dependency: `ktor-server-status-pages` (an exception escaping a `/api/v1` route answers `500 internal`). See `current/docs/api-reference.md` ("Response envelope", "Error catalog").
- **Added: strict requests and per-element idempotency.** An unknown parameter at any depth is `unknown_parameter`, and a malformed value is `invalid_request` with RFC 6901 style pointers (`/ops/0/type`), all problems at once; handlers receive typed values only. Each `ops[]` element may carry an `idempotencyKey`, which replaces the 3.x `requestId`: results are recorded durably in `idempotency_records` (migration V19) per `(principal, tool, key)` for 24 hours, a repeat replays the stored result with `replayed: true`, the same key with a different element is `idempotency_mismatch`, and only a committed element, or a rejection that depends on the payload alone, is recorded. Expired records are pruned at startup and hourly.
- **Added: `POST /api/v1/ops/{tool}`, the canonical RPC route.** One route calls any registry tool with the tool's envelope request, with the HTTP status taken from the error code and every registered select and op served (REST-only members included). Faults are envelopes: `400 invalid_request` for an unknown tool, a non-JSON `Content-Type`, an `Idempotency-Key` header or a body that is not a JSON object, and `413 payload_too_large` over 1 MiB. Its `call_log` rows record the registry tool, operation, batch size, failed count, result count and host. See `current/docs/api-rest.md` section 20a and `current/docs/api/openapi.yaml`.
- **Added: per-operation capability checks and `ScopeGuard`, shared by MCP and REST.** Every select and write op declares the capability it needs (a write op must declare one) and the dispatcher checks it with the single `grants` predicate the REST authorization plugin also uses (`ADMIN` grants every capability): a select the caller lacks the capability for fails the call with `forbidden` (`scope: capability`), an op element fails alone and is never executed. A token's root and tag scope reaches handlers through the call context, and `ScopeGuard` answers `forbidden` (`scope: root` or `tag`) for an item outside it and fails closed for a missing item or a lookup fault, so scope is no existence oracle.
- **Added: request-field consumption guard.** A bytecode-analysis test fails the build when a field of any tool select or op request class (or a project class nested in one) is never read by production code, ratcheted two-way against `current/src/test/resources/architecture/request-field-consumption-baseline.txt`; it proves presence of a read, not per-handler use (shared request classes, dead branches, log-only reads and interface dispatch are documented limits).
- **Added: the MCP/REST parity harness (test side).** It runs every registry tool over MCP and over `POST /api/v1/ops/{tool}` against identically seeded databases and compares envelope bytes, status and resulting database state; a coverage guard fails the build for a registered select or op without a happy-path and a scope-forbidden scenario.

#### Reads

- **Added: `items_read`.** Selects `get` (one item, full view; `fields` adds timestamps or `ancestors`; `ifVersion` answers `{unchanged, version}`), `list` (a filtered, sorted page of compact views; `tags` and `traits` are arrays matching any; `sort`/`order`), `schema` (the note schema of a type or an item; `{schemaFree: true}` when none matches; per-note `guidance` only with `fields: ["guidance"]`), `search`, `overview` (global, scoped by `itemId` or anchored by `anchorId`) and `related`, plus the orchestration-profile selects `context`, `next`, `blocked` and `history`. Views are lean (empty members omitted, `version` always present, tags an array), compact views and hits carry 8-hex short ids, and claim identity is never rendered. Every id field takes a full UUID or 8 to 32 hex characters, and every tool resolves ids by one scope-aware rule: under a restricted scope an id (or prefix) that matches nothing visible is `forbidden`, never `not_found`, and an ambiguous prefix (`ambiguous_id`) lists only the candidates the scope admits. Every list is `data: {items, page: {total, truncated, capped?, next?}}` with an opaque cursor: `total` is the full count on every page, `truncated` is true exactly when entries exist after the page, and `next` is present exactly when `truncated`; the 3.x 200/500 caps and `offset` paging are gone. Under a restricted token every list, count and total covers visible items only, applied in SQL before paging, ranking and capping.
- **Added: `context`, `next` and `blocked` (orchestration profile).** `context` with `itemId` answers the gate state of one item from one preview read unit (`gateStatus` with `canAdvance`, `blockedBy` of `table`, `dependency`, `note` or `lease`, `blockers`, `contended`, `missing`, `missingBySeat`, `violations`; `next`, `transitions` with `resume` resolved to the previous role, `guidanceKey`, `skillPointer`, `dispatch`, `seats`, `dispatchBySeat`, `claimDetail`, `resourceLeases`); without `itemId` it answers a health view `{active, blocked, stalled, claimSummary}` paged by one `limit`/`cursor`, where `blocked` is role `blocked` or an unsatisfied dependency. `next` pages the items ready to pick up (default limit 1, max 200; eligibility decided in SQL over the whole matching set, so an eligible item is found however many ineligible ones rank ahead; `orderBy`; `includeClaimed` adds a boolean `isClaimed`; an empty page carries `reason` `claim_held` or `dependency_unmet` when items matched but none is ready; ties break by id and items without a complexity sort last). `blocked` pages the blocked items with `blockType` and every blocker (`id`, `title`, `role`, `threshold`, `satisfied`); a blocker that cannot be read is reported with role `unknown` and `satisfied: false`.
- **Added: `items_read` `history` and the V29 backfill.** `history` is the transition audit trail, read from the `item.transitioned` rows of the event log: `{itemId?, since?, until?, limit?, cursor?}` pages newest first with an exact `page.total` (default limit 50, max 200, no default time window). With `itemId` it lists one item; without it, every transition of the items the caller may see. An entry is `{seq, id, itemId, occurredAt, trigger, fromRole, toRole, fromStatusLabel?, toStatusLabel?, origin?, summary?, consumedCredentials?, backfilled?, actor?, verification?}`; `actor` and `verification` appear only when the caller may see attribution, and the proof hash and claims are never returned. Migration V29 copies every `role_transitions` row older than the installation of V20 into `events` as an `item.transitioned` row under the same id (`data.backfilled: true`, no `origin`, seq 1..N below the seq floor, in `transitioned_at` order) and drops the table; a newer row has its twin event and is not copied. A row whose `transitioned_at` does not parse is copied at the item's `created_at` (else the epoch) with `data.timestampRepaired: true`, never skipped, and the server logs the row and backfill counts at INFO, with a WARN naming why any row was not copied. Every audit column survives inside `data`. `EventStore` gains `findByEntity` and `findByTypes`. The SSE seq floor keeps the backfill out of the stream: `EventStore.maxSeq()` never answers below it and `readAfter` never returns a row at or below it, so a resume with `Last-Event-ID` equal to the floor is not `sync.lost` and a connect without a cursor does not replay the backfill.
- **Added: search and knowledge reads.** `search` ranks item content, note bodies and document title and body in one list, narrowed by `kinds` (`item`, `note`, `document`; with `kinds` absent it covers item, note and document when none of `role`, `tags`, `traits` is set, else item and note; naming `document` together with one of them is `invalid_request` on `/role`, `/tags` or `/traits`). A document is visible exactly when its root item is in scope, archived documents are never searched, and `itemId`/`ancestorId` apply through the root. A hit carries `title` (the item's own, or the owning item's for a note) and the `field` that actually matched (`title` when both do; the 3.x item hit reported `title` even when only the summary matched), item search covers `description`, an FTS `limit` above 100 is `invalid_request`, a query with a control character other than ASCII whitespace is `invalid_request` on `/query`, and a search runs its index reads in one transaction. `limit`, `matchMode` and `kinds` are validated; the token's `root_ids` and `tags_include` are applied inside the FTS5 query before ranking and the cap, so a scoped token gets a full page of in-scope hits. One `SearchService` (RRF k=60, ordered by score, kind, id, capped at 100, `page.capped` when more matched) serves it behind a dialect-free `SearchIndex` port. The call's `request_shape` carries `queryHash` (first 16 hex characters of an unsalted SHA-256 of the lowercased terms), `termCount`, `matchMode`, `hitsByKind` and, for each document hit on the returned page, its id and version as a target version; the query text is never stored. `related` returns the items and documents related to an item from four ranked signals fused with weighted Reciprocal Rank Fusion (`edges` 1.0, `links` 1.0, `tags` 0.5, `text` 0.5), each hit `{kind, ..., score, reasons, explain?}` with `reasons` from `relates_to`, `blocks`, `blocked_by`, `links_to`, `linked_from`, `shared_tags:<n>` and `text`; every signal stays in the item's root unless `acrossRoots: true`, scope applies before fusion and the cap of 50, `limit` is 1 to 50 (default 10), and `request_shape` gains `relatedSignals`.

#### Items, notes, dependencies and claims

- **Added: `items_write`.** One `ops[]` array of flat `create`, `update`, `delete` and (orchestration profile only) `create_tree`, `complete_tree` and `repin` elements. `create` requires `type` and takes `tags` and `traits` as arrays and `properties` as an object, and always lands in `queue` (there is no `role` field; a closed `terminal` parent under `auto` lifecycle takes no new children and no reparent onto it); `update` keeps an absent or null field and clears the listed optional fields with `clear` (`tags: []` and `traits: []` clear those lists); `delete` of a parent needs `recursive: true`. `expectedVersion` is the optimistic lock (`version_conflict` with `kind`, `id`, `expected`, `actual`; an update without it locks against the version it just read). A reparent restamps every descendant's `depth` and `rootId` in one statement, and a reparent away from, or a delete under, a parent re-evaluates that old parent like a child completion and reports its cascades as `cascade` on the element (dashboards see them as `item.advanced` events nobody explicitly triggered). `create_tree` writes a root (or attaches to `root.id`), its children (any order, nested with `parentRef`), the dependencies between refs and their notes (explicit, cut from a plan document with `docRef` + `noteAnchors`, or `createNotes` blanks) as ONE unit: all of it or nothing, the document adopted last. `complete_tree` applies `complete` or `cancel` to the descendants of `rootId` (root last) or to `itemIds`, in blocking order, through the `advance` pipeline in one write unit; each item reports `outcome` `applied`, `skipped` (`already_terminal`, or `upstream_failed` with `blockedBy`) or `failed` (the catalog `error` the `transition` op gives), with a `summary`, and a per-item rejection is data that never rolls the others back while a store or per-root config fault fails the whole element. Scope is checked per element before any write (a root create, a reparent target and a move to root included), the caller's principal is recorded on the `item.*` events, and the algorithms live in `WorkTreeService` and `TreeCompletionService`. One `ItemCommandService` owns item writes: placement is computed from the just-inserted parent, and every write service records its own `events` rows.
- **Added: item types and typed traits.** `type` is required, and a create or type change naming an undeclared (or blank) type is `schema_violation` with `detail.availableTypes` when the effective set is non-empty. An item's traits live in the `item_traits` table (V27; one row per item and position, unique per item and name) instead of `properties.traits`, so every store read returns them in a constant number of statements; `ItemStore.traitsOf(ids)` reads any id set in one statement, and `ItemFilter.traitsAny` / `ScopeFilter.traitsAny` select by trait in SQL (exact, case-sensitive). A `properties` object carrying a `traits` key is `invalid_request`; a trait name must be non-blank, trimmed, comma-free, at most 100 characters and unique within the item; when the root's effective config declares any trait, a create or changed list naming an undeclared trait is `schema_violation` with `detail.availableTraits`. `item.updated` reports `traits` in `changed` when the list changes. The `traits-from-properties` data step moves the names out of `properties` on the first 4.0 start (an array or a comma-separated string; other keys are kept and an emptied bag becomes NULL; a row it cannot migrate is reported, and startup logs the count of items whose `properties` still hold a `traits` key). The one-time `type-backfill` step (phase `ITEM_BACKFILL`) fills missing types (see Upgrading): it reads the global config file and every stored per-root config for their `schema_resolution` and schema keys only (an unparseable document counts as no layer, with a WARN, and never fails startup), writes the `type` column only (no `version` or `modified_at` bump, no events; the `data_steps` row is the record) and never retypes an already-typed item.
- **Added: lifecycle-free items (`lifecycle: none`).** A schema may declare `lifecycle: none`. Items of that type have no role: user triggers answer `invalid_transition` (`fromRole: "none"`, no allowed triggers), cascades skip them, `claim` refuses them, and they are left out of role roll-ups, `next`, blocked views and role counts. A `none` schema may not declare `seats` or required notes, and a `BLOCKS` edge with a lifecycle-free endpoint is rejected. An item flips into or out of `none` only at a pin point (type change, trait change, cross-root move, `repin`) and only from `queue` (or `terminal` into `none`); `repin` and `delete` can answer `invalid_transition` when a re-pin would cross lifecycle class. The item view omits `role` for a lifecycle-free item, and config `validate` counts lifecycle-free items in `affectedInFlight`.
- **Added: schema pins.** Every item is pinned to the server and root config versions that were the heads when the pin was taken and to the base schema (key and layer) its type resolved to under them (the V26 `pinned_*` and `lifecycle` columns). A pinned item's schema, trait merge, seats, dispatch and resource requirements come from its pinned versions, so a later push, rollback or reset changes nothing for it; status labels, note limits, independence and the resource registry still follow the heads. Pins are taken on create, on an update that changes `type`, moves the item to another root or changes `traits`, on every transition out of `terminal` (`reopen` and the reopen cascade) and by `items_write` `repin` (`{id, expectedVersion?}`; answers `{id, version, repinned, schemaVersion?}`, writes nothing when the pin is current). `items_read` `get`, the scoped `overview` item and `schema` (by `itemId`) show `schemaVersion: {server?, root?, key?, layer?}` and `stale: true` when a pinned version is no longer the head. Each pin point records `item.pinned` (`{from, to, reason}`; reason `create`, `reopen`, `type_change`, `trait_change`, `reparent` or `repin`), in the events table only. Upserting a note key that a pinned item's schema does not declare, while its current-heads schema requires it, fails `schema_pinned_conflict` (409; `itemId`, `pinnedVersion`, `currentVersion` as `server:<n|none>/root:<n|none>`): repin first. The one-time `pin-backfill` data step (phase PIN) pins every unpinned item on the first 4.0 start with the base schema 3.x gave it and that schema's lifecycle, writes no events and leaves `version` and `modifiedAt` alone; startup logs a WARN-only count of items without a pin.
- **Added: `notes_read` and `notes_write`.** `notes_read` has `get` (one note with its body; `ifVersion` answers `{unchanged, version}`) and `list` (an item's notes without bodies, paged). `notes_write` takes `upsert` and `delete` in an `ops[]` batch with a per-element `idempotencyKey` and optional `atomic`. Every note has a `version` (V28) that starts at 1 and goes up on every upsert of an existing note (a schema-only `ADD COLUMN`); `expectedVersion` is `0` = must not exist, `n` = must be at `n`, otherwise `version_conflict` with nothing written, and `GET /items/{id}/notes/{key}` answers `ETag: "v{version}"` with `If-None-Match` support. One `NoteCommandService` owns the write policy for `notes_write` and `create_tree` notes (`createNotes` blanks bypass it): the `role` is accepted in any letter case and stored lowercase, CRLF is normalized to LF, a body is capped at 65536 UTF-8 bytes in every `note_limits` mode (`payload_too_large`), a schema-declared key must carry the schema role (`schema_violation`), and the schema `maxLength` applies per `note_limits.mode` (`warn` stores the note with a `warning`, `reject` is `note_too_long`). Note attribution (`actor`, `verification`) is shown only to a caller allowed to see it, and proof evidence (`verification.proof`) is never returned to any caller, `ADMIN` included (3.x showed it to `ADMIN`). `bodyFromFile` is MCP-only: over REST an element that sets it fails `invalid_request` (`/bodyFromFile`) and no file is read. A note's `createdAt` and `modifiedAt` are the unit's instant (the same clock as the item and event rows of that call), and a store-agnostic `bodyLength` counts code points. One `BodySource`, built once from the agent config root, reads `bodyFromFile` for `notes_write` and `docs_write`: a relative path strictly inside the root (no absolute path, no `..`, no symlink escape, opened without following links), a regular UTF-8 file of at most 65536 bytes, CRLF normalized to LF, with fixed failure reasons that never echo filesystem text; a context without a configured source fails closed.
- **Added: `deps_read` and `deps_write`.** `deps_read` `list`s the edges touching an item (`direction` `incoming` / `outgoing` / `all`, `type`, paged; full UUIDs, ordered by creation; backlinks are `direction: incoming`) or walks its `graph` of `blocks` edges in topological order (`level` per node, cycle members flagged `cyclic`, a 1000-node walk cap reported as `page.capped`). `deps_write` takes `create`, `delete` (by dependency `id`, by `from`/`to` with an optional `type`, or every edge of an `item`; `{deleted: n}`) and the patterns `linear`, `fan_out` and `fan_in`, each one element whose edges are all created or none. The name `IS_BLOCKED_BY` is not accepted: name the blocker as `from`. A blocking dependency is stored in one orientation (`BLOCKS` from blocker to blocked item, V22) and one `DependencyCommandService` owns the write policy: normalize, reject duplicates within the request (`duplicate`), then against stored rows (a restatement is a duplicate), then cycles (`cycle_detected` with the path); any rejection stores nothing. Migration V22 rewrites existing rows (an `IS_BLOCKED_BY` row is rewritten in place under its id with its ends swapped; when its `BLOCKS` twin exists the twin is dropped and the stricter `unblock_at` survives, `terminal` being stricter than `review`, `work`, `queue`, a missing value counting as `terminal`; mutual blocks are kept and reported at startup) and tightens the `type` CHECK to `BLOCKS|RELATES_TO`. Reads drop edges whose other end is out of scope; a write needs every named item, both stored ends for a delete, in scope; `dependency.added` and `dependency.removed` events carry the call principal.
- **Added: the `advance` tool, phase transitions and claim ownership in one `ops[]` call.** Ops: `transition` (`itemId`, `trigger` of start, complete, block, hold, resume, cancel or reopen, optional `summary` and `credentialRefs`), `claim` (by `itemId`, or find-and-claim with a `select` filter; `ttlSeconds` 1..86400, default 900; `claimRef`), `release` and `heartbeat` (extends a claim the caller holds and never takes an item). All four need the `advance` capability. A `transition` result is `{itemId, previousRole, newRole, trigger, statusLabel?, cascades?, unblocked?, expectedNotes?, guidanceKey?, skillPointer?, dispatch?, violations?}`; a claim result is `{itemId, claimed, claimedAt, expiresAt, originalClaimedAt, refreshed?, selected?, claimRef?}`. A `select` that finds nothing claimable is ok with `claimed: false` and, when matches exist but are held or blocked, `reason` (`claim_held`, `dependency_unmet`), `retryAfterMs` 30000 and `excluded` counts; items outside a restricted token's scope are skipped, never `forbidden`. `gate_blocked` carries `detail.violations` (`[{gate, reason}]`) for an independence block. A claim is refused with `claim_held` (always with the holder claim's `expiresAt`), `resource_unavailable` names the real contended keys with their declared requirement mode, and claim ownership is enforced on every surface: a transition on an item claimed by someone else is `not_claim_holder` over REST as well as MCP (an operator who must take over waits out the claim TTL).
- **Added: write fencing on claimed items.** While an item has an active claim, only its holder may write to it: `notes_write` `upsert`/`delete`, `items_write` `update`/`delete` (a recursive delete over a held descendant included; the first held descendant in id order is reported) and every `deps_write` op naming the item (both ends of each created edge, both ends of a `from`/`to` delete, the stored edge's two ends of a delete by `id`, and the item itself, not its counterparties, for a delete by `item`) answer `403 not_claim_holder{itemId}`, write nothing and record no event, on MCP and REST. The fence runs after the item lookup and before any version compare. A lapsed claim (expiry at or before now) fences nothing, and the holder is never named.
- **Added: `ownershipBlocked` cascades.** A cascade into a parent with an active claim held by another principal is not applied: the parent keeps its role and claim, the primary still commits, and the chain stops there. It is reported as `blocked: "ownership"` on `advance` `transition` and inside each `complete_tree` item's `cascades`, and as `reason: "not_claim_holder"` in `items_write` `update`/`delete` `cascade` entries. Ownership is checked before every other cascade gate, for start, reopen and terminal cascades alike.
- **Added: `version` and `released` on the claim result.** `claim` and `heartbeat` elements carry `version` (the item row version; claim writes do not bump it) and `released` (the ids of the caller's other items the claim auto-released, in store order), omitted when nothing was released.
- **Added: note ownership.** A note is owned by the principal whose 4.0 write created it. The server stores the owner in `notes.owner_id` (V34), apart from the note's `actor` attribution, and never takes it from a request. Another principal's `notes_write` `upsert` or `delete` of an owned note answers `403 note_owned_by_other{itemId, key}` (the owner is never named), writes nothing and records no event, on MCP and REST. A note schema entry (schema or trait level) may set `shared: true` (boolean, default `false`; served as `shared: true` only when set) to exempt its key; a write to a shared key leaves the owner unchanged. A note with no owner, which is every note written before 4.0, is unowned: its first 4.0 `upsert` makes the caller its owner and records an audit `note.ownership_transferred{itemId, key, from: null, to, legacyActor}` before the `note.upserted`, and a `delete` of it is allowed. `notes_write` `upsert` takes an admin-only `takeover: true` that moves an owned note to the caller and records an audit `note.ownership_transferred{itemId, key, from, to}` before the `note.upserted` (a non-admin is `forbidden{capability, admin}`, even on an unowned note; never under a seat assignment; a delete never takes ownership). `notes_read` `get` and `list` show `owner{id}` to a caller who may see attribution, and omit it for an unowned note.

#### Config and documents

- **Added: `config`, the tool for per-root config and rule text.** One `ops[]` call, orchestration profile only, served over MCP and as `POST /api/v1/ops/config`. Ops: `get` (the head or a version of a root's config; `fingerprint` adds `relation`), `history` (versions newest first, paged by `before`), `push` (validate and append; `force`, `expectedVersion`), `validate` (a dry run of `push` that reports problems in the result), `rollback`, `reset` (destructive; an absent or already reset config appends nothing), `effective` (the resolved view of one root, or the server config alone without a `rootId`, with the status graph) and `rules` (list, read one, or follow a note's skill pointer; a root item is its own root). Reads need `read`, `push`, `rollback` and `reset` need `write-config`, and the scope check runs before existence. A push of content an earlier version held is `version_conflict` (`expected` the version that held it, `actual` the head), as is a wrong `expectedVersion`; a rejected document is `config_invalid` with `detail.errors` as `{path, reason}`. For a valid document `validate` reports `affectedInFlight: {count, sample}`, the caller-visible in-flight items of the root (queue, work, review, blocked, lifecycle-free) whose resolved schema would change on a repin after the push, compared at each item's pin (heads when unpinned), with up to 20 `{itemId, changedNotes, schema?, lifecycle?}`; only note keys, `role`, `required`, the base schema and the lifecycle mode count. The `get` op does not list `config_invalid` among its errors.
- **Added: versioned per-root config (V25).** Every pushed per-root config is an append-only version in `config_versions` (version number, canonical JSON, fingerprint, who pushed it and from which request) with a head pointer per scope. `reset` appends an empty reset version instead of deleting, so history and pins stay valid, and a root whose head is a reset reads as having no per-root config; re-pushing identical content appends nothing. `project_config` is replaced (V25 copies every row as version 1, ids preserved), the `project_config.*` events are replaced by `config.version_created`, and stored per-root config that cannot be parsed no longer falls back silently to the server config. A one-time `config-canonicalize` data step fills the canonical form of versions migrated from 3.x. A per-root config read that fails with a transient database error serves that root's last cached parse without evicting it (shared by MCP and REST through one `EffectiveConfigResolver`) and logs a WARN; a read inside a unit of work, or with a cold cache, fails closed with `unavailable`; a head that cannot be loaded is `config_invalid`.
- **Added: strict config validation, a published JSON Schema and `extensions`.** A pushed config is validated before it is stored, and any error rejects it with nothing written. Validation checks the document against a JSON Schema (draft 2020-12, served by `GET /api/v1/config/schema` as `application/schema+json` with an ETag), treats everything the 3.x parser used to skip with a warning as an error (an unknown key, a note with no or an invalid `role`, an invalid `lifecycle` or dispatch `effort`, a seat with no `name`, `maxHolders` above 1), and applies semantic rules: `resources` is server-only, `work_item_schemas` and `note_schemas` cannot be combined, every `default_traits` entry must resolve in the document or the server config, and every trait `resources[].key` must be declared in the server registry. A per-root `schema_resolution` or `resources` registry is rejected (the `ignoredSections` response field is gone), and `schemaWarnings` carries advisory findings only (resource fan-out, a required note no seat owns). Plugin and hook settings (the 3.x top-level `orchestration`, `retrospective` and `actor_attribution`) move under a top-level `extensions` mapping such as `extensions.claude-code`, which the server stores and never reads. A `ParseMode` (`STRICT` for a push, `MIGRATION` for stored versions, the server file and `config-canonicalize`) keeps every config already stored from 3.x loading. New reference: [Config reference](current/docs/config-reference.md). New dependency: `com.networknt:json-schema-validator` 1.5.9, with Jackson pinned to 2.21.7 through its BOM.
- **Added: the mounted global config is imported into the database at every boot.** The every-boot data step `server-config-import` (phase `CONFIG_IMPORT`) parses the file strictly and appends it as a `file-import` config version, byte for byte; a file without `project.rootId` becomes the server config, one with it is imported as that depth-0 root's config. From then on the runtime reads only the stored head, so editing the file without a restart changes nothing and a version another process appends to a shared volume is served on the next read (the last booting process wins on a shared volume). An identical file appends nothing and an absent, empty or comment-only file imports nothing. The 3.x keys listed under Upgrading boot as stored warnings (`configWarnings`).
- **Added: `docs_read` and `docs_write`, versioned documents and links.** Plan documents and rules move into `documents` (V24; one row per root, `kind` `plan`|`rule`|`page` and slug, current body, optional title, `version`, `archived_at`), an append-only `document_versions` history (every write, version 1 included) and a polymorphic `links` table; adoption is an `adopted_by` document-to-item link that outlives the adopting item (`adoptedBy` keeps naming a deleted adopter for an unrestricted caller). Migration V24 copies every `plan_documents` row with its id, body bytes, `content_hash` and timestamps unchanged (a `rule/` slug, matched case-sensitively, becomes kind `rule` without the prefix), writes its version-1 row, turns each non-null `adopted_by_item_id` into a link (a row marked `adopted` whose adopter was deleted migrates as pending) and drops `plan_documents`; two FTS5 tables index titles and bodies. `docs_read` has `get` (the current document, one stored `version`, or `{unchanged, version}` for a current `ifVersion`), `list` (metadata only, archived left out unless `includeArchived`), `history` and `links` (item and note ends outside the caller's scope or no longer existing are dropped before paging, except that the adopter of an `adopted_by` link is shown whenever the scope admits it, even when deleted; `to` is absent while a reference names no document yet, and `toRef` is the reference text). `docs_write` takes `upsert` (`body` or MCP-only `bodyFromFile`, `title`, `expectedVersion`, a slug guard: 1 to 200 characters, no edge whitespace, control characters or `[ ] #`, a plan slug not starting with `rule/`), `archive`, `unarchive`, `delete`, `attach` and `detach`; every op needs `write-config` and the caller's root scope. A write over an adopted plan is `duplicate`, and a plan's `status` is `adoptedBy`. Documents normalize CRLF to LF before hashing and storing, so the `contentHash` and `rulesVersion` of a CRLF body differ from 3.x. `[[...]]` references in documents and notes resolve into links with backlinks, and deleting an item removes the links it was the source of and the resolved links into it (an `adopted_by` link is kept), and deletes the documents anchored to it (archived and adopted ones included) with the same link cleanup as a document delete and one `document.deleted` each, recorded before the item's `item.deleted`. Moving an item to another root restamps the root of the links sourced from its notes and its descendants' notes, so the old root's delete no longer removes them. Startup logs a WARN-only report of links whose target no longer exists. See `current/docs/api-reference.md` (`docs_read`, `docs_write`).

#### Events and SSE

- **Added: the events table and SSE as its projection (V20).** Every mutating store call records typed rows in an append-only `events` table inside the same unit of work as the change, whether or not the REST API is enabled: item create, update, reparent and delete (with one `note.deleted` and one `dependency.removed` per row the foreign-key cascade removes), transitions, notes, dependencies, claims, resource leases, config versions (`config.version_created`), documents (`document.created`, `document.updated`, `document.archived`, `document.deleted`, `document.adopted`) and pins. Rejections are recorded too: `transition.rejected` (`gate_blocked` with the missing note keys, `dependency_unmet` with the blocker ids) by a short follow-up unit that survives the caller's rollback, and `claim.rejected` / `lease.rejected` in the claiming unit. `item.transitioned` carries the full transition audit (`summary`, credentials, actor, verification and `origin` `user` or `cascade`); `modifiedAt` is the instant of the writing unit. `GET /api/v1/events` streams from the table: event `id` is the row's `seq`, monotonic, durable across restarts, shared by every process writing the database and above 10^12; a `Last-Event-ID` below 10^12, above the newest id or unparsable gets `sync.lost` `unknown_event_id`; `API_SSE_BUFFER_SIZE` (default 1000, `0` = none) is the replay window in ids, and a resume further behind gets `sync.lost` `buffer_evicted` with the sentinel id `max(10^12, newest - window)`; every domain event carries `rootId` (a root-scoped resume replays writes made while no client was connected); a connection without `Last-Event-ID` starts at the newest event; control events (`sync.lost`, `auth.expired`) are not stored. Deleting all of an item's notes emits one `note.deleted` per note, deleting an item emits the cascaded `note.deleted` and `dependency.removed` events before `item.deleted`, a transition that ends a held claim emits `item.updated`, an item update that changes no field emits no event and a role change that also edits other fields emits only `item.advanced`. Writes made by another process on the same database (for example an MCP stdio server beside the HTTP server) reach subscribers within about one second. Rejection, lease, config, document and pin rows are table-only, not streamed. A tag-scoped connection drops `item.deleted` and the `note.deleted` and `dependency.removed` events an item delete cascades, unless it already resolved the item's tags from an earlier event. A `note.upserted` row records `bodyFromFile`, the server-side path a caller supplied (never the file contents).
- **Added: run, seat and decision events stream on `GET /api/v1/events`.** `run.opened`, `run.updated`, `run.paused`, `run.resumed`, `run.ended` and `run.marked` stream with `runId` and no `itemId`; `seat.ended` streams with its run and member item (`seat.assigned` is table-only); `decision.raised`, `decision.acknowledged`, `decision.answered` and `decision.withdrawn` stream with `decisionId`, `class` and `severity`, the run and item when the decision names them, and never an `actor`. `ApiEvent` gains the optional `runId`, `decisionId`, `class` and `severity` fields. A `run.*` event, and a decision that names no item, is anchored to its root: a scoped subscriber receives it only when that root is in its root set and, for a `tags_include` token, the root item carries an allowed tag (`sync.lost` and `auth.expired` still reach everyone). The `types=` filter takes these names as written. A `claim.expired` row recorded before its `reason` existed has none and reads as `expired`.

#### Reports

- **Added: `report` (usage select) and `POST /api/v1/ops/report`.** A director-profile tool (also served by the REST route) that reports `call_log` usage aggregates over a half-open window `[since, until)` grouped by one column (`groupBy`: `tool`, `operation`, `host`, `surface`, `principalKind`, `errorCode`, `day`, or admin-only `principal`). The window defaults to the last 7 days and is capped at 400 days (31 days on the raw basis, see the next entry), and `since`/`until` must be ISO 8601 instants with Z or an offset whose UTC year is 0000 to 9999 (anything else is `invalid_request` on `/since` or `/until`). For `usage` a tag-scoped caller is `forbidden` (call rows carry no tags); a root-scoped caller sees only its roots, applied in SQL before grouping, and `rootId` narrows further. It returns counts, errors, response bytes and token estimates, average and maximum latency (`avgLatencyMs`, `maxLatencyMs`), retries and replays per group, with an exact `total` and cursor paging, and never a title, body or argument. A group whose column is NULL is reported with a `key` that omits the `groupBy` member (`key: {}`), never JSON null.
- **Added: `usage_daily` and the daily basis of `report` `usage` (V33).** The retention pruner folds each completed UTC day of `call_log` into `usage_daily` (a replace-semantics upsert plus a one-row `usage_fold` watermark, one write unit per day, today never folded) before it deletes; `call_log` rows are pruned only for folded days. A `report` `usage` window can reach 400 days: a window of at most 31 days inside the raw horizon reads the raw table (`window.basis: raw`), anything else reads `usage_daily` plus the raw tail with `window.basis: daily` (`since` floored, a folded `until` ceiled to UTC midnight; `groupBy: principal` is raw-only). The Retention INFO line gains `folded_days=`; a fold failure pauses only `call_log` pruning for that pass (WARN names the UTC day and exception), and an emptied `call_log` with a fold present reads the daily basis.
- **Added: `report` selects `gates` and `contention`.** `gates` counts `transition.rejected` events in the window: `groupBy` `noteKey` (default; one count per missing note key), `type`, `trait` (these three count only `gate_blocked` rejections, and join the item's current type and traits, not those at the time of the rejection) or `errorCode` (every rejection code, `gate_blocked` and `dependency_unmet`); each group reports `blocks` (distinct rejections) and `items` (distinct item ids). `contention` counts claim and lease events per resource (`groupBy` `resource` only; `kind` `claim` or `lease`): `acquired`, `rejected`, `expired`, `released` and `forceReleased` (forced lease releases; always 0 for claims), plus the server-computed `contentionRate` (`rejected / (acquired + rejected)`), `expiryRate` (`expired / acquired`) and `forceReleaseRate` (`forceReleased / acquired`), each rounded half up to 4 decimals and omitted when the denominator is 0. Both use the same window, paging and `key: {}` rule as `usage`, and `window.retainedSince` names the oldest event of their own source. Unlike `usage`, they are not refused for a tag-scoped caller: a tag-scoped caller sees only the events whose item currently carries an allowed tag (a deleted item's events are invisible), and a root-scoped caller sees the events of its roots.
- **Added: `report` `waste` select.** One object of call-pattern waste signals over a window of at most 7 days, each `{value?, numerator, denominator, basis?}` (`value` rounded half up to 4 decimals, omitted for a zero denominator) and always present: `redundantReads`, `readsWithoutLaterWrite` and `relatedFollowThrough` (`edges`, `links`, `tags`, `text`) over MCP session rows only (`basis: "mcp_sessions"`), and `overFetch`, `emptyNext` and `lifecycleFreeRejections` over both surfaces. Rows are classed into READ and WRITE tools by constants checked against the registry; replayed rows are not counted as lifecycle-free rejections. Visibility is that of `usage`, applied to both sides of every session join, on the reader pool, and the signals are one statement; retainedSince and the root lookup run separately. The `rootId`, `since` and `until` descriptions are now shared by every report select.
- **Added: `report` `rollup` select (anchor scope).** One object summarising an item subtree for the director: `anchorId` (required; the anchor and every descendant, any depth) gives `itemCounts {total, byRole}` (lifecycle-free items excluded, their descendants still counted; zero roles omitted), `blocked {total}`, `transitions {total, cascades, rejected}` and `notes {written}` over a half-open window, and `cost {calls, errors, retries, responseBytes, responseTokensEst, basis: "targets"}` from the call log (a call counted once whether it names a subtree item or shares a request id with a subtree event). The default window spans the subtree's own events, clamped to 31 days (`window.clamped: true`); an explicit window over 31 days is `invalid_request`. A tag-scoped caller is `forbidden`; a root-scoped caller's cost counts only its roots' rows. One read unit and a constant number of statements regardless of subtree size. An anchor rollup has none of the run-scoped fields (`run`, decisions, seats, attempts, budget); see the run rollup.
- **Added: `report` `rollup` over a run (`runId`) and the close snapshot in `run.ended`.** `rollup` now takes exactly one of `anchorId` and `runId` (neither is `invalid_request` on `/anchorId`, both on `/runId`). `runId` resolves like `runs_read` `get` (a root-scoped caller asking for another root's run, or an unknown one, is `forbidden`) and always covers the whole run, so `since` or `until` with it is `invalid_request`. It answers `scope {runId, rootId}`, an informational `window` (`openedAt` to `closedAt` or now), `run {status, terminalReason?, openedAt, closedAt?}`, `itemCounts` and `blocked {total, byReason, resumable?, dwellMs?}` over the current members (`resumable`: blocked `awaiting_decision` members whose decision no longer waits; `dwellMs`: blocked milliseconds per reason inside the window, from the members' transition events), `transitions` and `notes` from the events stamped with the run, `cost {..., basis: "run"}` from the calls stamped with it (a lower bound), `decisions {open, byClass, bySeverity}` (effective severity), `seats {active, stalled (at most 5), count}`, `attempts {total, max}` and `budget {used, limit?, basis: "enforced"}` (tokens, from the run row, never from the call log). Every close, whatever its reason (the decision `stop` close included), computes the rollup inside the close unit and stores it in `run.ended` as `data.rollup` (full UUIDs); a closed run then answers that snapshot, so its figures survive retention. One read unit and a constant number of statements whatever the members, seats, decisions, events and calls. The tool's compact entry stays within its 1,800-character fixture (the shared `since`, `until`, `groupBy` and `cursor` field docs and the tool description were shortened).

#### Storage and operations

- **Added: a call log with per-call `reqId` correlation (V21) and client host capture.** Every MCP tool call and every non-SSE `/api/v1` request gets an 8-character `reqId` (lowercase Crockford base32, 40 random bits), returned as `meta.reqId` and `_meta.reqId` on MCP and as the `X-Req-Id` response header on REST (in the default `CORS_EXPOSE_HEADERS`), set as the MDC `reqId` log key, stamped as `req_id` and `session_id` on the `events` rows the call writes, and the key of the call's row in the new `call_log` table: principal, surface, tool, operation, target ids and versions, request shape, outcome and error code, BUSY-retry attempts, replayed, latency, request and response byte sizes, a `bytes/4` response token estimate, batch size, failed count and result and eligible counts. Rows are written in batches by a background writer off the request path (bounded queue of 10 000, batches of 100 or every 250 ms, drained on shutdown); a full queue drops rows with a periodic WARN notice and never blocks or fails a call. Client-derived text is bounded: REST `tool` is the matched route template as declared (a call that matched no route is `<METHOD> /api/v1/<first segment>` for a served resource, else `<METHOD> unmatched`), `operation` must match `^[a-z_]{1,64}$` (else `invalid`), `request_shape` keeps at most 16 conforming keys; only `/api/v1` and `/api/v1/**` are logged. The `host` column of a `call_log` and `events` row records the calling client: the client's `initialize` `clientInfo.name` for MCP, and for REST the `X-Client-Host` request header, else the first `User-Agent` product token, else `rest`, normalized (control characters dropped, trimmed, lowercased, capped at 64 code points) and data only.
- **Added: related follow-through and lifecycle-free rejection telemetry (V30) and the MCP per-call log line.** Migration V30 adds two nullable `call_log` columns. `hit_signals` records, for an `items_read` `related` call, the returned page hit by hit as `[{id, kind, signals}]` (signals in the order edges, links, tags, text; never a title or text), so a later MCP call in the same session that targets a hit (other than a later `related` or `search`, which record their own hits) can be attributed to the signals that found it (REST rows carry no `session_id`, so follow-through is MCP-only); NULL for every other call and for an empty page. `lifecycle_free_rejections` counts the rejections in a response caused by a lifecycle-free item (`invalid_transition` with `fromRole: "none"`, or the BLOCKS-endpoint `invalid_request`), per failed batch element or else the top-level error, NULL when none. Both MCP and `POST /api/v1/ops/{tool}` fill them. Each MCP call that was not cancelled also logs one INFO `tool call:` line inside its MDC (tool name, operation, outcome, error code, latency, response bytes; never argument values or body text). The BLOCKS-endpoint reason text is now the shared constant `DependencyCommandService.LIFECYCLE_FREE_ENDPOINT_REASON` (unchanged wording).
- **Added: retention pruning for `events`, lease history and `call_log`.** A background pruner deletes rows older than `RETENTION_EVENTS_DAYS` (activity-class events, default 180), `RETENTION_AUDIT_EVENTS_DAYS` (audit-class events, default 0 = forever), `RETENTION_LEASE_HISTORY_DAYS` (closed lease intervals, default 365) and `RETENTION_CALL_LOG_DAYS` (default 90, effective only for call-log days already folded into `usage_daily`). It runs once after startup and then hourly, in write units of at most 5,000 rows and at most 200,000 rows per pass. It never prunes the SSE replay window, the latest `claim.expired` per item, open lease intervals or an unclassified event type, and it runs no `VACUUM`. An invalid `RETENTION_*` value fails startup. See `current/docs/fleet-deployment.md` "Data Retention".
- **Added: call root, run indexes and request-shape fields in the call log (V31).** Migration V31 adds one nullable `call_log.root_id` column (BLOB, no default, no foreign key) and three indexes: `idx_call_log_root_at` on `call_log(root_id, at)`, and the partial `idx_call_log_run_at` on `call_log(run_id, at)` and `idx_events_run_seq` on `events(run_id, seq)`, both `WHERE run_id IS NOT NULL`. It is additive: existing rows read NULL (not captured), with no backfill. `root_id` is the single root the call read (an `items_read` select that loads an item, a related reference, a document hit) or wrote (the roots of the `events` rows it appends); NULL when the call touched no root or more than one. `request_shape.multiRoot` is `true` when a call touched more than one distinct root (the key is absent otherwise). `request_shape.fields` lists, sorted, the `fields` names an `items_read` select accepted. `events.run_id` and `events.seat` now carry the call's recorded run and seat (NULL until a run is stamped). The `request_shape` target-id keys also cover `from`, `to`, `item`, `itemIds`, `rootId`, `anchorId`, `ancestorId`, `fromIds` and `toIds`. Both MCP and `POST /api/v1/ops/{tool}` fill them.
- **Added: runs and run members (V32), the run lifecycle, run events and the `runs:` config.** Migration V32 adds `runs` (one row per run, kept forever: root, optional scope item, label, title, status `open`, `paused` or `closed`, pause reason `budget`, `attempts` or `manual`, terminal reason `completed`, `cancelled`, `failed`, `abandoned` or `budget_exhausted`, who opened and closed it and when, the coordinator-seen time, `stall_after_s`, the budget, reported and observed token and cost counters, hints, marks, and a version) and `run_items` (the member items of a run with the time each was added; deleting a root removes its closed runs, and the service refuses a root delete while a run on it is open or paused). A run is `open`, may be `paused` and resumed, and ends `closed` with a terminal reason; a close cascades, releasing the claims and resource leases held by the run's assignees. New errors: `run_closed` (a write to a closed run), `run_paused` (a write that needs an open run), `invalid_run_state` (a transition the state does not allow). Run events `run.opened`, `run.updated`, `run.paused`, `run.resumed`, `run.ended` and `run.marked` are recorded in the `events` table and are audit class (kept under `RETENTION_AUDIT_EVENTS_DAYS`, default forever). The `runs:` config block sets `stall_after` (seconds, default 600); run budgets are opt-in with no default, so an absent budget means unlimited. This entry covers the entity, store and service layer; the tools that drive it are described in the run tool entries that follow.
- **Added: the run tools `runs_read` and `runs_write`.** `runs_read` serves `get` and `list` (newest first; `status`, `rootId`, `endedSince`, and `unmarked`, a mark key that keeps only closed runs without it) in the orchestration and director profiles, with capability `read`. `runs_write` serves `open` and `close` (orchestration and director), `update` (orchestration), `pause` and `resume` (director) and `mark` (REST only: the first op on no MCP profile; it sets a named instant on a run in any status, first write wins, at most 32 marks), each needing the `runs` capability, which is now enforced. Permissions: `close` is for the coordinator, the director profile or a human token, `pause` and `resume` for the director profile or a human token (the opener may not pause), `update` for the coordinator only (the coordinator token's holder), and a refusal is `forbidden` with `detail.scope: principal_kind`; a run is as visible as its root, so an out-of-scope run is `forbidden` before any permission rule and `list` leaves it out; under a restricted scope an unknown run id or prefix is `forbidden` too and an ambiguous prefix lists only admitted runs. `open` records `openedBy` and `openedByKind` (the credential's kind, or `agent` for the local principal). A run id is a UUID or an 8 to 32 character hex prefix. Budgets are written as `costUsd` on the wire. The 4.0 close takes no `summary`: a run has no column for it.
- **Added: run seats, the seat token and seat-scoped attribution (V35).** Migration V35 adds `run_seats` (one row per seat assignment, kept forever: run, member item, seat, attempt, the principal the token is bound to, the SHA-256 of the token, status `active` or `ended`, outcome `done`, `failed`, `abandoned`, `superseded` or `run_closed`, summary, links, hints, assigned/ended/last-seen times and the highest reported usage), `work_items.claim_assignment_id`, `notes.seat` (attribution: the seat of the token that made the note's latest write, NULL without a token and on every pre-4.0 note) and `notes.owner_seat` (the seat half of the note owner `(owner_id, owner_seat)`, written only with `owner_id`); it is additive (no backfill). `runs_write` `open` now also returns `coordinatorToken`, the run's coordinator credential: the coordinator is the caller presenting that token, no longer the opening principal, so `update`, the new `assign` and a coordinator `close` need it. `assign{runId, itemId, seat, hints?}` (orchestration) assigns a seat of a member item (a seat of its schema, or `implementer`/`planner` for an item with none; unknown is `not_found` with `detail.kind: seat`) and returns `{assignmentId, seatToken, attempt}`; re-assigning an active seat supersedes it (its token dies and its claims are released) and counts a new attempt. `end_seat{outcome, summary?, links?}` ends the calling seat's own assignment (outcome `done`, `failed` or `abandoned`; summary at most 500 characters, at most 10 `{kind, ref}` links) and releases its claims, reported in `released`. `runs_read` `seats{runId, itemId?, ended?}` lists a run's assignments (orchestration only), never with a token. Any tool call, MCP or REST, may carry a top-level `seatToken`: a 43-character token is accepted only while its assignment is active and only from the principal it was issued to, anything else is `unauthenticated` with the fixed message `The seat token is not accepted.` and no detail, checked before anything else in the arguments is read. A call under a token drops ADMIN (no principal visibility, no `takeover`), records its run and seat on its events and call-log row, and is its own idempotency principal (`<principal>#<assignmentId>`). Claims are held by the pair (principal, assignment): two seats of one stdio principal no longer evict each other's claims, and a call without the seat's token is not the holder of the seat's claim. A stdio or local agent that presents a `seatToken` holds claims as `(local, assignmentId)`, so two local agents with different seats are distinct holders for claims, fencing and note ownership. A note created or adopted under a token is owned by (principal, seat) (`owner_id`, `owner_seat`; an admin `takeover`, never under a token, clears the owner seat), `notes_read` shows `owner.seat` from `owner_seat`, and `note_owned_by_other` reports `ownerSeat`. Every note write records the token's seat as attribution (`notes.seat`, rewritten like the actor), and the independence gate compares the attribution pair `(actor, seat)`, never the owner, so two seats of one principal are independent (a token-less note and a seat note of the same principal are distinct identities too). `advance` `heartbeat` takes `usage{tokens, costUsd}` under a token (absolute; the highest value per field is kept). New events `seat.assigned` and `seat.ended` (activity class). A run closed with any reason ends its seats (`run_closed`), the coordinator included; `completed` is still refused while a member seat is active.
- **Added: seat enforcement inside open runs.** On an item that is a member of a run that is not closed (open or paused) and whose pinned schema declares `seats`, `advance` `transition` (MCP and `POST /api/v1/ops/advance` alike) now produces `seat_forbidden{itemId, seat, action, allowedSeats}` (403): a member-seat token acts only on its own item, may `start` it only into the phase its seat `enters` and may send any other trigger only when its seat's new `transitions:` list it (default `block`, `hold`); the run's coordinator token may send any trigger the table allows; a call without a token, or with another run's or another item's token, is refused (`seat` is omitted without a token). The check runs right after claim ownership and before the table, dependency, note and lease gates, applies nothing and records no rejection row (the call log carries the error code, seat and host). `complete_tree` is seat-checked per item. A human credential without a token is exempt; previews (`items_read` `select=context`) ignore seats, but a cascade into a seat-enforced member of an open run is applied only when the write's caller could send the equivalent trigger to that member itself (the coordinator, a human, or a seat token bound to that member whose seat may send it): otherwise it is suppressed as `blocked: "seat"` (`items_write`: `reason: "seat_forbidden"`), after ownership and before every other cascade gate, on `advance`, `complete_tree`, and the `items_write` reparent-away and delete cascades alike, so a write on a non-member child no longer moves a member it could not touch directly; items outside an open run or with a seat-less schema behave as before. Note ownership rule 2: in the same scope a seat-owned key (its schema entry's `seat`, declared in the same phase) is written (`upsert` or `delete`) only by that seat's token, or by the coordinator token when the seat is `orchestrator`; any other caller is `note_owned_by_other{itemId, key, ownerSeat}`, even on a first write and even with an admin `takeover`; `shared` keys and humans are exempt. Seats gain `transitions:` (a list of `complete`, `block`, `hold`, `resume`, `cancel`, `reopen`; served by `items_read` `select=schema` only when declared); an entering seat listing `complete` or `cancel` is a structural config error in every mode. `ErrorDetail` `seat_forbidden.seat` is now omitted without a token. A seat or coordinator token is shown once: the idempotency store never holds a token, so a keyed replay of `runs_write` `open` or `assign` returns the result without `coordinatorToken`/`seatToken` and with `tokenRedacted: true`; recover a seat token by re-assigning the seat, and a coordinator token only by closing the run and opening a new one.
- **Added: run liveness, the `runs_read` `frontier` select and claim displacement reporting.** One stalled rule serves seats and the coordinator: stalled when the time since last seen (a seat's `lastSeenAt`, else its `assignedAt`; the run's `coordinatorSeenAt`) is strictly longer than the run's pinned `stallAfterSeconds`; a closed run is never stalled, and stalled is a derived view only. Every run view gains `stalledSeats` beside `activeSeats`. `runs_read` `select=frontier{runId}` (orchestration and director) answers `{runId, status, stalled, counts, ready, inFlight, waiting}` in one read unit: per-role member counts, the members ready to start (not terminal or blocked, no active seat, no unsatisfied blocker, no foreign live claim), the active member seats with their stalled flag, and the waiting members with reason `manual` or `dependency` and the visible unsatisfied blockers. Only a call made with a seat or coordinator token on a tool that takes an `ops` array refreshes `lastSeenAt` (whatever op it names, a read-only one such as `config` `get` included; an `advance` `heartbeat` is the explicit form); a call to a `select` tool and a `describe: true` call never do. A `claim` that takes an item over a different holder pair's lapsed claim now reports it: the result gains `displaced: {assignmentId?, expiredAt, principal?}` (the principal only for a caller that may see principals), and the claim's one `claim.expired` row is recorded in the same unit with `reason: "displaced"` and `displacedBy`; every other `claim.expired` row carries `reason: "expired"`, and a claim already reported by a sweep is not recorded twice.
- **Added: run budgets, attempt caps and the active-seat cap.** A run's reported usage is now the SUM of its seats' kept reports (`runs.reported_tokens` / `reported_cost_micro`), and the call-log writer adds each stored row's `response_tokens_est` per `run_id` (a duplicate or refused row adds nothing) into `runs.observed_tokens` (it never runs a check). Every token-carrying write (`heartbeat`, `transition`, `assign`, `end_seat`, `raise`) checks its run's budget in its own unit (tokens `max(reported, observed)`, cost reported, breach strictly over the limit): a crossing pauses the run (`budget`) and raises a server-raised `run-budget` decision (`extend` / `stop`), committed with the write, whose result carries `paused: {decisionId}`. Past `attemptsPerSeat` an `assign` is `run_paused` (`attempts`) and the run pauses on an `attempt-cap` decision (`allow_one` / `skip_item` / `stop`); past `maxActiveSeats` it is the new transient error `run_at_capacity{runId, active, max}` (409). While a run is paused for a limit (`budget` or `attempts`), a `start` under a seat or coordinator token is `run_paused` (a manual pause never refuses it). Answers act on the run: `extend{amount}` (new `runs_write` `answer` field) raises the limit and resumes, `allow_one` and `skip_item` resume, `stop` closes the run `budget_exhausted` with the close cascade.
- **Added: decision requests (V36) and the `decision_classes:` config.** Migration V36 adds `decision_requests` (one row per decision a run, a seat or a coordinator raises: root, optional run and item, class, an answerers snapshot, severity `high`, `normal` or `low`, status `open`, `acknowledged`, `answered` or `withdrawn`, the question, 1 to 8 options, the recommended option, links, context, who raised it, expiry, the acknowledgement and the answer with its provenance); it is additive (no backfill), with a partial unique index on `(root_id, scope_key, dedupe_key)` for the rows that are not withdrawn. `runs_write` gains `raise` (orchestration, `runs`): one of `itemId`, `runId` or `rootId` is required (a seat token supplies its run and item), an item without a lifecycle is `invalid_request` on `/itemId`, the payload is capped at 4,096 bytes (`payload_too_large`), and re-raising the same `dedupeKey` in the same scope (the item, else the run, else the root) returns the pending or answered decision with `existing: true`, or `idempotency_mismatch` (with the dedupe key as `idempotencyKey`) when the class, question or option keys differ; `ack`, `answer` (director, the new enforced `decide` capability) and `withdraw` (director, `runs`). Who may answer is the class's `answerers`: `human-only` (a human credential with proof `verified`, `verified_stale` or `issued`, no seat token), `director-or-human` (also the director profile without a seat token) or `any-actor`; a refusal is `forbidden` with `scope: principal_kind` and `required` `human`, `director,human` or, for `withdraw` (the raiser or the run's coordinator token), `coordinator,raiser`; a server-raised `run-budget` or `attempt-cap` decision cannot be withdrawn by anyone (the same `forbidden`) and stays pending until it is answered (closing its run withdraws no decision; `run.ended` counts it in `openDecisions`). An answer is final and records the answerer's kind, proof status, seat, run and profile and `afterExpiry`; acknowledging, answering or withdrawing an answered or withdrawn decision is the new error `decision_closed{decisionId, status}` (409). `runs_read` gains `decisions` (newest first; `runId`, `itemId`, `rootId`, `overdue`; no status filter, each summary carries `status` for callers to filter client-side) and `decision` (director, `read`); the answerer's and raiser's principal ids, host and session id are shown only to `admin`. `not_found` gains `detail.kind: decision`. An open or acknowledged decision is overdue from `expires_after` and reads with `effectiveSeverity` one step up. `items_read` `context` (item mode) gains `decisions[{id, class, status, option?}]`. New audit events `decision.raised`, `decision.acknowledged`, `decision.answered` and `decision.withdrawn`; `run.ended` now counts the run's pending decisions in `openDecisions`. The config block `decision_classes:` (server and per-root; a per-root entry replaces the server entry of that name) declares `{answerers, severity, expires_after, on_expiry: escalate, freeform}` per class and overrides the built-ins field by field: `question` (any-actor, low, freeform), `deviation` (director-or-human, normal, overdue after 14,400 s) and the server-raised `run-budget` and `attempt-cap` (director-or-human, high; refused on `raise`, and their answerers can never be `any-actor`, a `config_invalid` error).
- **Added: typed blocked reason (V37).** Migration V37 adds two nullable `work_items` columns, `blocked_reason` (`awaiting_decision`, `dependency`, `resource` or `manual`) and `blocked_ref` (the decision an `awaiting_decision` block waits on); no row is rewritten, and a blocked item from before V37 reads as `manual`. `advance` `transition` takes `reason` and `decisionId` on `block` and `hold` (default `manual`; `decisionId` goes with `awaiting_decision` and must name a pending decision about the same item; `resume` clears both), `runs_write` `raise` takes `block: true` to raise and block in one unit, and the reason is read as `blockedReason` on `items_read` `get` and `context`, as `reason`, `decisionId` and `resumable` on `blocked` entries, as `blockedByReason` beside `childCounts` on `overview` and on the `frontier` waiting reason. The `item.transitioned` payload of a transition into blocked adds `blockedReason` and `decisionId`. Answering a decision never resumes the item.
- **Added: startup data steps (V23).** A `DataStepRunner` runs idempotent code-level upgrade steps after Flyway and before any transport starts, in fixed phases (`CONFIG_CANONICALIZE`, `CONFIG_IMPORT`, `ITEM_BACKFILL`, `PIN`), recorded in the `data_steps` ledger (a `once` step and its row commit in one transaction; an every-boot step leaves no row). A failed step, or a registered once step with no row after the run, fails startup as `DATA_STEPS` and the readiness marker is never written. Data steps also run with `SCHEMA_MODE=validate` but never on a `FLYWAY_REPAIR` run.
- **Added: `SCHEMA_MODE`, `FTS_INTEGRITY` and the migration lock.** `SCHEMA_MODE=migrate|validate` (`validate` runs no migrate and no baseline; a pending or future version, or an empty database, fails startup). `FTS_INTEGRITY=startup|off` (`off` skips the per-boot FTS5 `integrity-check`, about 2.6 s at 100 000 notes; the FTS inventory check still runs and still fails startup on a missing table or trigger; an unrecognized value falls back to `startup` with a WARN). Concurrent boots serialize on `<db>.migrate.lock`, and Flyway's connection gets `busy_timeout` and an explicit foreign-keys-off policy. Startup verifies the FTS5 tables and triggers, rebuilds a desynced FTS index, and logs WARN-only reports for placement drift, mutually blocking dependency pairs and dangling links.
- **Added: a CI guard keeps the dogfood config frozen on `release/4.0`.** `.github/scripts/dogfood-freeze-guard.sh`, run by the PR base guard workflow, fails a pull request into `release/4.0` that changes `.taskorchestrator/config.yaml` or `deploy/global-config/` unless its head branch is `4.0/sync-main-*`, an explicit hatch for a twin pull request that mirrors a `main`-side change to those files (there is no `main` to `release/4.0` merge sync). Architecture tests also pin `parent_id` / `root_id` / `depth` writes to a ratcheted baseline, `claim_expires_at` comparisons to the claim predicate files and persisted timestamps to the UTC column type.

### Changed

#### Workflow and claims

- **Changed: advance is one transaction, evaluated by one transition policy.** Each transition runs as ONE unit of work: the item is re-read inside the unit, the transition policy decides it (ownership, transition table, cascade warrant, hold, dependency, required notes, resource lease, in that order), and the role change, its audit row, its `item.transitioned` event, the lease acquire or release and every cascade it triggers commit together or not at all (a fault while applying a cascade, or releasing a lease, fails the whole advance and rolls the child back too). `resume` into work or review is dependency-gated and `cancel` never is. Start and reopen cascades are dependency-gated and honour the parent's lifecycle (under `manual` or `permanent` no start or reopen cascade happens), and a terminal cascade is suppressed for a `blocked` parent or one with an unmet blocking dependency, cancel-originated cascades included, and reported with its reason (`blocked: role | dependency | gate | resource`). One label policy applies (a per-root or global `status_labels.cancel` takes effect). `canAdvance` of `context` means "a `start` would be allowed right now", claim ownership excluded. A contended resource is detected from the current holders before anything is written, with `retryAfterMs` the time until the soonest holder's lease expires, and a lease-store fault fails the advance. `transition.rejected` (`gate_blocked` / `dependency_unmet`) and `lease.rejected` rows are recorded by the advance inside its unit and survive its rollback, exactly one per rejected advance. Config is read inside the advance's unit (the advanced item's schema, independence policy, resource requirements and status labels, and every cascade parent's), so a required note added by a config push just before the advance is enforced; a unit has no last-known-good fallback, so a per-root config read fault fails that transition with `unavailable` even when the cache is warm (on a cascade parent it skips that cascade with a WARN). A transition into `terminal` clears the claim unconditionally in the unit.
- **Changed: claims and resource leases are owned by `ClaimService`, which also reports expiry.** The claim and lease writes of an advance (lease acquire on entering work, release on leaving it, claim clear on entering terminal), item deletion and `DELETE /api/v1/resources/leases/{key}` go through one service that records the `claim.*` and `lease.*` events itself. New audit rows (table only, not streamed over SSE): `claim.expired` (`holder`, `expiresAt`) and `lease.expired` (`key`, `expiresAt`), exactly one per lapsed claim or lease (lapsed means expiry at or before the unit instant), written by a contender's claim, release or lease acquire, by a committing transition of the item, or by a sweep that runs once at server start and then hourly (a failure only logs a WARN). Overwriting, clearing or removing a lapsed claim or lease records `*.expired` instead of `claim.released` / `lease.released`. Claims are not cleared by detection, so `claimStatus=expired`, `isExpired` and the health expired counts keep their meaning; lapsed lease rows are removed by the sweep or a contender, their history interval closing as `expired`. An expiry equal to "now" is expired everywhere, and a claim with `claimedBy` set but no usable timestamps is never active. Claiming, refreshing, releasing, auto-releasing and clearing a claim no longer bump the item `version`. Claim and resource-lease SQL binds the ambient unit instant instead of a database clock (`WorkItemRepository.dbNow()` is removed), and a claim `retryAfterMs` is the time left on the bound clock and at least 1.
- **Changed: `GET /api/v1/resources/leases/history` is a list page with an exact total.** `page.total` is the count of ALL matching intervals on every page (it was the size of the returned page), `page.truncated` and `page.next` work, and the new `cursor` query parameter takes the previous `next` (an offset only, not bound to `key` or `at`; empty or malformed is `invalid_cursor`, and `invalid_request` field faults are answered first). Order is `(acquiredAt desc, id)`, newest first. Walking `next` visits every interval once only while no lease starts or ends during the walk; a walk during lease activity can repeat or skip rows.
- **Changed: claim selectors and `next` rank in SQL.** Eligibility (claim, ancestor-claim shielding and dependency blocking) and ordering run in one query over the whole candidate set: an eligible item is found even when more than 200 ineligible candidates rank ahead, `call_log.eligible_count` is the exact number of eligible items, and with `includeClaimed` the whole set is ranked, tag matching is trimmed and case-insensitive, and an item whose blocker cannot be read is treated as blocked (fail closed).
- **Changed: cascade and unblocked echoes are scope-filtered.** Under a restricted scope, a `cascades`/`cascade` or `unblocked` entry for an item the scope does not admit is echoed as `{itemId}` only (no roles, applied flag, blocker, missing notes, contended keys or title), on `advance` `transition`, `complete_tree` and `items_write` `update`/`delete`. An unrestricted caller sees the full entries, unchanged.
- **Changed: a selector claim re-checks ancestor claims inside the claim unit.** A candidate whose ancestor gained an active claim by another principal after ranking is skipped (counted in `excluded.ancestorClaimed`, `reason: claim_held`) and the next candidate is tried; an ancestor the caller holds never shields.
- **Changed: runs and rollup hardening.** Under a restricted scope, `advance` `block` with a decision id outside the scope answers "names no decision", like an unused id, and a run view (`runs_read` get and list, every `runs_write` result) counts only the members and seats the scope can see. A seat token whose run has no budget skips the run-limit reads (the token lookup is one statement and carries the hint). The run rollup `budget` gains `breaches` and `extends` (omitted when 0), the close-time snapshot reads each table once and stores no rollup, instead of failing the close, when computing or encoding the rollup fails (a row that cannot be decoded in the snapshot's reads, a data fault, no longer fails the close; any other fault still does), and a blocked item or event with no reason (or an unknown one) reads `manual` everywhere.

#### Config

- **Changed: config resolves by one precedence rule; `schema_resolution` and tag-selected schemas are removed.** One `TraitResolver` does every trait merge. An item's schema comes from its `type` only: per-root exact type, then server exact type, then per-root `default`, then server `default` (an item with no type gets only the `default` chain). Every trait dimension uses item traits first, then `default_traits`; note order and resource `ttlSeconds` follow that order, and a note key's `required` is true when the base schema or any trait marks it required, its other fields coming from the first declaration, base first. When the per-root `traits:` map declares a trait, the per-root entry replaces the whole server entry (notes, seats, resources and dispatch). A per-root `resources:` registry is not allowed (the registry is server-only), and the `effective` view no longer carries `schemaResolution`.
- **Changed: `RESOURCE_LEASES_ENFORCED` is read once at startup.** The kill switch is parsed into `AppConfig.resourceLeasesEnforced` when the server boots; changing it requires a restart (the 3.x text that said it was re-read per advance call was wrong for a running process).

#### REST

- **Changed: the REST surface is the RPC route plus a few resources, and every body is the envelope.** `GET /items/{id}` and `GET /items/{id}/children` are thin translations to `items_read` (any other query parameter is `400 unknown_parameter`; `GET /items/{id}` answers `ETag: "v{version}"` and `304` for a matching `If-None-Match`), `GET /items/{id}/notes[/{key}]` to `notes_read`, and no route takes an item `If-Match` any more (writes use `expectedVersion`). `GET /api/v1/config/schema` is new. The resource-lease admin routes answer list pages and envelopes (`GET /resources/leases` with `page.total` equal to `items.length`; `/history` with `page.total` the count of ALL matching intervals, `limit` 1..200 default 50; `DELETE /resources/leases/{key}` answers `{resourceKey, releasedCount}` or `not_found` kind `lease`; holder identity fields stay ADMIN-only). A missing, empty, invalid or expired bearer token is `401 unauthenticated` (the RFC 6750 `WWW-Authenticate` challenge is unchanged); a missing capability is `403 forbidden` with `detail: {scope: "capability", required}`; a rejected `Host` header is `403 forbidden` with `detail: {scope: "host", required: "an allowed Host header"}` on `/api/v1`, `/.well-known` and `/mcp` alike (neither the allowlist nor the rejected value is echoed); an unsupported `Content-Type` is `400 invalid_request` (was `415`); a body over the limit is `413 payload_too_large`; `GET /api/v1/info` answers `{ok, data: {...}}` with `configWarnings`, and a server-config head that cannot be read is `503 unavailable` or cannot be loaded `422 config_invalid`; the `GET /api/v1/events` pre-flight answers envelopes. The REST `call_log` `error_code` is the envelope's `error.code`. `?root` on `GET /api/v1/events` is strict: any value that is not a UUID is `400 invalid_request`, a mix of valid and invalid values included. An unrecognized `role`, `priority` or `claimStatus` filter value is `invalid_request`.

#### Storage and operations

- **Changed: the compose `http` profile opts into `MCP_ALLOW_UNAUTHENTICATED`.** `docker compose --profile http up` runs with the REST API off, which now fails startup without the opt-in, so the profile sets `MCP_ALLOW_UNAUTHENTICATED: "true"` (loopback-published, `/mcp` served as the `local` principal). The quick-start HTTP example sets it too, and the docs now describe the three `/mcp` modes (credential, local with the API off, local with `API_AUTH_MODE=none`) in place of "`/mcp` is unauthenticated by design".
- **Breaking: Flyway is the only schema path.** `USE_FLYWAY=false` no longer selects Direct mode, a database ahead of the binary is refused, and a Direct-mode database is baselined at V17 only on an exact V17 match, otherwise refused (Flyway `clean` is disabled). `USE_FLYWAY` is removed and ignored (one WARN if still set); `DirectDatabaseSchemaManager` and `SchemaManagerFactory` are deleted. Migrations move to `db/migration/sqlite/` (files byte-identical, existing histories validate unchanged). Shutdown cleanup runs LIFO so the database closes last.
- **Changed: units of work and connection pools.** Database access goes through a `UnitOfWork` port (`application/port`) with an ambient unit in the coroutine context: a nested `write` or `read` joins the outer transaction, and the outermost block commits or rolls back. Two HikariCP pools back it (one `IMMEDIATE` writer connection serialized by an in-process lock, and `DATABASE_MAX_CONNECTIONS` `query_only` reader connections). SQLITE_BUSY/LOCKED retries the whole unit for up to 10 s, then surfaces as `unavailable` (`retryAfterMs` 1000; a connection-pool timeout is `unavailable` with 2000); unique and foreign-key violations at the boundary become `duplicate` and `not_found`; any other store fault is `internal`. `DATABASE_BUSY_TIMEOUT_MS` applies to Flyway and startup compaction only (pools use a fixed 1 s), in-memory SQLite URLs are refused, and `RepositoryProvider` moved to `application.port` and no longer exposes `database()`. Every write site runs in exactly one unit of work (one per batch element); a store write outside a unit fails at once with `OutsideUnitWriteException` instead of running as an implicit transaction; stores throw instead of returning `Result`/`RepositoryError`; a missing row is `null`, a lost optimistic lock throws `VersionConflictException` (`version_conflict`), and a store fault inside a unit poisons it so it rolls back. New dependency: HikariCP 7.1.0.
- **Changed: SQLite code moves to `infrastructure.sqlite` and storage ports become `*Store`.** Logger names of the moved classes now start with `infrastructure.sqlite.` (was `infrastructure.database`, `infrastructure.repository` and `infrastructure.service`), and the writeTx op labels in WARN logs, `OutsideUnitWriteException` messages and `outsideUnitWrites` keys use the `*Store` names (`NoteStore`, `DependencyStore`, `LeaseStore`, `ProjectConfigStore`, `PlanDocumentStore`). The work-item store is split into `ItemStore`, `HierarchyStore`, `ClaimStore` and `SearchIndex` (`WorkItemRepository` remains as a member-less composite). The server entry wiring (`CurrentMcpServer`, `ServerComposition`, `StartupOutcome`) lives in the `bootstrap` package, which no layer may import. Log filters and dashboards keyed on the old names need updating.
- **Changed: one UTC timestamp format, one bound clock (V18).** Every persisted timestamp (19 columns) is canonical UTC text, `yyyy-MM-dd HH:mm:ss.SSS`, formatted and parsed with an explicit UTC offset; the data-only migration `V18__Normalize_Timestamps.sql` rewrites older shapes in place (idempotent; values written as local wall-clock by a non-UTC JVM carry no offset and are read as UTC). The row mapper is total: a stored row that fails domain validation is returned with its violations in `WorkItem.diagnostics` instead of being dropped, and a timestamp that cannot be read returns the row with a diagnostic naming the column (the field is null; a non-null column falls back to the epoch). `GET /api/v1/resources/leases/history` applies `limit` in SQL, and a release at exactly the lease expiry records reason `expired`.
- **Changed: the default `CORS_ALLOWED_HEADERS` gains `X-Client-Host`** (now `Authorization,Content-Type,If-Match,X-Client-Host`), and the default `CORS_EXPOSE_HEADERS` gains `X-Req-Id` (now `ETag,Last-Event-ID,Retry-After,X-Req-Id`). A deployment that sets either variable explicitly keeps its own list and must add the header itself.
- **Changed: item search covers descriptions; `work_items` is recreated (V26).** The item FTS tables index `title`, `summary` and `description`, and the unused `prefix` index on them is dropped. The migration recreates `work_items` (every row, rowid and value preserved) and adds the nullable `lifecycle` and pin columns; `role` becomes nullable. Back up the database file before upgrading.
- **Changed: environment variables are read only through `AppConfig`.** The composition root builds the config base directory once from `AppConfig`; `bodyFromFile` paths and the global config file resolve against it (the working directory when `AGENT_CONFIG_DIR` is unset), so no service reads `AGENT_CONFIG_DIR` itself.

#### Identity

- API JWT rejections now log one INFO line (`API JWT rejected: reason=<code> verifier=api keySource=jwks_uri suppressed=<n>`) instead of only DEBUG, capped at 10 lines per reason code per 60 seconds; the line never carries the token or claim values. Observability only, no change to verdicts.
- Per-token WARN lines on the API JWT path are scrubbed and flood-limited: a defective `to_scope`, `to_kind` or `to_capabilities` claim logs `API JWT claim defect: claim=<claim> reason=<code> action=<rejected|ignored> suppressed=<n>`, and a pipeline fault logs `JWT verification fault: reason=internal suppressed=<n>`, each capped at 10 lines per key per 60 seconds. No such line carries the token, a claim value or token-derived exception text (DEBUG keeps the detail), and the unknown-`kid` refresh WARN no longer names the `kid` (it keeps the key-fetch error, which comes from the configured source). Observability only, no change to verdicts.
- **Scope is `root_id`, roots only.** A token's `scope.root_ids` is tested against the item's stamped `root_id` everywhere (tool scope checks, search, SSE); the ancestor-chain walk and the recursive search fallback are gone. Every `root_ids` anchor must be a depth-0 root: an existing non-root anchor fails startup naming the token id and the anchor, and a JWT carrying one is a `401`. Breaking for scoped REST tokens: creating a depth-0 root and moving an item to the top level are `forbidden` (`root`) unless the token holds `admin`, so a `tags_include`-only token loses root create and a `root_ids` token loses `clear: ["parentId"]`.
- The JWT and bearer-token identity code moved to `infrastructure.identity.{jwt,bearer}` (`ScopeClaimParser` sits at the `identity` root; `Principal` and `ApiCapability` live in `application.api` and `AccessScope` replaces `ApiScope`). Logger categories follow the classes: a log filter on `...interfaces.api.v1.auth.JwksApiVerifier` must name `...infrastructure.identity.jwt.JwksApiVerifier`. `CurrentMcpServer` no longer defaults `appConfig`; only the entry point reads the process environment.
- **Changed: one JWT verification pipeline.** The REST JWT verifier is a thin mapper over one `JwtVerifier` (algorithm allowlist, key selection, signature, `exp` required, `nbf`, lifetime cap, `iss`, `aud`), so a fix to one check lands in one place. Results, reasons and `failureKind` values are unchanged.
- **Changed: key refresh backs off after a failure.** A failed JWKS refresh no longer re-fetches on every call past the TTL: the stale key set is served and the next attempt waits 15 s, doubling per consecutive failure up to 300 s, reset by a success.
- **Changed: an unknown `kid` forces one key refresh.** A token naming a key the cached set lacks triggers one refresh (at most one per 30 s per provider) before it is rejected, so a rotated-in key verifies without waiting for the TTL.
- **Changed: key fetches are egress-guarded.** See Upgrading. The guard resolves the host before the fetch; DNS rebinding between that check and the connect is a known limit.
- **Changed: an unavailable key set answers `503 unavailable`, never a guess.** A JWT presented while the JWKS key set cannot be had (never fetched, or older than `max_stale_seconds`) is answered `503` with the `unavailable` error and `detail.retryAfterMs` (15000, the key-set refresh backoff) on `/api/v1/*`, on `/mcp`, `/mcp/knowledge`, `/mcp/director` and on the SSE `?token=` path, with one WARN naming the reason and no `WWW-Authenticate` header (the credential was not found invalid). A rejected token is still `401` with `Bearer error="invalid_token"`. A token verified against a stale key set (within `max_stale_seconds`) still authenticates, with proof `verified_stale`. The decision is one sealed `TrustDecision` (`Trusted`, `Unauthenticated`, `Unavailable`) from the one credential resolver, and `JwksApiVerifier.verifyWithExpiry` returns a sealed `ApiTokenVerdict`.
- **Changed: claim ownership is always enforced and the caller is the principal.** `AdvanceService.advance` loses `degradedModePolicy` and `enforceOwnership`; the claim holder id of `claim`, `heartbeat` and `release` is the principal's audited id (`api:<id>`, or `local`) and cannot be rejected by a policy. `ToolExecutionContext`, `TreeCompletionService` and `CompositionResult` lose the policy as well.
- **Changed: the proof vocabulary is `verified`, `verified_stale`, `issued`, `none` and `rejected`.** Every attribution object a wire emits is now `verification: {proofStatus, verifier?, reason?}` (the `status` key is gone): `notes_read` views and entries, the REST note resources and `items_read` `history`. New `events.proof_status` and `call_log.proof_status` rows are written in the same vocabulary; old rows are read through one mapping (`unchecked` with verifier `api-bearer` is `issued`, any other `unchecked` and `absent`, `unverified`, `unavailable` are `none`, `failed` is `rejected`) with no data step, so nothing wire-visible says `unchecked`, `absent` or `unavailable`. `rejected` is history only: the pipeline never produces it, and a rejected credential yields no principal. A note's stored `verification_status` keeps `verified` for a stale-verified principal.
- **Changed: `require_verified` independence counts only cryptographic proof.** A note whose proof is `issued` (a server-issued token), `none` or `rejected` is `unverified`; only `verified` and `verified_stale` satisfy it.
- **Changed: `GET /api/v1/info` reports the API credential verifier.** `verifier` (`none`, `bearer` or `jwks`) and, with `jwks`, `maxStale` (the `max_stale_seconds` of the key set, in seconds).
- **Changed (internal): the `application` sub-packages are acyclic.** `PageRequest`, `ListPage` and `CursorCodec` moved to `application.port`; `IdRef`, `ScopeGuard` and `eventRootOf` to `application.support`; `StatusLabelService` and `WorkItemSchemaService` to `application.config`; `UnscopedUnitOfWork` to `application.service`; `LIFECYCLE_FREE_ENDPOINT_REASON` to `Dependency`. The package-cycle baseline is empty. No wire or behaviour change.

### Removed

- **Removed: the 3.x tool layer and its adapter.** Nothing 3.x is served any more: the `application/tools` package, `McpToolAdapter`, the legacy error mappers (`LegacyMcpErrorMapper`, `LegacyRestErrorMapper`, `ErrorDto`), the hand-written `AdvanceFailure` serializers and the REST helpers only they used are deleted. Every 3.x error code string (`VALIDATION_ERROR`, `RESOURCE_NOT_FOUND`, `DATABASE_ERROR`, `INTERNAL_ERROR`, `config_unavailable`, `rejected_by_policy`, the REST `*_failed` and `db_error` codes) is replaced by the closed error catalog, and the MCP `notifications/message` log notifications are no longer sent.
- **Removed: `manage_items`, and `POST /api/v1/items`, `PATCH /api/v1/items/{id}` and `DELETE /api/v1/items/{id}`.** `manage_items` is gone from `tools/list`; the three routes answer a client error (`404` or `405`) and never write. Use `items_write`. The merge-patch request handling (RFC 7396) and its `Accept-Patch` negotiation go with `PATCH`, and the 3.x comma-separated `tags` and `traits` strings, the shared top-level `traits` and `parentId` defaults and the `requestId` keying of item writes have no `items_write` equivalent.
- **Removed: `query_items`, `query_notes` and the 3.x REST read routes `GET /items`, `GET /items/roots`, `GET /items/{id}/tree`, `GET /items/{id}/breadcrumbs`, `GET /items/{id}/schema`, `GET /search` and `GET /notes/search`, with the `include` parameter of `GET /items/{id}`.** Use `items_read` over MCP or `POST /api/v1/ops/items_read` (`api-rest.md` section 10 maps each route). `offset` paging, `includeAncestors` / `includeTimestamps`, comma-separated `tags`, `sortBy` / `sortOrder` and the 3.x sort aliases are gone (use `cursor`, `fields`, a `tags` array, `sort` / `order`); a schema-free item answers `{schemaFree: true}` instead of `RESOURCE_NOT_FOUND`. `GET /items` and `GET /items/roots` answer a client error; the other removed routes are `404`.
- **Removed: `get_context`, `get_next_status`, `get_next_item`, `get_blocked_items` and `GET /api/v1/items/{id}/gate`.** Use `items_read` `context` (one item: `data.gateStatus`; no `itemId`: the health view), `next` and `blocked`. The session-resume mode (`since`, `recentTransitions`) is replaced by `items_read` `history`, which serves the scope-filtered transition trail. The plugin SubagentStop phase guard still polls the removed gate route and fails open until the plugin is re-pointed.
- **Removed: `advance_item`, `claim_item`, `POST /api/v1/items/{id}/advance`, the `overrideResourceLeases` advance flag and `API_WARN_ON_CLAIMED_ADVANCE`.** Use the `advance` tool (`transition`, `claim`, `release`, `heartbeat`) over MCP or `POST /api/v1/ops/advance`. `DELETE /api/v1/resources/leases/{key}` is the recovery path for a stuck lease. The REST advance DTOs (`AdvanceRequestDto`, `AdvanceResponseDto`) and their OpenAPI schemas are gone, and `claim_item`'s mandatory `requestId` is replaced by the optional per-element `idempotencyKey`.
- **Removed: `manage_notes`, `manage_dependencies`, `query_dependencies` and the REST note write and dependency routes.** `PUT` and `DELETE /items/{id}/notes/{key}`, `GET /items/{id}/dependencies`, `GET /items/{id}/backlinks`, `POST /dependencies` and `DELETE /dependencies/{id}` are gone: use `notes_write`, `deps_read` and `deps_write`. `manage_notes` delete by note ids, delete of every note of an item and the `itemContext` guidance block have no replacement (read the next required note with `items_read` `context`); the 3.x `counts`, `includeItemInfo` and `limit`/`offset` parameters of the dependency tools and the REST codes `duplicate_dependency` have no replacement. The `is_blocked_by` input alias is not accepted.
- **Removed: `create_work_tree` and `complete_tree` as MCP tools.** Use `items_write` ops `create_tree` and `complete_tree` over MCP or `POST /api/v1/ops/items_write`. Neither 3.x tool had a REST route, and their `requestId` keying has no equivalent beyond the per-element `idempotencyKey`. `create_tree` takes the 4.0 grammar (`tags`/`traits` arrays, lowercase `priority`, dependency `type` `blocks`/`relates_to`, note `role` `queue`/`work`/`review`) and drops the 3.x `schemaMatch`/`expectedNotes` decorations (use `items_read` `select: schema`); `complete_tree` skips an already-terminal item (`reason: already_terminal`, no `error`) instead of reporting `invalid_transition`.
- **Removed: `manage_project_config`, `query_rules` and the per-root config, rule and config-discovery REST routes.** Use the `config` tool. `GET`, `PUT` and `DELETE /api/v1/roots/{rootId}/config`, `GET /roots/{rootId}/config/history`, `POST /roots/{rootId}/config/rollback`, `GET /roots/{rootId}/config/effective`, `GET /roots/{rootId}/rules[/{key}]`, `GET /config`, `GET /config/schemas[/{type}]`, `GET /config/traits`, `GET /config/types` and `GET /config/status-graph` answer `404`. The `cfg-<fingerprint>` and `cfg-v<version>` ETags and `If-Match` are gone with them (`expectedVersion` replaces `If-Match`); `GET /api/v1/config/schema` stays. A client of the old tools or routes, including the plugin's `config-sync` hook and every rule-fetching agent prompt, breaks against this server until it moves to `POST /api/v1/ops/config`.
- **Removed: `manage_plan_documents` and the REST `/roots/{rootId}/plans` routes.** Use `docs_read` and `docs_write`; a 3.x `rule/<key>` slug is `kind` `rule`, slug `<key>`. A plan's `status` is gone (an adopted plan reports `adoptedBy`), the REST code `adopted_conflict` is removed, and `bodyFromFile` is MCP-only. The plugin's `config-sync` rule push and the `plan-capture` hook still call the removed route and move to `docs_write` in the plugin work.
- **Removed: `GET /items/{id}/transitions`, `GET /transitions` and the `role_transitions` table.** Both routes answer `404`; read the trail with `items_read` `history` (the 3.x 24-hour default window and 1000-row scan cap are gone). The table and its `TransitionStore` writer are gone: an applied transition is recorded once, as an `item.transitioned` event. `RoleTransitionDto` leaves `current/docs/api/openapi.yaml`.
- **Removed: the `actor` parameter.** No tool accepts a top-level or element `actor` any more (it is `unknown_parameter`); the caller is always the transport principal, and `idempotencyKey` no longer needs one. `ActorRequest`, `ActorAware`, `ActorParseResult`, `ActorVerificationScope` and the `api-unauthenticated` verifier name are deleted.
- **Removed: the `schema_resolution` modes, tag-selected schemas, `IS_BLOCKED_BY` rows, the in-memory idempotency cache, the event-publishing decorator and `TransitionStore.deleteByItemId`.** See Added for their replacements.
- **Removed: the `actor_authentication` config path (the JWKS actor verifier) and the `GET /api/v1/info` `actorAuthenticationEnabled` field.** A leftover `actor_authentication:` block, a legacy `auditing:` block or a set `DEGRADED_MODE_POLICY` fails startup with an upgrade message. See Upgrading.
- **Removed: `DegradedModePolicy`, `actor_authentication.degraded_mode_policy` and `DEGRADED_MODE_POLICY`.** See Upgrading. The `ActorTrustPolicy` resolver, `AdvanceFailure.PolicyRejected`, the startup accept-cached WARN and the self-reported fallback go with them.

### Plugin

- **Changed: the whole test suite runs once per wave, and budget red-proofs run at the wave boundary.** Seats (implementer, test author, and any post-run fix or second-round seat) run only the pinned compile self-check plus a scoped run of their own test classes; `:current:test`, `:current:ktlintCheck` and `--rerun-tasks` are orchestrator-only, with `--rerun-tasks` reserved for the run after a dependency or Gradle upgrade. The lock helper is now checked in (`.claude/skills/implement/references/gradle-locked.ps1`), copied to the scratchpad at wave setup, and refuses the orchestrator-only tasks unless `TO_GRADLE_OWNER=orchestrator` is set; it also accepts `-TestFiles`, mapping test files to `--tests` classes, which the run profile's new `owned-tests` entry uses for the test author. The decision-point budget mutations no longer run per item before merge: `run-wave` `post-run.md` gains Step 7a (and `SKILL.md` row 8a), which batches every item's budget in one scratch worktree at the merged SHA after the wave's PRs merge, runs each mutation only against the test classes the item's `test-manifest` maps to it, allows one whole-suite run per survivor, records the results once on the wave parent's `red-proof-results`, and turns a real gap into a carry-in or a test-only follow-up rather than a reopen. Bug-fix revert proofs and the coupled-authorship red checks stay per item and pre-merge. The `test-author` rule section 5 remains the single statement (rules 10 and 11 added); `/implement` Step 4 carries the when-and-who table, Step 6 the post-merge step, and `review-quality` reads the recorded build state instead of re-running the suite. Evidence: on `release/4.0` every seat ran the forced suite (about 8,000 tests, 7+ minutes), so an item spent an hour or more of its 1-4 hours in Gradle; phase 1's 228 budget mutations found 8 gaps and phase 2's found 3, while the blind tests themselves found the real defects.
- **Changed: red-proofs follow a targeted budget instead of one proof per scenario.** A bug fix always gets the revert-the-fix red-first run; new guard, gate, transition, classification, boundary or security behaviour gets a capped budget of about 5 decision-point mutations per item in one batched round; test-only, refactor, migration and glue items are skipped; a real gap escalates to the item's other scenarios; a surviving mutation is triaged rather than failed; and non-independent authorship keeps a red check on every new-behaviour scenario. The policy lives in the served `test-author` rule (section 5) and is pointed to from the `test-author` and `implement` skills, the dispatch contract template and `run-wave` `post-run.md`. Machine contracts (`red-proof-shape`, `items[].redProof.shape`) keep their names and shapes; only their meaning is now "the item's budget". Evidence: 228 mutation runs in Phase 1 found 8 real gaps, all from concurrency or atomicity items and two mutation kinds (dropping one of several parallel enforcement paths, a guard matching only one spelling).
- **Changed: the implementer agent stops and reports a deviation from the plan instead of implementing it.** `agents/implementer.md` gains a Plan fidelity rule: queue-phase notes and orchestrator `fix-decisions-*` notes are binding; a conflict is implemented around, recorded under `## Deviations requested` with `file:line` evidence, and reported for the orchestrator to rule on. Any constraint cited to justify a departure must be verified in source.
- **Fixed: implement-wave recovers a seat interrupted before commit.** Writing seat prompts gain an INTERRUPTED-ATTEMPT CHECK: when the seat's own commit is absent but the worktree holds uncommitted changes the seat may own, the seat snapshots them to `refs/wip/<short>-<seat>` (temporary index, tree untouched), reviews them against the item's notes and continues from them, never discarding them wholesale. In a shared worktree only files the seat owns count. The envelope gains optional `interruptedAttempt` and `wipRef` fields (not a failure). The run-wave Resume matrix and `method-b.md` snapshot interrupted worktrees to `refs/wip/<short>` before relaunch, and `post-run.md` deletes the snapshots once the seats have committed.
- **Fixed: implement-wave reports the entry seat's advance result.** The envelope schema now requires `entry` for an entering stage and the seat prompts tell the seat to copy the `advance_item` result into it, so a correctly entered seat is no longer stopped as `no entry report`. In seat entry mode, when an entering stage returns `stopped` or `deferred` with a reason but no entry report, that status and reason are kept instead of being masked.
- **Fixed: retro-analysis and session-retrospective follow-ups.** `retro-analysis` accepts the `plan-to-execution` dimension, validates `maxAgents`, `staleDays` and `maxAdjudicate`, keeps a key-collision match's evidence and observation links (including in audit mode), and returns `stats.unmatchedShards` as a `[{label, kind, targetIds}]` array instead of a count (a result-v1 shape change). Match prompts now define strong vs weak and the deep-mode clusters. `/session-retrospective` documents `--deep` in its argument hint, matches unmatched shards inline, and gains an audit-sweep paragraph; `create-item` materializes an audit proposal under one user-confirmed category container and honours each row's own `type`.
- **Added: `task-orchestrator-mod` (opt-in, early access).** A separate plugin in the same marketplace of in-process function hooks: a live `/to-graph` work-graph pane, an in-flight band and status line, opt-in graph toasts and cross-session change marks, and in-process retrospective, phase-guard and call-shape hooks. See its [README](claude-plugins/task-orchestrator-mod/README.md).
- **Fixed: run-wave verify ownership checks.** Post-run verify now normalizes declared paths to repo-relative form (absolute and backslash declarations match), treats a declared directory or glob as owning the files beneath it, and resolves symbolic commit refs such as `HEAD` instead of silently skipping that stage's checks. An exact declaration by another item still wins and is reported as `owned by item <short>` (#414).
- **Added: per-seat `<ownedTests>` verify placeholder.** A run-profile `verify` entry can name `<ownedTests>` (with an optional `ownedTestsPattern`) so implementer and test-author seats run only the test files they own; an empty or invalid set renders a `SKIP` line. In a shared worktree every seat's VERIFY block now says failures in another seat's files are expected (#395).
- **Changed: run-wave provenance records run usage once.** The Workflow notification's token and duration totals go on the run's first item and every other item reads `see:<short>`; seats dispatched outside a run (re-review, fix cycle, amendment) are recorded on an `extra-seats=` second line, and `/session-retrospective` scores and counts them (#399).
- **Added: run-wave fix cycle after a review Fail.** `post-run.md` documents a frozen numbered amendment, the same seats resumed by name (or a fresh `<seat>-a<n>`), orchestrator red-proofs, and a scoped re-review (#401).
- **Changed: security reviews target the worktree diff.** Security-assessment guidance (example schemas, run-wave review prompts, the review-wave lane) applies the `/security-review` method to the item's owned-file diff in its worktree instead of invoking the built-in command, which diffs the session folder (#398).
- **Changed: semantic claims cite their source line.** Planner and reviewer guidance require a `file:line` citation for any nullability, redaction, ownership or honest-limit claim, and reviewers confirm each one (#396).
- **Changed: retro and phase-guard command hooks step aside for the mod.** `retro-trigger` and `retro-backstop` exit early on the mod's `to_mod_retro` flag; `phase-guard-record` and `phase-guard` exit early on `to_mod_active`. There is no effect without the mod.
- **Breaking: output styles removed.** The plugin no longer ships the `Workflow Orchestrator`, `Schema Orchestrator` or `Ralph Iteration` output styles, and the `outputStyles` key is gone from `plugin.json`. A `"outputStyle": "task-orchestrator:..."` setting now falls back to the default style, so remove it from your settings. If you copied the plugin's Zone 1 into a personal output style, trim it: the orchestration core now arrives from the plugin, so the personal style keeps only Zones 2 and 3. A personal style in `~/.claude/output-styles/` remains valid.
- **Added: `orchestration-context` SessionStart hook.** Delivers the orchestration core on every session start, including after `/compact`, with nothing to select. It stays inert in headless ralph iterations, when no config is located, and when `orchestration.mode` is `off`.
- **Added: `dispatch-model-guard` PreToolUse hook.** Denies an `Agent` dispatch that omits `model`, because the shipped agents use `model: inherit` and an omitted model silently runs the subagent on the caller's own model. The deny reason says how to retry. It stays inert in headless ralph iterations, when no config is located, and when `orchestration.mode` is `off`.
- **Added: `dispatch-hint` PostToolUse hook.** After `advance_item`, emits one line per transition into `work` or `review` naming the item's dispatch profile (`agent`, `model`, optional `effort`) and reminding the dispatcher to pass `model` explicitly. Main session only, with no server call. It stays inert in headless ralph iterations, when no config is located, and when `orchestration.mode` is `off`.
- **Added: `task-orchestrator:orchestrate` skill.** On-demand tier classification, delegation model table and phase-owner dispatch rules, invoked from the orchestration context.
- **Added: `orchestration.mode` config key** (`workflow` default, `schema`, `off`), read client-side by the plugin hooks. See `skills/manage-schemas/references/config-format.md`.
- **Changed: `/task-orchestrator:init` reports the orchestration mode.** Project-mode init closes with the effective `orchestration.mode` and how to change it. `init --user` asks which mode directories without a project config should get, because a personal root makes the orchestration hooks apply everywhere; choosing `schema` or `off` writes the block to the user-level config.
- **Changed: Tier 5 guide renamed** from `integration-guides/output-styles.md` to `orchestration-mode.md` and rewritten for hook delivery; docs, skills and the field guide no longer describe output styles as a plugin component.
- **Fixed: ralph iterations resolved to the Default style.** Ralph passed a style file stem that Claude Code resolved to Default (obs a6f6086c); iterations now append `skills/ralph/iteration-system-prompt.md` and pin the default style.
- **Fixed: tier fragment surfaced as a bogus style.** The tier-classification fragment under `output-styles/` appeared as a selectable style (obs 9275379c); it now lives in `_fragments/`.
- **`run-wave` actors audit no longer fails on optional notes.** The planner now marks each stage's
  optional notes (`optionalNotes`), and `actors` treats an absent optional note as neither missing nor
  a failure (one written under the wrong actor is still a `mismatched` lane violation). In seat-less
  schemas `delegation-metadata` is no longer assigned to the implicit implementer: it moves to the
  item's `orchestratorNotes`, the seat prompt says never to write it, and the orchestrator records it.
  Plans without `optionalNotes` audit exactly as before.
- **Fixed: yaml-lite section matching.** The hook-side YAML reader now matches only column-0 section headers and first-level keys, so a nested key with the same name as a top-level section is no longer read as that section.
- **Fixed: hook output shapes and ralph git calls.** `retro-trigger` reads `structuredContent` first when parsing MCP results, the `config-sync` FileChanged hook reports through a top-level `systemMessage`, and the ralph loop's worktree git calls pass arguments as an argv array instead of a shell string.

### Fixed

- **Fixed: `call_log.principal_kind` records the credential's principal kind.** The column held the audit claim's kind, always `external`, so `report` select `usage` `groupBy: principalKind` returned one group. It now holds `human`, `agent` or `service` on MCP and REST (the local principal records `agent`). Rows written earlier keep `external` and are not backfilled; note and event actor kinds are unchanged.
- **Fixed: items are created and reparented through one placement pipeline.** Creating and reparenting items runs the same placement validation (`ItemCommandService`) on every surface, and the reparent cycle check fails closed when an ancestor lookup errors.
- **Fixed: subtree delete, reparent and complete scale past the SQLite variable limit.** Id lists are chunked, and a subtree delete runs in bulk by traversal level; SSE delete events are emitted per traversal level.
- **Fixed: stale readiness marker cleared at startup.** The `READINESS_FILE` marker left by a crashed run is removed before DB init, so the Docker health check cannot report ready before startup succeeds.
- **Fixed: stdio transport shuts down on stdin EOF.** In `stdio` mode the server only exited on a signal, so a host that just closed stdin (`docker run -i` pipelines, CI harnesses) left a running process with an open database and a healthy readiness marker. The transport's close callback now completes the run and triggers the normal shutdown sequence (marker cleared, database closed).
- **Fixed: `docker build` runs out of memory compiling the server.** The Dockerfile builder stage did not copy `gradle.properties`, so the 2 GB Gradle and Kotlin daemon heaps never applied inside the image build, and `compileKotlin` ran out of memory on the default heap. The builder stage now copies it.
- **Fixed: `FLYWAY_REPAIR=true` leaves newer migration rows alone.** Repair now ignores future-version rows (`*:future`) as well as missing ones, so running it against a database migrated by a later 4.x no longer marks those rows deleted; the next normal start still fails closed naming the newer version instead of serving the database.

## [3.16.0] - 2026-10-02

### Plugin

- Bumped plugin version to **3.9.0** — the `init` skill and personal root, bundled process rules,
  the project-level `client.json`, and the `run-wave` front door with the `implement-wave`,
  `review-wave`, `audit` and `retro-analysis` workflows (new skill, workflows and agents: minor).
- **Project init and personal root.** New `/task-orchestrator:init` skill sets up a project root and config for the current directory, or with `--user` a personal root (anchor-only; reads stay unscoped) in the user-level `~/.taskorchestrator/config.yaml`. Session start now reports the setup state (not set up, config without a root, personal scope, project scope), shows the `Config:` file the skills should read and edit, and, when an orchestrator MCP server is registered, suggests init at most once per directory per day (`TASK_ORCHESTRATOR_SETUP_HINT=off` silences it). Config discovery also checks the main checkout of a linked worktree and the user-level file, with `TASK_ORCHESTRATOR_HOME` and `TASK_ORCHESTRATOR_CEILING` for isolation, and the REST URL resolves from `TASK_ORCHESTRATOR_API_URL`, then a project-level `client.json` beside the located config or, for a linked worktree, in the main checkout (written by project-mode init, honoured only as a bare loopback origin with no path, query or fragment), then the user-level `client.json` (written by `init --user`). The hooks ignore a `project.rootId` that is not 1 to 64 letters, digits or hyphens, or that starts with a hyphen. Config-sync pushes the plugin's bundled process rules by manifest policy, shares one time budget across its requests and reports unfinished work as deferred to the next session, and plan dispatch sends multi-item work to `run-wave`. Skills that scope reads by root (including quick-start, ralph and session-retrospective) scope only by a project root; a personal root anchors new items and reads stay unscoped.
- **run-wave Method B: envelope schema in seat prompts, blind test-author default (#393).** `run-planner.mjs prompt` appends a METHOD B RETURN block for plans marked `meta.method: "B"` (no StructuredOutput tool exists for a dispatched Agent; the envelope is the last JSON object of the final message; the exact envelope schema is inlined, and planner prompts get a line superseding the core StructuredOutput sentence), so Method B stages no longer cost a re-emit retry each. `next` defaults a `test-author` seat whose dispatch names no agent to `task-orchestrator:test-author` under Method B. Method A prompt bytes are unchanged (S6 parity); recorded Method B hand-backs are replayed as conformance fixtures.
- **Test-author red-proof runs only in a scratch copy (#387).** The served `test-author` rule, the `task-orchestrator:test-author` agent and the project `test-author` skill now state that a seat in a shared worktree never runs `git stash`, `git checkout --`, `git restore` or `git reset`; the revert that observes red runs in a detached scratch copy that is removed afterwards, and `test-manifest` records where and at which commit it ran. An in-place revert is not red evidence.
- **Seat-owned notes are never rewritten by the orchestrator (#384).** `run-wave` post-run and both output styles state that re-review after fixes goes to a fresh `task-orchestrator:reviewer` seat and that orchestrator confirmations go under their own `orchestrator-confirmation` key; the `enforce-actor-attribution` hook adds a best-effort, non-blocking warning when an orchestrator actor upserts a note the server stores under a subagent actor (fires only where note attribution is not redacted).
- **`implement-wave` workflow (B1).** New Claude Code Workflow script `workflows/implement-wave.js`
  that runs one work item's seats in order (planner, implementer, declarations extractor, blind test
  author) and several items concurrently from a run plan the front door builds: settle-once milestones
  for in-run dependency edges, per-file lock chains in a shared worktree, entry-result mapping
  (`dependency_blocked`/`resource_unavailable`/`config_unavailable` → deferred, never dropped), rerun-safe
  seats, rule text fetched by key via `query_rules` (never embedded), and a `scanDeclarations` step between
  the extractor and the author. Never call it bare — it returns `{started:false}`; the `run-wave` front door
  (B2) builds its `args`. Ships with two new agent definitions, `task-orchestrator:planner` and
  `task-orchestrator:test-author`, and a fake-agent test harness (`scripts/tests/workflow-harness.mjs`).
- **`audit` workflow (Phase C / C2).** New Claude Code Workflow script `workflows/audit.js`, generalized from the
  September architecture review: presets `quick|standard|full`, barrier-per-phase reviewers → gap critic → merge →
  adversarial verification (1–3 lenses by severity) → synthesis → triage. It never writes MCP items; its result carries a
  findings *proposal* that `/task-orchestrator:create-item` materializes after a human confirms (new section
  "From a workflow findings proposal"). Cost is projected up front and refused above `maxAgents` unless `allowLarge`.
- **`retro-analysis` workflow (Phase C / C3b).** Sharded trend matching and adjudication over prior retrospectives and
  observations, opt-in from `/session-retrospective --deep` (user-invoked only; the retrospective hooks stay the trigger).
- `session-retrospective`: Step 4.2/4.3 now read trend ids and titles from the list-mode search and fetch summaries with
  `get` (list mode never returned `summary`); the 24 h window uses `modifiedAfter`.
- Test harness extension `scripts/tests/workflow-harness-ext.mjs` (`loadCoreNamed`, `autoAgent`, `assertBarrier`,
  `freeRuntimeIds`, `ruleWindowHits`) for workflow scripts beyond implement-wave.
- **`review-wave` workflow (Phase C / C1).** New Claude Code Workflow script `workflows/review-wave.js` that runs an
  item's review-phase notes as independent lanes (this repo derives reviewer, security, api-compat and plugin-impact
  lanes plus an advisory simplify lane feeding the reviewer; the script itself is lane-agnostic), each on the
  owned-file diff with rules fetched by key, and aggregates a verdict in args order (pass / pass-with-observations /
  fail-blocking). Lanes arrive as validated `review-wave/args-v1` stages, hand-built by the orchestrator until the
  `run-wave` front door wires them (C1-productize); the script never derives lanes from config and refuses a reviewer
  whose actor matches an implementer or test author.
- **`run-wave` front door (Phase B / B2+B3).** New plugin skill `/task-orchestrator:run-wave` that plans a wave of ready
  items with the `run-planner` helper (`scripts/run-planner.mjs`: `probe|plan|explain|validate` over a pure planner
  that derives per-item seat stages from the resolved schema, classifies dependency edges as in-run or cross-run,
  defers on exclusive resources and file overlaps, and assembles the `implement-wave` args), launches the workflow
  (Method A) or dispatches the same run plan with the Agent tool (Method B: `next|prompt|stage-result|
  scan-declarations`), and on the result verifies commit ownership, audits seat actors, writes the structured
  `delegation-metadata` provenance line and advances items (`verify|actors|provenance|review-prompt`). Run plans and
  state persist as `run/<runId>` plan documents so a run survives turns and compaction.
- `/implement` now routes Delegated waves with two or more ready items, and every Parallel wave, through `run-wave`;
  the dispatch-contract template fetches its rule slots by key (new served rules `declarations-extractor` and
  `contract-change-sweep`); `WORKTREE.md` dispatch examples read the resolved seat profile; `.taskorchestrator/
  run-profile.json` carries this repo's verify commands and search scope; `workflowSizeGuideline: large` is set.
- `session-retrospective` reads the structured per-seat provenance line (`seats=<seat:model,...>`) alongside the
  legacy one-model-per-item form.
- Fixed `config-sync.mjs` pushing all `.taskorchestrator/rules/*.md` PUTs in parallel (`Promise.all`), which raced
  the server's SQLite writer and produced one success plus N-1 `db_error` failures per session; rule PUTs now go
  sequentially, one at a time. `run-planner`'s `validateSnapshot` now requires a top-level `probe` object (the
  verbatim `probe` subcommand output), matching `chooseEntry`'s and `plan`'s existing reliance on it; a snapshot
  missing `probe` previously planned silently pre-entered instead of failing validation. `references/snapshot.md`
  documents `probe` next to `profile`.
- Fixed four `implement-wave.js`/`run-exec-lib.mjs` bugs found in the B1d live
  acceptance runs (L1–L4): (1) `lockKeysFor` no longer disables locking outright when a shared-mode writer's
  derived file set comes back empty (no planner output, or the planner reported no files for that
  role) — it now takes a single `worktree:<path>` key instead of none. (2) `mapStageResult` now
  catches a `planner-v1` envelope that reports `proceed:true` with `mainFiles`/`docFiles`/`testFiles`
  all empty while the stage owns required notes, mapping it to `stopped`/`envelope-mismatch` instead
  of silently proceeding with nothing for downstream seats to act on; the planner's seat prompt also
  now says to emit `StructuredOutput` exactly once. (3) An implementer stage on an item with no
  `test-author-v1` seat now has the planner's `testFiles`/`existingTestEdits[].file` folded into both
  its lock keys and its "owned files" set in `run-exec-lib.mjs`'s `verify` — previously those tests
  were locked and owned by nobody, so `verify` reported them as unowned writes. (4) The
  `missingDeclaration`/`breachDisclosure` "nothing to disclose" sentinel is no longer a literal
  `=== 'none'` compare (so `"None."` or `"none - pre-fix tree extracted via git archive"` incorrectly
  failed the gate) — a new `isNoneSentinel` helper matches the trimmed value against `/^none\b/i`,
  used by both fields; the test-author output schema and seat prompt now state the accepted format.
  `run-exec-lib.mjs`'s `auditActors` gained an optional `opts.result` (and the `actors` CLI subcommand
  a `--result` flag) so a Step-2 actor audit no longer flags notes from stages that never ran (deferred
  or missing from the result) as `missing` — those items instead report `skipped: true`.
- **Subagents are told to leave nothing running.** The SubagentStart hook's
  Subagent Discipline gains item 4, "Leave nothing running" (no background command may outlive the
  return; filesystem searches bounded to the repo/worktree and known caches, never `/` or a drive root),
  and the `implementer` and `reviewer` agent definitions carry the same rule (#356).

### Added

- **Seats (A1): explicit `seats:` declarations on schemas and traits.** A schema or trait can now
  declare a `seats:` list — named orchestration roles within a phase, each with an optional
  `enters` (the seat that transitions the item into that phase), `after` (an ordering hint), and
  `reads_exclude` (a blindness hint, parsed and served only — enforcement is a later phase). Note
  entries gain `seat` (the owning seat) and `independent_of` (also parsed/served only). A trait's
  `dispatch.<phase>:` block can carry a `seats:` sub-key with per-seat overrides
  (`dispatch.<phase>.seats.<seat>: {agent?, model?, effort?}`, an explicit `agent: null` clearing
  the phase default for that seat only). Seats are pure orchestration signals — the server parses,
  validates, merges, and serves them, but enforces nothing new. See
  [`config-format.md`](claude-plugins/task-orchestrator/skills/manage-schemas/references/config-format.md)
  → "Seats (Trait & Schema Dimension)" for the full syntax, merge rules, and per-root layering.
  - Served as `seats` and `dispatchBySeat` on `get_context` (current phase only, flat per-seat
    profiles) and `query_items(operation="schema")` (all phases, per-phase-nested profiles), as
    `seat`/`independentOf` on note-schema entries, and as `missingBySeat` (required notes
    bucketed by owning seat, with a reserved `unowned` bucket for a note with no seat owner) on
    `get_context`'s `gateStatus`, `advance_item`'s gate-blocked failures, REST `GET
    /items/{id}/gate`, and REST `POST /items/{id}/advance`'s `422 gate_blocked` response. The
    `seat` field appears on every note-schema entry when the resolved schema is seat-aware
    (declares `seats:` on itself or a merged trait) — JSON `null` for an entry with no declared
    seat — and is omitted entirely for a seat-less schema; `independentOf` appears only on an
    entry that declares a non-empty list.
  - New REST route `GET /api/v1/items/{id}/schema` — the item's resolved schema view, identical to
    `query_items(operation="schema", itemId=...)`'s response body (both built by the same shared
    function). See [`api-rest.md`](current/docs/api-rest.md) → "GET /items/{id}/schema".
  - New `features` array (currently `["seats", "dispatchBySeat"]`) on `query_items(schema)`, `GET
    /api/v1/info`, and the `.well-known` service descriptor — advertised unconditionally so a
    client can tell an old server (which never emits these fields) apart from a new server whose
    current config simply declares no seats.
  - Two `enters: true` seats in the same phase, a duplicate seat name, a seat named the reserved
    `unowned`, or an `after` cycle now fail config load (global startup) or a per-root config push
    outright — nothing is stored. Malformed seat/dispatch-override entries and unknown keys warn
    and skip rather than failing the load.
  - **Byte-identity preserved.** A config with no `seats:` anywhere produces byte-identical
    MCP/REST responses to a pre-A1 server, with exactly one additive exception: the `features`
    field described above.
  - **Upgrade note.** A per-root config row stored *before* this upgrade that would now fail one of
    the new fatal seat checks (e.g. two `enters: true` seats in one phase) is **not** rejected
    retroactively — it is not re-validated just because the server upgraded. The server logs a
    `WARN` and falls back to the global config for that root until the row is re-pushed (at which
    point the new checks apply and the push is rejected if still invalid).

- **Independence attestation gate (A2): `independent_of` is now enforced, not just served.** A
  top-level `independence: {mode: off|warn|reject, require_verified: bool}` config block (default
  `warn`/`false`) turns a note-schema entry's `independent_of` (A1) into a gate check: at
  `start`/`complete` (and the terminal/start cascade gates), the server compares the actor who wrote
  a declaring note against the actor(s) who wrote the seat(s) it names, and reports `same_actor` /
  `missing_actor` (fail-closed on an actor-less note) / `unverified` (with `require_verified: true`)
  findings. `warn` mode reports without blocking; `reject` mode blocks on any non-waived finding. A
  note may waive its own `same_actor` findings with a first body line of exactly
  `independence: temporal-only`, when the note's `createdAt` is strictly after every same-identity
  conflicting note's `createdAt` — the waiver never covers `missing_actor`/`unverified`. Findings
  are actor-free by construction (`{key, seat?, constraint, conflictingSeat?, waived?}` — never an
  actor id, proof, or claim) and are per-root honorable: a per-root `independence` block replaces
  the global one wholesale. See
  [`config-format.md`](claude-plugins/task-orchestrator/skills/manage-schemas/references/config-format.md)
  → "Independence (A2)" for the full semantics, including an honest-limits section on what this
  gate does and does not catch.
  - Surfaced as `violations` on `get_context`'s `gateStatus`, `advance_item`'s success/failure
    results and `cascadeEvents`, `complete_tree`'s applied/gate-failed entries, REST `GET
    /items/{id}/gate`'s `GateStatusDto`, REST `POST /items/{id}/advance`'s `AdvanceResponseDto`/
    `CascadeEventDto`/`422 gate_blocked` `details.violations`, and the plugin's SubagentStop
    phase-guard hook (which blocks only when the gate's missing notes are empty and the block is a
    `reject`-mode, non-waived, own-seat finding — `warn`-mode findings never block). Omitted
    entirely (not `[]`/`null`) wherever the resolved schema declares no `independent_of` or mode is
    `off`; on every surface OTHER than `gateStatus`, also omitted (not sent as `[]`) whenever the
    check ran and simply found nothing — `gateStatus` alone reports an explicit `[]` for that case.
  - No tool description or `parameterSchema` change on `advance_item`/`get_context`/`complete_tree`.

- `schema_resolution: legacy | layered | isolated` — an opt-in, per-document top-level config key
  (AR-39) controlling how a root's per-root and global config layers combine for schema/tag
  lookup. Settable in the global config, in a per-root pushed document, or both; the effective mode
  per root is the per-root document's own key, else the global file's key (`isolated` in the
  *global* file is treated as `layered`, with a load warning), else `legacy`. Absent everywhere is
  `legacy` — no default flip, no behavior change for existing deployments beyond the tag-matching
  fix below. See [`config-format.md`](claude-plugins/task-orchestrator/skills/manage-schemas/references/config-format.md)
  → "Global vs Per-Project Config".
- `GET /api/v1/roots/{rootId}/config/effective` — a new, purely additive REST route returning every
  registered type's RESOLVED base schema against `rootId`'s layered (per-root-over-global) config
  in one response, plus resolved traits, the status graph, and the effective `schemaResolution`
  mode. Uses a new `"eff-<fingerprint>"` ETag prefix, distinct from `/config*`'s and
  `/roots/{rootId}/config`'s `"cfg-"` prefix.

- Added an opt-in `(iss, jti)` replay cache for actor-authentication JWTs
  (`actor_authentication.verifier.jti_replay_protection`, default `false`). A per-MCP-call memo
  keeps a proof that is legitimately re-verified multiple times within one call (idempotency-key
  lookups, multi-transition batches) from tripping the cache as a false replay. (#366)

- **One-time startup compaction after the V17 actor-proof scrub.** On the first Flyway-mode start
  after upgrading, the server now runs a one-time `VACUUM` + FTS5 shadow-table rebuild (with
  integrity checks) + WAL checkpoint, gated on `PRAGMA user_version` so it runs exactly once per
  database file. This reclaims free-page copies of pre-upgrade `actor_proof` values that V17's
  migration could not reach. Runs before the readiness marker is written; failures are WARN-logged
  and retried on the next boot, never fail startup. Opt out with `DB_COMPACT_ON_UPGRADE=false` to
  keep using the offline compaction runbook instead. (#365)

- **Rule text per project (A3): `query_rules` MCP tool + REST rules routes.** Git-tracked,
  client-neutral operating rules (blind-authorship protocol, commit discipline, forbidden test
  patterns, review scoping, and more) are now servable as text, stored per project root as
  `rule/<key>` plan documents via the existing `manage_plan_documents` tool -- no new table, no
  migration. A note-schema entry's `skill` pointer doubles as a rule key: the new `query_rules`
  tool reads a rule directly (`rootId`+`key`) or resolves it through an item's effective,
  trait-merged schema (`itemId`+`noteKey`); `query_items(operation="schema")` stays pointer-only
  and never inlines rule bodies. `rulesVersion` on every response is the backing plan document's
  `contentHash`.
  - New REST routes `GET /api/v1/roots/{rootId}/rules/{key}` and `GET
    /api/v1/roots/{rootId}/rules`, converging on the same `RuleService` as the MCP tool (no REST
    item/skill-pointer mode). See [`api-reference.md`](current/docs/api-reference.md#query_rules)
    and [`api-rest.md`](current/docs/api-rest.md) section 19a.
  - A `rule/`-prefixed plan-document body is capped tighter than the general 64 KiB plan-document
    limit, at **16384 bytes** (16 KiB, UTF-8), enforced at the same `PlanDocumentService.stash`
    validation point both `manage_plan_documents` and the REST plan PUT route already share (MCP
    `VALIDATION_ERROR` / REST `413 payload_too_large`).
  - `create_work_tree` now refuses a `docRef.slug` starting with `rule/` -- rule documents are
    read-only via `query_rules` and cannot be adopted into a work item.
  - This repository's own initial rule set ships git-tracked under `.taskorchestrator/rules/`.
    The plugin's `config-sync` hook pushes those files into the per-root store automatically at
    session start (plugin 3.8.0, #375). See
    [`config-format.md`](claude-plugins/task-orchestrator/skills/manage-schemas/references/config-format.md)
    → "Rule text".

### Changed

- `features` (on `query_items(operation="schema")`, `GET /api/v1/info`, and the `.well-known`
  descriptor) now also lists `"rules"`, so MCP and REST-only clients can detect per-root rule text
  (`query_rules`, `GET /api/v1/roots/{rootId}/rules[/{key}]`) with one check.
- `create_work_tree`'s `docRef` and `manage_plan_documents`' `body`/`bodyFromFile` parameter
  descriptions now state the `rule/` limits: a `rule/` docRef slug is rejected, and a `rule/<key>`
  body is capped at 16 KiB.
- **`features` gained `"independent_of"`** (A2): `query_items(operation="schema")`, REST `GET
  /items/{id}/schema`, `GET /api/v1/info`, and the `.well-known` service descriptor now advertise
  `["seats", "dispatchBySeat", "independent_of"]` — `independent_of` was parsed and served but not
  enforced in A1, so it was deliberately absent from `features` then; A2 (above) now enforces it, so
  it joins the advertised list.
- REST and the MCP tools now read per-root config through the SAME `EffectiveConfigResolver`
  instance (one per-root last-known-good cache, built once in `ServerComposition`) instead of each
  maintaining its own — REST previously built a separate `PerRootConfigService`/cache. This makes a
  transient per-root DB read failure less likely to surface as `503 config_unavailable`, since a
  cache warmed by one surface now serves the other.
- Each MCP tool invocation and REST config-resolving request now reads per-root config against one
  per-request/per-call config snapshot (`ConfigSession`) instead of re-reading it on every
  individual resolution step within that call. The snapshot also remembers a failed read: if a
  root's per-root config cannot be read cold (no last-known-good yet), every later lookup for that
  root within the SAME call (e.g. the rest of an `advance_item`/`complete_tree` batch) reports
  `config_unavailable` without re-reading; the next call reads fresh.
- The global `.taskorchestrator/config.yaml` file is now parsed exactly ONCE per process, into a
  single shared `GlobalConfigFile` — the schema service, the status-label service, and the
  actor-authentication service all read from this one parsed document instead of each
  independently re-reading and re-parsing the file.
- The legacy per-root and global-file YAML loaders (`YamlStatusLabelService`,
  `YamlActorAuthenticationConfigService`) now construct SnakeYAML's `Yaml` with an explicit
  `SafeConstructor`, matching the parser already used for `manage_project_config`/`PUT
  /roots/{rootId}/config` pushes. Defense in depth, not a vulnerability fix: SnakeYAML 2.x's
  default `TagInspector` already rejects arbitrary `!!`-tagged global tags, so this closes no
  exploitable gap in the shipped version — it removes reliance on that default holding across a
  future SnakeYAML upgrade.
- `YamlWorkItemSchemaService.getSchemaForType`/`getSchemaForTags` (the library-level schema-service
  API) are now EXACT — they no longer fold an unmatched lookup onto a `default` schema themselves.
  The `default` fallback moved to `LayeredConfig`, which now applies it per the root's effective
  `schema_resolution` mode instead of unconditionally. Direct callers of this service (outside the
  config-resolution path) that relied on the implicit `default` fold must call `getSchemaForType`/
  `getSchemaForTags` on `"default"` themselves.

- Actor-authentication and REST API JWTs are now capped at a maximum lifetime, default 24 hours
  (`actor_authentication.verifier.max_token_lifetime_seconds` / `API_JWKS_MAX_TOKEN_LIFETIME_SECONDS`).
  **Upgrade note:** a token whose `exp - iat` (or remaining `exp - now`) exceeds 24 hours is now
  REJECTED where it previously verified; raise the new setting before or immediately after
  upgrading if your issuer intentionally mints longer-lived tokens. (#366)

### Fixed

- `manage_items`: the top-level `type` and `properties` parameters were declared in the schema but
  never read on any operation, so a caller setting them got a silent no-op reported as success. Their
  schema descriptions now state they are ignored at the top level and must be set on each item instead
  (no behaviour change). A new CI guard (`ToolParameterReadCoverageTest`) fails when any registered
  tool declares a parameter that its source never reads, unless that parameter's own description
  explicitly qualifies it as ignored (#290).
- The LEGACY global tag-matching step could report the first tag in an item's tag list as the
  "matched" tag whenever the global config defined a `default` schema — even when that tag had no
  schema of its own — because the probe folded a `default`-schema hit into "this tag matched". The
  probe now requires an EXACT tag match before reporting a match; this fix applies in every
  `schema_resolution` mode, not just `legacy`. Narrow, gate-relevant behavior change: an untyped or
  unmatched-type item with two or more tags, whose first tag has no schema of its own but whose
  config defines a global `default`, now resolves the first tag that DOES have an exact schema
  (previously it resolved `default`).
- `GET /api/v1/roots/{rootId}/config/effective`'s root lookup now returns `500 db_error` for a
  repository failure other than not-found, instead of folding every lookup error into `404
  not_found`.

- Fixed a malformed JWT claims set (e.g. a non-numeric `exp`/`iat`) being misreported as
  `UNAVAILABLE`/`failureKind: network` under DID trust, or `REJECTED`/`failureKind: internal` in
  static-JWKS mode; both now report `REJECTED`/`failureKind: claims`. (#366)
- Fixed `SQLITE_BUSY_SNAPSHOT` on concurrent writers: every SQLite transaction now begins `IMMEDIATE`
  (writer lock acquired at `BEGIN`) instead of the driver default `DEFERRED`, so a transaction that reads
  then writes (e.g. the plan-document upsert behind `PUT /roots/{rootId}/plans/{slug}`) queues on
  `DATABASE_BUSY_TIMEOUT_MS` instead of failing immediately when another connection commits a write
  after its snapshot started. The JDBC URL is unchanged.

### Security

- Bumped Ktor 3.3.3 to 3.4.3 and the MCP Kotlin SDK 0.12.0 to 0.13.0 (the SDK is compiled against
  one Ktor minor, so the two move together). This closes CVE-2026-68762 (JetBrains Ktor before
  3.4.1, potential denial of service via WebSocket decompression; fixed upstream in 3.4.1): the
  server jar previously bundled `ktor-websockets` and `ktor-server-websockets` 3.3.3, pulled in
  transitively by the SDK, and now bundles 3.4.3. The server never installs the Ktor WebSockets
  plugin, so the vulnerable path is not believed to have been reachable. SDK 0.13.0 adds its own
  DNS-rebinding guard on `/mcp`, on by default and loopback-only; it is turned off in favour of the
  server's existing Host allowlist (`MCP_ALLOWED_HOSTS`), which stays the single Host authority for
  `/mcp`, `/api/v1` and `/.well-known`. No behaviour change for clients.

### Removed

- **BREAKING (REST): `?include=proof` and `API_REDACT_ACTOR_PROOF`**, deprecated in 3.15.0.
  `?include=proof` is now ignored like any unknown include value (no more `Warning: 299` header);
  `API_REDACT_ACTOR_PROOF` is no longer read (no startup WARN). `actor.proof` is removed from the
  `ActorClaimDto` schema (it was already never sent). Admins read proof evidence via
  `verification.proof`. (#362)

## [Plugin 3.8.0] - 2026-09-28

Plugin-only release — no server changes, no image rebuild. Server stays at 3.15.0 (the server-side
changes listed under Unreleased above ship with the next server release).

### Added

- `config-sync` now also syncs this workspace's git-tracked `.taskorchestrator/rules/*.md` files
  into the per-root rule store at session start: one `GET /api/v1/roots/{rootId}/rules`, then a
  `PUT /api/v1/roots/{rootId}/plans/rule%2F<key>` only for keys whose local hash (SHA-256 of the
  CRLF-normalized body) differs from — or is absent from — the served `rulesVersion`. Filenames
  that fail the rule-key grammar and bodies over 16 KiB are skipped client-side and named in the
  hook's one output line; rules present on the server but absent locally are never deleted. The
  step is fail-open (an absent `rules/` dir is a silent no-op; any API error degrades to a note)
  and the SessionStart entry's timeout is raised to 10 s. Against an authenticated server the
  config-sync token now also needs `read` in addition to `write-config`. Replaces the manual
  `manage_plan_documents` stash that the A3 rule-text feature (server, unreleased) otherwise needs.
- Added a hook-local `actor_attribution.required: true` config option, independent of
  `actor_authentication`, that makes `enforce-actor-attribution` deny actor-less `advance_item`/
  `manage_notes(upsert)` writes without also requiring full `actor_authentication` (JWKS identity
  verification) to be configured.
- SessionStart now warns when a dev checkout's `claude-plugins/task-orchestrator/.claude-plugin/plugin.json`
  version differs from the version of the plugin actually running the hook, pointing to
  `claude-plugins/CLAUDE.md` → "Plugin Discovery and Cache Refresh". Silent outside a dev checkout
  or when either version can't be read.

### Changed

- Workflow Orchestrator output style: for a seat-aware item, every seat's dispatch model comes from
  the resolved `dispatchBySeat`; a pinned model is changed through the project's traits, not
  overridden at dispatch time.
- SubagentStart now skips protocol injection entirely for `agent_type: "workflow-subagent"` —
  Claude workflow agents follow their own script-driven transition logic — and reworded the
  injected protocol to be seat-conditional: only an entry seat, or a single agent that owns the
  whole phase with no seat named, is told to call `advance_item(trigger="start")`; a non-entry or
  read-only seat is told not to call it at all.
- Phase-Guard Record now skips recording any transition whose `actor.parent` starts with the
  literal prefix `workflow:` (a Claude workflow-script seat), and records the role each item was
  entered in (`newRole`, or `previousRole` for an already-in-phase `gate_blocked`) alongside its
  itemId. Phase Guard (SubagentStop) uses that recorded role to block only while the item is
  still in the role this agent entered — once a later seat has advanced the item further, it is
  skipped rather than blocked on notes belonging to a phase it never owned. The recorded role is
  kept across the guard's own blocks.
- Skill enforcement now skips its length/placeholder heuristic for a note upserted via
  `bodyFromFile` — that file's content isn't visible to the hook, so a short literal `body` is no
  longer conflated with a substantive file-backed note.
- `enforce-actor-attribution` now also checks the singular-sugar `advance_item` form
  (`itemId` + `trigger` with a top-level `actor`); previously an actor-less singular call passed
  the hook even when enforcement was on.
- Reworded the implementer and reviewer agent definitions' seat rules to defer to a named seat
  assignment in the dispatch prompt, rather than assuming the agent owns every required note in
  its phase.
- `post-plan-workflow` (before each new root or feature item it materializes) and `create-item`
  (before each item it creates, or a work tree's root) now run one unscoped title search and
  surface close matches (role + short id) as an FYI line, leaving the decision to the user —
  nothing is auto-linked, skipped or cancelled. The search is unscoped so process-global items
  outside the project tree, such as agent-observations, are caught too.

### Plugin

- Bumped plugin version to **3.8.0** — rule-text sync in `config-sync`, the
  `actor_attribution.required` option, the version-freshness warning, workflow-safe phase guard and
  SubagentStart behavior, and seat-aware agent definitions.

## [3.15.0] - 2026-09-25

### Highlights

- Added dispatch profiles — traits can now declare which agent, model and effort should pick up
  each phase, surfaced on `advance_item`, `get_context` and `query_items(schema)`
- Added a completion guard — a new `GET /items/{id}/gate` route and a SubagentStop hook that sends
  a phase-owning subagent back while its required notes are still missing
- Added DNS-rebinding protection — `/mcp`, `/api/v1` and `/.well-known` reject non-loopback `Host`
  headers unless allowed via the new `MCP_ALLOWED_HOSTS`
- Changed actor proofs to be stored as evidence (SHA-256 + verified claims) instead of raw JWTs; the
  V17 migration scrubs existing rows, and MCP responses no longer echo `actor.proof`
- Changed configuration handling to fail closed — an unparseable global config, invalid
  `actor_authentication` values, malformed token scopes, and unreadable per-root config no longer
  silently degrade to weaker enforcement
- Changed logging to JSON on stderr with per-call correlation fields (tool, session, request id),
  with opt-in file logging via `LOG_FILE`
- Changed `claim_item`'s empty-selector outcome from `no_match` to `queue_empty` / `none_eligible`,
  and every `advance_item` failure now carries an `errorCode`
- Fixed inverted cycle detection and ordering for `IS_BLOCKED_BY` dependencies
- Fixed several REST scope escapes, write races, missing SSE events, and lease leaks on failed
  transitions and deletes
- Release images are now published only after tests, an image smoke test, and a Trivy
  CRITICAL-vulnerability gate all pass

Full per-item detail follows.

### Upgrade notes

- **Non-loopback clients need `MCP_ALLOWED_HOSTS`.** Requests whose `Host` header is not
  `localhost`, `127.0.0.1` or `[::1]` now get `403`. LAN, reverse-proxy and Docker-network
  deployments must list their hostnames in `MCP_ALLOWED_HOSTS`. (#327)
- **A broken global config now stops startup.** YAML syntax errors, a non-mapping root, unknown
  `degraded_mode_policy` / `verifier.type` values, a `jwks` verifier with no key source, or a
  wrong-typed verifier field previously fell back to schema-free / noop-verifier behaviour; they
  now fail startup naming the config path. `status_labels` alone still falls back with a WARN.
  (#331)
- **Malformed token scopes are rejected.** A bearer token with a malformed `scope` block, or an
  unrecognised token-entry key (e.g. `scopes:`), now fails startup; a JWKS token with a malformed
  `to_scope` claim is rejected with `401 invalid_token`. Both previously granted unrestricted
  access. (#332)
- **Actor proofs need `exp`, and `actor_authentication` JWKS sources need `https`.** A JWKS actor
  proof without an `exp` claim is now `REJECTED`. `jwks_uri` / `oidc_discovery` must be `https`;
  set the new `allow_insecure_url: true` to permit `http` for a literal loopback host only. (#330,
  #334)
- **DID trust binds `sub` to `iss`.** Under `did_allowlist` / `did_pattern` trust, a token's `sub`
  must equal its issuer DID; tokens asserting a different `sub` no longer verify. (#329)
- **The V17 migration scrubs stored actor proofs.** Raw proofs are nulled and replaced by
  `actor_proof_sha256` / `actor_proof_claims`. Free pages and backups taken before the upgrade may
  still hold live tokens until they expire — rotate actor-signing keys and, optionally, run an
  offline `VACUUM` plus FTS5 rebuild (see `fleet-deployment.md`). (#335)
- **`claim_item` selector outcome renamed.** `no_match` is replaced by `queue_empty` (nothing
  matches) and `none_eligible` (matches exist but are claimed or blocked — back off and retry).
  Consumers branching on `no_match` must update. (#340)
- **Logs are JSON on stderr.** Log scrapers expecting the old plain-text pattern must switch to
  JSON parsing; the Docker image no longer creates `/app/logs`. Set `LOG_FILE` for file output.
  (#350)
- **`lifecycle: auto-reopen` is gone.** It never reopened anything; configs using it still load
  as `auto` with a load warning. (#342)
- **The MCP server no longer advertises `prompts` / `resources` capabilities.** Nothing was ever
  registered under them. (#350)

### Added

- **Dispatch profiles as a fifth trait dimension.** A trait can declare
  `dispatch.<queue|work|review>: {agent?, model?, effort?}`; resolution walks per-item traits
  before `defaultTraits`, layers per-root over global config, and surfaces the resolved profile on
  `advance_item`, `get_context`, `query_items(operation="schema")` and `GET /config/traits`. The
  plugin ships `implementer` and `reviewer` agent definitions that consume it. (#325)
- **`GET /items/{id}/gate` REST route.** Returns an item's canonical gate status (`canAdvance`,
  missing notes) with the same trait merging and per-root layering as `get_context`, plus ETag
  support. (#324)
- **SubagentStop phase guard (plugin).** When a phase-owning subagent ends its turn while the item
  still has missing required notes, the hook sends it back with a capped continuation naming what
  is still open. It respects seats: an implementer is held only to `work`, a reviewer only to
  `review`, and test-author notes (`test-manifest`) are never demanded of an implementer. (#324,
  #353)
- **Ralph resumes iterations that end without a `RALPH_OUTCOME` marker.** Instead of counting an
  error, the loop resumes the same session in place up to `--max-continuations` times while budget
  remains. (#324)
- **`MCP_ALLOWED_HOSTS` environment variable** for the Host-header allowlist, documented in the
  `configure-server` skill with LAN, reverse-proxy and compose examples. (#327, #353)
- **`LOG_FILE` environment variable** for opt-in file logging, and MDC correlation fields on every
  MCP tool call (`transport`, `tool`, `sessionId`, `requestId`, `actorId`) and REST request
  (`requestId`, `httpMethod`, `httpPath`); self-reported values are length-capped. (#350, #355)
- **`allow_insecure_url`** key for `actor_authentication.verifier`. (#334)
- **Structured `error` field on cascade events** (`AdvanceCascadeEvent` / `CascadeEventDto`),
  populated when a cascade's apply fails. (#341)
- **Headless execution-mode signal (plugin).** Ralph iterations set
  `TASK_ORCHESTRATOR_MODE=headless-iteration`, and retrospective and phase-guard hooks stay silent
  under it; the SubagentStart hook injects the agent-owned-phase protocol only into implementer
  and reviewer agents. (#346)
- **SessionStart registration self-check (plugin).** Warns when an MCP registration key would not
  match the plugin's hook matchers. (#346)
- **Release gating.** Docker publishing now runs the test suite, a smoke test that boots the image
  and completes an MCP stdio handshake (including a stdout-purity check), and a Trivy
  CRITICAL/fixable scan before any image is pushed. `server.json` now mounts a data volume so
  registry installs persist their database. (#348, #350)

### Changed

- **Bumped plugin version to 3.7.0.** Adds the SubagentStop phase guard, the `implementer` /
  `reviewer` agent definitions, the headless execution-mode signal and the SessionStart
  registration self-check; hook matchers now accept any `task-orchestrator` registration key.
  Because the plugin cache is version-keyed, these hook changes reach sessions only after the
  plugin refreshes.
- **JVM pinned to UTC.** The Docker image sets `-Duser.timezone=UTC`, and non-Docker launches
  override a non-UTC default with a WARN, keeping claim-freshness math consistent with SQLite
  `datetime('now')`. (#348)
- **`FLYWAY_REPAIR=true` exits cleanly** with status `0` after repair, without binding a transport
  or writing the readiness marker, and warns when ignored under `USE_FLYWAY=false`. (#348)
- **The stdio service in `docker-compose.yml` sits behind `profiles: [stdio]`**, so
  `docker compose --profile http up` no longer starts it alongside the HTTP service. (#348)
- **Every `advance_item` failure carries an `errorCode`.** Hooks and Ralph branch on the code
  (e.g. `gate_blocked`) instead of inferring state from its absence; Ralph backs off on
  `none_eligible` instead of exiting. (#340)
- **MCP validation errors from a tool's execute phase** now return the same structured
  `VALIDATION_ERROR` envelope as parameter validation, instead of an "Internal error". (#349)
- **Plugin hook matchers accept any registration key containing `task-orchestrator`**, not only
  the literal `mcp-task-orchestrator`, so hooks fire for HTTP and custom registrations. (#346)
- **Config fingerprints ignore CRLF and BOM.** The server and the `config-sync` hook hash
  normalised text, so Windows and Linux checkouts of the same config no longer defeat the
  fast-forward guard. (#346)
- **Response metadata reports the real server version** instead of a hard-coded `0.1.0`. (#346)
- **`implementer` and `reviewer` agent definitions state which notes each seat owns.** (#353)

### Deprecated

- **`?include=proof` and `API_REDACT_ACTOR_PROOF`.** Both are now no-ops: the query parameter adds
  a `Warning: 299` header, and the env var logs a startup WARN. Admins read proof evidence through
  `verification.proof`. Both will be removed in a later release. (#335)

### Removed

- **`AUTO_REOPEN` lifecycle mode** (see Upgrade notes). (#342)
- **Unused `prompts` / `resources` MCP capabilities and the dead `McpLoggingService`.** (#350)

### Fixed

- **`IS_BLOCKED_BY` dependencies were treated backwards.** Cycle detection, `complete_tree`
  ordering, `create_work_tree`'s cycle check and the REST pre-check assumed every edge was stored
  as (blocker, blocked); all now orient edges by type. (#326)
- **Child placement could be stamped from a stale parent.** Every create/reparent path now reads
  the parent's depth and root inside the same transaction as the write. (#339)
- **Project-config push could race a concurrent writer.** The fingerprint compare-and-set now runs
  inside the upsert transaction with bounded retry, and REST `If-Match` fails closed on a
  fingerprint-read error. (#338)
- **A per-root config read error fell back to the global config**, skipping per-project gates and
  leases. It now serves the last-known-good config or returns a transient `config_unavailable`
  error. (#333)
- **Failed transitions and deletes could hold resource leases until TTL.** Leases acquired in the
  same call are released when the apply or a cascade fails, and both delete paths release an
  item's leases in the delete transaction, rolling back together if the delete fails. (#341, #342,
  #343)
- **REST parity gaps.** `DELETE /items/{id}` on a parent returns a structured `409 has_children`
  (or deletes recursively with `recursive=true`) instead of `500`; a duplicate dependency returns
  `409` instead of `500`; `create_work_tree` rejects an invalid priority instead of coercing it to
  `MEDIUM`. (#343)
- **`query_items` sorting.** `sortBy` values `title`, `complexity` and `modifiedAt` silently sorted
  by `createdAt`, and `priority` sorted alphabetically; `sortBy`/`sortOrder` are now validated.
  The global overview's `includeChildren` also keeps terminal children that still have open
  descendants. (#337)
- **Missing SSE events.** Bulk dependency/note/item deletes, claims and releases (including
  auto-released claims), and the whole `create_work_tree` flow now publish events; deletes made
  while no subscriber is connected are buffered for `Last-Event-ID` replay. (#345, #354)
- **stdout pollution on the stdio transport.** A transitive logging library printed a startup
  banner to stdout, corrupting the JSON-RPC stream; it is now suppressed. (#350)
- **Log timestamps dropped fractional seconds on whole-second instants.** (#355)
- **Bearer scheme parsing** is now case-insensitive with a single shared parser for REST and SSE.
  (#334)

### Security

- **DNS-rebinding protection** via the Host-header allowlist, enforced before CORS,
  authentication and routing. (#327)
- **Raw actor proofs are no longer exposed or stored.** MCP tool responses stopped echoing
  `actor.proof` (a replayable credential); non-admin REST callers never receive it; and proofs
  are persisted only as a hash plus verified claims. (#330, #335)
- **DID-trust impersonation closed.** `sub` is bound to `iss` under DID trust, and `did:web`
  identifiers are validated before any trust check or fetch, blocking percent-encoded separators
  that could redirect resolution to an attacker-controlled host. (#329)
- **REST scope escapes closed.** Root creation and move-to-root are scope-checked, and
  `GET /items/{id}/children` filters by tag scope before paging instead of leaking counts. (#328)
- **Fail-closed configuration and scopes** — see Upgrade notes. (#331, #332, #333)
- **`https` required for `actor_authentication` JWKS sources**, including OIDC-discovered URIs.
  (#334)

### Internal

- Decomposed the highest-complexity methods (`claim_item`, `create_work_tree`, item write paths,
  advance) behind characterization tests; no behaviour change. (#347)
- Added a Konsist layering test with a two-way-ratcheted baseline of known violations; guard tests
  now derive their tool list from the production registration. (#351)
- Corrected architecture and behaviour drift in the docs, `CLAUDE.md`, `CONTRIBUTING.md` and
  `README.md`. (#352)

## [3.14.0] - 2026-09-16

### Highlights

- Added paging (`limit`/`offset`) to `query_dependencies` edge listings and a 1000-node cap on
  graph traversal — large dependency graphs no longer blow past response limits
- Added `schemaWarnings` to `manage_project_config` and `PUT /roots/{rootId}/config` responses —
  invalid note roles and other parse problems surface instead of being dropped
- Added a Docker `HEALTHCHECK` backed by a readiness marker (`READINESS_FILE`) — startup failures
  now exit non-zero and orchestrators can detect a half-started server
- Added an SSE `sync.lost` control event on unreplayable `Last-Event-ID` — reconnecting clients
  know when a full resync is required
- Changed every boolean env var to one parser (`true`/`1`/`yes`, `false`/`0`/`no`) —
  `USE_FLYWAY=1` now selects Flyway; `API_ENABLED`/`API_ALLOW_UNAUTHENTICATED` fail startup on a
  bad value
- Changed `API_JWKS_URL` to require `https` (loopback `http` only with
  `API_JWKS_ALLOW_INSECURE_URL=true`) and JWKS-mode tokens to require `exp`
- Changed start cascades to respect the parent's queue-note gate, reported via
  `gateBlocked`/`missingNotes`
- Fixed root- and tag-scoped REST reads, SSE fan-out, dependency and backlink listings that leaked
  out-of-scope items — fail-closed everywhere now
- Fixed `complete_tree` bypassing the advance pipeline (claims, dependency validation, audit rows,
  cascades)
- Fixed FTS pagination returning overlapping pages, unbounded REST request bodies (now `413`), and
  `500`-on-conflict for `PATCH /items/{id}` (now `409`)
- Fixed recursive traversals hanging on cyclic rows, and shutdown cleanup racing registrations
- Bumped plugin version to 3.6.1 (hook and skill text fixes; `config-sync` prints schema warnings)

Full per-item detail follows.

### Changed

- **Bumped plugin version to 3.6.1.** The session-start hook and `quick-start` skill drop the
  stale depth-3 claim, `config-sync` prints `schemaWarnings` returned by the config push, and the
  `workflow-orchestrator` output style gains the dispatch-contract paragraph for Parallel-tier
  waves. No new skill, hook, or contract change.
- **`/implement`'s shared-worktree commit and review guidance now mandates path-scoped commits.**
  `git commit --only -- <owned paths>` plus a `git show --stat HEAD` verification replaces a bare
  `git commit`, and reviews scope by owned-file diff instead of SHA range. (#301)
- **Agent compile self-checks exit early on foreign-file errors.** A self-check failing entirely
  in files outside an agent's owned list is recorded under `session-tracking` Friction and
  committed as-is instead of retried three times; the `gradle-locked.ps1` invocation is pinned as a
  comma-separated array literal to avoid the nested-PowerShell string-flattening bug. (#302)
- **`test-author` skill requires fixtures to satisfy domain `validate()` invariants.** A new
  "Fixture invariants" rule derives dependent fields from each other instead of defaulting them
  independently, and the `test-manifest` note format gains an invariants-respected line. (#303)
- **`/implement` adds a named planning seat for the Parallel tier.** One `opus` agent per stream
  verifies the diagnosis against source and fills `test-plan` before any implementer is
  dispatched, returning a structured `diagnosis-corrections` / `cross-stream-file-overlaps` /
  `missing-api-or-seam` / `test-plan-status` block instead of free-form prose. (#304)
- **Test plans label scenarios `EXISTING-SURFACE` or `NEW-SURFACE` with a red-proof plan.** Each
  `NEW-SURFACE` scenario states a narrowest-possible-revert recipe (or a named substitute
  verification), and the planning-seat return template gains a `red-proof-shape` field. (#306)
- **Adopted process rules for Parallel-tier waves now ship through the file dispatch prompts
  actually reference, not through skill prose.** A controlled two-wave comparison showed
  prose-only adoption let the same failure classes re-fire, while plan-file delivery drove
  shared-index sweeps, dropped commits, and wasted gradle runs to zero. (#307)
- **Test-author blindness is now a structural, tool-enforced boundary.** The dispatch contract's
  Test author protocol supplies every public declaration inline, bars any tool from reading
  `src/main`, requires an explicit `keys=` filter on every `query_notes` call, and directs the
  author to stop and ask rather than derive a missing declaration. (#310)
- **New dispatch-contract reference template.** `.claude/skills/implement/references/dispatch-contract-template.md`
  gives every Parallel-tier wave 12 fixed slots (header, conflict rule, items, planning-seat
  return, commit discipline, compile self-check, file ownership, test-author protocol,
  contract-change sweep, docs, notes, review scoping) to copy into the run's plan file, plus an
  "Adoption reach" subsection mapping each prior proposal to the slot that now carries it.
- **Contract-tightening changes get a post-wave sweep step.** After a parameter becomes required,
  a `validate()` invariant is added, or a new accessor lands on a strict-mocked interface, the
  orchestrator greps every call site and fixture and repairs them by construction — never by
  relaxing the contract — declaring each repair in the contract's commit map. (`82034e9a`,
  `31a1abeb`)
- **`container` and the new `project` type carry a `permanent` lifecycle in this repo's dogfood
  config.** A container is a filing cabinet, not a work item, so cascades can no longer terminalize
  the Bugs container or the project anchor root over dozens of queued children (both had sat in
  terminal at the start of the September bug wave). Adopts proposal `7b03dad6`. (#300)
- **Plugin session-start hook and `quick-start` skill no longer claim a maximum tree depth of 3.**
  Hierarchy depth has been unbounded since the V7 cycle-guard migration; both surfaces now say
  trees nest to any depth. Because the plugin cache is version-keyed, the corrected hook text
  reaches sessions only after this release's plugin version bump. (#317)
- **`/prepare-release` regained its `server.json` step and learned to fold `[Unreleased]`.** The
  "bump `server.json` version + immutable OCI identifier" paragraph from #298 had been added to a
  flat duplicate of the skill file that #321 later deleted, leaving the surviving skill unable to
  satisfy the release workflow's own `server.json` guard. Step 8a now carries the paragraph, the
  staging lists include `server.json`, and Step 8c folds an existing `## [Unreleased]` section into
  the new version header instead of inserting a second section above it.
- **Bumped `sqlite-jdbc` from 3.53.2.0 to 3.53.4.0** (bundles SQLite 3.53.4). Routine patch
  refresh ahead of the release; no advisory is open against either version.

### Fixed

- **REST tag-scope enforcement had gaps across read routes, and a re-parent could create a cycle.**
  A shared tag-scope predicate now guards every read surface a `tags_include`-scoped principal can
  reach — items, roots, breadcrumbs, search, notes/search, transitions, and `?include=children` —
  and `PATCH /items/{id}` re-parent enforces scope on the new parent while rejecting self or
  descendant re-parents with `400` instead of entering an infinite cascade loop. (`ffa12a2f`,
  `544ae4b9`)
- **`complete_tree` bypassed the advance pipeline.** Every transition it performs now runs through
  the same path as `advance_item` — claim ownership, dependency validation, cascade and unblock
  reporting, actor-attributed audit rows, and per-root status labels — instead of calling the
  transition handler directly. (`3e455253`)
- **Startup failures exited `0` and Docker had no health check.** Startup now reports a
  `StartupOutcome` and exits non-zero on any failure (including a transport-start exception on
  either the stdio or http branch, which previously still reported `Started`); a readiness marker
  file (`READINESS_FILE`) is touched only after DB init, schema update, and transport bind all
  succeed, cleared on shutdown, and backs a new Docker `HEALTHCHECK`. (`56ac1690`, `56593660`)
- **Four incompatible env-var boolean parsers.** One `EnvBoolean` parser now handles every boolean
  environment variable: `true`/`1`/`yes` and `false`/`0`/`no` (case-insensitive) are all recognized
  — `USE_FLYWAY=1` now selects Flyway — and an unrecognized value either falls back to the default
  with a warning or, for `API_ENABLED`/`API_ALLOW_UNAUTHENTICATED`, fails startup. (`64f7b265`)
- **Per-root config push silently dropped schema parse warnings.** Parse warnings (including an
  invalid note `role`) now surface as `schemaWarnings` on both the `manage_project_config` and
  `PUT /roots/{rootId}/config` responses, and the `config-sync` hook prints them. (`40d755cc`)
- **SSE `?token=` auth was unreachable, and unauthenticated mode returned `401` for every SSE
  subscription.** Both paths now behave as documented. (`3a6c0e5a`)
- **An optimistic-lock conflict on `PATCH /items/{id}` surfaced as `500`.** It now maps to
  `409 version_conflict`. (`20ccc9fb`)
- **The MCP adapter coerced the strings `"true"`/`"false"` to booleans for every parameter.**
  Coercion now applies only to boolean-typed parameters, so a string-typed field keeps its literal
  value. (`462931bb`)
- **Recursive item delete was not atomic.** A cascade delete now runs in one transaction per root
  id, so a mid-cascade failure leaves no half-deleted subtree. (`c75085d3`)
- **A child `start` cascaded a queued parent into WORK past its unfilled required queue notes.**
  Start cascades now respect the parent's note gate: the cascade is suppressed and reported with
  `gateBlocked` / `missingNotes`, exactly like terminal cascades, before any resource lease is
  acquired. Reopen cascades keep their documented bypass. (`473e4f49`)
- **Dependency and backlink reads leaked out-of-scope items.** `GET /items/{id}/backlinks`
  disclosed the `fromTitle` of items outside the caller's scope, and the same leak existed under
  root scope and on `GET /items/{id}/dependencies`; both are now filtered fail-closed for `rootIds`
  and `tags_include` principals alike. (`72911c9f`)
- **`GET /api/v1/events` ignored `tags_include`.** SSE events are now filtered per event, fail-closed,
  for tag-scoped principals on both the live stream and `Last-Event-ID` replay; a tag-scoped
  subscription the server cannot evaluate is refused with `403 insufficient_scope`. (`ad2c23ea`)
- **The re-parent cycle guard was depth-bounded and failed open.** It now tests full ancestor-chain
  membership, and a lookup error returns `500 db_error` before any write instead of allowing the
  re-parent. (`1a5ccf06`)
- **Recursive traversals could hang on cyclic rows.** Descendant and ancestor traversals are now
  cycle-guarded and bounded at depth 1000 on both SQLite (recursive CTE) and H2 (BFS with a visited
  set): cascade deletes and re-parent depth recomputes fail loud at the boundary, search-scope
  traversals bound-and-continue, and a new detailed ancestor-chain result reports whether a chain
  was truncated by a cycle or a missing ancestor. (`71bc3d09`)
- **A malformed `requestId` silently disabled idempotency.** `manage_items`, `manage_notes`,
  `manage_dependencies`, `advance_item`, `complete_tree`, and `create_work_tree` now reject a
  non-canonical `requestId` (blank, number, null, object, array, or non-36-char form) with a
  validation error naming the field, matching what `claim_item` already did. (`71dea46e`)
- **A shutdown-cleanup registration racing the drain threw `ConcurrentModificationException`**,
  skipping every remaining cleanup while shutdown still reported clean. Registration is now
  thread-safe, late registrations run immediately, and forward order is preserved. (`eeba1b12`)
- **SSE reconnects with an unreplayable `Last-Event-ID` got no signal.** A `sync.lost` control
  event now leads the stream with `reason` = `buffer_evicted` or `unknown_event_id` (alongside the
  existing `queue_overflow`), carrying id `oldestRetained - 1` so reconnecting at the sentinel
  yields a full replay; control events bypass the `types=` filter, and `API_SSE_BUFFER_SIZE=0` now
  means retain nothing instead of crashing. (`a3ebd108`)

- **MCP Registry record followed the mutable `:latest` image tag.** `server.json` pinned
  `ghcr.io/jpicklyk/task-orchestrator:latest` under an immutable registry version, so the published
  3.2.0 record resolved to whichever image later owned `latest` — and the file itself had not been
  bumped since 3.2.0 despite `/prepare-release` instructing it. Now pins the per-release tag
  (`:3.13.1`), `docker-publish.yml` fails the release if `server.json` disagrees with
  `version.properties`, and every tagged release publishes its own registry record via
  `mcp-publisher` (GitHub OIDC). The `/prepare-release` skill spells out the identifier bump. (#297)
- **`/prepare-release` release-notes extraction silently produced an empty body.** The awk step built
  a *dynamic* regex from the version string (`$0 ~ "^## \\[" ver "\\]"`); the bracket escaping is
  interpreted twice in a string-built regex and degrades to a character class on gawk, so the header
  never matched and the extraction yielded zero lines. Because `gh release edit --notes-file` accepts
  an empty file without complaint, the curated notes were replaced with nothing. Now matches the
  header literally via `index($0, hdr) == 1` and fails loudly on an empty extraction instead of
  publishing it. Observed live during the v3.13.1 release.
- **`API_JWKS_URL` accepted plaintext `http`, exposing JWKS key material to network interception.**
  The loader now requires `https`; plaintext `http` is accepted only for a loopback host
  (`localhost`, `127.x.x.x`, `::1`) with the new opt-in `API_JWKS_ALLOW_INSECURE_URL=true`. Any
  other scheme, an `http` URL on a non-loopback host, or an unparseable
  `API_JWKS_ALLOW_INSECURE_URL` value fails startup. (`231dd7f3`)
- **`page`/`pageSize` query parameters on REST list endpoints could overflow the internal `Int`
  offset.** `page`/`pageSize` are now validated and rejected with `400 validation_error` when
  non-integer, `page < 1`, `page > 100000`, or `pageSize < 1` — previously out-of-range values were
  silently clamped. `GET /transitions`'s underlying scan is now bounded at 1000 rows regardless of
  the requested page. (`c471607b`)
- **A claim survived an item reaching TERMINAL, so `reopen` could resurrect a stale claim.**
  `claimedBy`/`claimedAt`/`claimExpiresAt`/`originalClaimedAt` are now cleared whenever a transition
  reaches (or leaves) TERMINAL, across `advance_item`, `complete_tree`, the REST advance route, and
  cascades — a reopened item always starts unclaimed. (`3785f37a`)
- **`query_items`/`query_notes` full-text search pagination could return overlapping or duplicate
  results across pages.** The FTS candidate window is now fixed-size and offset-independent, ties
  are broken deterministically by id, and the fused result list is capped before the page slice is
  taken — `totalHits` is now identical on every page of the same query and `truncated` can fire on
  page 1. (`0ba7c92d`)
- **`query_items`'s `limit` parameter description was wrong for FTS search and scoped-overview
  modes.** The `parameterSchema` description now states the correct per-mode defaults and caps
  (20/FTS, 50/list and global-or-anchored overview, no cap on overview, ignored on scoped overview).
  (`97aa5855`)
- **`query_notes` validated a `scope.role` value that was never used to filter results.** The dead
  validation (and its undeclared schema field) has been removed — note search has always been
  role-less by role, and now the code and docs agree. (`9ad250e3`)
- **SSE events with an unresolved root set were broadcast to every subscriber, including root-scoped
  ones.** `ApiEventBus.publish` now carries a `rootsResolved` flag; an unresolved result (ancestor
  cache miss, resolution failure) fails closed, reaching only unrestricted subscribers, on both the
  live fan-out and `Last-Event-ID` replay. `GET /api/v1/events` also now rejects `?root=` values
  entirely outside a root-scoped token's `scope.rootIds` with `403 insufficient_scope`, and a
  `?root=` yielding no valid UUID with `400 validation_error`. (`ffce70f6`)
- **JWTs with no `exp` claim were accepted indefinitely, and the SSE expiry watchdog silently
  skipped sessions without one.** JWKS-mode tokens now require `exp`; a token with no `exp` is
  rejected with `401 invalid_token` (no max-lifetime opt-in). The `auth.expired` SSE watchdog now
  runs for every JWKS-authenticated session as a direct consequence — only unauthenticated sessions
  and bearer tokens with no `expires_at` remain watchdog-free. (`708063fa`)
- **Domain events were published inside the same DB transaction as the write, so a subscriber could
  observe an event for a change that later rolled back.** Events now publish after the enclosing
  transaction commits, and are dropped entirely if it rolls back instead; ordering on the success
  path (including `Last-Event-ID` replay) is unchanged. (`0e9d5675`)
- **`query_items`'s global and anchored overview modes accepted `limit < 1`.** A `limit` of `0`
  silently returned an empty page, and a negative `limit` crashed with an uncaught
  `IllegalArgumentException` on the anchored path. Both arms now reject `limit < 1` with the same
  validation error as the `search` arm; scoped overview (`itemId` set) is unaffected and still
  ignores `limit`. (`1a04106a`)
- **Root-scoped WorkItem queries ignored the `root_id` column and expanded every subtree id into a
  bound-variable list**, hitting SQLite's ~32,766-parameter ceiling on large subtrees.
  `findByRole`/`findForNextItem`/`findClaimable`/`countByClaimStatus`/`findInScope`/`countInScope`/`countInScopeByRole`
  now filter `root_id = ?` directly when every requested scope id is a stamped depth-0 root
  (binding `O(|rootIds|)` params instead of one per row); below-root or unstamped scopes still take
  the original recursive-CTE path. No public signature or migration change. (`09205394`)
- **`dependency.added`/`dependency.removed` SSE events were withheld from root-scoped subscribers
  whenever the ancestor-root cache was cold.** Eight of `DependencyRepository`'s ten methods are
  now `suspend` (joining the caller's coroutine transaction), and the event decorator resolves
  roots via a live database lookup instead of the cache-only path, so a cold-cache dependency write
  now correctly reaches root-scoped subscribers as long as one is connected at write time.
  `createSuspend` is removed (collapsed into `create`); `findByFromItemId`/`findByToItemId` remain
  non-suspend. (`33e96efd`)
- **`query_dependencies`'s `get` edge listing had no paging, issued two `getById` calls per edge for
  item-info enrichment, and its BFS graph traversal was unbounded.** `get` now accepts optional
  `limit`/`offset` on the edge listing (rejecting `limit < 1` / `offset < 0`), enrichment is fetched
  in one batched lookup instead of per-edge calls, and BFS traversal caps at
  `MAX_DEPENDENCY_GRAPH_NODES` (1000) nodes, setting `graph.truncated` when the cap is hit.
  Responses that omit both `limit` and `offset` are byte-identical to before. (`6e2d8fc2`)
- **The REST idempotency cache held one process-wide write lock across the entire
  compute-and-store operation, serializing unrelated requests, and read the request body inside
  that lock.** `IdempotencyCache` now locks only the store read-check-write and per-key in-flight
  computations — concurrent same-key callers coalesce onto one execution and different keys never
  block each other — and `POST /items`, `PATCH /items/{id}`, and `PUT /items/{id}/notes/{key}` read
  the body before entering the idempotency path. `POST /items` and `PUT .../notes/{key}` also now
  explicitly reject a non-JSON `Content-Type` with `415 unsupported_media_type` (an absent header
  is still accepted). Replay semantics are unchanged: same key + different body still replays the
  first response verbatim. (`c7751104`)
- **The Exposed-generated `work_items` table (used by Direct-mode/dev databases) was missing the
  `role`/`previous_role`/`priority` `CHECK` constraints and the `claim_expires_at` index that the
  Flyway migration (V7) defines**, so a Direct-mode database created today could accept an invalid
  role/priority value the Flyway-migrated schema would reject. `WorkItemsTable` now declares the
  same three CHECK constraints and index; no migration file changed. (`97f8632f`)
- **Every REST write route buffered the entire request body into heap before any size check
  ran.** `POST /items`, `PATCH /items/{id}`, `POST /items/{id}/advance`, `PUT
  /items/{id}/notes/{key}`, and `POST /dependencies` had no size limit at all; `PUT
  /roots/{rootId}/config` and `PUT /roots/{rootId}/plans/{slug}` checked their existing limits only
  after the full body had already been read. A shared `receiveBounded()` helper now rejects an
  over-limit `Content-Length` before touching the body, and caps the actual channel read at
  `limit + 1` bytes to catch a chunked or understated `Content-Length` — no oversized body is ever
  buffered in full. All seven routes now share one `413 payload_too_large` shape; the two
  pre-existing numeric limits (128 KiB, 64 KiB) are unchanged, and the five previously-uncapped
  routes share a new 1 MiB limit. (`e941c2c7`)

### Documentation

- **Rewrote the README for first-time readers.** Leads with what the server does and the shortest
  path to a working setup, drops volatile tool counts in favor of the API reference, and removes
  the broken `workspaceFolder` mount examples. (#315)

## [3.13.1] - 2026-08-04

### Changed

- **Adopted three retrospective improvement proposals** (002fa6c6, d7662676, 4aa8d058): `/implement`
  Step 2 gains the dedicated-worktree fallback for an unavailable/dirty main checkout; CLAUDE.md's
  plugin-refresh instruction is replaced with the verified version-keyed-cache procedure (purge +
  lazy extraction + verification); `/implement` Step 1 and the `workflow-orchestrator` output style
  now apply the `delegated` trait at Delegated/Parallel classification so `delegation-metadata`
  notes are schema-visible.

### Added

- **Mid-session config re-sync.** The plugin now pushes a project's `.taskorchestrator/config.yaml`
  to the server's per-root config store as soon as the file changes, not just at session start — the
  `session-start.mjs` SessionStart hook now returns `hookSpecificOutput.watchPaths` for the
  discovered config file, and a new `FileChanged` hook entry re-runs `config-sync.mjs` when it's
  edited mid-session. Requires Claude Code ≥ 2.1.220.
- **New `needs-test-author` trait separates test authoring from implementation.** Adds a
  `test-author` skill and a three-note gate (`test-plan` at queue, `test-manifest` at work,
  `test-independence-audit` at review) so scenarios and oracles are frozen before implementation
  exists rather than derived from it. `/implement` gains a dedicated test-author wave between the
  implementation wave and orchestrator verification; `review-quality` gains an independence
  verification check. Applied by default to `bug-fix`; opt-in for `feature-task` via the
  `/implement` Step 1 trigger rule. Direct tier uses a temporal-only degraded mode (single actor,
  ordering-only separation) instead of a second dispatch.

### Changed

- **Plugin: `/review-proposals` dispositions now write back to the source trend item.** Rejecting a
  proposal appends a do-not-re-graduate line to the trend that graduated it; accepting one whose
  change is applied/landed retires the trend (gate-free `cancel`); the maintainer tracked-only path
  leaves the trend active until a later retrospective verifies the change. Closes the loop the
  trend-memory MCP migration made possible — previously the skill could not touch file-based trends.
- **Plugin: retrospective trend memory moved from a per-project memory file into MCP work items.**
  `/session-retrospective` previously read and rewrote a whole-file `memory/retrospectives.md`
  (~55k tokens at steady state, exceeding the 25k read cap on a single call) on every run; trend
  patterns now live as items under a process-global `Retrospective Trends` container, with one
  evidence note per recurrence. Active-trend reads are now a targeted `query_items` list-mode call
  (title + summary only) instead of a whole-file load — roughly an 80% reduction in token cost for a
  typical retrospective's trend read. Retirement is gate-free via `advance_item(trigger="cancel")`,
  with no `start`/`complete` ever used on trend items, so the lifecycle stays safe under any user's
  schema config. A one-time automatic migration ships in the skill: it runs at each project's next
  `/session-retrospective` invocation, migrating any existing `memory/retrospectives.md` entries into
  the new container and rewriting the file to a pointer stub.

- **Plugin: retrospective dispatch directives are now durable, not immediate.** The `retro-trigger`
  hook's `mode: dispatch` directive previously said "dispatch now", so orchestrators launched (and
  later surfaced) the background retrospective mid-session while implementation subagents were still
  running — no Claude Code hook event signals "all background tasks finished", and the model is the
  only party that knows. The directive and the `workflow-orchestrator` output style now instruct
  dispatch at the next run boundary: hold while background tasks/subagents are in flight, merge any
  directives that accumulate while holding into one dispatch covering the union of their roots.
  Nudges gain a matching "background tasks still running" skip condition. The known trade-off — a
  held directive is lost if the session is killed or compacts first (recover with a manual
  `/session-retrospective`) — is documented in `config-format.md` §Retrospective.
- Bumped plugin version to 3.6.0 (minor): adds a `FileChanged` hook for mid-session config re-sync,
  the pattern-driven schema advisor in `manage-schemas` with 12 workflow profiles, and the
  retrospective trend-memory migration to MCP items.

### Fixed

- Lease-interval history close comparisons now normalize timestamp shapes with `datetime()`: Exposed
  timestamp columns store fractional seconds while `datetime('now')` is second-precision, so a hold
  lapsing inside the current second could compare as unexpired in SQLite's lexicographic TEXT
  comparison — skipping the expiry clamp and recording `released` instead of `expired`. Sub-second
  window, audit view only (beta field report).
- **Plugin: `skill-enforcement` hook no longer nags in a loop.** The hook's `additionalContext`
  previously told the model to "abort this call, invoke the skill, then retry" — the model obeyed,
  retried, and re-triggered the same warning forever, since the hook kept no state and its 200-char
  substantive floor was unreachable for concise-by-design notes bound to a small `maxLength`.
  Now: advisory-once semantics (a per-session/item/key marker suppresses a repeat warning for the
  same note), a `maxLength`-aware substantive floor (scales down when a key's configured
  `maxLength` is small), and honest wording that no longer claims the call is blocked and adds an
  "Unknown skill" escape hatch for invalid skill pointers. Also documented the skill-pointer
  exact-name rule (qualified vs. bare names, built-in name collisions like `review`) in
  `config-format.md`, and added a `manage-schemas` `validate` check that flags `skill:` values
  colliding with built-in skill names (`review`, `plan`, `run`, `init`).
- **`advance_item` gate-blocked results now name the blocked transition.** The gate-block error JSON
  gains structured `previousRole`/`targetRole` fields alongside `missingNotes`, so a rejected
  transition identifies which phase's note set it failed instead of leaving the caller to infer it
  from the trigger. The tool description now also states that `start`'s gate is scoped to the item's
  CURRENT phase — successive `start` calls gate different note sets as the role advances, which is
  deterministic and not timing-dependent — and that a transition can cascade a parent to terminal
  when the parent's downstream gates are already satisfied by prefilled notes. MCP surface only: the
  REST `422 gate_blocked` payload already carried `targetRole` and does not yet carry `previousRole`.
- **`manage_items(update)` now honors the top-level `traits` parameter.** It was previously accepted
  and silently ignored — the call returned `{updated:1, failed:0}` having merged nothing, with no
  error — because the update branch never read the parameter, while `create` always had. Precedence
  now matches `create`: a per-item `traits` field overrides the shared top-level default, and traits
  absent at both levels leave the item's existing traits untouched. Merge semantics are replace, not
  union. The per-item form (`items: [{itemId, traits: "..."}]`) was never affected and needs no
  migration.
  **Behavior change:** `traits: ""` on update now **clears** an item's traits, where it was
  previously a silent no-op. Because trait-derived note requirements merge into an item's resolved
  schema, clearing traits also drops their gates — a caller that passes an empty `traits` string on
  update (e.g. a template that always sets the field) will now strip both. Omit the field entirely
  to leave traits unchanged.

## [3.13.0] - 2026-07-25

> **Resource/credential leasing ships in this release as a beta feature.** The interfaces below are
> stable enough for evaluation and feedback, but the capability is deliberately v1-scoped —
> multi-holder admission (`maxHolders > 1`), budget counting, and phase-scoped leases are designed-for
> but deferred, and details may evolve based on real-world usage. Deployments that declare no
> `resources:` see zero behavior change.

### Added

- **Resource leasing (beta).** Work items can now declare exclusive or advisory access to shared resources
  (a staging slot, a test database, a deploy credential, a fleet-wide deployment mutex) via a new
  `resources:` dimension on trait definitions, enforced as a gate inside `advance_item` at WORK
  entry (`start` and `resume`) — no new MCP tool, no explicit acquire/release verb. `exclusive`
  resources take a TTL-bounded lease (default 3600s, max 86400s); contention rejects with a
  transient `resource_unavailable` error (MCP) or `409` + `Retry-After` (REST), disclosing the
  contended keys only, never the current holder. `advisory` resources are audit-only. Lease
  acquisition retries up to 5 times (40ms delay) on `SQLITE_BUSY`/`SQLITE_LOCKED` contention before
  falling through to a transient DB-error response, so a losing writer in a genuine cross-connection
  race gets a clean re-evaluation instead of a spurious failure.
- **`credentialRefs` audit field (beta).** `advance_item` transitions accept an optional `credentialRefs`
  array of opaque credential/resource labels (never secret values), persisted to
  `role_transitions.consumed_credentials` and surfaced read-only via `RoleTransitionDto.consumedCredentials`.
  Once an item declares `resources:`, the field becomes a closed set validated against the
  declared/registered keys, and declared keys are auto-recorded on work entry.
- **Resource-lease read/recovery surfaces.** `get_context(itemId=...)` item mode gains a
  `resourceLeases` block (mirroring `claimDetail`). New REST routes `GET /api/v1/resources/leases`
  (list active leases; holder actor identity ADMIN-only) and
  `DELETE /api/v1/resources/leases/{key}` (ADMIN-only force-release — the operator recovery path for
  a crashed holder, without waiting out the TTL or disabling enforcement server-wide).
- **Enforcement matrix and kill switch.** Both MCP and REST enforce the resource-lease gate by
  default; REST additionally accepts an ADMIN-only `overrideResourceLeases` request flag (403 for
  non-admin callers who set it, WARN-logged when used). New `RESOURCE_LEASES_ENFORCED` env var
  (default `true`) is a deployment-wide kill switch, read per advance call rather than once at
  startup. `CORS_EXPOSE_HEADERS` now defaults to include `Retry-After`.
- **Lease-interval history (beta, audit log).** New append-only `resource_lease_history` table records one
  row per lease hold interval — answering "who held resource R at time T" after a lease has already
  been released, stolen, or force-released. Every acquire/refresh/release/steal/force-release write
  is mirrored into history in the same transaction as the live-row write; `ResourceLeaseRepository`
  gains `findHoldersAt` / `findRecentIntervals`, and `forceReleaseByKey` gains an optional `actorId`
  parameter recorded on the closed interval. New REST route
  `GET /api/v1/resources/leases/history?key=&at=&limit=` (holder/closer actor identity ADMIN-only,
  `at` optional ISO-8601 instant, 400 on an unparseable value, `limit` default 100 max 500). No
  pruning/retention in v1 — append-only, cardinality tracks lease events.

### Changed

- Every exit from the work phase — primary transitions, cascades, and `complete_tree` — now releases
  the item's resource leases automatically; TTL expiry remains the crash backstop.
- Bumped plugin version to 3.5.2 — skills and hooks distinguish transient lease contention from
  note-gate blocks (`schema-workflow`, `dependency-manager`, `status-progression`, the dispatched-agent
  context, both orchestrator output styles), `/manage-schemas` recognizes and preserves the
  `resources:` config section, and `/configure-server` documents the `RESOURCE_LEASES_ENFORCED`
  kill switch.
- Documented the **one item = one concurrent actor** modelling assumption (from beta field
  feedback): item-keyed exclusivity arbitrates between items, not between actors sharing one item —
  cut items at actor granularity; `claim_item` is the opt-in enforcement. Stated in the
  workflow-guide guarantees section and fleet capacity planning.

### Fixed

- Lease-interval history could report two simultaneous holders for one key (beta field report): a
  re-take of a previously-held key after an intervening expired holder left that holder's interval
  open, and a late release then stamped it closed at "now". Stale holder intervals now close on
  every acquire path, and history closes are clamped to the interval's own expiry with a truthful
  `expired` reason. Live-lease exclusivity was never affected — audit-view accuracy only.

---

## [Plugin 3.5.1] - 2026-07-23

Plugin-only release — no server changes, no image rebuild. Server stays at 3.12.0.

### Fixed

- **`/work-summary` now computes each workstream's "next up" correctly on project-scoped
  workspaces.** The dashboard previously pulled the highest-priority queue child from an overview
  *children array that the anchored (project-scoped) overview never returns* — so scoped boards
  silently lost their per-workstream next-up recommendation. It now sources queue children from a
  single `role="queue"` list call, the canonical queue source.

### Changed

- **Queued headline count excludes container/anchor shelves.** A freshly-created container or
  project anchor sitting in `queue` role until its first advance no longer inflates the `N queued`
  figure.
- **Dropped the redundant `includeChildren` fetch** from the work-summary overview call — a no-op
  on the anchored path and redundant on the global path, now that the queue list supplies queue
  children.

### Plugin

- Bumped plugin version to **3.5.1** — work-summary queue-children sourcing and headline-count
  fixes.

## [Plugin 3.5.0] - 2026-07-23

Plugin-only release — no server changes, no image rebuild. Server stays at 3.12.0.

### Changed

- **Retrospective dispatch is now gated by run substance.** In `dispatch` mode, the plugin
  auto-launches a background retrospective only when the number of items reaching terminal since
  the last retrospective meets `dispatchThreshold` (default 3); below the threshold it emits a
  nudge instead of spawning an agent. Stops a single closed housekeeping item from triggering a
  full retrospective run.
- **Stop-hook backstop downgraded to nudge-only.** The end-of-turn backstop no longer hard-
  dispatches a retrospective and no longer falls back to whole-project scope when it has no
  specific root — eliminating the most expensive accidental firing.

### Added

- **New `retrospective` config keys:** `dispatchThreshold` (items reaching terminal since the last
  retrospective required to auto-dispatch, default 3) and `cooldownMinutes` (default 30, replacing
  the previously hardcoded value). Absent or invalid values fall back to defaults.

### Fixed

- **Config parser no longer mis-reads a comment as end-of-section.** A column-0 `#` comment inside
  a `retrospective:`, `project:`, or `actor_authentication:` block previously ended the block
  early, silently breaking `rootId` discovery and actor-authentication detection across six hooks.
  Consolidated onto a shared section parser and covered by the plugin's first hook test suite (71
  cases).
- **Retrospective substance no longer leaks across runs.** `retro-ack` now resets the terminal
  counter, so items closed during a retrospective can't accumulate and trigger a spurious dispatch
  on the next run.

### Plugin

- Bumped plugin version to **3.5.0** — substance-gated retrospective dispatch, nudge-only Stop
  backstop, and the six-hook section-exit parser fix.

## [3.12.0] - 2026-07-20

### Added

- **REST per-root config write endpoint.** `GET`/`PUT`/`DELETE /api/v1/roots/{rootId}/config`,
  gated by a new `WRITE_CONFIG` capability, backed by a shared `ProjectConfigPushService` so the
  MCP `manage_project_config` tool and the REST path converge on identical validation and DB state
  (#234).
- **`config-sync` SessionStart hook.** Opt-in, fail-open plugin hook that fingerprints the
  workspace's `.taskorchestrator/config.yaml` at session start and, if it differs from the
  server's stored per-root config, pushes it via the new REST endpoint — so a shared HTTP server
  picks up per-project schemas/traits without a restart. No-ops silently unless
  `TASK_ORCHESTRATOR_API_URL` (and, for bearer mode, `TASK_ORCHESTRATOR_API_TOKEN`) is set in the
  client environment (#235).
- **Opt-in unauthenticated REST mode.** `API_AUTH_MODE=none` (plus the required confirm flag
  `API_ALLOW_UNAUTHENTICATED=true`) attaches a synthetic `ADMIN`/unrestricted principal to every
  `/api/v1/*` request, skipping bearer/JWKS auth entirely. Intended for a loopback-bound local
  server only — see `current/docs/api-rest.md` §1 and the loopback-footgun guidance in the new
  `configure-server` skill below (#237).
- **New plugin skill `/configure-server`.** End-user decision flow for how the server runs and is
  reached — transport (HTTP vs STDIO), REST API mode, port publishing, config mount, and
  config-sync — plus a companion `references/runtime-config.md` rendering catalog shared with
  `/deploy_to_docker`. Establishes a new **recommended default for new installs**: localhost +
  HTTP + REST enabled + unauthenticated (loopback-bound), which enables config-sync out of the box
  for a single developer working across multiple projects. **Image/server defaults are unchanged**
  — still STDIO, REST off, `MCP_HTTP_HOST=0.0.0.0` — the new posture is delivered entirely by the
  skill's render, the reframed README/quick-start recipes, and an additive `http-rest`
  `docker-compose.yml` profile (the existing `http` profile stays REST-off).
- **`/deploy_to_docker` reuse-on-redeploy.** Every redeploy now detects the existing container's
  settings via `docker inspect` before removing it and defaults to reusing them, via a
  Reuse/Reconfigure chooser; Reconfigure adds a REST-mode chooser (Off / Unauthenticated-local /
  Bearer+token-file) and a config-mount chooser (this-project fallback / none for multi-project).
  Resolved settings persist to `~/.taskorchestrator/deploy.env` so they survive a full container
  removal (#238).
- **`advance_item` singular-form sugar.** `advance_item` now accepts a top-level `itemId` + `trigger`
  (plus optional `summary`/`actor`) as shorthand for a one-element `transitions` array, wrapped
  server-side. The batch `transitions` array is unchanged and takes precedence when both are
  supplied; the missing-parameter error now names both accepted shapes (#255).
- **Plan-document ingestion.** New `manage_plan_documents` MCP tool and REST `PUT`/`GET
  /api/v1/roots/{rootId}/plans/{slug}` (plus `GET .../plans`) — stash a plan once and materialize
  work items from it instead of duplicating the plan text into every item. Both ingestion paths
  converge on a shared `PlanDocumentService` with identical content hashing; the pending→adopted
  lifecycle is one-way and enforced at the repository layer (#243).
- **Plugin: native deterministic retrospective trigger.** Plugin hooks now fire the session
  retrospective deterministically when work reaches terminal, replacing the earlier best-effort
  nudge (#248).
- **Plugin: `schema-orchestrator` output style.** A lean orchestration style focused on schema- and
  gate-driven workflows (#233).

### Changed

- **⚠ Breaking (MCP + REST): normalized identity/cascade parameter names.** `manage_items`
  `items[].id`→`itemId` and `ids`→`itemIds`; `query_notes` get `id`→`noteId`; `manage_dependencies`
  fan-out/fan-in source/target(s)→`fromItemId(s)`/`toItemId(s)` and delete `id`→`dependencyId`;
  `manage_project_config`/`manage_plan_documents` `rootItemId`→`rootId` (including REST DTOs);
  `query_items` overview `ancestorId`→`anchorId`. Behavior is unchanged — only the parameter names
  differ. The bundled plugin skills are updated in lockstep, so plugin users are unaffected;
  **direct MCP or REST integrations must update their call sites** (#246).

### Fixed

- `query_items` overview (scoped and anchored modes) with `excludeTerminal=true` no longer drops a
  terminal-role container that still has non-terminal descendants — such a container represents
  active work and was silently disappearing from `/work-summary`. The global (unscoped) overview
  path has the same latent behavior pending a separate follow-up (#255).
- Array-parameter validation across `manage_items`, `manage_notes`, `manage_dependencies`, and
  `advance_item` now reports the received type (string vs object vs other) instead of an
  undiagnosable "must be a JSON array", so a JSON-encoded-string argument is distinguishable from
  malformed input (#254).
- FTS5 search now returns correct results under the REST API, plus status-label stamping
  corrections (three bugs) (#244).
- `manage_dependencies` now reports a failed count that matches its failures detail list (#253).
- **Plugin robustness.** Corrected tool-call shapes and schema-key drift across skills, a
  shape-tolerant retrospective-trigger response parser, and the documented PreToolUse deny shape in
  actor-attribution enforcement (#245, #249, #251, #252).

### Documentation

- Documented global-vs-per-project config precedence (workspace file is canonical; the per-root DB
  row is a hot-reloaded synced replica; the file wins at session boundaries) and reframed
  `AGENT_CONFIG_DIR` in `CLAUDE.md` as the global/fallback config layer, with a pointer to the
  per-root DB layer and the `config-sync` hook (#236).

### Plugin

- Bumped plugin version to **3.4.0** — new `/configure-server` skill, `schema-orchestrator` output
  style, and native retrospective-trigger hooks.

## [3.11.0] - 2026-07-15

### Added

- **New skill: `/adopt-project-scope`** — one-command, in-place migration of an existing unscoped
  Task Orchestrator database to the project-scoping model. It creates a project anchor root,
  re-parents your existing work trees under it, and writes `rootId` back to
  `.taskorchestrator/config.yaml`, so established workspaces can adopt multi-project scoping without
  rebuilding their database. This is the recommended entry point for existing users moving to
  project scoping.
- **Project-root scoping** — new `manage_project_config` tool plus an `ancestorId` scope parameter
  on `query_items`, `get_next_item`, `get_context`, and `get_blocked_items`, so work in a shared
  database can be scoped to a single project subtree.
  > **Note:** project scoping is an organizational convenience, **not** a security mechanism for
  > data separation. `ancestorId` filters which items a query returns — it does not isolate or
  > access-control data. Any caller with database or MCP access can still reach every item
  > regardless of scope. For true data separation between projects, use separate databases.
- **Per-root schema configuration** layered over the global config, resolved via a new per-root
  config service.
- `excludeTerminal` filter on the `query_items` overview operation, powering a leaner,
  attention-first `/work-summary` dashboard.

### Changed

- Trimmed MCP tool-description payloads (Token-Efficiency Program) — lower context cost per
  `tools/list` call.
- Attributed note and transition output omits no-op verification blocks.

### Fixed

- Patched `sqlite-jdbc` to 3.53.2.0, closing CVE-2025-6965 (SQLite memory corruption; fixed
  upstream in SQLite 3.50.2 — the prior 3.49.1.0 pin bundled SQLite 3.49.1 and was still exposed).
- `complete_tree` now records already-terminal (including cancelled) items as skipped instead of
  failing their gate and spuriously skipping their dependents.
- Reparenting an item now recomputes its descendants' depths instead of leaving them stale
  (MCP + REST).
- `query_items` returns true totals, deterministic ordering, and skipped-row visibility
  (MCP tools and REST `/items/roots`).

## [3.10.0] - 2026-06-24

### Added

- REST `POST /items/{id}/advance` now has full parity with the MCP `advance_item` tool — it
  enforces required-note gates, runs cascade detection, and returns cascade / unblock /
  `expectedNotes` data (previously the REST path silently skipped all of these).
- `create_work_tree` attach mode — a new optional `root.id` attaches children, dependencies, and
  notes to an existing item instead of creating a new root; `root.title` is optional when
  `root.id` is provided.

### Changed

- `create_work_tree` now rejects a nested `children` array with a clear validation error instead
  of silently dropping those items — build nested trees with the flat `children` array plus
  `parentRef`.
- REST `/advance` now returns `422` when required notes are missing, rather than silently
  advancing the item.

### Fixed

- Wired JWKS verification into the REST auth path (jwks mode previously rejected every request)
  and hardened `/mcp` HTTP exposure.
- Hardened workflow transitions — atomic transition + audit writes, trigger-based cancel-cascade
  detection, and DB-clock `roleChangedAt`.
- Fixed concurrency in note upsert (now atomic), claim TTL handling, and claim release.
- Scope-isolated SSE event replay and dependency events so API subscribers only receive events
  for their own root.
- Corrected `manage_notes`, `complete_tree`, and `manage_dependencies` delete operations and a
  note schema-fallback bug.
- `manage_items` traits now tolerate a comma-separated string in the properties bag.

### Performance

- Restricted FTS5 search-index update triggers to the title and summary columns, cutting write
  overhead on item and note updates.

## [3.9.0] - 2026-06-01

### Added

- **REST API layer (opt-in).** New authenticated HTTP API under `/api/v1` for reading and
  writing the work-item graph without an MCP client — items, notes, dependencies, role
  transitions, config/schema discovery, FTS5 search, and a real-time SSE event stream. Enable
  with `API_ENABLED=true` and `API_AUTH_MODE` (`bearer` static tokens or `jwks` JWTs). Includes
  capability- and scope-based authorization, ETag optimistic concurrency, and idempotency keys.
- **MCP over HTTP (Streamable HTTP transport).** Set `MCP_TRANSPORT=http` to serve the MCP
  endpoint at `/mcp` (protocol `2025-11-25`), on its own or alongside the REST API on the same
  port. Validates the `Origin` header (DNS-rebinding protection) and binds to loopback by default.
  *Correction (4.0 docs):* `Origin` validation is a browser defence, not DNS-rebinding protection (that is the
  `Host` allowlist, which arrived in 3.15.0), and the default bind is `0.0.0.0`.
- **HTTP setup guides.** Step-by-step quick-start sections for running MCP over HTTP and for
  enabling the REST API with a bearer token, with verified macOS/Linux and Windows commands.

### Changed

- **The REST API is off by default.** `API_ENABLED` defaults to `false`, so the stock container
  (stdio or MCP-over-HTTP) starts with no API configuration; enable the API explicitly with
  `API_ENABLED=true` (which then requires `API_AUTH_MODE`).

> **Correction (added 2026-07-15):** the "binds to loopback by default" clause above was inaccurate
> both then and now — `MCP_HTTP_HOST` has always defaulted to `0.0.0.0` (verified at the `v3.9.0` tag
> and unchanged since; see `AppConfig.kt`). For Docker deployments, host-level exposure has always
> been controlled by the `-p` port-publish mapping (e.g. `-p 127.0.0.1:3001:3001`), not by the
> server's internal bind address. See `current/docs/quick-start.md` "HTTP transport security" and
> `SECURITY.md` for the accurate guidance.

## [3.8.0] - 2026-05-22 (Plugin v3.2.2)

### Breaking Changes

- **`query_items.search` — LIKE-based `query` parameter removed.** The top-level `query` parameter
  that performed a `LIKE '%text%'` filter on `operation=search` has been removed from the JSON
  schema. All text search now uses the FTS5-backed `search` operation where `query` is an FTS5
  query string (multi-word implicit AND; special characters auto-escaped). The parameter name
  `query` is preserved but its semantics changed: in `query_items(operation="search", query=...)`
  the presence of `query` now triggers FTS5 mode instead of LIKE filtering.

### Added

- **`query_items(operation="search", query=...)` — FTS5 full-text search.** Returns ranked hits
  with ~32-token `<mark>…</mark>` snippets from item titles and summaries. Fusion of trigram
  (substring) and porter+unicode61 (natural language) FTS5 tables via Reciprocal Rank Fusion
  (k=60). Parameters: `scope` (ancestorId/itemId/tags/role), `matchMode` (auto/substring/text),
  `snippet`, `explain`, `limit` (max 100), `offset`.

- **`query_notes(operation="search", query=...)` — FTS5 note body search.** Same RRF fusion
  architecture applied to note bodies. Returns `kind="note"` hits with `noteKey` and `field="body"`.
  Scope parameters: `scope.itemId` and `scope.ancestorId`. Use `list` for role-filtered note
  retrieval.

- **`query_dependencies(operation="backlinks", itemId=...)` — reverse-edge lookup.** Returns all
  items that hold a dependency edge pointing AT the given item. Each backlink: `{ fromItemId, type,
  fromTitle }`. Optional `type` filter. Uses the existing `to_item_id` index — no table scan.

- **Unbounded hierarchy depth.** Work item trees are no longer capped at depth 3. Items can nest
  at any depth. Cycle protection is enforced at the database level by a recursive trigger (V7
  migration).

- **`create_work_tree parentRef`** — attach a new work subtree to an existing parent item by
  passing `parentRef: { itemId: "..." }`. Eliminates the need to pre-create a container when
  nesting child trees under an existing item (#180).

- **FTS5 architecture concept doc.** `current/docs/search-and-discovery.md` — explains the
  two-table FTS5 design, RRF scoring, scope filters, backlinks, score interpretation, and
  `explain=true` usage.

### Fixed

- Fixed MCP server startup failure and FTS5 search errors in production Docker deployments
  introduced with the V7 migration (#179).
- Fixed 8 MCP API surface consistency issues — error codes, field names, and response shapes
  now match documented contracts across all tools (#183).
- Fixed 3 doc/code mismatches in the API reference where documented behavior differed from
  implementation (#184).

### Changed

- Bumped plugin to 3.2.2 — updated ralph skill content.

### Requirements

- **SQLite ≥ 3.45** — required for the FTS5 trigram tokenizer. Bundled automatically via
  `xerial/sqlite-jdbc` in the Docker image. Direct JAR execution against a system SQLite must
  be ≥ 3.45 or the V7 migration fails at startup.

### Test Environment Note

FTS5 is SQLite-only. All `search` operations return empty results when running against H2 (unit
test environment). Integration tests for FTS5 use a real SQLite database.

---

## [3.7.0] - 2026-05-07 (Plugin v3.2.1)

### Breaking Changes
- **`claim_item` now rejects `claims.size > 1`** with error code `multi_claim_not_supported`. Multi-claim was previously silently broken — the repository auto-released all but the last claim while the response reported all as `success`. The behavior is now rejected explicitly at the validation layer. Migration: issue one `claim_item` call per item. The cap derives from the heartbeat write-budget assumption (one TTL refresh per agent per cycle); a future `claim_heartbeats` table mitigation could re-evaluate the constraint. Only `claims` is restricted — the `releases` array remains batchable.

### Added
- `claim_item` selector mode — atomic find-and-claim in a single call. Each claim entry may now use a `selector` object (instead of `itemId`) to resolve a filter+rank query and claim the top match without the race window of the two-call `get_next_item → claim_item` pattern. New outcomes: `no_match` (`kind=permanent`, no `retryAfterMs`, no `itemId`) when the queue is empty for the given filters; `selectorResolved: true` on success. New `claimRef` field (up to 64 chars) echoed verbatim in every claim result for caller-side correlation. Single-claim-per-call enforced by validation for all claim modes.
- `get_next_item` filter parameters: `tags` (comma-separated, any-match), `priority` (`high`|`medium`|`low`), `type` (exact match), `complexityMax` (1–10), `createdAfter` (ISO 8601), `createdBefore` (ISO 8601), `roleChangedAfter` (ISO 8601), `roleChangedBefore` (ISO 8601), `orderBy` (`priority` default | `oldest` FIFO | `newest` recency). Filter shape is identical to `claim_item.selector` — both tools share the same underlying eligibility logic (`NextItemRecommender`).
- `findClaimable` repository method composing the rich filter surface with active-claim exclusion — single source of truth for "what's eligible next" across both tools.
- `NextItemRecommender` shared service — encapsulates the recommend+dependency-block logic so `get_next_item` and `claim_item` selector path can never diverge on eligibility semantics.
- `NextItemOrder` enum — `PRIORITY_THEN_COMPLEXITY`, `OLDEST_FIRST`, `NEWEST_FIRST`.

### Changed
- Bumped plugin to 3.2.1 — updated Ralph queue-drain skill and session-start hook for single-claim behavior.

---

## [3.6.0] - 2026-05-04 (Plugin v3.2.0)

### Added
- Added inline `notes` parameter to `create_work_tree` — materialize fully-populated work trees in one call, attaching note bodies to root and child items as part of atomic creation (#164)
- Added `actor` parameter to `create_work_tree` — propagates actor attribution onto every persisted note (both explicit notes and `createNotes=true` blanks) and keys idempotency together with `requestId` (#166)
- Added Ralph-style queue drain skill and `ralph-loop.mjs` script to the plugin — an autonomous worker that repeatedly invokes `claude -p` with worktree isolation to drain queue items one per iteration, with configurable filters and bounds (#169)

### Changed
- `create_work_tree` now enforces strict role matching for inline notes — when an explicit note's key is declared in the resolved schema, its role must equal the schema role; mismatch returns `VALIDATION_ERROR` to prevent silently leaving gate-required roles unfilled (#167)
- `create_work_tree` response now always includes `schemaMatch` and `expectedNotes` on root and each child, plus `unblockAt` on dependencies when set — gives callers immediate visibility into gate requirements without a follow-up `get_context` call (#164)
- Bumped plugin version to 3.2.0 — adds the `ralph` skill, `ralph-iteration` output style, and `ralph-loop.mjs` script for autonomous queue-drain workflows (#169)

### Documentation
- Aligned `create_work_tree` API reference with actual tool description and behavior (#168)
- Documented orchestration vs claim mode in `CLAUDE.md` (#165)

---

## [3.5.0] - 2026-05-01 (Plugin v3.1.3)

### Breaking Changes
- **Config section renamed**: `auditing:` → `actor_authentication:`. The section configures actor-claim authentication policy (JWT verifier, JWKS sources, DID trust, degraded-mode policy), which the prior name did not describe. Migration: `sed -i 's/^auditing:/actor_authentication:/' .taskorchestrator/config.yaml`. The MCP server emits a clear startup error pointing to the new key when legacy `auditing:` is encountered. Inner `verifier:` block name is unchanged. (#160)

### Added
- Added native `did:web` actor authentication — resolve DID documents directly via `did_allowlist` / `did_pattern` config, with per-issuer caching and segment-bounded glob matching (#159)
- Added HTTP hardening for DID document resolution — strict timeouts (5s request, 3s connect, 5s socket), redirect rejection, content-type validation, and 1 MiB body cap (#159)
- Added per-issuer JWKS fetch coalescing — concurrent first-misses for the same issuer share one underlying resolve, eliminating the global serialization point under fleet-restart load (#159)

### Changed
- `algorithms` is now required under `type: jwks` verifier — empty/omitted list fails at config load (was previously implicit) (#159)
- `did_pattern '*'` now matches a single DID segment instead of multiple — sub-path hijacks like `did:web:host:agents:alice:fake` matching `...:agents:*` are rejected. Use `did_allowlist` for multi-segment matches (#159)
- Multiple static-JWKS sources (`oidc_discovery` + `jwks_uri` + `jwks_path`) now produces a startup error instead of a warning (#159)
- DID document `id` mismatch and missing-id-field cases are now classified as REJECTED+policy (was UNAVAILABLE) — substitution attacks no longer leak as transient errors (#159)
- Bumped plugin version to 3.1.3 — `enforce-actor-attribution` hook updated for the config rename; `manage-schemas` skill references updated for `actor_authentication` block and DID-trust fields

### Documentation
- Restructured Tier 6 self-improving workflow guide around three nested feedback loops (per-turn, per-detection, per-session) (#162)
- Added fleet-deployment sections: claim-mode rollout stages, JWT contract, claim/PII surfaces, live-fleet operation (#157)
- Added DID-rooted trust subsection to SECURITY.md (#159)
- Public wiki now correctly renders internal Markdown links and integration-guides subdirectory pages (#158)

---

## [3.4.0] - 2026-04-29 (Plugin v3.1.2)

### Added
- Added `claim_item` tool — explicit work-item claiming with TTL, atomic SQL claim/release, and self-reclaim semantics for fleet coordination (#117)
- Added `requestId` parameter to all mutating tools (`claim_item`, `advance_item`, `manage_items`, `manage_notes`, `manage_dependencies`) — replays of the same request return the cached response without re-applying writes (#117)
- Added `claimStatus` filter and tiered claim disclosure to `query_items` — filter the backlog by who holds what, with the level of detail scaled to the requesting actor (#117)
- Added `role` parameter to `get_next_item` — pull the next item scoped to a workflow phase, with claim-aware skipping of items held by other actors (#117)
- Added `DATABASE_BUSY_TIMEOUT_MS` environment variable — tune SQLite `busy_timeout` for fleet deployments where concurrent writers compete for the database lock (#117)
- Added `DEGRADED_MODE_POLICY` config flag (`accept-cached` / `accept-self-reported` / `reject`) — control how the server behaves when actor identity cannot be freshly verified (#117)
- Added standardized `ToolError` response envelope across all tools — consistent error shape with `code`, `field`, and `message`, plus a structured `metadata` bag (#117)
- Added `IdempotencyCache` service backing the `requestId` mechanism — bounded cache with TTL, validation, and self-reported-actor handling (#117)
- Added `UserTrigger` enum — separates user-initiated triggers from internal cascade triggers, preventing external callers from forging cascade transitions (#117)
- Added fleet deployment guide and claim mechanism reference in `current/docs/` — covers single-tenant, multi-actor, and shared-database fleet topologies (#117)

### Changed
- `advance_item` now enforces claim ownership across all user triggers — when an item is claimed, only the holder can transition it; unclaimed items advance as before (#117)
- `claim_item` errors return the new `ToolError` envelope with disclosure-tightened messages — never leaks claimant identity beyond what `query_items` already exposes (#117)
- Bumped plugin version to 3.1.2 — `subagent-start` hook context refinements, `manage-schemas` and `pre-plan-workflow` skill content updates

### Fixed
- Fixed `originalClaimedAt` invariant on claim renewals — preserves the original-claim timestamp across self-renewals while resetting cleanly on release (#117)
- Fixed UTC consistency in claim time fields — pinned test JVM to UTC and aligned DB-side timestamp handling so claim TTLs behave identically across developer machines and Linux CI (#117)
- Fixed blank `actor.id` validation — empty or whitespace-only IDs now fail validation cleanly rather than passing through (#117)

### Documentation
- Added claim mechanism reference, fleet deployment guide, and expanded workflow guide (#138, #150)
- Removed broken Integration Guides wiki link from README (#140)

---

## [3.3.0] - 2026-04-24 (Plugin v3.1.1)

### Changed
- Improved skill descriptions across 8 plugin skills to use third-person verb form and consistent "Use when:" trigger patterns, improving skill routing accuracy
- Added trigger phrase expansions to `pre-plan-workflow`, `work-summary`, `create-item`, `batch-complete`, `dependency-manager`, `manage-schemas`, `schema-workflow`, and `status-progression`
- Updated `pre-plan-workflow` with a minimal config YAML example for schema illustration
- Bumped plugin version to 3.1.1 (skill description content fixes and routing improvements)

---

## [3.3.0] - 2026-04-23

### Added
- Added `metadata` field to `VerificationResult` — structured JSON bag carrying `failureKind` (`crypto | claims | policy | network | internal`) on rejected verifications, plus `verifiedFromCache` and `cacheAgeSeconds` when the JWKS was served from stale cache (#120)
- Added `stale_on_error` config key on the JWKS verifier (default `true`) — serves the cached key set when the JWKS endpoint is temporarily unreachable, distinguishing transient infrastructure failures from cryptographic rejections (#120)
- Added `ABSENT`, `UNCHECKED`, and `UNAVAILABLE` verification statuses — `ABSENT` when the actor supplied no proof, `UNCHECKED` when the deployment uses the no-op verifier, `UNAVAILABLE` for transient infrastructure failures that are safe to retry (#121)

### Changed
- **Breaking (verification public beta):** Split the `VerificationStatus` enum for clearer semantics — `UNVERIFIED` is now `ABSENT` (no proof) or `UNCHECKED` (no-op verifier); `FAILED` is now `REJECTED` (cryptographic / policy / claim violation — do not retry) or `UNAVAILABLE` (transient network — retry-safe). Stored records remain readable: legacy `"unverified"` maps to `ABSENT` and legacy `"failed"` maps to `REJECTED` on read (#121)
- Changed `JwksActorVerifier` failure mapping — network errors return `UNAVAILABLE` with `failureKind=network`; cryptographic errors return `REJECTED` with `failureKind=crypto`; expired or mismatched claims return `REJECTED` with `failureKind=claims`; algorithm policy violations return `REJECTED` with `failureKind=policy` (#120)

### Fixed
- Fixed `server.json` metadata so the MCP registry publication succeeds (#116)

### Documentation
- Documented the database-per-tenant security model in `SECURITY.md` (#118)
- Documented the 5-value `VerificationStatus` enum, the `metadata` bag, and the `stale_on_error` config key in `current/docs/api-reference.md` (#122)

---

## [3.2.0] - 2026-04-15 (Plugin v3.1.0)

### Added
- Added actor attribution to `advance_item` and `manage_notes` -- optional `actor` object tracks who made each transition and note update, persisted via new database columns (V4 migration)
- Added config-driven auditing enforcement -- when `auditing.enabled: true` in config.yaml, the PreToolUse hook blocks `advance_item` calls that lack actor claims
- Added JWKS-based actor verification as a public beta (`auditing.verifier.type: jwks`) -- validates JWT bearer tokens against JWKS key sets with support for OIDC discovery, direct URI, local file sources, algorithm allowlists, and configurable claim validation
- Added MCP registry metadata and Smithery configuration for marketplace discovery
- Bumped plugin version to 3.1.0 -- new `enforce-actor-attribution` hook and updated auditing docs

### Fixed
- Patched dependency CVEs and updated all dependencies to current stable versions

---

## [3.1.0] - 2026-04-13 (Plugin v3.0.1)

### Added
- Added short hex prefix resolution (4+ chars) to all WorkItem ID parameters — use `a1b2` instead of full UUIDs across all 13 tools, including `manage_items` update, delete, and per-item `parentId`

### Fixed
- Fixed `query_items(operation="overview")` scoped view to include `childCounts` and `traits` on child items
- Fixed contradictory agent-owned-phase documentation across plugin skills, output styles, and integration guides — aligned to single-phase agent model
- Bumped plugin version to 3.0.1 — content fixes to skills and output style

---

## [3.0.0] - 2026-04-06 (Plugin v3.0.0)

### Breaking Changes
- Changed schema matching from tag-based to type-based — items now use a dedicated `type` field for schema activation; tags remain for categorization only
- Evolved config format from `note_schemas:` to `work_item_schemas:` with lifecycle modes and trait support (legacy format still parsed for backward compat)

### Added
- Added `type` and `properties` fields to WorkItem — `type` drives schema resolution, `properties` stores extensible JSON including traits
- Added `LifecycleMode` enum (AUTO, MANUAL, AUTO_REOPEN, PERMANENT) — controls cascade behavior per work item type
- Added composable traits — agents assign traits per-item that merge additional note requirements into the base schema at gate-check time
- Added `skill` field on note schema entries — surfaces as `skillPointer` in `get_context` and `advance_item` responses for deterministic skill routing
- Added `skill-enforcement` PreToolUse hook on `manage_notes` — detects shallow notes on skill-required entries and injects skill-invocation directive
- Added 3 trait-specific review skills: `migration-review`, `plugin-impact-review`, `perf-review`
- Added `traits` as shared default parameter on `manage_items(create)` — applies to all items when per-item traits not specified
- Enriched `query_items(overview)` response — root items and children now include `tags`, `type`, `childCounts`, and `traits`

### Changed
- Reduced `/work-summary` skill from 4 to 3 MCP calls by leveraging enriched overview response
- Updated `subagent-start` hook with deterministic `skillPointer` instruction
- Updated `post-plan-workflow` skill with skill-aware delegation instructions
- Bumped plugin version to 3.0.0 — aligned with server major version

---

## [2.5.2] - 2026-04-03 (Plugin v2.7.3)

### Fixed
- Fixed `manage_items` create response to always include `expectedNotes` and `schemaMatch` fields — agents no longer need a separate `get_context` call after item creation

### Improved
- Unified schema-to-JSON serialization across `manage_items`, `create_work_tree`, `advance_item`, and `get_context` via shared `SchemaEntryJsonBuilder`
- Added structured warning collection to schema config loading — config issues are surfaced programmatically via `getLoadWarnings()` instead of silently logged
- Deduplicated WorkTreeService insert/upsert logic with shared repository helpers
- Expanded test coverage across 12 identified gaps — WorkTreeService rollback, cascade detection, schema loading edge cases, note preservation, and gate lifecycle
- Bumped plugin version to 2.7.3 — added `container` schema example to manage-schemas skill

---

## [2.5.1] - 2026-03-31 (Plugin v2.7.2)

### Fixed
- Fixed `IS_BLOCKED_BY` dependencies not being enforced by `advance_item` — items could advance past IS_BLOCKED_BY gates that `get_blocked_items` and `get_next_item` correctly reported as blocked
- Fixed `advance_item` unblock detection not discovering items with IS_BLOCKED_BY edges when their blocker completes
- Fixed reversed dependency example in quick-start documentation

### Changed
- Improved manage-schemas skill templates to align with spec-quality and review-quality frameworks
- Added guidance generation rules and cross-schema duplication checks to manage-schemas edit workflow
- Bumped plugin version to 2.7.2

---

## [2.5.0] - 2026-03-30 (Plugin v2.7.1)

### Changed
- Strengthened planning hook language — pre-plan and post-plan hooks now use imperative gate framing (PREREQUISITE/MUST) instead of suggestive phrasing that was deprioritized by plan mode
- Enforced WHAT/HOW separation — output style trimmed to principle-level statements, procedural detail lives in skills
- Added explicit handoff sections to pre-plan and post-plan workflow skills for clean control flow between hook, skill, and plan mode
- Bumped plugin version to 2.7.1 (planning hook and output style refinements)

---

## [2.5.0] - 2026-03-25 (Plugin v2.7.0)

### Added
- Added tiered execution model (Direct/Delegated/Parallel) to the Workflow Orchestrator output style — classifies work by scope and applies proportional process
- Added Direct Tier Workflow section — orchestrator implements, tests, and reviews inline for 1-2 file known fixes
- Added force-UP/DOWN signals for tier classification (migration, new API, collaborative language, "just fix it")
- Added Direct tier safety net to pre-plan hook — warns if plan mode is entered for Direct tier work
- Added YAML frontmatter to workflow-orchestrator output style (name, description, keep-coding-instructions)

### Changed
- Changed Principle #1 from "Never implement directly" to "Delegate by default" with tier-conditional behavior
- Changed Principle #2 from "Plan before acting" to "Plan proportionally" with tier-conditional plan mode
- Trimmed workflow-orchestrator to universally valuable content — removed project-specific references, redundant templates, and niche operational details
- Bumped plugin version to 2.7.0 (tiered execution model)

---

## [2.5.0] - 2026-03-23

### Added
- Added `McpLoggingService` for MCP protocol-level logging — tool validation errors and internal exceptions now emit `notifications/message` to connected clients, improving visibility for MCP client UIs
- Added `logback.xml` to the `current` module routing all logs away from stdout — fixes MCP spec compliance violation where log output corrupted the JSON-RPC stream for stdio transport clients (Fixes #84)

### Fixed
- Fixed stdout pollution breaking stdio transport clients — Logback previously fell back to `BasicConfigurator` (DEBUG+ to stdout) because no `logback.xml` was bundled in the JAR
- Fixed internal documentation cross-references to use `.md` extensions for proper link resolution

---

## [2.4.1] - 2026-03-22

### Changed
- Removed archived clockwork (v2) module — simplifies Docker configuration and build scripts to a single runtime target
- Standardized phase transition ownership across plugin skills and output styles — implementation agents own queue→work and work→review; orchestrator owns review→terminal
- Fixed `complete` trigger documentation in README — correctly states it requires all notes across all phases (does not bypass gates)
- Added missing `reopen` trigger to README trigger table
- Fixed output style name reference from "Workflow Analyst" to "Workflow Orchestrator"
- Updated MCP SDK version reference from 0.8.4 to 0.9.0

### Added
- Added 7 progressive integration guide cookbooks covering bare MCP, CLAUDE.md-driven workflow, note schema gating, plugin skills and hooks, output styles, and self-improving workflow patterns
- Added `git reset --hard origin/main` to implement skill post-merge sync to prevent local main drift

### Plugin (2.6.1)
- Fixed contradictory phase transition rules in `schema-workflow`, `post-plan-workflow`, and `workflow-orchestrator` output style

---

## [2.4.0] - 2026-03-09

### Added
- Added config-driven status labels to `advance_item` — custom label names per role transition surfaced in responses and `query_items` results
- Added session tracking notes as a schema-enforced work-phase gate — agents fill context that `/session-retrospective` aggregates across the feature tree
- Added cascade gate enforcement to `advance_item` — parent items now verify child note gates before cascading to terminal
- Added `NoteSchemaEntry.role` type safety — role field changed from `String` to `Role` enum, eliminating stringly-typed comparisons across the gate layer
- Added ktlint enforcement with `.editorconfig` and CI integration

### Fixed
- Fixed short UUID prefix lookup failing in SQLite — `CAST(blob AS VARCHAR)` produced raw bytes instead of hex; now uses `HEX()`/`RAWTOHEX()` for correct cross-dialect behavior
- Fixed `RELATES_TO` edges incorrectly blocking cycle detection in dependency validation
- Fixed input validation hardening across multiple tools

### Changed
- Upgraded MCP SDK to 0.9.0 with Ktor Streamable HTTP transport
- Improved plugin worktree reliability — SubagentStart hook now injects commit, scope, and cd-discipline rules; orchestrator output style includes Worktree Dispatch checklist
- Bumped plugin version to 2.6.0 (new hook content, updated skills and output styles)

---

## [2.3.0] - 2026-03-07

### Added
- Added `reopen` trigger to `advance_item` — reopens terminal items back to queue, clears statusLabel on cancelled items
- Added short UUID prefix resolution to `query_items` get — resolve items by 4+ hex character prefix instead of full UUID
- Added `guidancePointer` and `noteProgress` to `advance_item` success response — shows next required note guidance and fill counts for the new role
- Added `itemContext` to `manage_notes` upsert response — returns `guidancePointer` and `noteProgress` per item, eliminating N-1 `get_context` round-trips
- Added role guard to `manage_items` update — rejects direct role changes with guidance to use `advance_item` triggers instead

### Changed
- Refined agent-owned-phase protocol in subagent-start hook — agents now enter their phase, iterate notes via JIT progression, and never double-advance
- Extracted shared `PhaseNoteContext` computation — unified gate-check logic across `manage_notes`, `get_context`, and stalled-item detection
- Bumped plugin version to 2.5.2 (agent-owned-phase protocol refinements)

---

## [2.5.1] - 2026-03-05 (plugin-only)

### Fixed
- Fixed subagent-start hook to enforce transition-before-guidance ordering — agents now call `advance_item(start)` before `get_context`, ensuring they receive work-phase `guidancePointer` instead of stale queue-phase guidance
- Bumped plugin version to 2.5.1

---

## [2.2.0] - 2026-03-05

### New Features
- Added `schema-workflow` skill to the plugin — guides items through schema-defined lifecycle with gate-enforced phase transitions
- Added shared tool helper utilities in `BaseToolDefinition` — reduces boilerplate across all MCP tool implementations

### Improvements
- Improved `complete_tree` with BFS-based traversal — handles deep hierarchies more efficiently and fixes edge cases with dependency ordering
- Improved `query_dependencies` response format — clearer structure for dependency chain visualization
- Overhauled `work-summary`, `batch-complete`, and `create-item` plugin skills — better prompts and more reliable behavior
- Restructured `workflow-orchestrator` output style — cleaner zone separation between shared core and extensions

### Performance
- Parallelized sequential `findByRole` queries in `get_context` — reduces latency when loading items across multiple workflow phases

### Changed
- Automated releases via tag push (`v*` triggers Docker + GitHub Release) — replaces manual `workflow_dispatch`
- Bumped plugin version to 2.5.0 (new skill, skill improvements)

---

## [2.1.0] - 2026-03-04

### Added
- Added `guidancePointer` and `expectedNotes` to `advance_item`, `create_work_tree`, `manage_items`, and `get_context` responses — agents see exactly which notes need filling and get human-readable guidance at each gate transition
- Added 6 new plugin skills: `batch-complete`, `dependency-manager`, `manage-schemas`, `quick-start`, `status-progression`, and `create-item` — covering bulk operations, dependency visualization, schema management, onboarding, and workflow navigation
- Added `pre-plan-workflow` and `post-plan-workflow` internal skills with automatic hook injection — plan mode now checks existing MCP state and materializes items after approval without manual steps

### Changed
- Renamed output style from `current-analyst` to `workflow-orchestrator` to better reflect its orchestration focus
- Overhauled plugin hooks (`pre-plan`, `post-plan`, `subagent-start`) for schema-aware context injection
- Improved quick-start and workflow guide documentation with end-to-end examples

### Fixed
- Fixed 20 skill quality issues across 9 skills (contradictions, jargon, missing guards, unclear guidance)

---

## [2.0.3] - 2026-02-19

### Added
- Added Claude Code plugin installation instructions to README and quick-start guide — covers skills, hooks, output style, and correct install commands
- Added automatic GitHub Wiki sync — documentation in `current/docs/` now auto-publishes to the GitHub Wiki on every merge to `main`
- Added `/prepare-release` skill for streamlined release preparation with changelog drafting and PR creation

### Changed
- Renamed Claude Code plugin from `current` to `task-orchestrator` — install via `/plugin marketplace add https://github.com/jpicklyk/task-orchestrator` then `/plugin install task-orchestrator@task-orchestrator-marketplace`
- Removed legacy `clockwork` plugin (superseded by `task-orchestrator`)
- Fixed quick-start guide Step 2 Option B to use `.mcp.json` (was incorrectly referencing `.claude/settings.json`); added Option C for other MCP clients; corrected note schemas Docker config block to show full config

---

## [2.0.2] - 2026-02-19

### Fixed
- Corrected Docker image tag references and version badge in README

### Documentation
- Restructured Quick Start with explicit `docker pull` step and simplified default config
- Scoped config mount documentation to `.taskorchestrator/` only
- Fixed all project mount path references across docs
- Trimmed README — removed padding sections, collapsed marketing copy

---

## [2.0.1] - 2026-02-19

### Fixed

- Fixed `manage_items(update)` now rejects self-referencing and circular `parentId` assignments, preventing hierarchy corruption
- Fixed `findAncestorChains` BFS detects and breaks parent cycles instead of looping infinitely
- Fixed `version.properties` included in Docker build context so container image versioning is accurate

### Added

- Added `/bump-version` Claude Code skill — automates release PR preparation with semver inference and changelog drafting

---

## [2.0.0] - 2026-02-18

### 🚨 BREAKING CHANGES

**Unified WorkItem Architecture**: Complete rewrite of the domain model. The Project → Feature → Task three-tier hierarchy is replaced by a single `WorkItem` entity with flexible depth (0–3) and a role-based workflow engine. All previous container tools (`manage_container`, `query_container`) are replaced by the new tool surface.

**Tool Surface**: 13 tools replace the prior architecture. No migration path — this is a clean break requiring a fresh database.

---

### Added

**Core WorkItem Model**
- Single `WorkItem` entity replaces Project/Feature/Task — hierarchical via `parentId` and `depth` (max 3)
- Role-based lifecycle: `queue → work → review → terminal` (roles are semantic, statuses are named per-workflow)
- Note schema system: YAML-configured schemas gate role transitions — required notes must be filled before `advance_item` advances to the next phase
- `previousRole` tracking and `roleChangedAt` timestamps on all items

**13 MCP Tools**
- **`manage_items`** — create, update, delete (batch, with `recursive` subtree delete)
- **`query_items`** — get, search, overview; `includeAncestors` for breadcrumb chains; `includeChildren` for scoped hierarchy views; `role` filter for semantic phase queries
- **`manage_notes`** — upsert and delete notes; batch upsert via `notes` array
- **`query_notes`** — list notes per item; `includeBody=false` for metadata-only checks
- **`manage_dependencies`** — create (batch array or `linear`/`fan-out`/`fan-in` pattern shortcuts) and delete; consistent batch failure response shape
- **`query_dependencies`** — neighbor lookup or full BFS graph traversal (`neighborsOnly=false`) returning topologically-ordered chain and depth
- **`advance_item`** — trigger-based role transitions (`start`, `complete`, `block`, `hold`, `resume`, `cancel`) with gate enforcement, cascade detection, and batch support via `transitions` array
- **`get_next_status`** — read-only progression recommendation before transitioning
- **`get_context`** — three modes: item context (schema, gate status, `guidancePointer`), session resume (active + recent transitions since timestamp), health check (active/blocked/stalled)
- **`get_next_item`** — priority-ranked recommendation of next actionable queue item
- **`get_blocked_items`** — dependency blocking analysis with `blockType` (`explicit` vs `dependency`)
- **`create_work_tree`** — atomic single-call hierarchy creation (root + children + dependencies + notes)
- **`complete_tree`** — batch completion of descendants in topological dependency order with gate checking; `cancel` trigger bypasses gates

**Graph-Aware Queries**
- `includeAncestors=true` on `query_items`, `get_context`, `get_blocked_items`, `get_next_item` — embeds full ancestor chain on each item, eliminating sequential parent-walk calls
- 2-call work-summary pattern: `get_context(includeAncestors=true)` + `query_items(overview)` replaces multi-call traversal

**Transport**
- HTTP transport via Ktor Streamable HTTP (MCP spec 2025-03-26): `MCP_TRANSPORT=http`, endpoint `http://host:port/mcp`
- `MCP_HTTP_HOST` and `MCP_HTTP_PORT` environment variables
- Stdio transport remains default

**Infrastructure**
- SQLite WAL mode (`PRAGMA journal_mode=WAL`) for concurrent reads + write without full file locking
- `PRAGMA busy_timeout=5000` — 5-second retry window instead of immediate failure under write contention
- Fixed dangling JDBC Statement on `setupConnection` (prevented SQLITE_BUSY on parallel MCP calls)
- Connection pool via `DATABASE_MAX_CONNECTIONS` environment variable
- `AGENT_CONFIG_DIR` environment variable for Docker-compatible config resolution

**Claude Code Plugin (v1.0.14)**
- `work-summary` skill — insight-driven project dashboard with active/blocked/up-next sections
- `create-item` skill — container-anchored work item creation from conversation context; infers type, anchors to Bugs/Features/Tech Debt/Observations/Action Items containers, pre-fills required notes
- `status-progression` skill — interactive role transition guidance
- `schema-builder` skill — YAML note schema builder for new work item types
- `check_schema_version` and `deploy_to_docker` skills for local development workflow
- Workflow Analyst output style — orchestration mode with MCP efficiency analysis and observation logging
- Planning hooks (PreToolUse/PostToolUse on EnterPlanMode/ExitPlanMode) enforcing MCP container materialization before implementation

**Documentation**
- Complete v3 docs under `current/docs/`: quick-start, api-reference (all 13 tools), workflow-guide
- Full audit of all 13 tools against implementation — 42 discrepancies identified and resolved
- v2/Clockwork docs archived to `clockwork/docs/`

### Fixed

- `manage_dependencies(create)`: validation failures now return consistent batch response shape `{success: true, created: 0, failed: N, failures: [{index, error}]}` instead of top-level error response — matches `manage_items` and `manage_notes` patterns
- SQLite PRAGMA dangling statement: `journal_mode=WAL` returns a result row; wrapping all PRAGMAs in `Statement.use {}` prevents open prepared statement from blocking subsequent `setTransactionIsolation` calls

### Architecture Notes

- **Clean Architecture**: Domain → Application → Infrastructure → Interface layers; domain has no framework dependencies
- **v2/Clockwork archived**: Previous architecture preserved as `:clockwork` Gradle submodule in `clockwork/`; not built by default
- **Flyway migrations**: Production schema management; `DirectDatabaseSchemaManager` for development iteration
- **No sections or templates**: The v3 WorkItem model uses notes (key/value pairs with role phase) rather than rich sections; template system from v2 is not present in v3

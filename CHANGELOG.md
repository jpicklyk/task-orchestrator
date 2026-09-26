# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- Added an opt-in `(iss, jti)` replay cache for actor-authentication JWTs
  (`actor_authentication.verifier.jti_replay_protection`, default `false`). A per-MCP-call memo
  keeps a proof that is legitimately re-verified multiple times within one call (idempotency-key
  lookups, multi-transition batches) from tripping the cache as a false replay. (#366)

### Changed

- Actor-authentication and REST API JWTs are now capped at a maximum lifetime, default 24 hours
  (`actor_authentication.verifier.max_token_lifetime_seconds` / `API_JWKS_MAX_TOKEN_LIFETIME_SECONDS`).
  **Upgrade note:** a token whose `exp - iat` (or remaining `exp - now`) exceeds 24 hours is now
  REJECTED where it previously verified; raise the new setting before or immediately after
  upgrading if your issuer intentionally mints longer-lived tokens. (#366)

### Fixed

- Fixed a malformed JWT claims set (e.g. a non-numeric `exp`/`iat`) being misreported as
  `UNAVAILABLE`/`failureKind: network` under DID trust, or `REJECTED`/`failureKind: internal` in
  static-JWKS mode; both now report `REJECTED`/`failureKind: claims`. (#366)

### Plugin

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
- SessionStart now warns when a dev checkout's `claude-plugins/task-orchestrator/.claude-plugin/plugin.json`
  version differs from the version of the plugin actually running the hook, pointing to
  `claude-plugins/CLAUDE.md` → "Plugin Discovery and Cache Refresh". Silent outside a dev checkout
  or when either version can't be read.
- Added a hook-local `actor_attribution.required: true` config option, independent of
  `actor_authentication`, that makes `enforce-actor-attribution` deny actor-less `advance_item`/
  `manage_notes(upsert)` writes without also requiring full `actor_authentication` (JWKS identity
  verification) to be configured.
- `enforce-actor-attribution` now also checks the singular-sugar `advance_item` form
  (`itemId` + `trigger` with a top-level `actor`); previously an actor-less singular call passed
  the hook even when enforcement was on.
- Reworded the implementer and reviewer agent definitions' seat rules to defer to a named seat
  assignment in the dispatch prompt, rather than assuming the agent owns every required note in
  its phase.

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

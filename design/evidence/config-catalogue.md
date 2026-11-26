# Task Orchestrator project configuration: what it can express today

Abbreviations. SCHEMA = `current/src/main/resources/config/config.schema.json` (JSON Schema, every level `additionalProperties:false`). CR = `current/docs/config-reference.md`. CF = `claude-plugins/task-orchestrator/skills/manage-schemas/references/config-format.md`. PLUG = `claude-plugins/task-orchestrator/`. KT = `current/src/main/kotlin/io/github/jpicklyk/mcptask/current/`. Description only; no recommendations.

## 0. Document, layers, delivery

- Config is one YAML document. Top-level keys and owner (SCHEMA:8-76; CR:18-32): `project`, `work_item_schemas` (legacy alias `note_schemas`, never both), `traits`, `resources` (server file only), `note_limits`, `status_labels`, `independence`, `runs`, `decision_classes`, `extensions` (opaque). Removed/rejected: `actor_authentication` (fails startup), `schema_resolution`, top-level `orchestration`/`retrospective`/`actor_attribution` (rejected on push, warned at boot import).
- Two layers per item by root: server floor (`AGENT_CONFIG_DIR/.taskorchestrator/config.yaml`, imported to DB at each boot; restart to reload) and per-root (pushed with `config push`, hot-reloaded). CR:8-12, CF:"Global vs Per-Project Config".
- Combination rules (CF "How the layers combine"): schema for a type = root exact type, server exact type, root `default`, server `default`, else schema-free (no gates). Trait = whole-entry replacement by name (see 3). `note_limits.mode` root else server. `independence` root block replaces whole. `runs` per field. `decision_classes` per class name (root replaces whole entry, except built-ins override field by field). `status_labels` per trigger. `resources` server only.
- Delivery to the server: plugin `config-sync` hook (SessionStart, FileChanged) validates then pushes the located `.taskorchestrator/config.yaml` per `project.rootId`, then `rules/*.md` as rule documents, then (SessionStart only) bundled rules (PLUG/hooks/config-sync.mjs; CF "Rule text"). Needs REST API URL; caps `read`, `write-config`, `write-docs`.
- Validation: structure (SCHEMA), parser findings (errors under STRICT, warnings under MIGRATION), semantics; `config validate` also returns `lint` and `affectedInFlight` (CR:235-307). Errors are `{path, reason}` RFC 6901 pointers. Nothing stored on any error.
- Pins: every item records the server+root config versions and base schema at pin points (create, type_change, reparent, trait_change, reopen, repin); a later push does not change an in-flight item until `items_write repin` (CR:333-377). Status labels, `note_limits`, `independence`, registry follow heads, not pins.
- Plugin-side project files beside config: `rules/*.md`, `run-profile.json`, `tier-policy.json`, `client.json` (CF:16-28; PLUG/scripts/tier-policy.mjs:24). `api-tokens.yaml` (this repo) is credential data, not workflow.

## 1. `project`
- Keys: `rootId` (string, depth-0 item id; hooks accept 1-64 letters/digits/hyphens), `name` (display only). SCHEMA:77-84; CF "Project Scoping".
- Expresses: which root the file is pushed to; a pushed `rootId` differing from the target is rejected unless `force`. A server file whose `project.rootId` names a depth-0 root is imported as that root's config under per-root rules (CR:327-331).
- Readers: server (mismatch guard, import retarget); plugin hooks/skills (scope session output).

## 2. `work_item_schemas.<type>` (note schemas)
Selected by item `type` only (tags never). Exact, case-sensitive. A create/retype naming a type neither layer declares fails `schema_violation` when any layer declares types (CF "Matching Rules").
- Keys (SCHEMA:104-113): `lifecycle`, `default_traits`, `notes`, `seats`.
- `lifecycle`: `auto` (default; parent cascades terminal when all children terminal; a parent in `review` never cascades), `manual` (cascade and reopen cascade suppressed), `permanent` (never auto-terminates or reopens), `none` (lifecycle-free: no role, no transitions, no `blocks` edges, no seats, no `required: true` notes, never in next/blocked). CF:209-222; CR:379-422. `auto-reopen` removed.
- Phases are fixed: roles `queue` -> `work` -> `review` -> terminal (SCHEMA:`role` enum). Review is skipped when the resolved schema has no `role: review` notes (CF "Phase Flow"). Gate: required notes of the current phase block `start` (advance out of it).
- `default_traits`: list of trait names applied to every item of the type; each must name a trait defined in the document or server config (CR:253-257).
- Other structure the type can express: any number of types; a `default` type; `notes: []` for gate-free types (this repo: `container` permanent, `default`).
- Readers: server (gates, resolution, pins); plugin skills read via `items_read schema`/`context`/`config effective`.

### 2a. Note entry (`notes[]`, also in trait `notes`) SCHEMA:126-161
- `key` (required, string), `role` (required: queue|work|review), `required` (bool, parser default false), `description` (string), `guidance` (string; served as `guidanceKey` reference, read via `items_read schema`), `skill` (string or non-empty list of distinct strings; served as `skillPointer`/`skills`), `skill_mode` (`invoke` default | `method-on-diff`; only with `skill`), `maxLength` (int >=1), `seat` (owning seat name), `independent_of` (list of seat names), `shared` (bool, default false), `lane` (review notes only: `output` verdict|findings, `after` key or list).
- Expresses: which notes each phase's gate requires; who should fill them (seat); a method to follow (skill) and project instructions (guidance); body length cap; ownership exemption (`shared`); review lane ordering/findings-only lanes; authoring-independence attestation (`independent_of`).
- Merge by key (CF "Trait Merge Semantics"): base notes first, then trait notes in trait order, one entry per key; content from first declaration; `required` ORs (a trait can tighten, not loosen); a trait cannot change a base note's role. Changing a key orphans existing notes.
- `skill` doubles as a rule pointer: a value that is also a served rule key is fetched via `config rules` op as rule text and reaches the owning run-wave seat as a rule (CF:147-160). Exact-name rule: plugin skills qualified `task-orchestrator:x`, project/built-in skills bare (CF:129-145).
- `maxLength` enforced per `note_limits.mode`; a body over 65536 UTF-8 bytes always rejected (CF:921). A key a schema declares must be written with the schema's role (`schema_violation`).
- Ownership: a note is owned by the (principal, seat) pair that wrote it; other pairs get `note_owned_by_other` unless `shared: true` (CR:65-71). Enforcement of seat lanes only inside open/paused runs (see 4).
- Readers: server (gate, maxLength, shared, independent_of, lane shape); plugin: `skill-enforcement.mjs` (hints/denies note upsert without the skill, reads `config effective` + `items_read schema`), `seat-skill-hint/gate`, run-wave/review-wave (`skill_mode`, `lane`), run-planner (seat/notes -> stages).
- Not checked: that `lane.after` keys, `skill` names, or `seat` names exist (`seat` naming an undeclared seat or one in another phase files the note under `unowned`).

## 3. `traits`
SCHEMA:114-125. A trait is a named bundle; applied per item via the item's `traits` array and the schema's `default_traits`.
- Dimensions: `notes` (2a), `resources` (7), `dispatch` (6), `seats` (4), plus text `description` and `applies_when`.
- `applies_when`/`description`: prose only, trimmed, served in `config effective`, never evaluated by the server; the plugin `create-item` skill infers a trait only from `applies_when` text (CF:514-525). Trait choice is a planner/orchestrator act, not automatic.
- Trait order: item `traits` first, then `default_traits`, deduplicated, first position kept; one order for all dimensions (CF:247-283).
- Per-root replacement: if the root's `traits:` map contains the name, notes, seats, resources and dispatch ALL come from the root's entry; an omitted dimension is empty, not inherited; includes `description`/`applies_when` (CF:276-281, 415-421; CR:134-139).
- Create/update naming an undeclared trait fails `schema_violation` (`detail.availableTraits`).
- Resolution code: `config/internal/app/TraitResolver.kt`; `config/ConfigValidator.kt`.
- This repo's traits (`.taskorchestrator/config.yaml`): needs-migration-review, needs-api-compat-review, needs-plugin-update, needs-security-review, needs-perf-review, delegated, complex-implementation, high-stakes, reviewed, needs-test-author, session-tracked, has-carry-ins, needs-docs-accuracy-review. Real example of a trait adding notes + seats + dispatch.

## 4. Seats (`seats[]` on a schema or a trait) SCHEMA:162-185
- Keys: `name` (required, non-blank), `phase` (required, queue|work|review), `enters` (bool), `after` (names; ordering hint, not enforced), `reads_exclude` (note keys the seat should not read; process discipline only), `transitions` (subset of complete, block, hold, resume, cancel, reopen), `rules` (rule keys; grammar `^[a-z0-9][a-z0-9._-]{0,99}$`), `skills` (non-blank strings, kept verbatim). KT/config/internal/wire/YamlSchemaParser.kt:101.
- Expresses: which agent role owns entry into a phase (`enters`), which notes each seat owns (note `seat`), what each seat may send, which skills and rules it is given, ordering hints, blindness hints, per-seat dispatch (6).
- Reserved/special names: `unowned` (F3 fatal; the `missingBySeat` bucket); `orchestrator` (the one seat whose note keys a run's coordinator token may write; owns its labelled notes in any phase; exempt from "seat must be in the note's phase"). Names otherwise carry no server meaning. A `-a<n>` suffix is read by the plugin as a fix-cycle seat of its base name (PLUG/hooks/lib/seat-rules.mjs `seatRoleOf`).
- Structural errors, fatal in every mode (CF:630-653; CR:259-261): F1 two `enters` seats in one phase within a scope; F2 duplicate name; F3 `unowned`; F4 `after` cycle; F5 `enters:true` seat listing `complete` or `cancel`.
- Transitions: default `block`,`hold`; `start` governed by `enters` only (a seat token may `start` only into the phase of an `enters` seat). Enforced (`seat_forbidden`, `note_owned_by_other`) ONLY on an item that is a member of an open/paused run whose pinned schema declares seats; otherwise seats are a signal (CR:73-96).
- Merge (CF:583-619): base seats win by name over trait seats (dropped with warning); first trait wins duplicate names; order = base then traits; a resolve-time second `enters` seat is demoted, not rejected. No cross-layer merge of a schema's own seats.
- Lifecycle `none` schema may not declare seats. Advisory warning: required note with no owning seat on a seat-declaring schema. Lint: seat owning no note of its phase, unseated optional note, `rules`/`skills` entry naming something unserved or a served rule in `skills`.
- `rules`: absent = plugin defaults for the seat role; `[]` = none; declared replaces defaults only (see 9). `skills`: absent = plugin defaults; `[]` none; declared replaces, never merges (see 10); server serves it only when declared and advertises feature `seatSkills` (KT/config/ServerFeatures.kt:17). Server never enforces either; plugin hooks do (10).
- Readers: server (parse, serve, run-seat enforcement); plugin: `seat-skill-hint.mjs`, `seat-skill-gate.mjs`, run-planner/run-exec (stages), `phase-guard.mjs`.

## 5. Gates and the review phase (what the config can say about gating)
- Gate = required notes of the current phase + (when `independence.mode` != off) independence violations + resource lease acquisition at WORK entry. Required notes block `start`; `complete` evaluates all-phase `independent_of` declarations.
- Review phase exists per type only if a `role: review` note exists (schema or trait). Project policy: "never add review notes to the shipped default schema" (memory), but nothing in config enforces that.
- Review extras: `lane` (verdict vs findings-only lanes, `after` order), `skill_mode: method-on-diff` (plugin review-wave filters findings to owned files), `seats` with phase review.
- No config key for who may approve a phase transition other than seats' `transitions` (4) and credential capabilities (outside this file).

## 6. `dispatch` (trait dimension) SCHEMA:210-240
- Shape: `dispatch.<queue|work|review>` = profile `{agent?, model?, effort?}` plus `seats: {<seat>: {agent?|null, model?|null, effort?|null}}`. `effort` in low|medium|high|xhigh|max (case-sensitive); agent/model opaque strings. A phase map may carry only `seats`. A null clears the phase default for that seat.
- Precedence (CF:399-413; CR:98-109): first trait in order that has a phase profile wins whole (no field merge). Per-seat: first override in trait order wins, except a model-selecting override from a seat-only trait outranks others; winner replaces whole; result filtered to seats the schema declares for that phase.
- Surfaces: `advance` result `dispatch`, `items_read context/schema` (`dispatch`, `dispatchBySeat`), `config effective`.
- Readers: server (resolve/serve only); plugin `dispatch-hint.mjs` (PostToolUse advance), `dispatch-model-guard.mjs` (denies an Agent call without model and effort), run-planner/run-exec. Where no trait sets a field, `dispatch-policy.json` class default applies (see 12).

## 7. Resources / leases (`traits.<t>.resources`, top-level `resources`) SCHEMA:186-209
- Requirement (trait): bare key (exclusive, registry TTL) or `{key, mode: exclusive|advisory, ttlSeconds}`. Key `^[a-z0-9][a-z0-9\-_./]*$` max 128. TTL clamped [1, 86400], default registry `defaultTtlSeconds` else 3600.
- Registry (server file only; per-root rejected): `{description, maxHolders (only 1 allowed), defaultTtlSeconds 1-86400}`. Every trait resource key must be in the server registry. Reserved `budgetLimit`/`budgetWindowSeconds` rejected.
- Behaviour: gate at any transition whose target role is `work` (start, resume, cascades). `exclusive` takes a lease, contention rejects (`resource_unavailable`, retry hint); `advisory` records only in `consumedCredentials` audit. Union across traits; exclusive wins a mode conflict; first-seen TTL. Warning when 3+ traits declare one key. Release: leaving WORK, TTL expiry, or force-delete via REST. Docs rule: exclusive only on leaf types (CF:341-352; not enforced).
- Kill switch: env `RESOURCE_LEASES_ENFORCED` (startup-only, acquisition only), `current/docs/fleet-deployment.md:495`. Leases are item-keyed, one concurrent actor per item (fleet-deployment.md:1217).
- Reader: server only. This repo declares none (commented example, `.taskorchestrator/config.yaml:359-380`).

## 8. `runs`, run budgets, stalls SCHEMA:46-70
- `runs.stall_after` (int seconds 1..2147483647, default 600; pinned on the run when opened; stalled = quiet longer than this, a view only, nothing is ended). `runs.budgets.attempts_per_seat`, `runs.budgets.max_active_seats` (ints, opt-in; absent = unlimited). Resolved per field root > server > default. A run's own budget may only lower a ceiling. `budgets.tokens`/`cost_usd` removed 4.0.1: accepted with a warning, no effect (CR:165-191).
- Server behaviour (fleet-deployment.md:1407-1447): attempt over cap -> `assign` refused `run_paused`, run pauses, high-severity `attempt-cap` decision (answers `allow_one`, `skip_item`, `stop`); `max_active_seats` over -> `409 run_at_capacity`, no decision. `usage.observedTokens` is an estimate that enforces nothing.
- Readers: server only. Plugin run-planner reads a separate opt-in `runBudget` in `run-profile.json` (12).

## 9. `decision_classes` SCHEMA:86-103; CR:193-233; KT/application/decision/DecisionClasses.kt
- Name `^[a-z][a-z0-9_-]{0,63}$`. Keys: `answerers` (`human-only` | `director-or-human` | `any-actor`; a non-built-in defaults to director-or-human), `severity` (high|normal|low; default normal), `expires_after` (int seconds >=1; absent = never overdue), `on_expiry` (`escalate` only), `freeform` (bool; free-text answer).
- Built-ins: `run-budget` (director-or-human, high, never; legacy, not raised any more), `attempt-cap` (same; server-raised), `deviation` (director-or-human, normal, 14400s), `question` (any-actor, low, freeform). A built-in entry overrides field by field. `run-budget`/`attempt-cap` answerers can be raised to `human-only`, never lowered to `any-actor` (rejected at `/decision_classes/<name>/answerers`).
- Expresses: who may answer a class of decision raised by a seat or orchestrator (`runs_write raise` with `class`, 1-8 options, `dedupeKey`, `block`); overdue reading one step more urgent after `expires_after`. `on_expiry: escalate` changes only the reported severity.
- Limits: a raise naming an undeclared class fails `invalid_request /class`. On a stdio server every caller is the `local` agent principal, so `human-only` cannot be answered. Server takes no action on expiry beyond severity. No link from a note, phase or gate to a class: classes are raised by seats/orchestrator on their own initiative (no schema key makes a phase wait on a decision).
- Readers: server (raise/answer/ack authorization, overdue); plugin `decision-guard.mjs` (denies a subagent's `answer`, `ack`, `pause`, `resume`), director skill/hooks.

## 10. Rules (served rule documents, seat rule lists, classes)
- Rule text = `docs` of `kind: rule`, slug = key (grammar as above), body cap 16384 UTF-8 bytes, served verbatim by `config rules` (list / one / by `itemId`+`noteKey`). Written via `docs_write upsert` (`write-docs`). `rulesVersion` = body contentHash. Source files: `.taskorchestrator/rules/<key>.md` (this repo has 21: arbitration, closure-notes, commit-discipline, contract-change-sweep, declarations-extractor, forbidden-test-patterns, live-checks, orchestration-discipline, planning-sweeps, protocol.*, red-proofs, review-cited-claims, review-scoping, test-assertion-vacuity, test-author, test-file-ownership, test-harness.full-wiring, trait-selection, zero-warnings). CF:147-207.
- Plugin bundled rules (`PLUG/bundled-rules/`, manifest of known hashes): closure-notes, commit-discipline, orchestration-discipline, planning-sweeps, protocol.entry-seat, protocol.in-phase-seat, protocol.read-only-agent, review-cited-claims, review-scoping. Push policy: absent key pushed; server copy matching a known older hash overwritten; unknown (customized) copy never touched; a workspace `rules/<key>.md` wins over the bundled copy.
- Seat rule classes, one table `PLUG/hooks/lib/seat-rules.json` (roles planner, implementer, test-author, reviewer, declarations-extractor, work-seat, queue-seat, read-only): (a) protocol rule by role (`protocol.in-phase-seat`, `protocol.entry-seat`, `protocol.read-only-agent`), tool mechanics, always first, never removable, another role's `protocol.*` key dropped and reported; (b) plugin-chosen (`commit-discipline` for implementer, test-author, work-seat); (c) seat defaults only when the seat declares no `rules`: planner [planning-sweeps, review-cited-claims], implementer [closure-notes], reviewer [review-scoping, review-cited-claims], declarations-extractor [declarations-extractor], test-author/work-seat/queue-seat/read-only []. A declared `rules` list replaces (c) only. Role is derived from seat name / phase / `enters` / output by `seatRoleOf` (hooks/lib/seat-rules.mjs).
- A project may serve its own text under a protocol key (copy stops receiving plugin updates; audit reports it project-authored). Rules are bound to a seat by `rules:` or to a note via `skill:` naming a rule key. A rule is never enforced by the server; the plugin hands it to the seat (hint `SEAT RULES:`; run-planner reports a declared but unserved key as degradation, drops an unserved default silently).
- Readers: server (store/serve); plugin config-sync, seat-skill-hint, run-planner, served-rules.mjs.

## 11. Seat skill defaults (`PLUG/hooks/lib/seat-skill-defaults.json`) and skill binding
- Data: planner [task-orchestrator:planning-standards, task-orchestrator:designing-modules]; implementer [task-orchestrator:implementation-standards]; reviewer [task-orchestrator:review-method]; test-author [task-orchestrator:test-authoring]. Applies to a seat only when it declares no `skills`; a declared list (even `[]`) replaces, never merges. Declared lists honoured only if the server advertises `seatSkills`; otherwise defaults are hint-only (CF:527-535; hooks/lib/seat-skills.mjs).
- Enforcement (plugin, subagents only): `seat-skill-hint.mjs` writes a required record and hints once per agent; `skill-record.mjs` records loaded skills; `seat-skill-gate.mjs` denies edit calls (and role-matching `notes_write` upserts) of a subagent until the seat's skills are loaded; `skill-enforcement.mjs` denies a note upsert until its note `skill` is loaded; no release (an uninstalled skill ends in a report). Denials need the item's schema view to list `seatSkills`.
- A skill is bound by a seat's `skills:` or a note's `skill:`/`skill_mode`; no other channel (CLAUDE.md layer table, now [0018](../decisions/0018-content-placement.md)).

## 12. Run-profile, dispatch-policy, tier policy (plugin files, not server config)
- `.taskorchestrator/run-profile.json` (this repo, 2013 bytes), read by `PLUG/scripts/run-planner.mjs` + `run-planner-lib.mjs` (snapshot `profile`), shape validated at `run-planner-lib.mjs:325-430`. Keys: `shell` (default bash), `verify[]` ({name, command, seats, appliesTo[], ownedTestsPattern}; `<worktree>`, `<ownedTests>`, `<scratchpad>` placeholders), `searchScope[]`, `worktreeRoot` (default `.claude/worktrees`), `branchPrefix` {shared default `feat/`, perItem default `fix/`}, `maxItems` (default 5), `appendOnlyFiles[]` (repo-relative patterns), `review` ("handoff"|"generic" or {mode, skill, step, onFail: stop|fix-cycle}), `postRun[]` ({step, when: post-run|post-merge, rule, command?, appliesTo?}; unique step names), `knownRed[]` ({test, reason, expires YYYY-MM-DD or tracking}; expired draw a warning), opt-in `runBudget` ({maxActiveSeats, ...}; tokens/costUsd ignored with warning), `defaultModels` ignored with warning.
- `PLUG/dispatch-policy.json` (plugin data, no project override found for `classes`): `models` [opus, sonnet, haiku, fable]; `escalation` {ceiling xhigh, levels low..max}; `classes` (coordinator, planning, planning-high-stakes, implementation, implementation-complex, test-authoring, review, review-high-stakes, research, bulk, extraction, audit-review, audit-verify-high, audit-synthesis), each `{model, effort, use}`; `tiers` {thresholds directMaxFiles 2, delegatedMaxFiles 10; rows Direct/Delegated/Parallel; forceUp/forceDown}. Validated by `scripts/lib/dispatch-policy.mjs`. Effort escalates one level per failed review/test fix, capped at `escalation.ceiling`.
- `.taskorchestrator/tier-policy.json` (project scope only): overrides `tiers` only (keys `thresholds`, `rows`, `forceUp`, `forceDown`; unknown key errors); read by `scripts/tier-policy.mjs`, surfaced by the `orchestrate` skill.
- A trait's `dispatch` outranks policy class values field by field only where it sets one (6).

## 13. `extensions.claude-code` (plugin settings; server stores, never reads) CF:761-885
- `orchestration.mode`: `workflow` (default: tiers + model table + dispatch rules) | `schema` (no tiers/model table; schema drives) | `off` (all orchestration hooks silent). Unrecognized -> workflow. Read by orchestration-context, dispatch-model-guard, dispatch-hint, pre-plan, post-plan hooks.
- `retrospective.mode`: `nudge` (default) | `dispatch` | `off`; `retrospective.minItems` (int >=0, default 3; older name `dispatchThreshold`, this repo still uses it); `retrospective.boundary` `scope` (default) | `run`; `retrospective.github_feedback.enabled` (default false), `.repo` (default jpicklyk/task-orchestrator); `retrospective.baselines.<name>` (map of dated scalar figures echoed by `run-planner retro-metrics`). Read by retro-trigger, retro-backstop, retro-subagent-stop (`hooks/retro-lib.mjs`), session-retrospective and review-proposals skills, `scripts/lib/retro-metrics.mjs`.
- Parsing limits: `extensions` and `claude-code` must be block mappings (hooks ignore flow form); hand-rolled YAML reader (`hooks/orchestration-lib.mjs`, `yaml-lite.mjs`). Edits apply on next hook fire; the user-level `~/.taskorchestrator/config.yaml` `extensions.claude-code` applies wherever no project config exists. No `cooldownMinutes`, `headless`, or `actor_attribution`. Anything else under `extensions.*` is stored and unread.
- Director mode is not a config key: it is entered by skill and enforced by `director-guard.mjs`; MCP profile via env `MCP_TOOL_PROFILE=director` (fleet-deployment.md:36).

## 14. Other top-level server-owned keys
- `note_limits.mode`: `warn` (default; accept with warning) | `reject` (`note_too_long`). Per-root honourable. SCHEMA:29-33.
- `status_labels`: map trigger -> string label; `null` clears. Built-in defaults: start `in-progress`, complete `done`, block `blocked`, cancel `cancelled`, cascade `done`; resume/hold/reopen none (KT/config/StatusLabelService.kt:55-61). Trigger keys are not validated against a list (parser: any string key; `config/internal/wire/YamlSchemaParser.kt:1857`). Per-root per-trigger override.
- `independence`: `mode` off|warn|reject (default warn; quote `"off"`), `require_verified` bool (default false). Evaluates note-level `independent_of`; findings `same_actor`, `missing_actor`, `unverified`; first line `independence: temporal-only` waives only `same_actor`, only when the declaring note was created after the conflicting ones. Per-root block replaces whole. Attestation check, not enforcement (CF:938-1066).

## Known limits (one line each)
- Phases are exactly queue, work, review, terminal; no custom phases or names (SCHEMA `role` enum).
- `lifecycle` has four fixed modes; a schema can not define its own transition graph or cascade (SCHEMA:105; CF:209).
- Note content constraints are only `required`, `maxLength`, `role`; no field types, patterns, or enums on a note body (SCHEMA:126-161).
- Gate failure conditions are only: missing required notes, independence violations, resource lease contention; no custom predicates (CF "Phase Flow"; CR:253-257).
- Trait `applies_when` and `description` are text; the server never evaluates them; trait selection is by an agent/skill (CR:134-139).
- A per-root trait replaces the server trait entirely; no per-dimension inheritance (CF:276-281).
- Trait notes can add or tighten (`required`) but never loosen a base note or change its role (CF:262-264).
- A per-root `independence` block replaces the server's whole and can weaken the gate (CF:1050-1056).
- Seat enforcement exists only inside an open/paused run on an item whose pinned schema declares seats; outside runs seats are a signal (CR:84-86).
- `after`, `reads_exclude` are hints; seat `rules`, `skills` and their existence are not enforced or checked by the server (CR:141-163).
- Seat skill/rule enforcement is plugin-side, subagents only, and needs the `seatSkills` feature in the item's schema view (hooks/README.md:76-79).
- `seat_forbidden` and note ownership are keyed on (principal, seat); over stdio or no-auth every caller is the one `local` principal (CF:1022-1030; CR:231-233).
- Resources: only `maxHolders: 1`, server-file registry only, leases item-keyed, exclusive on containers not blocked but documented as wrong (CF:310-352).
- Run budgets are only `attempts_per_seat` and `max_active_seats`; token and cost budgets removed in 4.0.1 (CR:177-183).
- Decision `on_expiry` only escalates reported severity; the server takes no other action (CR:204).
- A `human-only` decision class cannot be answered on a stdio server (CR:231-233).
- `dispatch.effort` is a string passed through; higher-ranked environment/org caps outrank it (CF:442-465).
- Model, effort `classes` live in plugin `dispatch-policy.json`; only `tiers` have a project override file (`tier-policy.json`) (scripts/lib/dispatch-policy.mjs:84-86, 145-156).
- Config edits to the server file need a restart; per-root pushes hot-reload; in-flight items keep their pin until `repin` (CR:333-366).
- `config-sync` needs the REST API URL and `project.rootId`; without either nothing is pushed (CF:12).
- `extensions.*` is opaque to the server and parsed by a minimal YAML reader in the hooks; block form required (CF:765).
- Two skill systems: a note's `skill:` / seat `skills:` is the only schema-level skill binding (CLAUDE.md layer table, now [0018](../decisions/0018-content-placement.md)).

## Things a journey might want that I could not find any config for (looked, confirmed absent)
- Custom phase names or more than three work phases: SCHEMA `$defs.role` enum and `phase`/`role` fields; CF "Phase Flow".
- A conditional or computed gate (required-if, per-priority, per-tag): SCHEMA `noteEntry` has no such keys; CF "Matching Rules" says tags are never consulted.
- A note body schema (typed fields, regex, enumerated verdicts): SCHEMA `noteEntry` (only `maxLength`); `grep` of `config.schema.json` for pattern on note bodies found none.
- A human approval or sign-off bound to a phase or note (gate waits on a decision): `SCHEMA`, `ConfigValidator.kt`, `YamlSchemaParser.kt` have no link from schema to a decision class; decisions are raised at runtime by seats (CR:193-206).
- Due dates, SLAs, deadlines, timers other than `stall_after` and `expires_after`: `grep -iE "dueDate|due_date|sla|webhook"` over `current/src/main/kotlin` hit no config key.
- Outbound notifications or webhooks on transitions: same grep, and `config.schema.json` top-level keys; only SSE/MCP event streams exist (`event/internal/wire/ApiEvent.kt`).
- Child/parent type constraints (allowed child types, required children): `grep -iE "allowed_children|child_types|allowedChild|parent_type"` over `current/src/main` and manage-schemas found nothing.
- Per-project override of dispatch-policy `classes`/`models`/`escalation`: only `tier-policy.json` override exists (`scripts/lib/dispatch-policy.mjs` OVERRIDE_KEYS thresholds, rows, forceUp, forceDown; no override file in `.taskorchestrator/`).
- Token or cost budgets, prices: removed 4.0.1 (CR:179); memory records "no rate tables or prices".
- Automatic trait application by rule (e.g. by path or tag): `applies_when` is text-only (CR:136-138); `default_traits` is the only automatic channel.
- Multi-holder resource leases: `maxHolders > 1` rejected (CF:320).
- Per-seat tool or file-access restrictions in config: `reads_exclude` is a hint; blindness is a process discipline (CF:536); agent tool lists live in plugin agent files, not config.
- Per-user/role permissions in the YAML: capabilities and scope belong to credentials (`API_AUTH_MODE`, token capabilities such as `write-config`, `runs`, `decide`; fleet-deployment.md:501), not to config.yaml.

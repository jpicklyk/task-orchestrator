# J8 Keep a knowledge base

A first-class journey beside J1-J7: a user on the knowledge endpoint captures, relates and retrieves notes and documents. No phases, no sign-off, no builds. Served as the `knowledge` tool profile (HTTP `/mcp/knowledge`, stdio `MCP_TOOL_PROFILE=knowledge`): `items_read` without `context`, `next`, `blocked`, `history`; `items_write` without `create_tree`, `complete_tree`, `repin`; `notes_*`, `deps_*`, `docs_*`. No `advance`, `config`, `runs_*` or `report`.

Abbreviations. PLUG = `claude-plugins/task-orchestrator/`. KT = `current/src/main/kotlin/io/github/jpicklyk/mcptask/current/`. AR = `current/docs/api-reference.md`. CR = `current/docs/config-reference.md`. SD = `current/docs/search-and-discovery.md`. FLOOR = `deploy/global-config/.taskorchestrator/config.yaml`. Line numbers are as of main `982b7b7c5`.

---

## 1. The journey

**Persona.** Someone who reads, researches or learns for a living and wants one place where what they learned stays findable, connected and current; they talk to Claude, never to the tool.

**Dials.** Scope: one entry to the whole base (a few hundred to a few thousand entries under one root). Certainty: not considered (nothing is built, so nothing is uncertain in the J6 sense). Autonomy: high for capture, link proposals and retrieval; the user confirms link sets, merges and anything that deletes. Subject: knowledge (entries, sources, synthesised pages), not code.

Stage format: `[moment] name | what the user says | ideal experience | what serves it today | fit`.

| # | Stage | What the user says | Ideal experience | Today (tools, skills, hooks) | Fit |
|---|---|---|---|---|---|
| 8.1 | [S] Set up on the knowledge endpoint | "I want somewhere to keep what I learn about soil ecology." | Setup asks the domain first (reading notes, research, reference, personal wiki), then two or three questions (documents as well as notes? one base or one per project?). It picks the tested knowledge-base template, registers only the knowledge entry, creates the anchor, pushes the config over REST, records which template it applied, and says one line: "Knowledge base ready: entries, topics, sources. Say 'save this' to capture." | init (fails or misreports on a knowledge connection, registers orchestration and director only: KL1, KL2), quick-start (a build tour: KL7), configure-server, manage-schemas advisor (no knowledge profile: KL6) | missing |
| 8.2 | [I] Capture | "Save this: mycorrhizal networks move carbon between trees." / "File this article under soil carbon." | The agent writes one entry (content, source), proposes its topic, checks for duplicates across items, notes and pages, and answers "Saved as X under Soil carbon; may relate to A, B." A long document is kept as a source and split into entries on request. | `items_write` `create`, `notes_write` `upsert`, `docs_write` `upsert`; create-item routes it as a bug or feature through orchestration-only calls (KL16); the document-to-notes splitter is orchestration-only (KL24) | partial |
| 8.3 | [I] Link | "This contradicts what I saved on fungal decline." | The agent proposes links from `related` hits, each with a relation word (supports, contradicts, supersedes, source of); the user confirms the set; edges or `[[item:<id>]]` references are written. | `deps_write` `relates_to` (the default `blocks` is refused: KL23), `[[...]]` links parsed from note and page bodies (`KT/knowledge/internal/app/link/LinkParser.kt:35-44`), `items_read` `related`; dependency-manager speaks in blockers (KL18) | partial |
| 8.4 | [L] Find | "What do I know about carbon transfer?" | An answer composed from hits, each claim citing its entry id or page slug, one hop of related entries, and a line on what the base does not cover. | `items_read` `search` (items, notes, documents; stemmed and substring, fused), `related` (edges, links, tags, text; reasons per hit, SD:146-170), `docs_read` `get`/`links` (incoming = backlinks, `KT/knowledge/internal/app/DocHandlers.kt:206-209`); no skill. Gaps: rule documents in hits, no page as `related` subject, no multi-hop walk (KL5, KL23, KL26) | good |
| 8.5 | [G] Synthesise | "Write up what I know about soil carbon." | The agent writes a page that cites entries by `[[item:<id>]]`; asking again writes the next version, and the history shows what changed. The user's own write-up method can be bound once. | `docs_write` `upsert` (versioned, links parsed), `docs_read` `history`; a note `skill:` can bind a method on an item; nothing binds one to a page; no skill | partial |
| 8.6 | [L] Revisit | "What did I add this month? What haven't I looked at in a year? What did I mean to write but never did?" | A short digest: recent entries, entries untouched past a threshold, orphans (no links either way), dangling `[[refs]]` (pages wanted but never written), the biggest topics. | `items_read` `list` (`createdAfter`, `modifiedBefore`, `sort: modifiedAt`), `overview`, `docs_read` `list`; work-summary leaves lifecycle-free items out and calls orchestration-only selects (KL17); no root-wide orphan or dangling read (KL28) | missing |
| 8.7 | [M] Prune and reshape | "Merge these two. Archive the old reading notes. Add a 'person' kind of entry." | The agent proposes merges, archives and retags as one table and applies them on a yes; it never hard-deletes without a stated yes and prefers archive or a tag. A template change goes through the same file and push, with history and rollback. | `items_write` `update`/`delete` (delete is permanent; items have no archive), `docs_write` `archive`, tags; manage-schemas needs the `config` tool (KL19); batch-complete skips lifecycle-free items (KL19); type flips need `repin` (KL25) | partial |
| 8.8 | [E] Learn | "How does this base work? What kinds of entry can I make? How do I link two notes?" | The agent explains the applied template (its types, notes, link syntax and tags) and the four or five words the user needs (entry, topic, source, link, page), from the template record and the glossary. | Nothing on the knowledge profile: no config read, `items_read` `schema` needs a type name (KL20); quick-start teaches phases and plan mode (KL7); the glossary has no entry for the concepts this journey runs on (KL29) | missing |

Fit totals: good 1, partial 4, missing 3, too-tactical 0. The tools carry most of the journey; what fails is setup, the plugin text that assumes building, and the absence of any knowledge-shaped skill.

---

## 2. Leaks: where the development-centred design breaks or degrades this journey

Severity: **breaks** (the stage cannot be done as designed) or **degrades** (it works, with noise, cost or a workaround). "Holds" marks a check that found no leak.

### Setup

- **KL1 init cannot run on a knowledge connection (breaks 8.1).** Step 1 reads the health view with `items_read` `context` and stops when "the call errors", then requires `runs_read` and otherwise declares the server 3.x (`PLUG/skills/init/SKILL.md:33-43`). Both are absent on the knowledge profile (AR:41-45), so init either stops or tells a 4.x user to upgrade.
- **KL2 init registers orchestration and director, never knowledge (breaks 8.1).** Step 2 locates the orchestration entry and adds `mcp-task-orchestrator-director`; the only flag is `--no-director` (`PLUG/skills/init/SKILL.md:4,45-57`); the end report names only those two (`:314`). The plugin already recognises a knowledge entry by its URL (`PLUG/hooks/lib/mcp-registrations.mjs:95,103-121`), so the gap is in the skill, not the library. A knowledge user also gets a director entry it has no use for (no runs, no decisions on its profile).
- **KL3 The anchor is a role-bearing build type (degrades 8.1, 8.6).** P3 creates the anchor as `project` (`PLUG/skills/init/SKILL.md:117-125`), which the floor declares `lifecycle: permanent` (FLOOR:90-91), so the root sits in `queue` forever; the floor's grouping type `container` is `manual` (FLOOR:80-81) and would also sit in `queue`, with no `advance` on this profile to move either. The type check before the create uses `config` `effective`, skipped on a knowledge connection (`:115`).
- **KL4 Setup may not apply steering (breaks the settled "steering is part of the template").** P6 says "Do not write the `extensions:` block yourself in project mode" and announces `workflow` as the default (`PLUG/skills/init/SKILL.md:190-194`; default in `PLUG/hooks/orchestration-lib.mjs:16-17`). A knowledge base set up by init is steered as a build orchestrator until the user edits the file.
- **KL5 Build rules are pushed into every root, every session (degrades 8.4).** Init seeds the bundled rules (`PLUG/skills/init/SKILL.md:272-274`) and config-sync pushes them again on every SessionStart with no opt-out (`PLUG/hooks/config-sync.mjs:466-468,666`): `commit-discipline`, `review-scoping`, `planning-sweeps`, `protocol.*` and others (`PLUG/bundled-rules/`). They become `rule` documents of the knowledge root, appear in `docs_read` `list` and as document hits of `search`, which has no document-kind filter (AR:775-790: `kinds` is item, note or document).
- **KL6 There is no knowledge template to pick, and setup never asks (breaks 8.1).** The advisor's work-shape dimension lists only things that flow through phases (`PLUG/skills/manage-schemas/references/workflow-patterns.md:29`); the ten profiles have no knowledge base (`:36-47`; research-pipeline is phased and reviewed); the lifecycle selection table omits `none` (`:59-66`); the dispatch-model table assumes `advance` (`:68-74`); the template path offers only Feature Implementation and Bug Fix (`PLUG/skills/manage-schemas/references/create-workflow.md:104-137`). init and quick-start never call the advisor (critique, R21).
- **KL7 quick-start is a build tour (breaks 8.1, 8.8 on a knowledge connection).** It lists the knowledge profile (`PLUG/skills/quick-start/SKILL.md:21`) and then calls `context` (`:52`), builds a Design/Implement/Test tutorial with `blocks` edges through `create_tree` (`:133`), and teaches `advance` (`:170-186`). Every one of those is absent on the knowledge profile.
- **KL8 The only global floor is the build process floor (degrades, minor).** configure-server mounts the floor (`PLUG/skills/configure-server/SKILL.md:138-159`), whose types are `agent-observation`, `improvement-proposal`, `session-retrospective`, `container`, `project`, `retrospective-trend` (FLOOR:23-96). They show in every `availableTypes` answer of a knowledge root.

### Hooks

Hooks key on the MCP server key containing `task-orchestrator`, never on the profile (`PLUG/hooks/hooks-config.json:59,84,116,155,165,175,200`), and SessionStart hooks key only on a located config and its steering.

- **KL9 session-start always tells the session to use orchestration tools (degrades every stage).** `BASE_GUIDANCE` (advance, `context`, `next`) is emitted with any located config, whatever the steering (`PLUG/hooks/session-start.mjs:25-30`); its own hedge admits another profile "may not expose them" (`:30`). The Project Scope section says to pass `ancestorId` on `context`, `next` and `blocked` (`:92`).
- **KL10 orchestration-context injects a build orchestrator (degrades every stage; breaks 8.2 routing).** The workflow core sizes work into Direct, Delegated or Parallel, says "Materialize before implement", shows `advance`, `context` and `create_tree` as core calls, routes to run-wave, and tells the session to invoke create-item proactively for a "bug, feature idea, tech debt item" (`PLUG/hooks/orchestration-context.mjs:27-41,53-57,67-99`). The schema core still says "advance each item through exactly the phases its schema defines" (`:101-140`). It is gated only by a located config and `mode` (`:152-160`); there is no knowledge variant, only `off`.
- **KL11 Plan mode turns a reorganisation into implementation (degrades 8.7).** In any mode but `off`, entering plan mode orders the pre-plan skill before "any codebase exploration", and approving a plan orders the session to "materialize MCP items and dispatch implementation" (`PLUG/hooks/pre-plan.mjs:24-33`, `PLUG/hooks/post-plan.mjs:24-31`). "Plan how to restructure my notes on X" is a normal knowledge request.
- **KL12 The dispatch guard applies build dispatch policy (degrades 8.5).** In `workflow` and `schema` modes every `Agent` call without `model` and `effort` is denied (`PLUG/hooks/dispatch-model-guard.mjs:9-12`), and the values come from build classes (`PLUG/dispatch-policy.json`; orchestration-context MODEL_LINE `:47`). A background agent that reads twenty sources for a synthesis is fine to guard, but it has no class of its own.
- **KL13 Note and read hooks fire on knowledge tool names but stay silent (holds).** `notes_write` runs skill-enforcement and seat-skill-gate; `items_read` runs phase-guard-record, seat-skill-hint and director-guard (`PLUG/hooks/hooks-config.json:59-71,116-124,175-187`). seat-skill-hint acts only on a `context` read or an `advance` (`PLUG/hooks/seat-skill-hint.mjs:4-6`), phase-guard-record only in a subagent on those same calls (`PLUG/hooks/phase-guard-record.mjs:1-24`), and a `lifecycle: none` schema may declare no seats (`PLUG/skills/manage-schemas/references/config-format.md:94`), so seat skills and seat rules never apply. skill-enforcement hints only for a note that declares `skill:`, after one REST `config` `effective` read per session (`PLUG/hooks/skill-enforcement.mjs:6-11`); that is the right binding channel for a synthesis method. Cost: those REST reads, nothing injected.
- **KL14 The retrospective is run-shaped (degrades 8.7, 8.8; cost).** The Stop hook evaluates scopes over REST on every turn once a rootId exists (`PLUG/hooks/retro-backstop.mjs:66-71`; `PLUG/hooks/retro-lib.mjs:432-453`) and finds nothing, because a knowledge session opens no runs and the knowledge profile has no `runs_write`. session-retrospective analyses closed runs. A knowledge base has no improve loop of its own; `retrospective.mode: off` removes the per-turn read.
- **KL15 Seat tables name build roles only (holds, inert).** `PLUG/hooks/lib/seat-rules.json` (planner, implementer, test-author, reviewer, declarations-extractor, work-seat, queue-seat, read-only) and `PLUG/hooks/lib/seat-skill-defaults.json` (planner, implementer, reviewer, test-author). With no seats on a lifecycle-free schema nothing applies; recorded so a future "curator" seat is not added to serve this journey.

### Skills

- **KL16 create-item files knowledge as build work (breaks 8.2).** Kinds are bug, feature, tech debt, observation, action item (`PLUG/skills/create-item/SKILL.md:17`); containers are Bugs, Features, Tech Debt, Action Items (`:41-50`); types come from `config` `effective` (`:58-60`, orchestration-only, AR:64); the create is `create_tree` (`:86-96`, orchestration-only, AR:318); it drafts "queue notes" (`:84`); the dedup search covers items only (`:76-79`), so a note or page that already says the same thing is missed. Its triggers ("track this, log this", `:3`) and the orchestration core's "invoke proactively" (KL10) route a "save this" here.
- **KL17 work-summary shows a knowledge base as empty (breaks 8.6).** By design "the dashboard covers role-bearing work" and lifecycle-free items are left out (`PLUG/skills/work-summary/SKILL.md:135`); it reads `context` and `next` (`:53,65`, orchestration-only) and runs (`:87-97`). Its triggers include "overview", "show items" and "where did I leave off" (`:3`), which a knowledge user also says.
- **KL18 dependency-manager speaks in blockers (degrades 8.3).** "Why can't this start", unblock, blockers (`PLUG/skills/dependency-manager/SKILL.md:3,9`); `blocks` is the default type (`:66`); its graph view walks `blocks` only (`:53`). It does say a `blocks` edge to a lifecycle-free item is refused (`:76`).
- **KL19 Maintenance skills assume phases or the config tool (degrades 8.7).** batch-complete completes or cancels through `complete_tree`, which leaves lifecycle-free items out (`PLUG/skills/batch-complete/SKILL.md:65`); manage-schemas declares the `config` tool orchestration-only and uses it for every read, validate, push and rollback (`PLUG/skills/manage-schemas/SKILL.md:11,86-96,174-221`). On a knowledge connection a template change is a file edit plus config-sync's REST push, with no in-session validate.

### Server and profile contract

- **KL20 A knowledge client cannot discover its own types (breaks 8.8, degrades 8.2).** `config` is orchestration-only (AR:64, AR:1421); `items_read` `schema` needs a `type` or an `itemId` (AR:764-773). The only way to list the declared types is a failed `create`'s `detail.availableTypes`.
- **KL21 Create documents a build lifecycle (degrades 8.2).** The op reads "Create an item in queue" (AR:265) and offers `priority` (default medium), `complexity` and `requiresVerification`; the lifecycle-free exception is only in the purpose text (AR:87-89). Any role-bearing type created over the knowledge profile stays in `queue` for good, because the profile has no `advance`, and it inflates queue counts on every orchestration view.
- **KL22 Every note is filed under a phase (degrades, vocabulary).** `notes_write` `upsert` requires `role` queue, work or review (AR:1020); a schema note entry requires `role` from the same enum (`current/src/main/resources/config/config.schema.json:85,128`). A knowledge entry's "content" or "source" note must be declared `role: queue`.
- **KL23 Edges are blocking-first; the graph walk ignores relations (degrades 8.3, 8.4).** `deps_write` `create` defaults to `blocks` (AR:1164), which a lifecycle-free endpoint refuses (CR:398-399); `deps_read` `graph` walks `blocks` edges only and ignores `relates_to` (AR:1098). The only relation word is `relates_to`, with no kind (supports, contradicts, supersedes). Backlinks for items are dependency edges only (SD:134-144); note-body links reach an item only through `related` (`linked_from`, SD:155).
- **KL24 Splitting a document into entries is orchestration-only (degrades 8.2).** Heading-addressed sections and the `derived_from` link exist (`KT/knowledge/MarkdownSectionSplitter.kt:3-9`, `KT/knowledge/LinkService.kt:159`) but are reached only through `create_tree` with a `docRef` (AR:318), and only from a `plan` document.
- **KL25 Reshaping a type needs an op the profile lacks (degrades 8.7).** Only a `type_change` update and `repin` move an item between role-bearing and lifecycle-free (CR:408-414, AR:126-127); `repin` is orchestration-only (AR:388). A knowledge client can flip an item only by changing its type.
- **KL26 Retrieval gaps (degrades 8.4, 8.6).** `related` takes an item only, so a page cannot be the subject (AR:807-817); `search` has no document-kind filter (AR:775-790); `docs_read` `list` has no tag, parent or date filter and documents live only at the root (AR:1709-1719); notes have no history (`notes_read` is `get` and `list` only, AR:924-940) while pages do (AR:1721-1731).
- **KL27 The knowledge profile describes itself as work (degrades, vocabulary).** Its initialize instructions say "work items, their notes, dependencies and versioned documents, without phase transitions or runs" (`KT/bootstrap/CurrentMcpServer.kt:652-658`); `items_write` says "Create, update and delete work items" (`KT/itemwrite/ItemWriteTools.kt:12,:15`).
- **KL28 No root-wide read of orphans or dangling references (degrades 8.6).** Unresolved `[[refs]]` are visible per document (`docs_read` `links`, `toRef` without `to`, AR:1733) and nowhere across a root; orphans need one `related` call per entry.

### Glossary

- **KL29 The glossary has no words for this journey and frames the tool as building.** The opening line is "Work items move through gated phases" (`GLOSSARY.md:3`). Building item makes building "the default" and its opposite is "a question to be ruled, or a container" (`:171-176`); a knowledge entry is neither, it is off the build axis. Refined is defined by queue notes (`:178-184`), Acceptance ends in "shipped or sent back" (`:186-191`), Board is "work across runs" (`:70-76`). Lifecycle-free items appear only under Building item's "Distinct from" (`:175`), and there is no entry for a link (`[[...]]` reference) as distinct from a dependency edge. Candidate entries for the defining-project-terms test (not coined here): *Lifecycle-free item* (in code `lifecycle: none`) and *Link* (a parsed reference, distinct from a `relates_to` edge).

### Is `lifecycle: none` the right base?

Yes, and on the knowledge profile it is the only coherent one. What it removes (CR:381-406): role, transitions, claims, cascades, `next` and `blocked`, required notes, seats, and `blocks` edges. What it keeps: optional notes with guidance, `maxLength` and `skill:`, tags and traits, children at any depth, `relates_to` edges, `[[...]]` links, search, `related`, overview counts (under no role). A role-bearing type cannot move on this profile (KL21), so it would lie in `queue` and count as backlog wherever an orchestration view looks.

Costs, each a requirement below: losing `blocks` is right (nothing in a knowledge base holds anything back), but `relates_to` carries no relation word and the graph walk ignores it (R38, KL23); an entry has no state (draft, evergreen, archived), so maturity is a tag convention (R42); note roles stay phase words (R36); the floor's `project` anchor is still role-bearing (KL3).

### Retrieval coverage

| Need | Served by | Verdict |
|---|---|---|
| Free-text recall across entries, notes and pages | `items_read` `search`, `matchMode` auto (stemmed + substring fused), `snippet` | good; rule documents pollute hits (KL5) |
| "What else touches this?" | `related` (edges, links, tags, text, with reasons) | good for items; not for a page (KL26) |
| Backlinks | `docs_read` `links` `direction: incoming` for pages; `related` `linked_from` for items | partial: two shapes, items have no direct backlink read |
| Walk the web of ideas | `deps_read` `graph` | missing: `relates_to` ignored (KL23) |
| Recent, stale | `items_read` `list` with `createdAfter`, `modifiedBefore`, `sort` | good for items; pages have no date filter (KL26) |
| Orphans, wanted pages | nothing root-wide | missing (KL28) |
| How an entry changed | `docs_read` `history` for pages; none for notes | partial (KL26) |

---

## 3. Config requirements (continuing at R35)

Grades as in journey-config-fit.md: **E** expressible, **W** awkward, **X** impossible, **not-config** (a tool, skill or setup property, named so it is not lost). Stage ids are 8.n.

| # | The config must be able to say | Stages | Grade | Evidence |
|---|---|---|---|---|
| R35 | Entry types that have no lifecycle, carry notes, tags, links and children, and never appear in queue, `next`, blocked or completion views | 8.2, 8.6 | E | `lifecycle: none` (CR:381-406; config-format.md:218); counted under no role (CR:403) |
| R36 | That an entry's notes belong to no phase | 8.2, 8.8 | X | note entry `role` is required, enum queue, work, review (`config.schema.json:85,128`; AR:1020). Workaround: every note `role: queue`. Layer: server contract, not project config |
| R37 | Groupings (topics, collections) that are themselves lifecycle-free and hold entries at any depth, under an anchor that is not a build item | 8.1, 8.2 | W | a `topic` type with `lifecycle: none` holds children (CR:403-405); the anchor is created as the floor's role-bearing `project` before any root config exists (KL3, FLOOR:90-91) |
| R38 | Which relations entries can have (supports, contradicts, supersedes, source of), and which ones a walk follows | 8.3, 8.4 | X | edge types are `blocks` and `relates_to` only (AR:1164); link relations are fixed by the parser (`LinkParser.kt`; `docs_read` `links` relations, AR:1682). Workaround: a `relations` note with one line per relation and a `[[item:<id>]]` reference |
| R39 | What each kind of entry records and in what shape (claim, source, date read, summary), with a length cap that keeps entries atomic | 8.2, 8.5 | E | note `guidance`, `maxLength`, top-level `note_limits.mode: reject` (catalogue 2a, 14) |
| R40 | Which content lives as an item with notes and which as a page document (long-form synthesis, captured sources) | 8.2, 8.5 | W | `page` documents exist (AR:1668-1670), but documents have no type, schema, tag or parent, so the split is a guidance convention only |
| R41 | Where a capture lands (an inbox topic, or a topic by subject) and which entry type it gets | 8.2 | W | `project.rootId` only; a routing rule can be served as a `rule` document and read with `docs_read` `get` on the knowledge profile (the create-item path reads it through `config` `rules`, orchestration-only: `create-item/SKILL.md:66-70`) |
| R42 | An entry's maturity without a lifecycle (seedling, evergreen, archived) and which states retrieval hides | 8.6, 8.7 | W | tags only; `status_labels` is keyed by triggers, which a lifecycle-free item never fires (catalogue 14; CR:394-397); only documents have `archive` (AR docs_write) |
| R43 | Steering for a knowledge session: hooks inject knowledge mechanics or nothing, never sizing tiers, dispatch rules or plan-mode materialisation | 8.1-8.8 | W | `orchestration.mode: off` silences orchestration-context, pre-plan, post-plan and the dispatch guard (`orchestration-lib.mjs:16`; KL10-KL12), but not session-start's `BASE_GUIDANCE` (KL9); there is no knowledge value; setup may not write the key (KL4) |
| R44 | Which bundled process rules a root receives (none, for a root whose declared types are all `lifecycle: none`) | 8.4 | E | config-sync pushes every bundled rule on SessionStart with no opt-out (`config-sync.mjs:466-468,666`); no `extensions` key is read for it |
| R45 | No retrospective for a root that has no runs | 8.7 | E | `extensions.claude-code.retrospective.mode: off` (catalogue 13; `retro-backstop.mjs:66`) |
| R46 | Which template the root applied, and its version, so Learn can explain it and setup can see drift | 8.1, 8.8 | W | storable under `extensions` (stored, never read: catalogue 13) or in `project.name` text; nothing reads it. Same root cause as R21 and R22 |
| R47 | Which tool profile the root is meant to be used through, so setup registers that endpoint and skills choose calls that profile serves | 8.1, 8.2, 8.6 | X | the profile is a property of a connection (AR:33-50); no config key names it; the hooks can only infer it from registrations (`mcp-registrations.mjs:125-135`) |
| R48 | A synthesis method bound to the synthesis output | 8.5 | W | a note `skill:` is allowed on a lifecycle-free note and hinted by skill-enforcement (catalogue 2a; KL13); a page document has no schema, so a method cannot be bound to a page |
| R49 | Which document kinds count as knowledge in retrieval | 8.4 | not-config | a tool contract gap: `search` has no document-kind filter (AR:775-790); fix in the tool, not config |
| R50 | The staleness threshold for "not looked at in a while" | 8.6 | not-config | a skill argument of a revisit digest, like R27's audit presets |

Totals: E 3 (R35, R39, R45); W 7 (R37, R40, R41, R42, R43, R46, R48); X 4 (R36, R38, R44, R47); not-config 2 (R49, R50).

Root causes: (1) setup and steering cannot express "this root is a knowledge base" (R43, R44, R46, R47); (2) the note and edge model keeps build vocabulary (R36, R38); (3) documents sit outside the schema (R40, R48); (4) a lifecycle-free item has no state (R42).

### Template sketch: `knowledge-base` (what setup would apply)

Only existing keys. Type and note names belong to the template, not to Task Orchestrator. No `default` type: an explicit set makes a wrong type fail with `availableTypes` instead of silently resolving to a lifecycle-free default.

```yaml
project:
  rootId: "<anchor root id>"        # written by setup
  name: "<base name>"
# NEEDS R46: nothing the plugin reads records "template knowledge-base, version 1"
# NEEDS R47: nothing says this root is used through /mcp/knowledge
# NEEDS R37: the anchor itself is the floor's role-bearing `project` type

work_item_schemas:

  topic:                             # a grouping; may nest
    lifecycle: none                  # R35
    notes:
      - key: scope
        role: queue                  # NEEDS R36: a lifecycle-free note still names a phase
        required: false
        maxLength: 800
        description: "What belongs under this topic and what does not."
        guidance: "`Covers:` one or two sentences. `Not here:` neighbouring topics by `[[item:<id>]]`."

  entry:                             # one idea, in the user's words
    lifecycle: none
    notes:
      - key: content
        role: queue                  # NEEDS R36
        required: false              # lifecycle: none forbids required notes
        maxLength: 3000              # R39: atomic entries
        description: "The idea itself, restated in the user's own words."
        guidance: "One idea per entry. Lead with the claim in one sentence, then the reasoning or example. Link other entries inline as [[item:<id>]]; never paste a source verbatim beyond one short quote."
      - key: source
        role: queue
        required: false
        maxLength: 600
        description: "Where the idea came from."
        guidance: "`From:` title, author, link or [[page:<slug>]]. `Read:` date. `Where:` page or section. Empty for an original thought."
      - key: relations
        role: queue
        required: false
        maxLength: 1200
        description: "How this entry bears on others."
        guidance: "One line each: `supports | contradicts | supersedes | refines: [[item:<id>]] <why, one clause>`."
        # NEEDS R38: the relation word lives in text; deps_write relates_to carries none, and deps_read graph ignores relates_to

  source:                            # a captured article, book or paper
    lifecycle: none
    notes:
      - key: summary
        role: queue
        required: false
        maxLength: 2500
        description: "What the source argues, in a paragraph, and how far to trust it."
        guidance: "`Argues:` three to five sentences. `Trust:` one line (evidence, bias, date). `Entries:` the entries drawn from it as [[item:<id>]]. A full text over 64 KB is kept as a page [[page:<slug>]], not here."
        # NEEDS R40: the item-or-page split is this guidance line, nothing more

  # Long-form synthesis is a `page` document written with docs_write (versioned, links parsed).
  # NEEDS R48: no schema can bind a write-up method to a page; bind it here instead if the user has one:
  #   synthesis-notes on `topic` with `skill: <the user's own write-up skill>`

# NEEDS R42: maturity is a tag convention (seedling, evergreen, archived); retrieval hides `archived` by skill text only
# NEEDS R41: where a capture lands is a served rule document, e.g. rules/capture-routing.md, read with docs_read get

note_limits:
  mode: reject                       # R39: a cap that holds

extensions:
  claude-code:
    orchestration:
      mode: off                      # R43: silences the build core, plan-mode materialisation and the dispatch guard
                                     # NEEDS R43: session-start BASE_GUIDANCE (advance, context, next) still fires; no `knowledge` value
    retrospective:
      mode: off                      # R45: no runs, so no run retrospective and no per-turn REST pass
# R44 closed: config-sync pushes no bundled build rules into this root and archives copies pushed before (#780, #790)
```

What setup does around the file (not config): register only the knowledge entry (HTTP `<base>/mcp/knowledge`, or stdio with `MCP_TOOL_PROFILE=knowledge`, under a key containing `task-orchestrator`), skip the director entry, create the anchor, push through config-sync over REST (the `config` tool is not served), and report the template it applied.

---

## 4. Anti-requirements: what the development design must not break

1. A session whose root is a knowledge base receives no sizing tiers, model table, dispatch rules, run-wave routing or "materialize before implement" from any hook.
2. Plan mode in a knowledge session never orders materialisation or implementation dispatch.
3. A hook names only selects and ops the session's profile serves, or stays silent.
4. Setup never reads a missing `context` or `runs_read` as "the server is 3.x"; it checks which profile it is connected to first.
5. Setup never registers a director entry for a user who asked only for a knowledge base.
6. A root whose declared types are all `lifecycle: none` receives no bundled build rules, and process rule documents never appear as knowledge hits.
7. No lifecycle-free item is ever listed or counted as queue, backlog, next or stalled work.
8. No skill picks `blocks` as the default edge when either endpoint is lifecycle-free.
9. create-item, work-summary and dependency-manager do not offer bug, feature, tech-debt, blocker or backlog framing on a root whose types are all lifecycle-free.
10. The knowledge journey runs end to end on the knowledge profile alone, with config delivered over REST; no stage needs the orchestration endpoint.
11. Nothing deletes an entry or a page without the user's explicit yes; prune prefers archive or a tag.
12. No new phase, gate, seat, decision class or detector is added to serve this journey (no enforcement machinery without data).
13. Knowledge terms in the glossary stay method-neutral and borrow no build words (build, ship, refined, backlog).
14. The knowledge profile keeps serving no `advance` and no runs; the fix for KL20 is a read of declared types, not the `config` tool.

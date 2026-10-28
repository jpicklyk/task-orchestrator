---
name: api-compat-review
description: Compatibility assessment for items with the needs-api-compat-review trait. Evaluates the tool surface (served over both MCP and REST ops) and the REST-only surface separately, since their compatibility models differ. Invoked via skillPointer when filling api-compatibility notes.
user-invocable: false
---

# API Compatibility Review Framework

Assess API changes by surface. There is ONE tool surface, served over MCP and over REST `POST /api/v1/ops/{tool}` (the same `ToolDispatcher`, see `api-rest.md` 20a). MCP clients re-discover the schema every session, but REST ops callers hardcode tool, field and select names. A REST-only surface (routes, DTOs, openapi) has its own hardcoded clients. Do not treat a tool change as rename-friendly just because MCP clients re-read the schema.

## Step 1: Classify the Change

Determine which surface(s) the change touches:
- **Tool surface (MCP and REST ops)** - request-class `@Doc` fields under `application/api/`, the `ToolSpec`s registered in `buildToolSpecs()` (`interfaces/shared/tools/ToolRegistry.kt`), the goldens under `current/src/test/resources/golden/tools/`, the generated blocks of `api-reference.md`, and `call-shapes.md`
- **REST-only surface** - `interfaces/api/v1/routes/`, `interfaces/api/v1/dto/Dtos.kt`, `current/docs/api/openapi.yaml`, SSE and leases

A single change (e.g., a domain model field rename) can touch both surfaces independently — assess each.

## Step 2: Tool Surface Assessment

MCP clients re-read the `tools/list` schema every session, but the same tools are reachable over REST `POST /api/v1/ops/{tool}` by callers that hardcode names. Only `description` and `doc` prose is rename-cheap. A field rename or removal, or a response-shape change, breaks hardcoded REST ops callers. The item GETs (`interfaces/api/v1/routes/ItemRoutes.kt`, `NoteResourceRoutes.kt`) dispatch `items_read` and `notes_read`, so a select's output change reaches them too. Verify:
- [ ] Every changed field's request-class `@Doc` and its decode site stay in sync - no schema-says-X/code-reads-Y drift
- [ ] Response-shape changes are additive where possible; a rename or removal has a migration path for REST ops callers
- [ ] ALL first-party callers update in lockstep: plugin skills, hooks (including the `orchestration-context` hook's injected text), auto-memory references, and the generated artifacts: `./gradlew :current:regenerateToolGoldens`, `./gradlew :current:regenerateApiReference`, `node claude-plugins/task-orchestrator/scripts/gen-call-shapes.mjs`, the `to-call` blocks and `tool-catalog.json`
- [ ] The tool `description` and `doc` prose accurately reflect the new behavior

## Step 3: REST-Only Surface Assessment

Routes, DTOs, openapi, SSE and leases. HTTP clients hardcode field and param names, so compatibility matters here too:
- [ ] Response-shape changes are additive (new fields only) where possible
- [ ] Renames or removals of existing fields/params have an explicit migration path or version bump — not a silent break
- [ ] Note when a surface has effectively zero consumers (e.g., a days-old endpoint) so the reviewer can right-size caution instead of over-indexing on hypothetical clients
- [ ] `current/docs/api/openapi.yaml` is updated for any REST-facing change, including the `/ops/{tool}` body
- [ ] `api-rest.md` is updated for any REST-facing change

## Output

Compose the `api-compatibility` note with findings from Step 2 and/or Step 3, scoped to whichever surface(s) the change actually touches. If a surface wasn't touched, say so explicitly rather than omitting it silently.

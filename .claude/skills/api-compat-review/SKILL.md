---
name: api-compat-review
description: Compatibility assessment for items with the needs-api-compat-review trait. Evaluates the MCP tool surface and the REST surface separately, since their compatibility models differ. Invoked via skillPointer when filling api-compatibility notes.
user-invocable: false
---

# API Compatibility Review Framework

Assess API changes by surface — the MCP tool surface (dynamically re-discovered by clients) and the REST surface (hardcoded clients) have different compatibility models. Do not apply REST-style breaking-change caution to MCP tools, and do not apply MCP's rename-friendliness to REST.

## Step 1: Classify the Change

Determine which surface(s) the change touches:
- **MCP tools** — `application/tools/` tool definitions, `parameterSchema`, tool `description` strings
- **REST API** — `interfaces/api/v1/routes/`, `interfaces/api/v1/dto/Dtos.kt`, `openapi.yaml`

A single change (e.g., a domain model field rename) can touch both surfaces independently — assess each.

## Step 2: MCP Surface Assessment

LLM clients re-read the `tools/list` schema every session — there is no persistent client binding to break. A pure parameter rename does NOT require keeping the old name working. Verify instead:
- [ ] Every changed param's `parameterSchema` key and its arg-parsing read site stay in sync — no schema-says-X/code-reads-Y drift
- [ ] ALL first-party callers update in lockstep: plugin skills, hooks, output styles, auto-memory references, and `api-reference.md`. This doc coordination is the real cost of an MCP change, not client breakage.
- [ ] The tool `description` string accurately reflects the new behavior

## Step 3: REST Surface Assessment

HTTP clients hardcode field and param names, so compatibility DOES matter here:
- [ ] Response-shape changes are additive (new fields only) where possible
- [ ] Renames or removals of existing fields/params have an explicit migration path or version bump — not a silent break
- [ ] Note when a surface has effectively zero consumers (e.g., a days-old endpoint) so the reviewer can right-size caution instead of over-indexing on hypothetical clients
- [ ] `openapi.yaml` is updated for any REST-facing change
- [ ] `api-rest.md` is updated for any REST-facing change

## Output

Compose the `api-compatibility` note with findings from Step 2 and/or Step 3, scoped to whichever surface(s) the change actually touches. If a surface wasn't touched, say so explicitly rather than omitting it silently.

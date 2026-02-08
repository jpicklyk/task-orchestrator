---
name: template-management
description: Create, configure, and apply custom templates for tasks and features using MCP Task Orchestrator. Use when building reusable templates, adding template sections, or applying templates to existing entities.
---

# Template Management

Templates define reusable section structures that standardize documentation across tasks and features.

## Template Lifecycle

1. **Discover** existing templates with `query_templates` before creating new ones
2. **Create** a template with `manage_template(operation="create")` specifying `targetEntityType` (TASK or FEATURE)
3. **Add sections** with `manage_template(operation="addSection")` — use sequential `ordinal` values (0, 1, 2...) for display order
4. **Apply** — either at entity creation time or to existing entities

## Applying Templates

**At creation time (preferred):** Pass `templateIds` array to `manage_container(operation="create")`. This is a single call. Templates auto-create documentation sections.

**After creation:** Use `apply_template` with `templateIds`, `entityType`, and `entityId`. Use this only for entities that already exist — it costs an extra tool call.

**Composing multiple templates:** Pass multiple IDs in the `templateIds` array. Sections from later templates appear after earlier ones. Useful for combining templates like bug-investigation + testing-strategy.

## Template State

Use `manage_template` operations `enable`, `disable`, and `delete` to manage template lifecycle. Disabled templates are hidden from default `query_templates` results but findable with `isEnabled=false`.

Use `query_templates(operation="get", includeSections=true)` to inspect a template's section structure.

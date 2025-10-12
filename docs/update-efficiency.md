---
layout: default
title: Update Efficiency Best Practices
---

# Update Efficiency Best Practices

**Critical guidance for AI agents using MCP Task Orchestrator update operations**

---

## Table of Contents

- [The 90% Token Waste Problem](#the-90-token-waste-problem)
- [Core Principle: Partial Updates](#core-principle-partial-updates)
- [Common Update Scenarios](#common-update-scenarios)
- [Token Usage Calculations](#token-usage-calculations)
- [Anti-Patterns to Avoid](#anti-patterns-to-avoid)
- [Best Practices Summary](#best-practices-summary)
- [Quick Reference](#quick-reference)

---

## The 90% Token Waste Problem

### The Issue

AI agents often waste **90-95% of tokens** when updating tasks, features, projects, or sections by sending entire entities with unchanged fields.

### Why It Happens

1. **Hidden Documentation**: Partial update capability wasn't prominently documented
2. **Safety Bias**: AI agents default to sending "complete" data to avoid errors
3. **Schema Ambiguity**: All fields appear in parameter schemas, suggesting they're needed
4. **Pattern Mimicry**: Agents copy read patterns (full entity) to write operations

### The Impact

**For a typical task update:**
- Task summary alone: ~500 characters
- 6 unchanged fields: ~600 characters total
- Multiple operations: **Massive cumulative waste**

**Example: Changing just the status across 10 tasks:**
- Inefficient: ~6,000+ characters
- Efficient: ~300 characters
- **Savings: 95%**

---

## Core Principle: Partial Updates

### All Update Tools Support Partial Updates

✅ **Only the 'id' parameter is required**

All other parameters are optional:

| Tool | Required | Optional Parameters |
|------|----------|-------------------|
| `update_task` | `id` | `title`, `summary`, `status`, `priority`, `complexity`, `featureId`, `tags` |
| `update_feature` | `id` | `name`, `summary`, `status`, `priority`, `projectId`, `tags` |
| `update_project` | `id` | `name`, `summary`, `status`, `tags` |
| `update_section` | `id` | `title`, `usageDescription`, `content`, `contentFormat`, `ordinal`, `tags` |

### The Golden Rule

> **NEVER fetch an entity just to update one field**
>
> **ALWAYS send only the fields you're changing**

---

## Common Update Scenarios

### Scenario 1: Changing Task Status

**❌ INEFFICIENT** (583 characters):
```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "title": "Implement OAuth Authentication API",
  "summary": "Create secure API endpoints for OAuth 2.0 authentication with JWT tokens. Must support password grant, refresh tokens, and proper token expiration. Include rate limiting and security headers. Integrate with existing user database. Follow OAuth 2.0 RFC standards. Include comprehensive error handling for invalid credentials, expired tokens, and malformed requests.",
  "status": "completed",
  "priority": "high",
  "complexity": 8,
  "tags": "task-type-feature,oauth,authentication,api,security"
}
```

**✅ EFFICIENT** (30 characters):
```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "status": "completed"
}
```

**Token Savings: 95% (553 characters saved)**

---

### Scenario 2: Updating Priority

**❌ INEFFICIENT** (540 characters):
```json
{
  "id": "f6c4fa9b-81ad-4184-9507-8f8970e5f53d",
  "name": "AI Update Efficiency Improvements",
  "summary": "Improve documentation and tooling to guide AI agents toward efficient partial update patterns. Add monitoring to detect inefficient patterns. Create comprehensive tests validating documentation improvements. Ensure 90%+ token savings through better guidance.",
  "status": "in-development",
  "priority": "critical",
  "tags": "documentation,efficiency,token-optimization,ai-guidance"
}
```

**✅ EFFICIENT** (25 characters):
```json
{
  "id": "f6c4fa9b-81ad-4184-9507-8f8970e5f53d",
  "priority": "critical"
}
```

**Token Savings: 95% (515 characters saved)**

---

### Scenario 3: Adding Tags

**❌ INEFFICIENT** (612 characters):
```json
{
  "id": "772f9622-g41d-52e5-b827-668899101111",
  "name": "Mobile App Redesign",
  "summary": "Complete redesign of the mobile application with improved UI/UX, accessibility features, dark mode support, and modernized design language. Includes new onboarding flow, enhanced navigation, and improved performance. Target completion: Q2 2025.",
  "status": "planning",
  "priority": "high",
  "tags": "mobile,ui,ux,redesign,accessibility,2025-roadmap,high-priority"
}
```

**✅ EFFICIENT** (45 characters):
```json
{
  "id": "772f9622-g41d-52e5-b827-668899101111",
  "tags": "mobile,ui,ux,redesign,accessibility,2025-roadmap,high-priority"
}
```

**Token Savings: 93% (567 characters saved)**

---

### Scenario 4: Updating Section Content

**For section content updates, use specialized tools:**

#### Option A: Full Section Update (update_section)
**Use when**: Changing multiple section properties

**❌ INEFFICIENT** (Sending unchanged title, usageDescription, format):
```json
{
  "id": "section-uuid",
  "title": "Requirements",
  "usageDescription": "Key requirements for this task",
  "content": "Updated content here...",
  "contentFormat": "MARKDOWN",
  "ordinal": 0,
  "tags": "requirements,core"
}
```

**✅ EFFICIENT** (Only changed content):
```json
{
  "id": "section-uuid",
  "content": "Updated content here..."
}
```

#### Option B: Text-Only Update (update_section_text)
**Use when**: Only changing part of the content

**✅ MOST EFFICIENT** (Send only the text segment changing):
```json
{
  "id": "section-uuid",
  "oldText": "version 1.0",
  "newText": "version 2.0"
}
```

**Token Savings: 90-99% depending on content size**

#### Option C: Metadata-Only Update (update_section_metadata)
**Use when**: Only changing title, format, ordinal, or tags

**✅ EFFICIENT** (Excludes content entirely):
```json
{
  "id": "section-uuid",
  "title": "Updated Requirements",
  "ordinal": 1
}
```

---

## Token Usage Calculations

### Real-World Example: 10 Task Updates

**Scenario**: Mark 10 tasks as completed after implementation

**Inefficient Approach** (fetch + full update):
```
Step 1: Fetch 10 tasks with get_task
  - 10 tasks × ~800 chars each = ~8,000 characters

Step 2: Update 10 tasks with full entities
  - 10 tasks × ~600 chars each = ~6,000 characters

Total: ~14,000 characters
```

**Efficient Approach** (partial updates only):
```
Update 10 tasks with status only
  - 10 tasks × ~30 chars each = ~300 characters

Total: ~300 characters
```

**Overall Savings: 98% (13,700 characters saved)**

### Token Impact

Assuming ~4 characters per token:
- Inefficient: ~3,500 tokens
- Efficient: ~75 tokens
- **Tokens Saved: 3,425 per batch operation**

For 100 such operations:
- Inefficient: ~350,000 tokens
- Efficient: ~7,500 tokens
- **Total Savings: 342,500 tokens = $$$**

---

## Anti-Patterns to Avoid

### ❌ Anti-Pattern 1: Fetch-Modify-Update

```
DON'T DO THIS:
1. Call get_task to fetch entity
2. Modify one field in memory
3. Send entire entity to update_task
Result: Wasted 90%+ tokens!
```

**Instead:**
```
✅ DO THIS:
1. Call update_task with id + changed field only
Result: Maximum efficiency!
```

### ❌ Anti-Pattern 2: Defensive Over-Specification

```
DON'T DO THIS:
{
  "id": "uuid",
  "status": "completed",        // ← Changing this
  "priority": "high",           // ← Unchanged (unnecessary!)
  "complexity": 8,              // ← Unchanged (unnecessary!)
  "tags": "tag1,tag2,tag3"     // ← Unchanged (unnecessary!)
}
```

**Instead:**
```
✅ DO THIS:
{
  "id": "uuid",
  "status": "completed"  // Only what changed!
}
```

### ❌ Anti-Pattern 3: Batch Inefficiency

**❌ INEFFICIENT** - Multiple individual update_task calls:
```json
// 20 separate calls!
update_task({"id": "task-1-uuid", "status": "completed"})
update_task({"id": "task-2-uuid", "status": "completed"})
update_task({"id": "task-3-uuid", "status": "completed"})
...
update_task({"id": "task-20-uuid", "status": "completed"})

// Cost calculation:
// 20 calls × ~250 chars each = ~5,000 characters
// Plus 20 response roundtrips = ~20,000 additional characters
// Total: ~25,000 characters
```

**✅ EFFICIENT** - Single bulk_update_tasks call:
```json
{
  "tasks": [
    {"id": "task-1-uuid", "status": "completed"},
    {"id": "task-2-uuid", "status": "completed"},
    {"id": "task-3-uuid", "status": "completed"},
    ...
    {"id": "task-20-uuid", "status": "completed"}
  ]
}

// Cost calculation:
// Single call with 20 minimal updates = ~700 characters
// Single response with minimal fields = ~600 characters
// Total: ~1,300 characters
//
// Token Savings: 95% (23,700 characters saved)
```

**Real-World Example:**

Marking 10 tasks as completed after feature implementation:

```json
// ❌ INEFFICIENT: 10 individual calls (~2,500 chars + responses ~10,000 chars = 12,500 total)
update_task({"id": "a1b2c3...", "status": "completed"})
update_task({"id": "d4e5f6...", "status": "completed"})
...

// ✅ EFFICIENT: Single bulk call (~350 chars + response ~300 chars = 650 total)
bulk_update_tasks({
  "tasks": [
    {"id": "a1b2c3d4-e5f6-7890-abcd-ef1234567890", "status": "completed"},
    {"id": "d4e5f6a7-b8c9-0123-def4-567890abcdef", "status": "completed"},
    {"id": "f6a7b8c9-d0e1-2345-6789-0abcdef12345", "status": "completed"},
    {"id": "a7b8c9d0-e1f2-3456-7890-abcdef123456", "status": "completed"},
    {"id": "b8c9d0e1-f2a3-4567-890a-bcdef1234567", "status": "completed"},
    {"id": "c9d0e1f2-a3b4-5678-90ab-cdef12345678", "status": "completed"},
    {"id": "d0e1f2a3-b4c5-6789-0abc-def123456789", "status": "completed"},
    {"id": "e1f2a3b4-c5d6-7890-abcd-ef123456789a", "status": "completed"},
    {"id": "f2a3b4c5-d6e7-890a-bcde-f123456789ab", "status": "completed"},
    {"id": "a3b4c5d6-e7f8-90ab-cdef-123456789abc", "status": "completed"}
  ]
})

// Savings: 95% (11,850 characters saved!)
```

---

## Best Practices Summary

### ✅ DO

1. **Only send the 'id' and fields you're changing**
   ```json
   {"id": "uuid", "status": "in-progress"}
   ```

2. **Use specialized tools for sections**
   - `update_section_text` for content snippets
   - `update_section_metadata` for title/format/ordinal
   - `update_section` for multiple properties

3. **Update directly without fetching**
   - You know the ID and what to change
   - No need to read first

4. **Use bulk operations for multiple updates**
   - Use `bulk_update_tasks` for 3+ task updates
   - Use `bulk_update_sections` for 3+ section updates
   - Use `bulk_create_sections` for creating multiple sections
   - Achieves 70-95% token savings vs individual calls

5. **Trust partial updates**
   - Unchanged fields are preserved
   - No data loss risk

### ❌ DON'T

1. **Don't fetch just to update one field**
   - Wastes 2x the tokens
   - Adds unnecessary latency

2. **Don't send unchanged fields**
   - Each unnecessary field wastes ~50-100+ characters
   - Multiplies across operations

3. **Don't use update_section for small text changes**
   - Use `update_section_text` instead
   - 90%+ more efficient

4. **Don't assume all parameters are required**
   - Only 'id' is required
   - Everything else is optional

5. **Don't copy read patterns to writes**
   - Read operations return full entities (necessary)
   - Write operations accept partial updates (efficient)

---

## Quick Reference

### Update Tool Efficiency Matrix

| Scenario | Tool | Required | Optional | Token Savings |
|----------|------|----------|----------|---------------|
| Change status | `update_task` | `id` | `status` | 94-95% |
| Change priority | `update_feature` | `id` | `priority` | 95% |
| Add tags | `update_project` | `id` | `tags` | 93% |
| Update content | `update_section_text` | `id`, `oldText`, `newText` | - | 90-99% |
| Change metadata | `update_section_metadata` | `id` | `title`, `ordinal`, etc. | 85-95% |
| Multiple properties | Any update tool | `id` | Only changed fields | 80-90% |
| Bulk task updates | `bulk_update_tasks` | `tasks` array | Per-task fields | 70-95% |
| Bulk section updates | `bulk_update_sections` | `sections` array | Per-section fields | 70-90% |

### Common Update Patterns

```json
// Status change (all entity types)
{"id": "uuid", "status": "completed"}

// Priority change
{"id": "uuid", "priority": "high"}

// Multiple fields (still efficient if needed)
{"id": "uuid", "status": "in-progress", "priority": "high"}

// Clear optional field
{"id": "uuid", "featureId": ""}  // Removes feature association

// Small content update
{
  "id": "section-uuid",
  "oldText": "old value",
  "newText": "new value"
}

// Bulk task updates (3+ tasks)
{
  "tasks": [
    {"id": "task-1-uuid", "status": "completed"},
    {"id": "task-2-uuid", "status": "completed"},
    {"id": "task-3-uuid", "priority": "high"}
  ]
}

// Bulk section updates (3+ sections)
{
  "sections": [
    {"id": "section-1-uuid", "ordinal": 0},
    {"id": "section-2-uuid", "ordinal": 1},
    {"id": "section-3-uuid", "title": "Updated Title"}
  ]
}
```

### Decision Tree

```
Need to update entities?
├─ Updating multiple entities (3+)?
│  ├─ Multiple tasks? → use bulk_update_tasks
│  ├─ Multiple sections? → use bulk_update_sections
│  └─ Creating multiple sections? → use bulk_create_sections
│
├─ Updating section content?
│  ├─ Just a small text change? → use update_section_text
│  ├─ Just metadata? → use update_section_metadata
│  └─ Multiple properties? → use update_section (partial)
│
└─ Updating single task/feature/project?
   ├─ One field? → Send id + that field only
   ├─ 2-3 fields? → Send id + those fields only
   └─ Many fields? → Send id + changed fields only
```

---

## See Also

- [API Reference](api-reference.md) - Complete tool documentation
- [AI Guidelines](ai-guidelines.md) - General AI agent best practices
- [Quick Start](quick-start.md) - Getting started guide
- [Templates](templates.md) - Template system documentation

---

**Remember**: Every unnecessary character in an update operation is a wasted token. With partial updates, you can achieve **90-95% token savings** while maintaining full functionality and data integrity.

**Always ask yourself**: "Am I sending only what's changing?"

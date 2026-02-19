-- V12: Add 4 new planning templates and disable 2 workflow templates
--
-- Changes:
-- 1. Disable 'Local Git Branching Workflow' template (superseded by skill-based workflows)
-- 2. Disable 'GitHub PR Workflow' template (superseded by skill-based workflows)
-- 3. Insert 4 new planning templates:
--    a. Feature Plan (FEATURE, 8 sections)
--    b. Codebase Exploration (TASK, 3 sections)
--    c. Design Decision (TASK, 3 sections)
--    d. Implementation Specification (TASK, 5 sections)
-- 4. Insert sections for each new template
--
-- This migration is IDEMPOTENT:
-- - Templates use INSERT OR IGNORE (unique name constraint)
-- - Sections use NOT EXISTS guards
-- - Safe to run on databases where the initializer already created these templates

-- =============================================================================
-- PART 1: Disable workflow templates
-- =============================================================================

UPDATE templates SET is_enabled = 0, modified_at = datetime('now')
WHERE name = 'Local Git Branching Workflow' AND is_built_in = 1;

UPDATE templates SET is_enabled = 0, modified_at = datetime('now')
WHERE name = 'GitHub PR Workflow' AND is_built_in = 1;

-- =============================================================================
-- PART 2: Insert 4 new templates
-- =============================================================================

-- Feature Plan template (FEATURE)
INSERT OR IGNORE INTO templates (id, name, description, target_entity_type, is_built_in, is_protected, is_enabled, created_by, tags, created_at, modified_at)
VALUES (
    randomblob(16),
    'Feature Plan',
    'Structures a feature as an engineering plan with problem definition, architecture, implementation phases, and verification. Composable with Requirements Specification for full coverage. Use for Detailed-tier planning.',
    'FEATURE',
    1,
    1,
    1,
    'System',
    'planning,architecture,ai-optimized,engineering',
    datetime('now'),
    datetime('now')
);

-- Codebase Exploration template (TASK)
INSERT OR IGNORE INTO templates (id, name, description, target_entity_type, is_built_in, is_protected, is_enabled, created_by, tags, created_at, modified_at)
VALUES (
    randomblob(16),
    'Codebase Exploration',
    'Scoped codebase investigation with target files, key questions, and structured findings. Prevents infinite exploration by requiring explicit scope and out-of-scope boundaries. These tasks are inherently parallelizable — multiple explorations can run as concurrent subagents.',
    'TASK',
    1,
    1,
    1,
    'System',
    'planning,exploration,research,ai-optimized',
    datetime('now'),
    datetime('now')
);

-- Design Decision template (TASK)
INSERT OR IGNORE INTO templates (id, name, description, target_entity_type, is_built_in, is_protected, is_enabled, created_by, tags, created_at, modified_at)
VALUES (
    randomblob(16),
    'Design Decision',
    'Structures an architectural decision with context, options analysis, and recommendation. Use when planning encounters a fork with multiple valid approaches.',
    'TASK',
    1,
    1,
    1,
    'System',
    'planning,architecture,design-decision,ai-optimized',
    datetime('now'),
    datetime('now')
);

-- Implementation Specification template (TASK)
INSERT OR IGNORE INTO templates (id, name, description, target_entity_type, is_built_in, is_protected, is_enabled, created_by, tags, created_at, modified_at)
VALUES (
    randomblob(16),
    'Implementation Specification',
    'Detailed implementation spec for one phase or component. Precise code change points, snippets, and test requirements. Use after exploration and design decisions are complete. These tasks typically depend on prior phases — check dependency ordering before parallelizing.',
    'TASK',
    1,
    1,
    1,
    'System',
    'planning,implementation,specification,ai-optimized',
    datetime('now'),
    datetime('now')
);

-- =============================================================================
-- PART 3: Insert sections for Feature Plan template (8 sections)
-- =============================================================================

-- Section 0: Problem Statement
INSERT INTO template_sections (id, template_id, title, usage_description, content_sample, content_format, ordinal, is_required, tags)
SELECT
    randomblob(16),
    t.id,
    'Problem Statement',
    'Define the problem being solved, why it matters, and any reference material. Include design constraints that scope the solution.',
    '## Problem

[What problem does this feature solve?]

## Why It Matters

[Impact of not solving this]

## Design Constraints

- [Constraint 1]
- [Constraint 2]',
    'MARKDOWN',
    0,
    1,
    'planning,problem-definition,constraints'
FROM templates t
WHERE t.name = 'Feature Plan' AND t.is_built_in = 1
AND NOT EXISTS (
    SELECT 1 FROM template_sections ts
    WHERE ts.template_id = t.id AND ts.title = 'Problem Statement'
);

-- Section 1: Architecture Overview
INSERT INTO template_sections (id, template_id, title, usage_description, content_sample, content_format, ordinal, is_required, tags)
SELECT
    randomblob(16),
    t.id,
    'Architecture Overview',
    'High-level architecture: core concept, key mechanisms, component interaction. The ''what'' before the detailed ''how.''',
    '## Core Concept

[One paragraph describing the central idea]

## Key Mechanisms

1. [Mechanism 1]
2. [Mechanism 2]

## Component Interaction

[How the pieces fit together]',
    'MARKDOWN',
    1,
    1,
    'architecture,design,components'
FROM templates t
WHERE t.name = 'Feature Plan' AND t.is_built_in = 1
AND NOT EXISTS (
    SELECT 1 FROM template_sections ts
    WHERE ts.template_id = t.id AND ts.title = 'Architecture Overview'
);

-- Section 2: Implementation Phases
INSERT INTO template_sections (id, template_id, title, usage_description, content_sample, content_format, ordinal, is_required, tags)
SELECT
    randomblob(16),
    t.id,
    'Implementation Phases',
    'Ordered phases targeting architectural layers. Each phase lists files, code patterns, and rationale.',
    '## Phase 1: [Layer Name]

**Files:**
- `path/to/file.kt` — [change]

**Pattern:**
```kotlin
// Code snippet
```

**Rationale:** [Why]

---

## Phase 2: [Layer Name]

[Same structure]',
    'MARKDOWN',
    2,
    1,
    'implementation,phases,planning'
FROM templates t
WHERE t.name = 'Feature Plan' AND t.is_built_in = 1
AND NOT EXISTS (
    SELECT 1 FROM template_sections ts
    WHERE ts.template_id = t.id AND ts.title = 'Implementation Phases'
);

-- Section 3: File Change Manifest
INSERT INTO template_sections (id, template_id, title, usage_description, content_sample, content_format, ordinal, is_required, tags)
SELECT
    randomblob(16),
    t.id,
    'File Change Manifest',
    'Every file affected, with change type and description. The ''blast radius'' assessment.',
    '| File | Change Type | Description |
|------|------------|-------------|
| `path/to/file.kt` | Modify | [What changes] |',
    'MARKDOWN',
    3,
    1,
    'files,manifest,impact-analysis'
FROM templates t
WHERE t.name = 'Feature Plan' AND t.is_built_in = 1
AND NOT EXISTS (
    SELECT 1 FROM template_sections ts
    WHERE ts.template_id = t.id AND ts.title = 'File Change Manifest'
);

-- Section 4: Design Decisions
INSERT INTO template_sections (id, template_id, title, usage_description, content_sample, content_format, ordinal, is_required, tags)
SELECT
    randomblob(16),
    t.id,
    'Design Decisions',
    'Significant design choices with alternatives considered and rationale.',
    '### Why [choice A] over [choice B]?

[Rationale]',
    'MARKDOWN',
    4,
    0,
    'design,decisions,rationale'
FROM templates t
WHERE t.name = 'Feature Plan' AND t.is_built_in = 1
AND NOT EXISTS (
    SELECT 1 FROM template_sections ts
    WHERE ts.template_id = t.id AND ts.title = 'Design Decisions'
);

-- Section 5: Execution Notes
INSERT INTO template_sections (id, template_id, title, usage_description, content_sample, content_format, ordinal, is_required, tags)
SELECT
    randomblob(16),
    t.id,
    'Execution Notes',
    'Optional. Note parallelization opportunities, delegation preferences, or team coordination needs. Which phases can run independently? Which need sequential ordering? Should work be delegated to subagents (focused tasks, report back) or an agent team (collaborative, peer discussion)? Skip this section for straightforward work.',
    '## Parallelization

- Phases 1-3: sequential (each builds on prior)
- Phases 4-6: can run in parallel (independent modules)

## Delegation

- [subagents | agent team | single session]
- Rationale: [why this approach suits the work]',
    'MARKDOWN',
    5,
    0,
    'execution,parallelization,delegation'
FROM templates t
WHERE t.name = 'Feature Plan' AND t.is_built_in = 1
AND NOT EXISTS (
    SELECT 1 FROM template_sections ts
    WHERE ts.template_id = t.id AND ts.title = 'Execution Notes'
);

-- Section 6: Risks & Mitigations
INSERT INTO template_sections (id, template_id, title, usage_description, content_sample, content_format, ordinal, is_required, tags)
SELECT
    randomblob(16),
    t.id,
    'Risks & Mitigations',
    'What could go wrong and how to handle it.',
    '| Risk | Severity | Mitigation |
|------|----------|------------|
| [Risk] | High/Med/Low | [Mitigation] |',
    'MARKDOWN',
    6,
    0,
    'risks,mitigations,planning'
FROM templates t
WHERE t.name = 'Feature Plan' AND t.is_built_in = 1
AND NOT EXISTS (
    SELECT 1 FROM template_sections ts
    WHERE ts.template_id = t.id AND ts.title = 'Risks & Mitigations'
);

-- Section 7: Verification
INSERT INTO template_sections (id, template_id, title, usage_description, content_sample, content_format, ordinal, is_required, tags)
SELECT
    randomblob(16),
    t.id,
    'Verification',
    'Acceptance criteria for the plan. All criteria must pass before implementation begins.',
    '[]',
    'JSON',
    7,
    1,
    'verification,acceptance-criteria,quality'
FROM templates t
WHERE t.name = 'Feature Plan' AND t.is_built_in = 1
AND NOT EXISTS (
    SELECT 1 FROM template_sections ts
    WHERE ts.template_id = t.id AND ts.title = 'Verification'
);

-- =============================================================================
-- PART 4: Insert sections for Codebase Exploration template (3 sections)
-- =============================================================================

-- Section 0: Exploration Scope
INSERT INTO template_sections (id, template_id, title, usage_description, content_sample, content_format, ordinal, is_required, tags)
SELECT
    randomblob(16),
    t.id,
    'Exploration Scope',
    'What to investigate and what NOT to investigate. List specific files and patterns. Narrow scope prevents context waste.',
    '## Target Files

- `path/to/file.kt` — [why]

## Target Patterns

- [What to understand]

## Out of Scope

- [What NOT to investigate]',
    'MARKDOWN',
    0,
    1,
    'scope,investigation,boundaries'
FROM templates t
WHERE t.name = 'Codebase Exploration' AND t.is_built_in = 1
AND NOT EXISTS (
    SELECT 1 FROM template_sections ts
    WHERE ts.template_id = t.id AND ts.title = 'Exploration Scope'
);

-- Section 1: Key Questions
INSERT INTO template_sections (id, template_id, title, usage_description, content_sample, content_format, ordinal, is_required, tags)
SELECT
    randomblob(16),
    t.id,
    'Key Questions',
    'Specific questions this exploration must answer. Each should be answerable from the codebase.',
    '1. [Question 1]
2. [Question 2]
3. [Question 3]',
    'MARKDOWN',
    1,
    1,
    'questions,research,discovery'
FROM templates t
WHERE t.name = 'Codebase Exploration' AND t.is_built_in = 1
AND NOT EXISTS (
    SELECT 1 FROM template_sections ts
    WHERE ts.template_id = t.id AND ts.title = 'Key Questions'
);

-- Section 2: Findings
INSERT INTO template_sections (id, template_id, title, usage_description, content_sample, content_format, ordinal, is_required, tags)
SELECT
    randomblob(16),
    t.id,
    'Findings',
    'Structured answers to key questions with file:line references. Becomes reference material for planning.',
    '### Q1: [Question]

**Answer:** [Answer with file:line references]

### Q2: [Question]

**Answer:** [Answer]',
    'MARKDOWN',
    2,
    1,
    'findings,answers,references'
FROM templates t
WHERE t.name = 'Codebase Exploration' AND t.is_built_in = 1
AND NOT EXISTS (
    SELECT 1 FROM template_sections ts
    WHERE ts.template_id = t.id AND ts.title = 'Findings'
);

-- =============================================================================
-- PART 5: Insert sections for Design Decision template (3 sections)
-- =============================================================================

-- Section 0: Decision Context
INSERT INTO template_sections (id, template_id, title, usage_description, content_sample, content_format, ordinal, is_required, tags)
SELECT
    randomblob(16),
    t.id,
    'Decision Context',
    'What needs to be decided, why, and what constraints apply.',
    '## Decision Required

[What needs to be decided]

## Context

[Why this matters]

## Constraints

- [Constraint 1]
- [Constraint 2]',
    'MARKDOWN',
    0,
    1,
    'context,constraints,decision'
FROM templates t
WHERE t.name = 'Design Decision' AND t.is_built_in = 1
AND NOT EXISTS (
    SELECT 1 FROM template_sections ts
    WHERE ts.template_id = t.id AND ts.title = 'Decision Context'
);

-- Section 1: Options Analysis
INSERT INTO template_sections (id, template_id, title, usage_description, content_sample, content_format, ordinal, is_required, tags)
SELECT
    randomblob(16),
    t.id,
    'Options Analysis',
    'Each viable option with approach, pros, cons, and effort.',
    '### Option A: [Name]

**Approach:** [Description]
**Pros:** [Advantages]
**Cons:** [Disadvantages]
**Effort:** [Low/Medium/High]

### Option B: [Name]

[Same]',
    'MARKDOWN',
    1,
    1,
    'options,analysis,tradeoffs'
FROM templates t
WHERE t.name = 'Design Decision' AND t.is_built_in = 1
AND NOT EXISTS (
    SELECT 1 FROM template_sections ts
    WHERE ts.template_id = t.id AND ts.title = 'Options Analysis'
);

-- Section 2: Recommendation
INSERT INTO template_sections (id, template_id, title, usage_description, content_sample, content_format, ordinal, is_required, tags)
SELECT
    randomblob(16),
    t.id,
    'Recommendation',
    'Chosen option with rationale referencing specific constraints.',
    '## Chosen: [Option Name]

**Rationale:** [Why this satisfies constraints]

**Key Factor:** [Deciding factor]',
    'MARKDOWN',
    2,
    1,
    'recommendation,rationale,decision'
FROM templates t
WHERE t.name = 'Design Decision' AND t.is_built_in = 1
AND NOT EXISTS (
    SELECT 1 FROM template_sections ts
    WHERE ts.template_id = t.id AND ts.title = 'Recommendation'
);

-- =============================================================================
-- PART 6: Insert sections for Implementation Specification template (5 sections)
-- =============================================================================

-- Section 0: Scope & Boundaries
INSERT INTO template_sections (id, template_id, title, usage_description, content_sample, content_format, ordinal, is_required, tags)
SELECT
    randomblob(16),
    t.id,
    'Scope & Boundaries',
    'What this spec covers and doesn''t. Prerequisites that must complete first.',
    '## In Scope

- [What this covers]

## Out of Scope

- [Handled elsewhere]

## Prerequisites

- [What must complete first]',
    'MARKDOWN',
    0,
    1,
    'scope,boundaries,prerequisites'
FROM templates t
WHERE t.name = 'Implementation Specification' AND t.is_built_in = 1
AND NOT EXISTS (
    SELECT 1 FROM template_sections ts
    WHERE ts.template_id = t.id AND ts.title = 'Scope & Boundaries'
);

-- Section 1: Code Change Points
INSERT INTO template_sections (id, template_id, title, usage_description, content_sample, content_format, ordinal, is_required, tags)
SELECT
    randomblob(16),
    t.id,
    'Code Change Points',
    'Every method, class, or file needing modification with line numbers and change description.',
    '| File | Method/Location | Line(s) | Change |
|------|----------------|---------|--------|
| `path/to/file.kt` | `methodName()` | ~123 | [Change] |',
    'MARKDOWN',
    1,
    1,
    'code-changes,files,methods'
FROM templates t
WHERE t.name = 'Implementation Specification' AND t.is_built_in = 1
AND NOT EXISTS (
    SELECT 1 FROM template_sections ts
    WHERE ts.template_id = t.id AND ts.title = 'Code Change Points'
);

-- Section 2: Technical Specification
INSERT INTO template_sections (id, template_id, title, usage_description, content_sample, content_format, ordinal, is_required, tags)
SELECT
    randomblob(16),
    t.id,
    'Technical Specification',
    'Code snippets, data structures, algorithmic details. Detailed enough to implement without ambiguity.',
    '## Data Model

```kotlin
// New/modified structures
```

## Logic

```kotlin
// Key implementation
```

## Integration Points

[How this connects]',
    'MARKDOWN',
    2,
    1,
    'technical,specification,code'
FROM templates t
WHERE t.name = 'Implementation Specification' AND t.is_built_in = 1
AND NOT EXISTS (
    SELECT 1 FROM template_sections ts
    WHERE ts.template_id = t.id AND ts.title = 'Technical Specification'
);

-- Section 3: Test Plan
INSERT INTO template_sections (id, template_id, title, usage_description, content_sample, content_format, ordinal, is_required, tags)
SELECT
    randomblob(16),
    t.id,
    'Test Plan',
    'Specific test cases: happy path, error paths, edge cases, backward compatibility.',
    '## Unit Tests

- [Scenario → expected result]

## Integration Tests

- [End-to-end scenario]

## Backward Compatibility

- [Existing behavior unchanged]',
    'MARKDOWN',
    3,
    1,
    'testing,test-plan,quality'
FROM templates t
WHERE t.name = 'Implementation Specification' AND t.is_built_in = 1
AND NOT EXISTS (
    SELECT 1 FROM template_sections ts
    WHERE ts.template_id = t.id AND ts.title = 'Test Plan'
);

-- Section 4: Verification
INSERT INTO template_sections (id, template_id, title, usage_description, content_sample, content_format, ordinal, is_required, tags)
SELECT
    randomblob(16),
    t.id,
    'Verification',
    'Acceptance criteria for this spec. All must pass before implementation begins.',
    '[]',
    'JSON',
    4,
    1,
    'verification,acceptance-criteria,quality'
FROM templates t
WHERE t.name = 'Implementation Specification' AND t.is_built_in = 1
AND NOT EXISTS (
    SELECT 1 FROM template_sections ts
    WHERE ts.template_id = t.id AND ts.title = 'Verification'
);

-- =============================================================================
-- Migration Complete
-- =============================================================================
-- This migration is IDEMPOTENT and handles multiple scenarios:
-- 1. Existing database (9 templates): Disables 2 workflow templates, adds 4 new planning templates with 19 sections
-- 2. Fresh install (initializer ran first): All INSERTs are safely skipped via OR IGNORE / NOT EXISTS
--
-- Templates affected:
-- - Local Git Branching Workflow: DISABLED
-- - GitHub PR Workflow: DISABLED
-- - Feature Plan: NEW (FEATURE, 8 sections)
-- - Codebase Exploration: NEW (TASK, 3 sections)
-- - Design Decision: NEW (TASK, 3 sections)
-- - Implementation Specification: NEW (TASK, 5 sections)
--
-- Total after migration: 13 templates (11 enabled, 2 disabled), up to 45 sections

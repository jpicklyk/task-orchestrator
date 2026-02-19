-- V11: Add Verification sections to implementation templates and disable Definition of Done
-- Part of the Completion Gate Verification feature.
--
-- Changes:
-- 1. Add Verification section (JSON format) to Task Implementation template
-- 2. Add Verification section (JSON format) to Bug Investigation template
-- 3. Add Verification section (JSON format) to Requirements Specification template
-- 4. Disable Definition of Done template (superseded by Verification Gate)
--
-- The Verification section enables the completion gate: when apply_template detects
-- a section titled "Verification", it auto-sets requiresVerification=true on the entity.
-- The MCP server then blocks completion until all JSON criteria have pass=true.

-- =============================================================================
-- PART 1: Add Verification section to Task Implementation template
-- =============================================================================

INSERT INTO template_sections (id, template_id, title, usage_description, content_sample, content_format, ordinal, is_required, tags)
SELECT
    randomblob(16),
    t.id,
    'Verification',
    'Define one or more acceptance criteria as a JSON array. Each criterion describes a condition that must be verified before this task can be completed. Format: [{"criteria": "description", "pass": false}, ...] Set pass to true only after personally confirming each condition. The MCP server will block completion until all criteria pass.',
    '[{"criteria": "Unit tests pass for new/modified code", "pass": false}, {"criteria": "No regressions in existing test suite", "pass": false}]',
    'JSON',
    (SELECT COALESCE(MAX(ts.ordinal), -1) + 1 FROM template_sections ts WHERE ts.template_id = t.id),
    1,
    'verification,acceptance-criteria,quality'
FROM templates t
WHERE t.name = 'Task Implementation' AND t.is_built_in = 1
AND NOT EXISTS (
    SELECT 1 FROM template_sections ts
    WHERE ts.template_id = t.id AND ts.title = 'Verification'
);

-- =============================================================================
-- PART 2: Add Verification section to Bug Investigation template
-- =============================================================================

INSERT INTO template_sections (id, template_id, title, usage_description, content_sample, content_format, ordinal, is_required, tags)
SELECT
    randomblob(16),
    t.id,
    'Verification',
    'Define one or more acceptance criteria as a JSON array. Each criterion describes a condition that must be verified before this bug fix can be completed. Format: [{"criteria": "description", "pass": false}, ...] Set pass to true only after personally confirming each condition. The MCP server will block completion until all criteria pass.',
    '[{"criteria": "Bug can no longer be reproduced", "pass": false}, {"criteria": "Regression test added", "pass": false}, {"criteria": "No side effects in related functionality", "pass": false}]',
    'JSON',
    (SELECT COALESCE(MAX(ts.ordinal), -1) + 1 FROM template_sections ts WHERE ts.template_id = t.id),
    1,
    'verification,acceptance-criteria,quality'
FROM templates t
WHERE t.name = 'Bug Investigation' AND t.is_built_in = 1
AND NOT EXISTS (
    SELECT 1 FROM template_sections ts
    WHERE ts.template_id = t.id AND ts.title = 'Verification'
);

-- =============================================================================
-- PART 3: Add Verification section to Requirements Specification template
-- =============================================================================

INSERT INTO template_sections (id, template_id, title, usage_description, content_sample, content_format, ordinal, is_required, tags)
SELECT
    randomblob(16),
    t.id,
    'Verification',
    'Define acceptance criteria for this feature as a JSON array. Feature criteria verify that child tasks integrate correctly and the feature works end-to-end. Format: [{"criteria": "description", "pass": false}, ...] Set pass to true only after verifying each condition across the completed tasks. The MCP server will block feature completion until all criteria pass.',
    '[{"criteria": "All child tasks completed", "pass": false}, {"criteria": "End-to-end flow works as specified", "pass": false}, {"criteria": "No regressions in existing test suite", "pass": false}]',
    'JSON',
    (SELECT COALESCE(MAX(ts.ordinal), -1) + 1 FROM template_sections ts WHERE ts.template_id = t.id),
    1,
    'verification,acceptance-criteria,quality'
FROM templates t
WHERE t.name = 'Requirements Specification' AND t.is_built_in = 1
AND NOT EXISTS (
    SELECT 1 FROM template_sections ts
    WHERE ts.template_id = t.id AND ts.title = 'Verification'
);

-- =============================================================================
-- PART 4: Disable Definition of Done template
-- =============================================================================
-- Superseded by the Verification Gate: structured JSON criteria replace the
-- generic checklist with enforceable, per-task acceptance criteria.

UPDATE templates SET
    is_enabled = 0,
    modified_at = datetime('now')
WHERE name = 'Definition of Done' AND is_built_in = 1;

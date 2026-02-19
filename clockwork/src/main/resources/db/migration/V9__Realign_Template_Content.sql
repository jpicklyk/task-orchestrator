-- V9: Realign template content to set a documentation floor, not ceiling
-- Templates now provide brief prompts telling the AI WHAT to document,
-- rather than prescriptive multi-step workflows telling HOW to plan.
--
-- Changes:
-- 1. Rename 3 templates (Task Implementation Workflow, Bug Investigation Workflow, Testing Strategy)
-- 2. Delete 2 redundant sections (Technical Decision Log, Testing Checkpoints)
-- 3. Rename sections to focus on outputs rather than processes
-- 4. Replace verbose contentSample with brief prompts
-- 5. Change some sections from required to optional

-- =============================================================================
-- PART 1: Rename templates
-- =============================================================================

UPDATE templates SET
    name = 'Task Implementation',
    description = 'Template for documenting task implementation approach, progress notes, and verification results.',
    tags = 'implementation,workflow',
    modified_at = datetime('now')
WHERE name = 'Task Implementation Workflow' AND is_built_in = 1;

UPDATE templates SET
    name = 'Bug Investigation',
    description = 'Template for documenting bug investigation findings, root cause analysis, and fix verification.',
    tags = 'bug,investigation,debugging,task-type-bug',
    modified_at = datetime('now')
WHERE name = 'Bug Investigation Workflow' AND is_built_in = 1;

UPDATE templates SET
    name = 'Test Plan',
    description = 'Template for documenting testing approach, test coverage areas, and acceptance criteria.',
    tags = 'testing,quality,validation,qa',
    modified_at = datetime('now')
WHERE name = 'Testing Strategy' AND is_built_in = 1;

-- Update descriptions for templates that keep their names
UPDATE templates SET
    description = 'Minimum documentation for technical decisions and architectural approach. Sections prompt the AI to document key decisions without prescribing how to plan.',
    tags = 'technical,architecture,implementation',
    modified_at = datetime('now')
WHERE name = 'Technical Approach' AND is_built_in = 1;

UPDATE templates SET
    description = 'Template for defining minimum completion criteria and production readiness checklist.',
    modified_at = datetime('now')
WHERE name = 'Definition of Done' AND is_built_in = 1;

UPDATE templates SET
    description = 'Template for capturing functional requirements, optional enhancements, and constraints.',
    modified_at = datetime('now')
WHERE name = 'Requirements Specification' AND is_built_in = 1;

-- =============================================================================
-- PART 2: Delete redundant sections
-- =============================================================================

-- Delete "Technical Decision Log" from Technical Approach (redundant with new "Technical Decisions" section)
DELETE FROM template_sections
WHERE template_id IN (SELECT id FROM templates WHERE name = 'Technical Approach' AND is_built_in = 1)
  AND title = 'Technical Decision Log';

-- Delete "Testing Checkpoints" from Test Plan (overlaps with Definition of Done)
DELETE FROM template_sections
WHERE template_id IN (SELECT id FROM templates WHERE name = 'Test Plan' AND is_built_in = 1)
  AND title = 'Testing Checkpoints';

-- =============================================================================
-- PART 3: Update Technical Approach sections
-- =============================================================================

-- Rename "Implementation Planning Checklist" → "Technical Decisions" and trim content
UPDATE template_sections SET
    title = 'Technical Decisions',
    usage_description = 'Document key technical decisions, chosen technologies, and architectural approach',
    content_sample = 'Document the following:
- Components identified and their responsibilities
- Technology choices with brief rationale
- Data management approach (storage, transformations, access patterns)
- Error handling strategy
- Key risks and mitigation plans
- Testing approach',
    ordinal = 0,
    is_required = 1,
    tags = 'technical-details,planning,decisions'
WHERE template_id IN (SELECT id FROM templates WHERE name = 'Technical Approach' AND is_built_in = 1)
  AND title = 'Implementation Planning Checklist';

-- Rename "Integration Points Checklist" → "Integration Considerations" and trim content
UPDATE template_sections SET
    title = 'Integration Considerations',
    usage_description = 'Document external dependencies, internal interfaces, and integration concerns',
    content_sample = 'Document applicable items:
- External dependencies and fallback strategies
- Internal interfaces and data contracts
- Database schema changes or migration needs
- Configuration and environment requirements
- API contracts (if creating or modifying APIs)',
    ordinal = 1,
    is_required = 0,
    tags = 'integration,dependencies,validation'
WHERE template_id IN (SELECT id FROM templates WHERE name = 'Technical Approach' AND is_built_in = 1)
  AND title = 'Integration Points Checklist';

-- =============================================================================
-- PART 4: Update Task Implementation sections
-- =============================================================================

-- Rename "Implementation Analysis" → "Analysis & Approach"
UPDATE template_sections SET
    title = 'Analysis & Approach',
    usage_description = 'Document the implementation approach, scope assessment, and key decisions before coding',
    content_sample = 'Document the following:
- Files to modify and new files to create
- Dependencies and libraries involved
- Chosen implementation approach and rationale
- Scope assessment (what''s in and out of scope)
- Key risks or unknowns identified',
    ordinal = 0,
    is_required = 1,
    tags = 'analysis,planning'
WHERE template_id IN (SELECT id FROM templates WHERE name = 'Task Implementation' AND is_built_in = 1)
  AND title = 'Implementation Analysis';

-- Rename "Step-by-Step Implementation" → "Implementation Notes" (optional)
UPDATE template_sections SET
    title = 'Implementation Notes',
    usage_description = 'Document significant implementation details, deviations from plan, and decisions made during coding',
    content_sample = 'Document as applicable:
- Notable implementation details or patterns used
- Deviations from the planned approach and why
- Problems encountered and how they were resolved
- Decisions made during implementation',
    ordinal = 1,
    is_required = 0,
    tags = 'implementation,execution'
WHERE template_id IN (SELECT id FROM templates WHERE name = 'Task Implementation' AND is_built_in = 1)
  AND title = 'Step-by-Step Implementation';

-- Rename "Testing & Validation" → "Verification & Results"
UPDATE template_sections SET
    title = 'Verification & Results',
    usage_description = 'Document testing approach, test results, and verification that acceptance criteria are met',
    content_sample = 'Document the following:
- Tests written or updated
- Test results summary (pass/fail)
- Acceptance criteria verification
- Build verification results
- Known limitations or follow-up items',
    ordinal = 2,
    is_required = 1,
    tags = 'testing,validation'
WHERE template_id IN (SELECT id FROM templates WHERE name = 'Task Implementation' AND is_built_in = 1)
  AND title = 'Testing & Validation';

-- =============================================================================
-- PART 5: Update Bug Investigation sections
-- =============================================================================

-- Rename "Investigation Process" → "Investigation Findings"
UPDATE template_sections SET
    title = 'Investigation Findings',
    usage_description = 'Document the bug investigation findings including reproduction results and diagnostic data',
    content_sample = 'Document the following:
- Reproduction steps and results (consistently reproducible? conditions required?)
- Environment and conditions where the bug occurs
- Diagnostic data collected (logs, stack traces, error messages)
- Scope of impact (what features/users are affected)
- Related issues or patterns found',
    ordinal = 0,
    is_required = 1,
    tags = 'investigation,reproduction,diagnosis'
WHERE template_id IN (SELECT id FROM templates WHERE name = 'Bug Investigation' AND is_built_in = 1)
  AND title = 'Investigation Process';

-- Rename "Root Cause Analysis" → "Root Cause"
UPDATE template_sections SET
    title = 'Root Cause',
    usage_description = 'Document the identified root cause, supporting evidence, and contributing factors',
    content_sample = 'Document the following:
- Root cause statement (clear, concise description)
- Supporting evidence (code paths, data flow, logs)
- When and how the issue was likely introduced
- Contributing factors (environment, configuration, edge cases)',
    ordinal = 1,
    is_required = 1,
    tags = 'root-cause,analysis'
WHERE template_id IN (SELECT id FROM templates WHERE name = 'Bug Investigation' AND is_built_in = 1)
  AND title = 'Root Cause Analysis';

-- Rename "Fix Implementation & Verification" → "Fix & Verification"
UPDATE template_sections SET
    title = 'Fix & Verification',
    usage_description = 'Document the fix approach, changes made, test results, and prevention measures',
    content_sample = 'Document the following:
- Fix approach and rationale
- Files and components changed
- Tests added to prevent regression
- Regression test results
- Prevention measures (monitoring, validation, documentation)',
    ordinal = 2,
    is_required = 1,
    tags = 'fix-implementation,verification,testing'
WHERE template_id IN (SELECT id FROM templates WHERE name = 'Bug Investigation' AND is_built_in = 1)
  AND title = 'Fix Implementation & Verification';

-- =============================================================================
-- PART 6: Update Test Plan sections
-- =============================================================================

-- Trim "Test Coverage" content
UPDATE template_sections SET
    content_sample = 'Document applicable test areas:
- Unit tests: key classes/functions to test, edge cases to cover
- Integration tests: component interactions, data flow verification
- API tests: endpoints, success/error responses, validation
- End-to-end tests: critical user workflows and business processes',
    tags = 'testing,coverage,unit-tests,integration-tests'
WHERE template_id IN (SELECT id FROM templates WHERE name = 'Test Plan' AND is_built_in = 1)
  AND title = 'Test Coverage';

-- Trim "Acceptance Criteria" content
UPDATE template_sections SET
    content_sample = 'Define measurable criteria:
- Functional: core functionality works, user workflows complete, error handling correct
- Quality: test coverage meets standard, tests pass consistently, no critical issues
- Performance: response times acceptable, resource usage within bounds',
    tags = 'acceptance-criteria,completion,quality'
WHERE template_id IN (SELECT id FROM templates WHERE name = 'Test Plan' AND is_built_in = 1)
  AND title = 'Acceptance Criteria';

-- =============================================================================
-- PART 7: Update Definition of Done sections
-- =============================================================================

-- Rename "Implementation Complete" → "Completion Checklist"
UPDATE template_sections SET
    title = 'Completion Checklist',
    usage_description = 'Minimum criteria confirming the implementation is functionally complete',
    content_sample = 'Verify and document:
- [ ] Core functionality implemented and working
- [ ] Acceptance criteria met
- [ ] Edge cases and error conditions handled
- [ ] Unit and integration tests written and passing
- [ ] Code reviewed (or self-reviewed)
- [ ] Documentation updated where needed',
    tags = 'implementation,quality,testing,checklist'
WHERE template_id IN (SELECT id FROM templates WHERE name = 'Definition of Done' AND is_built_in = 1)
  AND title = 'Implementation Complete';

-- Rename "Production Ready" → "Production Readiness" (now optional)
UPDATE template_sections SET
    title = 'Production Readiness',
    usage_description = 'Criteria for production deployment readiness — fill in only applicable items',
    content_sample = 'Verify applicable items:
- [ ] Builds successfully in CI/CD pipeline
- [ ] Deployed and verified in staging/test environment
- [ ] Logging and monitoring in place
- [ ] Performance meets requirements
- [ ] Security implications reviewed
- [ ] Rollback plan documented',
    is_required = 0,
    tags = 'deployment,monitoring,performance,security,checklist'
WHERE template_id IN (SELECT id FROM templates WHERE name = 'Definition of Done' AND is_built_in = 1)
  AND title = 'Production Ready';

-- =============================================================================
-- PART 8: Update Requirements Specification sections
-- =============================================================================

-- Trim "Must-Have Requirements" content
UPDATE template_sections SET
    content_sample = 'Document each requirement with:
- Description of the functionality needed
- User story: As a [user type], I want [functionality] so that [benefit]
- Acceptance criteria: Given [context], when [action], then [expected result]

Include requirements for: core functionality, data handling, integrations, and business logic.',
    tags = 'functional,core,critical,acceptance-criteria,requirements'
WHERE template_id IN (SELECT id FROM templates WHERE name = 'Requirements Specification' AND is_built_in = 1)
  AND title = 'Must-Have Requirements';

-- Trim "Nice-to-Have Features" content
UPDATE template_sections SET
    content_sample = 'List optional enhancements with:
- Feature description and value to users
- Rough effort estimate
- Dependencies on must-have requirements',
    tags = 'optional,enhancements,future,nice-to-have,requirements'
WHERE template_id IN (SELECT id FROM templates WHERE name = 'Requirements Specification' AND is_built_in = 1)
  AND title = 'Nice-to-Have Features';

-- Trim "Constraints & Non-Functional Requirements" content
UPDATE template_sections SET
    content_sample = 'Document applicable constraints:
- Technology stack requirements or limitations
- Performance requirements (response times, throughput, scalability)
- Security requirements (authentication, authorization, data protection)
- Data requirements (volume, retention, backup/recovery)
- Integration constraints (API limits, data formats, network requirements)
- Platform/browser support requirements',
    tags = 'constraints,non-functional,performance,security,requirements'
WHERE template_id IN (SELECT id FROM templates WHERE name = 'Requirements Specification' AND is_built_in = 1)
  AND title = 'Constraints & Non-Functional Requirements';

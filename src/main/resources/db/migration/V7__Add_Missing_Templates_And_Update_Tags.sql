-- V7: Add missing templates and update section tags for v2.0 tag filtering
-- This migration:
-- 1. Creates 3 missing templates (Technical Approach, Task Implementation Workflow, Bug Investigation Workflow)
-- 2. Updates existing template sections with new tag taxonomy for token-efficient filtering
--
-- Tag Taxonomy:
-- - Contextual: context, requirements, acceptance-criteria (Planning reads these)
-- - Actionable: workflow-instruction, checklist, commands, guidance, process (Implementation reads these)
-- - Reference: reference, technical-details (Read as needed)

-- =============================================================================
-- PART 1: Create ALL 9 templates (if they don't exist)
-- =============================================================================
-- This ensures templates exist whether upgrading from v1.0 or fresh install

-- Insert Definition of Done template
INSERT OR IGNORE INTO templates (id, name, description, target_entity_type, is_built_in, is_protected, is_enabled, created_by, tags, created_at, modified_at)
VALUES (
    randomblob(16),
    'Definition of Done',
    'Template for defining clear completion criteria, quality checklist, and handoff requirements for projects, features, and tasks.',
    'TASK',
    1,
    1,
    1,
    'System',
    'completion,done,checklist,handoff,quality',
    datetime('now'),
    datetime('now')
);

-- Insert Local Git Branching Workflow template
INSERT OR IGNORE INTO templates (id, name, description, target_entity_type, is_built_in, is_protected, is_enabled, created_by, tags, created_at, modified_at)
VALUES (
    randomblob(16),
    'Local Git Branching Workflow',
    'A standardized workflow template for local git operations and branch management, optimized for AI agents working with version control.',
    'TASK',
    1,
    1,
    1,
    'System',
    'git,workflow,ai-optimized,version-control,branching',
    datetime('now'),
    datetime('now')
);

-- Insert GitHub PR Workflow template
INSERT OR IGNORE INTO templates (id, name, description, target_entity_type, is_built_in, is_protected, is_enabled, created_by, tags, created_at, modified_at)
VALUES (
    randomblob(16),
    'GitHub PR Workflow',
    'A standardized workflow template for GitHub pull request creation and management using GitHub MCP server and standard git commands, optimized for AI agents with best practices integration.',
    'TASK',
    1,
    1,
    1,
    'System',
    'github,pull-request,workflow,ai-optimized,mcp-tools,git',
    datetime('now'),
    datetime('now')
);

-- Insert Context & Background template
INSERT OR IGNORE INTO templates (id, name, description, target_entity_type, is_built_in, is_protected, is_enabled, created_by, tags, created_at, modified_at)
VALUES (
    randomblob(16),
    'Context & Background',
    'Template for capturing the strategic context, user needs, and coordination requirements that inform project and feature planning.',
    'FEATURE',
    1,
    1,
    1,
    'System',
    'context,background,business,strategic,documentation',
    datetime('now'),
    datetime('now')
);

-- Insert Testing Strategy template
INSERT OR IGNORE INTO templates (id, name, description, target_entity_type, is_built_in, is_protected, is_enabled, created_by, tags, created_at, modified_at)
VALUES (
    randomblob(16),
    'Testing Strategy',
    'Template for defining comprehensive testing approach, coverage requirements, and validation criteria for tasks and features.',
    'TASK',
    1,
    1,
    1,
    'System',
    'testing,quality,validation,coverage,strategy',
    datetime('now'),
    datetime('now')
);

-- Insert Requirements Specification template
INSERT OR IGNORE INTO templates (id, name, description, target_entity_type, is_built_in, is_protected, is_enabled, created_by, tags, created_at, modified_at)
VALUES (
    randomblob(16),
    'Requirements Specification',
    'Template for capturing detailed functional and non-functional requirements, acceptance criteria, and constraints for projects and features.',
    'FEATURE',
    1,
    1,
    1,
    'System',
    'requirements,specification,acceptance-criteria,constraints,documentation',
    datetime('now'),
    datetime('now')
);

-- Insert Technical Approach template
INSERT OR IGNORE INTO templates (id, name, description, target_entity_type, is_built_in, is_protected, is_enabled, created_by, tags, created_at, modified_at)
VALUES (
    randomblob(16),
    'Technical Approach',
    'Actionable implementation planning with decision frameworks, checklists, and integration validation.',
    'TASK',
    1,
    1,
    1,
    'System',
    'technical,implementation,planning,architecture',
    datetime('now'),
    datetime('now')
);

-- Insert Task Implementation Workflow template
INSERT OR IGNORE INTO templates (id, name, description, target_entity_type, is_built_in, is_protected, is_enabled, created_by, tags, created_at, modified_at)
VALUES (
    randomblob(16),
    'Task Implementation Workflow',
    'Systematic task execution workflow with analysis, implementation, and validation steps optimized for AI agents.',
    'TASK',
    1,
    1,
    1,
    'System',
    'workflow,implementation,ai-optimized,mcp-tools',
    datetime('now'),
    datetime('now')
);

-- Insert Bug Investigation Workflow template
INSERT OR IGNORE INTO templates (id, name, description, target_entity_type, is_built_in, is_protected, is_enabled, created_by, tags, created_at, modified_at)
VALUES (
    randomblob(16),
    'Bug Investigation Workflow',
    'Systematic bug investigation workflow with MCP tool integration for AI agents to investigate, analyze, and fix bugs.',
    'TASK',
    1,
    1,
    1,
    'System',
    'bug,investigation,workflow,ai-optimized,debugging,mcp-tools,task-type-bug',
    datetime('now'),
    datetime('now')
);

-- =============================================================================
-- PART 2: Delete old sections for templates we're updating (idempotent)
-- =============================================================================
-- This ensures we can update existing templates with new content/tags

DELETE FROM template_sections
WHERE template_id IN (
    SELECT id FROM templates WHERE name IN (
        'Technical Approach',
        'Task Implementation Workflow',
        'Bug Investigation Workflow'
    )
);

-- =============================================================================
-- PART 3: Create sections for Technical Approach template
-- =============================================================================

INSERT INTO template_sections (template_id, title, usage_description, content_sample, content_format, ordinal, is_required, tags)
SELECT
    t.id,
    'Implementation Planning Checklist',
    'Decision-making framework to guide implementation planning before coding',
    '### Implementation Planning Checklist

Before you start coding, work through these planning questions:

#### 1. Component Identification
**What are the 2-4 main classes/modules you''ll create or modify?**
- List each component with its single responsibility
- Example: `UserService` (CRUD operations), `AuthController` (HTTP endpoints), `TokenValidator` (JWT validation)

#### 2. Dependency Analysis
**What external libraries or services will you integrate?**
- List each dependency with specific purpose
- Verify versions match project requirements
- Check compatibility with existing dependencies

#### 3. Data Flow Design
**How does data flow through the system?**
- Input: Where does data enter? (API, database, file, user input)
- Processing: What transformations occur?
- Output: Where does data exit? (API response, database, file, UI)
- Validation: Where is data validated?

#### 4. Error Handling Strategy
**What can go wrong and how will you handle it?**
- Input validation failures
- External service failures (network, database, API)
- Business rule violations
- Resource constraints (memory, disk, connections)

#### 5. Testing Approach
**How will you verify this works?**
- Unit tests: What classes/functions need tests?
- Integration tests: What interactions need testing?
- Manual testing: What scenarios require human verification?',
    'MARKDOWN',
    0,
    1,
    'guidance,checklist,planning'
FROM templates t
WHERE t.name = 'Technical Approach';

INSERT INTO template_sections (template_id, title, usage_description, content_sample, content_format, ordinal, is_required, tags)
SELECT
    t.id,
    'Technical Decision Log',
    'Framework for documenting key technical decisions and their rationale',
    '### Technical Decision Log

Document significant technical choices made during implementation:

#### Decision 1: [Decision Title]
**Context**: [What problem or choice prompted this decision]

**Options Considered**:
1. **[Option A]**: [Brief description]
   - Pros: [Benefits]
   - Cons: [Drawbacks]
2. **[Option B]**: [Brief description]
   - Pros: [Benefits]
   - Cons: [Drawbacks]

**Decision**: [Which option was chosen]

**Rationale**: [Why this option was selected over alternatives]

**Consequences**: [Implications of this decision for future work]

---

#### Decision 2: [Decision Title]
[Repeat structure above]

### Documentation References
- Architecture patterns used: [Link to patterns or docs]
- Similar implementations: [Reference to existing code that uses similar approach]
- External resources: [Links to documentation that informed decisions]',
    'MARKDOWN',
    1,
    0,
    'technical-details,reference,decision-log'
FROM templates t
WHERE t.name = 'Technical Approach';

INSERT INTO template_sections (template_id, title, usage_description, content_sample, content_format, ordinal, is_required, tags)
SELECT
    t.id,
    'Integration Points Checklist',
    'Validation checklist for dependencies, interfaces, and integration concerns',
    '### Integration Points Checklist

Verify all integration concerns before implementation:

#### External Dependencies
- [ ] All required dependencies added to build file (build.gradle.kts, package.json, requirements.txt)
- [ ] Dependency versions verified compatible with project
- [ ] No dependency conflicts identified
- [ ] License compatibility verified (for open source projects)

#### Internal Interfaces
- [ ] Identified all classes/modules this component depends on
- [ ] Verified interfaces are stable (not under active development)
- [ ] Understood data contracts (input/output formats)
- [ ] Checked for breaking changes in recent commits

#### Data Persistence
- [ ] Database schema changes identified (if needed)
- [ ] Migration strategy planned (for schema changes)
- [ ] Data validation rules defined
- [ ] Transaction boundaries identified

#### API Integration
- [ ] API endpoints identified (if creating/consuming APIs)
- [ ] Request/response formats documented
- [ ] Error codes and handling defined
- [ ] Authentication/authorization requirements understood

#### Configuration
- [ ] Environment variables identified
- [ ] Configuration files updated
- [ ] Default values defined
- [ ] Configuration validation added

#### Observability
- [ ] Logging strategy defined (what to log, at what level)
- [ ] Metrics identified (if performance-critical)
- [ ] Error tracking integration (if applicable)
- [ ] Debug hooks planned (for troubleshooting)',
    'MARKDOWN',
    2,
    0,
    'checklist,guidance,integration,validation'
FROM templates t
WHERE t.name = 'Technical Approach';

-- =============================================================================
-- PART 4: Create sections for Task Implementation Workflow template
-- =============================================================================

INSERT INTO template_sections (template_id, title, usage_description, content_sample, content_format, ordinal, is_required, tags)
SELECT
    t.id,
    'Implementation Analysis',
    'Analyze task requirements and gather context using MCP tools before implementation',
    '### Implementation Analysis

Before starting work, gather complete context:

#### 1. Task Context Review
Use `query_container` to get task details:
```javascript
task = query_container(
  operation="get",
  containerType="task",
  id="[task-id]",
  includeSections=false  // Get metadata only first
)
```

Extract key information:
- **Requirements**: What needs to be built (from description field)
- **Complexity**: Expected effort (1-10 scale)
- **Priority**: When this needs completion
- **Status**: Current state and where it should go

#### 2. Read Actionable Workflow Sections
Use `query_sections` with tag filtering for token efficiency:
```javascript
sections = query_sections(
  entityType="TASK",
  entityId="[task-id]",
  tags="workflow-instruction,checklist,commands,guidance,process,acceptance-criteria",
  includeContent=true
)
```

This retrieves only implementation-relevant content (45-60% token savings).

#### 3. Check Dependencies
Use `query_dependencies` to identify blockers:
```javascript
dependencies = query_dependencies(
  taskId="[task-id]",
  direction="incoming",
  includeTaskInfo=true
)
```

For each dependency, verify it''s completed. Read dependency summaries for context.

#### 4. Understand Feature Context (if part of feature)
If task.featureId exists, get feature overview:
```javascript
feature = query_container(
  operation="overview",
  containerType="feature",
  id=task.featureId
)
```

Review feature description and related tasks for broader context.',
    'MARKDOWN',
    0,
    1,
    'analysis,planning,mcp-tools,guidance,process,workflow-instruction'
FROM templates t
WHERE t.name = 'Task Implementation Workflow';

INSERT INTO template_sections (template_id, title, usage_description, content_sample, content_format, ordinal, is_required, tags)
SELECT
    t.id,
    'Step-by-Step Implementation',
    'Systematic implementation workflow with quality standards and progress tracking',
    '### Step-by-Step Implementation

Follow this systematic approach:

#### Step 1: Set Up Work Environment
```bash
# Ensure you''re on correct branch
git branch --show-current

# Pull latest changes
git fetch origin
git rebase origin/main

# Verify project builds
./gradlew build
# or: npm run build, cargo build, etc.
```

#### Step 2: Implement Core Functionality
- Break work into small, testable increments
- Commit after each logical unit of work
- Follow project coding standards
- Add appropriate error handling
- Include logging for debugging

**Quality Standards**:
- Code is readable and self-documenting
- Complex logic has explanatory comments
- No hardcoded values (use configuration)
- Consistent with existing patterns

#### Step 3: Write Tests
```bash
# Create test file alongside implementation
# Follow project test structure

# Run tests frequently during development
./gradlew test --tests "*[YourClass]*"
```

**Test Coverage Requirements**:
- Unit tests for business logic
- Integration tests for external interactions
- Edge cases and error conditions
- Happy path scenarios

#### Step 4: Update Documentation
- Update code comments for public APIs
- Document any configuration changes
- Update README if user-facing changes
- Add inline explanations for complex algorithms

#### Step 5: Self-Review
Before marking complete:
- [ ] All tests pass
- [ ] Code follows project conventions
- [ ] No debugging code or console.logs left
- [ ] Error handling is appropriate
- [ ] Documentation is updated',
    'MARKDOWN',
    1,
    1,
    'implementation,execution,quality,workflow-instruction,process,commands,checklist'
FROM templates t
WHERE t.name = 'Task Implementation Workflow';

INSERT INTO template_sections (template_id, title, usage_description, content_sample, content_format, ordinal, is_required, tags)
SELECT
    t.id,
    'Testing & Validation',
    'Comprehensive validation before marking task complete',
    '### Testing & Validation

Validate all work before completion:

#### Run Full Test Suite
```bash
# Run all project tests
./gradlew test

# Check test coverage (if configured)
./gradlew jacocoTestReport
# or: npm run test:coverage
```

**Test Results Validation**:
- All tests pass (no failures or errors)
- No new warnings introduced
- Test coverage meets project standards
- All edge cases covered

#### Integration Testing
```bash
# Run integration tests
./gradlew integrationTest

# Test in environment similar to production
# Verify with real data (sanitized/test data)
```

#### Manual Verification
Test the actual functionality:
- [ ] Feature works as expected in UI/API
- [ ] Error cases display appropriate messages
- [ ] Performance is acceptable
- [ ] No regressions in related functionality

#### Code Quality Checks
```bash
# Run linter
./gradlew ktlintCheck  # Kotlin
# npm run lint          # JavaScript
# cargo clippy          # Rust

# Check for security issues
./gradlew dependencyCheckAnalyze
```

#### Update Task with Results
Use `manage_sections` to document results:
```javascript
manage_sections(
  operation="add",
  entityType="TASK",
  entityId="[task-id]",
  title="Testing Results",
  content="All tests passed: [N] unit tests, [M] integration tests...",
  tags="testing,validation,results"
)
```

Use `manage_container` to populate summary:
```javascript
manage_container(
  operation="update",
  containerType="task",
  id="[task-id]",
  summary="Implemented [feature]. All tests passing. Ready for review."
  // Summary: 300-500 chars describing WHAT was done and OUTCOME
)
```

#### Ready for Completion
- [ ] All tests pass
- [ ] Code quality checks pass
- [ ] Manual testing complete
- [ ] Documentation updated
- [ ] Summary field populated (300-500 chars)
- [ ] Implementation sections updated',
    'MARKDOWN',
    2,
    1,
    'testing,validation,quality-assurance,mcp-tools,workflow-instruction,commands,checklist'
FROM templates t
WHERE t.name = 'Task Implementation Workflow';

-- =============================================================================
-- PART 5: Create sections for Bug Investigation Workflow template
-- =============================================================================

INSERT INTO template_sections (template_id, title, usage_description, content_sample, content_format, ordinal, is_required, tags)
SELECT
    t.id,
    'Investigation Process',
    'Systematic approach to investigating bug reports and gathering diagnostic information',
    '### Initial Information Gathering
Start investigation with comprehensive information collection:

1. **Bug Context Retrieval**
   - Use `query_container` MCP tool to review complete bug report
   - Examine all sections for reproduction steps, expected vs actual behavior
   - Identify severity, impact, and any user-reported workarounds
   - Review any attached logs, screenshots, or error messages

2. **Related Issue Search**
   - Use `search_tasks` to find similar or related bug reports
   - Look for patterns in recent issues that might indicate systemic problems
   - Check for recently completed tasks that might have introduced the issue
   - Identify any ongoing work that could be related

3. **Environment Analysis**
   ```markdown
   ### Environment Factors
   - **Platform/OS**: [Affected platforms]
   - **Version**: [Software version where bug occurs]
   - **Configuration**: [Relevant configuration settings]
   - **Data State**: [Database or file state that might affect issue]
   - **Load Conditions**: [System load, concurrent users, etc.]
   ```

### Reproduction Strategy
Establish reliable reproduction of the issue:

1. **Controlled Reproduction**
   - Follow exact reproduction steps from bug report
   - Document any deviations from expected behavior
   - Try variations to understand scope of the issue
   - Test in multiple environments if possible

2. **Isolation Testing**
   - Remove variables one by one to isolate cause
   - Test with minimal configuration
   - Check with different data sets
   - Verify in clean environment

3. **Documentation of Findings**
   ```markdown
   ### Reproduction Results
   - **Consistently Reproducible**: [Yes/No]
   - **Reproduction Rate**: [X out of Y attempts]
   - **Required Conditions**: [Specific conditions needed]
   - **Variations Tested**: [Different scenarios attempted]
   ```

### Diagnostic Data Collection
Gather technical evidence:

1. **Log Analysis**
   - Review application logs during reproduction
   - Check system logs for related errors
   - Examine database logs if data-related
   - Capture performance metrics if relevant

2. **Code Path Tracing**
   - Identify code paths involved in the reported functionality
   - Use debugger or logging to trace execution
   - Monitor variable states at key points
   - Check for unexpected code branches taken',
    'MARKDOWN',
    0,
    1,
    'investigation,reproduction,diagnosis,mcp-tools,process,workflow-instruction,guidance'
FROM templates t
WHERE t.name = 'Bug Investigation Workflow';

INSERT INTO template_sections (template_id, title, usage_description, content_sample, content_format, ordinal, is_required, tags)
SELECT
    t.id,
    'Root Cause Analysis',
    'Structured approach to identifying the underlying cause of the bug through systematic analysis',
    '### Systematic Analysis Process
Apply structured approach to identify root cause:

1. **Code Review and Analysis**
   - Examine the specific code areas identified during investigation
   - Look for recent changes that might have introduced the issue
   - Check for common bug patterns (null pointer, off-by-one, concurrency issues)
   - Review error handling and edge case management

2. **Data Flow Analysis**
   ```markdown
   ### Data Flow Investigation
   - **Input Sources**: [Where does problematic data originate]
   - **Transformation Points**: [Where data is modified or processed]
   - **Validation Steps**: [Where data should be validated]
   - **Output Destinations**: [Where incorrect data ends up]
   ```

3. **Dependency Analysis**
   - Check external dependencies and their versions
   - Verify third-party library behavior
   - Examine configuration dependencies
   - Review database schema and constraints

### Hypothesis Formation
Develop and test hypotheses about the root cause:

1. **Primary Hypothesis**
   ```markdown
   ### Root Cause Hypothesis
   **Theory**: [Most likely cause based on evidence]
   **Supporting Evidence**:
   - [Evidence point 1]
   - [Evidence point 2]
   - [Evidence point 3]

   **Test Plan**: [How to verify this hypothesis]
   ```

2. **Alternative Hypotheses**
   - List other possible causes in order of likelihood
   - Note why each is less likely than primary hypothesis
   - Plan testing approach for each alternative

### Verification Testing
Test hypotheses systematically:

1. **Hypothesis Testing**
   - Design specific tests to prove/disprove each hypothesis
   - Create minimal test cases that isolate the suspected cause
   - Use debugging tools to verify assumptions
   - Document results of each test

2. **Impact Assessment**
   ```markdown
   ### Impact Analysis
   - **Affected Functionality**: [What features are impacted]
   - **User Impact**: [How many users affected, severity of impact]
   - **Data Impact**: [Risk of data corruption or loss]
   - **Performance Impact**: [System performance effects]
   ```

### Root Cause Documentation
Document findings clearly:

1. **Root Cause Statement**
   - Clear, concise description of the actual cause
   - Explanation of how the cause leads to observed behavior
   - Timeline of when the issue was likely introduced

2. **Contributing Factors**
   - Environmental factors that enabled the bug
   - Process gaps that allowed the bug to reach production
   - Monitoring gaps that delayed detection',
    'MARKDOWN',
    1,
    1,
    'root-cause,analysis,hypothesis,verification,process,guidance'
FROM templates t
WHERE t.name = 'Bug Investigation Workflow';

INSERT INTO template_sections (template_id, title, usage_description, content_sample, content_format, ordinal, is_required, tags)
SELECT
    t.id,
    'Fix Implementation & Verification',
    'Structured approach to implementing bug fixes and thoroughly verifying the resolution',
    '### Fix Strategy Development
Plan fix approach based on root cause analysis:

1. **Solution Design**
   ```markdown
   ### Fix Strategy
   **Approach**: [High-level approach to fixing the issue]
   **Files to Modify**: [Specific files that need changes]
   **Risk Assessment**: [Potential risks of the fix]
   **Alternatives Considered**: [Other approaches evaluated]
   ```

2. **Implementation Planning**
   - Break fix into logical steps
   - Identify testing approach for each step
   - Plan rollback strategy if fix causes issues
   - Consider timing and deployment requirements

### Fix Implementation Process
Follow systematic implementation approach:

1. **Branch Setup**
   ```bash
   # Create fix branch
   git checkout main
   git pull origin main
   git checkout -b bugfix/[issue-description]
   ```

2. **Incremental Development**
   - Implement fix in small, testable increments
   - Test each increment thoroughly
   - Commit changes with clear, descriptive messages
   - Document any unexpected discoveries

3. **Code Quality Focus**
   - Follow existing code patterns and conventions
   - Add appropriate error handling
   - Include logging for future debugging
   - Consider performance implications

### Comprehensive Testing Strategy
Ensure fix resolves issue without creating new problems:

1. **Fix Verification Testing**
   ```bash
   # Run targeted tests
   ./gradlew test --tests "*[RelatedTestClass]"

   # Test specific bug scenario
   [Specific test commands for bug reproduction]
   ```

2. **Regression Testing**
   - Run full test suite to ensure no new issues
   - Test related functionality thoroughly
   - Verify edge cases still work correctly
   - Check performance hasn''t degraded

3. **Integration Testing**
   - Test in environment similar to where bug occurred
   - Verify fix works with real data
   - Test with concurrent users if applicable
   - Check monitoring and logging work correctly

### Verification Documentation
Document fix effectiveness:

1. **Fix Validation Results**
   ```markdown
   ### Verification Results
   - **Bug Reproduction**: [Can no longer reproduce original issue]
   - **Regression Tests**: [All existing tests pass]
   - **Performance**: [No performance degradation observed]
   - **Edge Cases**: [Edge cases still work correctly]
   ```

2. **Task Status Update**
   - Use `update_task` MCP tool to update progress
   - Document fix implementation approach
   - Note any complications or learnings
   - Update task complexity if it differed from estimate

3. **Prevention Measures**
   ```markdown
   ### Prevention Strategy
   - **Tests Added**: [New tests to prevent regression]
   - **Monitoring**: [Additional monitoring or alerts added]
   - **Process Improvements**: [Process changes to prevent similar issues]
   - **Documentation**: [Documentation updates made]
   ```

### Ready for Review
Prepare fix for review and deployment:
- All tests pass including new tests for the bug
- Code follows project conventions
- Fix is well-documented and includes prevention measures
- Verification results clearly demonstrate issue resolution',
    'MARKDOWN',
    2,
    1,
    'fix-implementation,verification,testing,prevention,mcp-tools,workflow-instruction,checklist'
FROM templates t
WHERE t.name = 'Bug Investigation Workflow';

-- =============================================================================
-- PART 6: Update existing template sections with new tags
-- =============================================================================

-- Update Definition of Done sections
UPDATE template_sections
SET tags = 'implementation,quality,testing,documentation,checklist,acceptance-criteria'
WHERE title = 'Implementation Complete' AND template_id IN (SELECT id FROM templates WHERE name = 'Definition of Done');

UPDATE template_sections
SET tags = 'deployment,monitoring,performance,security,sign-off,checklist,acceptance-criteria'
WHERE title = 'Production Ready' AND template_id IN (SELECT id FROM templates WHERE name = 'Definition of Done');

-- Update Local Git Branching Workflow sections
UPDATE template_sections
SET tags = 'git,branch-creation,workflow-instruction,commands'
WHERE title = 'Create Branch' AND template_id IN (SELECT id FROM templates WHERE name = 'Local Git Branching Workflow');

UPDATE template_sections
SET tags = 'implementation,commits,workflow,workflow-instruction,commands'
WHERE title = 'Implement & Commit' AND template_id IN (SELECT id FROM templates WHERE name = 'Local Git Branching Workflow');

UPDATE template_sections
SET tags = 'verification,finalization,checklist,commands'
WHERE title = 'Verify & Finalize' AND template_id IN (SELECT id FROM templates WHERE name = 'Local Git Branching Workflow');

-- Update GitHub PR Workflow sections
UPDATE template_sections
SET tags = 'github,sync,validation,workflow-instruction,checklist,commands'
WHERE title = 'Pre-Push Validation' AND template_id IN (SELECT id FROM templates WHERE name = 'GitHub PR Workflow');

UPDATE template_sections
SET tags = 'git,pull-request,mcp-tools,workflow-instruction,commands'
WHERE title = 'Create Pull Request' AND template_id IN (SELECT id FROM templates WHERE name = 'GitHub PR Workflow');

UPDATE template_sections
SET tags = 'review,merge,mcp-tools,cleanup,process,checklist,commands'
WHERE title = 'Review & Merge' AND template_id IN (SELECT id FROM templates WHERE name = 'GitHub PR Workflow');

-- Update Context & Background sections
UPDATE template_sections
SET tags = 'context,business,value,objectives'
WHERE title = 'Why This Matters' AND template_id IN (SELECT id FROM templates WHERE name = 'Context & Background');

UPDATE template_sections
SET tags = 'users,needs,pain-points,context'
WHERE title = 'User Context' AND template_id IN (SELECT id FROM templates WHERE name = 'Context & Background');

UPDATE template_sections
SET tags = 'dependencies,coordination,technical-context,context'
WHERE title = 'Dependencies & Coordination' AND template_id IN (SELECT id FROM templates WHERE name = 'Context & Background');

-- Update Testing Strategy sections
UPDATE template_sections
SET tags = 'testing,coverage,unit-tests,integration-tests,guidance,reference'
WHERE title = 'Test Coverage' AND template_id IN (SELECT id FROM templates WHERE name = 'Testing Strategy');

UPDATE template_sections
SET tags = 'acceptance-criteria,completion,quality,checklist'
WHERE title = 'Acceptance Criteria' AND template_id IN (SELECT id FROM templates WHERE name = 'Testing Strategy');

UPDATE template_sections
SET tags = 'checkpoints,validation,deployment,checklist,process,workflow-instruction'
WHERE title = 'Testing Checkpoints' AND template_id IN (SELECT id FROM templates WHERE name = 'Testing Strategy');

-- Update Requirements Specification sections
UPDATE template_sections
SET tags = 'functional,core,critical,acceptance-criteria,requirements,context'
WHERE title = 'Must-Have Requirements' AND template_id IN (SELECT id FROM templates WHERE name = 'Requirements Specification');

UPDATE template_sections
SET tags = 'optional,enhancements,future,nice-to-have,requirements,context'
WHERE title = 'Nice-to-Have Features' AND template_id IN (SELECT id FROM templates WHERE name = 'Requirements Specification');

UPDATE template_sections
SET tags = 'constraints,non-functional,performance,security,requirements,context'
WHERE title = 'Constraints & Non-Functional Requirements' AND template_id IN (SELECT id FROM templates WHERE name = 'Requirements Specification');

-- =============================================================================
-- Migration Complete
-- =============================================================================
-- This migration is IDEMPOTENT and handles both scenarios:
-- 1. Fresh install: Creates all 9 templates with 26 sections
-- 2. Existing templates: Updates content and tags to v2.0 structure
--
-- Templates created/updated:
-- 1. Technical Approach (3 sections) - Complete rewrite with new content
-- 2. Task Implementation Workflow (3 sections) - New template
-- 3. Bug Investigation Workflow (3 sections) - New template
-- 4-9. Existing templates from V5 - Tags updated to new taxonomy
--
-- Total: 9 templates, 26 sections
-- - 7 TASK templates
-- - 2 FEATURE templates
--
-- Tag taxonomy applied:
-- - Contextual: context, requirements, acceptance-criteria (Planning reads)
-- - Actionable: workflow-instruction, checklist, commands, guidance, process (Implementation reads)
-- - Reference: reference, technical-details (Read as needed)

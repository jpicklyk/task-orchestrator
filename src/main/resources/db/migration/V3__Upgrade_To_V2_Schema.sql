-- V3: Upgrade from v1.0.1 to v2.0.0 schema
-- This migration consolidates all v2.0.0 enhancements (originally V3-V12) into one migration.
--
-- Changes Applied:
-- 1. Add optimistic locking (version columns to projects, features, sections)
-- 2. Add description fields to projects and features
-- 3. Expand status enums to v2.0 values (31 total statuses)
-- 4. Add comprehensive performance indexes
-- 5. Initialize built-in templates (9 templates, 26 sections)
--
-- Supports:
-- - Upgrade from v1.0.1 (schema_version=2) → v2.0.0 (schema_version=3)
-- - Fresh installs (V1 → V2 → V3 sequentially)

-- =============================================================================
-- STEP 1: Add Optimistic Locking and Description Fields
-- =============================================================================

-- Add version column to projects
ALTER TABLE projects ADD COLUMN version INTEGER NOT NULL DEFAULT 1;
CREATE INDEX idx_projects_version ON projects(version);

-- Add description column to projects
ALTER TABLE projects ADD COLUMN description TEXT;

-- Add version column to features
ALTER TABLE features ADD COLUMN version INTEGER NOT NULL DEFAULT 1;
CREATE INDEX idx_features_version ON features(version);

-- Add description column to features
ALTER TABLE features ADD COLUMN description TEXT;

-- Add version column to sections
ALTER TABLE sections ADD COLUMN version INTEGER NOT NULL DEFAULT 1;

-- =============================================================================
-- STEP 2: Add Performance Indexes
-- =============================================================================

-- Dependency indexes for query optimization
CREATE INDEX idx_dependencies_from_task_id ON dependencies(from_task_id);
CREATE INDEX idx_dependencies_to_task_id ON dependencies(to_task_id);

-- Task composite indexes for filtering
CREATE INDEX idx_tasks_feature_status ON tasks(feature_id, status);
CREATE INDEX idx_tasks_project_feature ON tasks(project_id, feature_id);
CREATE INDEX idx_tasks_status_priority_complexity ON tasks(status, priority, complexity);
CREATE INDEX idx_tasks_feature_status_priority ON tasks(feature_id, status, priority);

-- Feature composite indexes for filtering
CREATE INDEX idx_features_status_priority ON features(status, priority);
CREATE INDEX idx_features_project_status ON features(project_id, status);

-- =============================================================================
-- STEP 3: Expand Status Enums to v2.0 Values
-- SQLite doesn't support ALTER TABLE for CHECK constraints, so we rebuild tables
-- =============================================================================

-- Rebuild projects table with expanded status enum (6 statuses)
CREATE TABLE projects_new (
    id BLOB PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    summary TEXT NOT NULL,
    description TEXT,
    status VARCHAR(20) NOT NULL CHECK (status IN ('PLANNING', 'IN_DEVELOPMENT', 'ON_HOLD', 'CANCELLED', 'COMPLETED', 'ARCHIVED')),
    version INTEGER NOT NULL DEFAULT 1,
    created_at TIMESTAMP NOT NULL,
    modified_at TIMESTAMP NOT NULL,
    search_vector TEXT
);

INSERT INTO projects_new SELECT id, name, summary, description, status, version, created_at, modified_at, search_vector FROM projects;
DROP TABLE projects;
ALTER TABLE projects_new RENAME TO projects;

-- Recreate projects indexes
CREATE INDEX idx_projects_status ON projects(status);
CREATE INDEX idx_projects_created_at ON projects(created_at);
CREATE INDEX idx_projects_modified_at ON projects(modified_at);
CREATE INDEX idx_projects_version ON projects(version);

-- Rebuild features table with expanded status enum (11 statuses)
CREATE TABLE features_new (
    id BLOB PRIMARY KEY,
    project_id BLOB,
    name TEXT NOT NULL,
    summary TEXT NOT NULL,
    description TEXT,
    status VARCHAR(20) NOT NULL CHECK (status IN ('DRAFT', 'PLANNING', 'IN_DEVELOPMENT', 'TESTING', 'VALIDATING', 'PENDING_REVIEW', 'BLOCKED', 'ON_HOLD', 'DEPLOYED', 'COMPLETED', 'ARCHIVED')),
    priority VARCHAR(10) NOT NULL CHECK (priority IN ('HIGH', 'MEDIUM', 'LOW')),
    version INTEGER NOT NULL DEFAULT 1,
    created_at TIMESTAMP NOT NULL,
    modified_at TIMESTAMP NOT NULL,
    search_vector TEXT,
    FOREIGN KEY (project_id) REFERENCES projects(id)
);

INSERT INTO features_new SELECT id, project_id, name, summary, description, status, priority, version, created_at, modified_at, search_vector FROM features;
DROP TABLE features;
ALTER TABLE features_new RENAME TO features;

-- Recreate features indexes
CREATE INDEX idx_features_project_id ON features(project_id);
CREATE INDEX idx_features_status ON features(status);
CREATE INDEX idx_features_priority ON features(priority);
CREATE INDEX idx_features_created_at ON features(created_at);
CREATE INDEX idx_features_modified_at ON features(modified_at);
CREATE INDEX idx_features_version ON features(version);
CREATE INDEX idx_features_status_priority ON features(status, priority);
CREATE INDEX idx_features_project_status ON features(project_id, status);

-- Rebuild tasks table with expanded status enum (14 statuses)
CREATE TABLE tasks_new (
    id BLOB PRIMARY KEY,
    project_id BLOB,
    feature_id BLOB,
    title TEXT NOT NULL,
    summary TEXT NOT NULL,
    status VARCHAR(20) NOT NULL CHECK (status IN ('BACKLOG', 'PENDING', 'IN_PROGRESS', 'IN_REVIEW', 'CHANGES_REQUESTED', 'TESTING', 'READY_FOR_QA', 'INVESTIGATING', 'BLOCKED', 'ON_HOLD', 'DEPLOYED', 'COMPLETED', 'CANCELLED', 'DEFERRED')),
    priority VARCHAR(20) NOT NULL CHECK (priority IN ('HIGH', 'MEDIUM', 'LOW')),
    complexity INTEGER NOT NULL,
    created_at TIMESTAMP NOT NULL,
    modified_at TIMESTAMP NOT NULL,
    version INTEGER NOT NULL DEFAULT 1,
    last_modified_by TEXT,
    lock_status VARCHAR(20) NOT NULL DEFAULT 'UNLOCKED' CHECK (lock_status IN ('UNLOCKED', 'LOCKED_EXCLUSIVE', 'LOCKED_SHARED', 'LOCKED_SECTION')),
    search_vector TEXT,
    FOREIGN KEY (project_id) REFERENCES projects(id),
    FOREIGN KEY (feature_id) REFERENCES features(id)
);

INSERT INTO tasks_new SELECT id, project_id, feature_id, title, summary, status, priority, complexity, created_at, modified_at, version, last_modified_by, lock_status, search_vector FROM tasks;
DROP TABLE tasks;
ALTER TABLE tasks_new RENAME TO tasks;

-- Recreate tasks indexes
CREATE INDEX idx_tasks_project_id ON tasks(project_id);
CREATE INDEX idx_tasks_feature_id ON tasks(feature_id);
CREATE INDEX idx_tasks_status ON tasks(status);
CREATE INDEX idx_tasks_priority ON tasks(priority);
CREATE INDEX idx_tasks_version ON tasks(version);
CREATE INDEX idx_tasks_lock_status ON tasks(lock_status);
CREATE INDEX idx_tasks_last_modified_by ON tasks(last_modified_by);
CREATE INDEX idx_tasks_feature_status ON tasks(feature_id, status);
CREATE INDEX idx_tasks_project_feature ON tasks(project_id, feature_id);
CREATE INDEX idx_tasks_status_priority_complexity ON tasks(status, priority, complexity);
CREATE INDEX idx_tasks_feature_status_priority ON tasks(feature_id, status, priority);

-- =============================================================================
-- STEP 4: Initialize Built-in Templates (9 templates, 26 sections)
-- =============================================================================

-- Template 1: Definition of Done (TASK) - 2 sections
INSERT INTO templates (id, name, description, target_entity_type, is_built_in, is_protected, is_enabled, created_by, tags, created_at, modified_at)
VALUES (
    randomblob(16),
    'Definition of Done',
    'Comprehensive checklist template for ensuring tasks meet quality standards before being marked as complete. Covers implementation quality, testing, documentation, and production readiness.',
    'TASK',
    1, 1, 1,
    'system',
    'quality,checklist,completion,standards',
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
);

INSERT INTO template_sections (id, template_id, title, usage_description, content_sample, content_format, ordinal, is_required, tags)
SELECT
    randomblob(16), t.id,
    'Implementation Complete',
    'Criteria confirming the implementation is functionally complete and meets quality standards',
    '### Functionality
- [ ] **Core Features**: All required functionality is implemented and working
- [ ] **Acceptance Criteria**: All defined acceptance criteria are met
- [ ] **Edge Cases**: Edge cases and error conditions are handled appropriately
- [ ] **Integration**: Required integrations are working correctly

### Code Quality
- [ ] **Code Review**: Code has been reviewed and approved
- [ ] **Coding Standards**: Code follows project conventions and standards
- [ ] **Documentation**: Complex logic and public APIs are documented
- [ ] **No Critical Issues**: Static analysis passes without critical issues

### Testing
- [ ] **Unit Tests**: Unit tests are written and passing
- [ ] **Integration Tests**: Integration tests are written and passing
- [ ] **Test Coverage**: Test coverage meets project requirements
- [ ] **No Regressions**: Existing functionality still works correctly',
    'MARKDOWN', 0, 1,
    'implementation,quality,testing'
FROM templates t WHERE t.name = 'Definition of Done';

INSERT INTO template_sections (id, template_id, title, usage_description, content_sample, content_format, ordinal, is_required, tags)
SELECT
    randomblob(16), t.id,
    'Production Ready',
    'Criteria confirming the implementation is ready for production deployment and operation',
    '### Deployment
- [ ] **Build Success**: Code builds successfully in CI/CD pipeline
- [ ] **Deployment Verified**: Successfully deployed to staging/test environment
- [ ] **Configuration**: Environment-specific configuration is correct
- [ ] **Rollback Plan**: Rollback procedure is documented and tested

### Observability
- [ ] **Logging**: Appropriate logging is in place for debugging and monitoring
- [ ] **Monitoring**: Health checks and key metrics are being monitored
- [ ] **Error Handling**: Errors are handled gracefully with proper logging

### Performance & Security
- [ ] **Performance**: Meets defined performance requirements
- [ ] **Security**: Security implications have been reviewed
- [ ] **Input Validation**: User input is properly validated and sanitized',
    'MARKDOWN', 1, 1,
    'deployment,monitoring,performance,security'
FROM templates t WHERE t.name = 'Definition of Done';

-- Template 2: Local Git Branching Workflow (TASK) - 3 sections
INSERT INTO templates (id, name, description, target_entity_type, is_built_in, is_protected, is_enabled, created_by, tags, created_at, modified_at)
VALUES (
    randomblob(16),
    'Local Git Branching Workflow',
    'Template for local git branching workflow without PR/remote push requirements. Ideal for solo development or local feature branches.',
    'TASK',
    1, 1, 1,
    'system',
    'git,workflow,local,branching',
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
);

INSERT INTO template_sections (id, template_id, title, usage_description, content_sample, content_format, ordinal, is_required, tags)
SELECT
    randomblob(16), t.id,
    'Create Branch',
    'Creating and setting up a new git branch for local development',
    '### Branch Naming
- Use descriptive branch names: `feature/task-name` or `fix/bug-name`
- Keep branch names concise but meaningful
- Use lowercase with hyphens for readability

### Create Branch
```bash
git checkout -b feature/task-name
```

### Verify Branch
```bash
git branch  # Confirm you are on the new branch
git status  # Check working tree is clean
```',
    'MARKDOWN', 0, 1,
    'git,branching,setup'
FROM templates t WHERE t.name = 'Local Git Branching Workflow';

INSERT INTO template_sections (id, template_id, title, usage_description, content_sample, content_format, ordinal, is_required, tags)
SELECT
    randomblob(16), t.id,
    'Implement & Commit',
    'Making changes and committing work to the local branch',
    '### Implementation
- Make focused, incremental changes
- Test changes locally as you go
- Keep commits logical and atomic

### Commit Changes
```bash
git add .
git commit -m "Clear description of changes"
```

### Best Practices
- Write clear, descriptive commit messages
- Commit related changes together
- Avoid committing unrelated changes in one commit
- Test before committing',
    'MARKDOWN', 1, 1,
    'git,commit,implementation'
FROM templates t WHERE t.name = 'Local Git Branching Workflow';

INSERT INTO template_sections (id, template_id, title, usage_description, content_sample, content_format, ordinal, is_required, tags)
SELECT
    randomblob(16), t.id,
    'Verify & Finalize',
    'Final verification and merging the branch',
    '### Verify Implementation
```bash
# Run tests
./gradlew test

# Verify build
./gradlew build

# Check for uncommitted changes
git status
```

### Merge to Main
```bash
git checkout main
git merge feature/task-name
```

### Cleanup
```bash
git branch -d feature/task-name  # Delete local branch
```',
    'MARKDOWN', 2, 1,
    'git,merge,verification'
FROM templates t WHERE t.name = 'Local Git Branching Workflow';

-- Template 3: GitHub PR Workflow (TASK) - 3 sections
INSERT INTO templates (id, name, description, target_entity_type, is_built_in, is_protected, is_enabled, created_by, tags, created_at, modified_at)
VALUES (
    randomblob(16),
    'GitHub PR Workflow',
    'Template for GitHub Pull Request workflow with remote collaboration. Includes pre-push validation, PR creation, and review process.',
    'TASK',
    1, 1, 1,
    'system',
    'git,github,pr,workflow,collaboration',
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
);

INSERT INTO template_sections (id, template_id, title, usage_description, content_sample, content_format, ordinal, is_required, tags)
SELECT
    randomblob(16), t.id,
    'Pre-Push Validation',
    'Validation steps before pushing to remote and creating a pull request',
    '### Run Full Test Suite
```bash
./gradlew test
```

### Verify Build
```bash
./gradlew build
```

### Check for Issues
- [ ] All tests passing
- [ ] No compilation errors
- [ ] No linting errors
- [ ] Code follows project conventions

### Commit and Push
```bash
git add .
git commit -m "Descriptive commit message"
git push origin feature/branch-name
```',
    'MARKDOWN', 0, 1,
    'validation,testing,pre-push'
FROM templates t WHERE t.name = 'GitHub PR Workflow';

INSERT INTO template_sections (id, template_id, title, usage_description, content_sample, content_format, ordinal, is_required, tags)
SELECT
    randomblob(16), t.id,
    'Create Pull Request',
    'Creating a well-documented pull request on GitHub',
    '### Create PR
```bash
gh pr create --title "Clear PR title" --body "Description of changes"
```

Or use GitHub UI: https://github.com/[repo]/compare/[branch]

### PR Description Template
**Summary**
- Brief overview of changes
- Why these changes are needed

**Changes Made**
- Bullet list of key changes
- Any new dependencies or configuration

**Testing**
- How changes were tested
- Any testing notes or caveats

**Checklist**
- [ ] Tests added/updated
- [ ] Documentation updated
- [ ] No breaking changes (or documented if necessary)',
    'MARKDOWN', 1, 1,
    'github,pr,documentation'
FROM templates t WHERE t.name = 'GitHub PR Workflow';

INSERT INTO template_sections (id, template_id, title, usage_description, content_sample, content_format, ordinal, is_required, tags)
SELECT
    randomblob(16), t.id,
    'Review & Merge',
    'Code review process and merging the pull request',
    '### Request Review
- Assign reviewers (if not automatic)
- Respond to CI/CD checks
- Address any automated feedback

### Address Feedback
- Respond to reviewer comments
- Make requested changes
- Push updates to same branch (PR auto-updates)

### Merge PR
Once approved and CI passes:
```bash
# Via GitHub CLI
gh pr merge --squash  # or --merge or --rebase

# Or use GitHub UI
```

### Post-Merge Cleanup
```bash
# Delete remote branch (usually automatic)
git push origin --delete feature/branch-name

# Delete local branch
git checkout main
git pull
git branch -d feature/branch-name
```',
    'MARKDOWN', 2, 1,
    'review,merge,collaboration'
FROM templates t WHERE t.name = 'GitHub PR Workflow';

-- Template 4: Context & Background (FEATURE) - 3 sections
INSERT INTO templates (id, name, description, target_entity_type, is_built_in, is_protected, is_enabled, created_by, tags, created_at, modified_at)
VALUES (
    randomblob(16),
    'Context & Background',
    'Template for documenting the context, rationale, and strategic importance of a feature. Helps teams understand the "why" behind the work.',
    'FEATURE',
    1, 1, 1,
    'system',
    'context,background,rationale,strategy',
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
);

INSERT INTO template_sections (id, template_id, title, usage_description, content_sample, content_format, ordinal, is_required, tags)
SELECT
    randomblob(16), t.id,
    'Why This Matters',
    'The strategic rationale and business value of this feature',
    '### Problem Statement
[Describe the problem this feature solves]

### Business Value
- **Impact**: [Who benefits and how]
- **Priority**: [Why this is important now]
- **Success Metrics**: [How we will measure success]

### Strategic Alignment
[How this fits into broader product/business strategy]',
    'MARKDOWN', 0, 1,
    'rationale,business-value,strategy'
FROM templates t WHERE t.name = 'Context & Background';

INSERT INTO template_sections (id, template_id, title, usage_description, content_sample, content_format, ordinal, is_required, tags)
SELECT
    randomblob(16), t.id,
    'User Context',
    'Understanding the users and their needs for this feature',
    '### Target Users
[Who will use this feature]

### User Needs
- **Current Pain Points**: [What problems users currently face]
- **Desired Outcomes**: [What users want to accomplish]
- **Usage Scenarios**: [How and when users will use this]

### User Experience Considerations
[Important UX aspects to keep in mind]',
    'MARKDOWN', 1, 1,
    'users,needs,experience'
FROM templates t WHERE t.name = 'Context & Background';

INSERT INTO template_sections (id, template_id, title, usage_description, content_sample, content_format, ordinal, is_required, tags)
SELECT
    randomblob(16), t.id,
    'Dependencies & Coordination',
    'External dependencies, stakeholders, and coordination needs',
    '### Dependencies
- **Technical Dependencies**: [Required systems, APIs, or infrastructure]
- **Team Dependencies**: [Other teams or roles involved]
- **Timeline Dependencies**: [Sequencing or deadlines]

### Stakeholders
- **Key Stakeholders**: [Who needs to be involved or informed]
- **Communication Plan**: [How and when to communicate progress]

### Risks & Constraints
- **Known Risks**: [What could go wrong]
- **Constraints**: [Limitations or boundaries to work within]',
    'MARKDOWN', 2, 1,
    'dependencies,stakeholders,risks'
FROM templates t WHERE t.name = 'Context & Background';

-- Template 5: Testing Strategy (TASK) - 3 sections
INSERT INTO templates (id, name, description, target_entity_type, is_built_in, is_protected, is_enabled, created_by, tags, created_at, modified_at)
VALUES (
    randomblob(16),
    'Testing Strategy',
    'Template for defining comprehensive testing approach including unit, integration, and acceptance testing.',
    'TASK',
    1, 1, 1,
    'system',
    'testing,quality,validation,strategy',
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
);

INSERT INTO template_sections (id, template_id, title, usage_description, content_sample, content_format, ordinal, is_required, tags)
SELECT
    randomblob(16), t.id,
    'Test Coverage',
    'Types of tests to write and what they should cover',
    '### Unit Tests
- **Scope**: [What units/components to test]
- **Key Scenarios**: [Critical paths and edge cases]
- **Mocking Strategy**: [What to mock and how]

### Integration Tests
- **Scope**: [What integrations to test]
- **Test Data**: [How to set up test data]
- **External Dependencies**: [How to handle external systems]

### Coverage Goals
- **Minimum Coverage**: [Target percentage or critical paths]
- **Focus Areas**: [High-risk or complex areas to prioritize]',
    'MARKDOWN', 0, 1,
    'unit-testing,integration-testing,coverage'
FROM templates t WHERE t.name = 'Testing Strategy';

INSERT INTO template_sections (id, template_id, title, usage_description, content_sample, content_format, ordinal, is_required, tags)
SELECT
    randomblob(16), t.id,
    'Acceptance Criteria',
    'Specific criteria that must be met for the task to be considered complete',
    '### Functional Acceptance
- [ ] [Criterion 1: Specific functionality works as expected]
- [ ] [Criterion 2: Edge cases are handled correctly]
- [ ] [Criterion 3: Error conditions produce appropriate results]

### Quality Acceptance
- [ ] All tests passing
- [ ] No new compiler warnings
- [ ] Code review approved
- [ ] Documentation updated

### Non-Functional Acceptance
- [ ] [Performance criteria met]
- [ ] [Security requirements satisfied]
- [ ] [Accessibility standards met (if applicable)]',
    'MARKDOWN', 1, 1,
    'acceptance,criteria,validation'
FROM templates t WHERE t.name = 'Testing Strategy';

INSERT INTO template_sections (id, template_id, title, usage_description, content_sample, content_format, ordinal, is_required, tags)
SELECT
    randomblob(16), t.id,
    'Testing Checkpoints',
    'Key verification points throughout the implementation process',
    '### During Development
- [ ] Write unit tests before or alongside implementation
- [ ] Run tests frequently during development
- [ ] Test edge cases and error conditions
- [ ] Verify changes do not break existing tests

### Before Committing
- [ ] Run full test suite locally
- [ ] Verify all tests pass
- [ ] Check test coverage meets requirements
- [ ] Review test quality and clarity

### Before Completing Task
- [ ] All acceptance criteria tests passing
- [ ] Integration tests cover key workflows
- [ ] Manual testing completed for UI changes
- [ ] Performance testing completed if applicable',
    'MARKDOWN', 2, 1,
    'checkpoints,verification,process'
FROM templates t WHERE t.name = 'Testing Strategy';

-- Template 6: Requirements Specification (FEATURE) - 3 sections
INSERT INTO templates (id, name, description, target_entity_type, is_built_in, is_protected, is_enabled, created_by, tags, created_at, modified_at)
VALUES (
    randomblob(16),
    'Requirements Specification',
    'Template for capturing detailed functional and non-functional requirements for features. Ensures clear understanding of what needs to be built.',
    'FEATURE',
    1, 1, 1,
    'system',
    'requirements,specification,planning,scope',
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
);

INSERT INTO template_sections (id, template_id, title, usage_description, content_sample, content_format, ordinal, is_required, tags)
SELECT
    randomblob(16), t.id,
    'Must-Have Requirements',
    'Core requirements that are essential for the feature to be considered complete',
    '### Functional Requirements
1. **[Requirement 1]**: [Detailed description of required functionality]
2. **[Requirement 2]**: [Detailed description of required functionality]
3. **[Requirement 3]**: [Detailed description of required functionality]

### User Acceptance
- **Success Criteria**: [How we know this requirement is met]
- **User Stories**: [As a [user], I want [feature] so that [benefit]]

### Dependencies
- [Any dependencies on other features or systems]',
    'MARKDOWN', 0, 1,
    'requirements,must-have,core'
FROM templates t WHERE t.name = 'Requirements Specification';

INSERT INTO template_sections (id, template_id, title, usage_description, content_sample, content_format, ordinal, is_required, tags)
SELECT
    randomblob(16), t.id,
    'Nice-to-Have Features',
    'Optional enhancements that would improve the feature but are not essential',
    '### Enhancement Opportunities
1. **[Enhancement 1]**: [Description and potential benefit]
2. **[Enhancement 2]**: [Description and potential benefit]
3. **[Enhancement 3]**: [Description and potential benefit]

### Prioritization
- **High Priority**: [Enhancements that add significant value]
- **Low Priority**: [Nice improvements for future consideration]

### Trade-offs
[Discussion of what we might gain vs. complexity added]',
    'MARKDOWN', 1, 0,
    'enhancements,nice-to-have,optional'
FROM templates t WHERE t.name = 'Requirements Specification';

INSERT INTO template_sections (id, template_id, title, usage_description, content_sample, content_format, ordinal, is_required, tags)
SELECT
    randomblob(16), t.id,
    'Constraints & Non-Functional Requirements',
    'Technical constraints, performance requirements, and quality attributes',
    '### Technical Constraints
- **Platform**: [Platform or environment constraints]
- **Technology**: [Technology stack limitations]
- **Integration**: [External system constraints]

### Performance Requirements
- **Response Time**: [Maximum acceptable latency]
- **Throughput**: [Expected load or transaction volume]
- **Scalability**: [Growth expectations]

### Quality Attributes
- **Security**: [Security requirements or concerns]
- **Reliability**: [Uptime or error rate expectations]
- **Usability**: [User experience requirements]
- **Maintainability**: [Code quality and documentation needs]',
    'MARKDOWN', 2, 1,
    'constraints,non-functional,performance,quality'
FROM templates t WHERE t.name = 'Requirements Specification';

-- Template 7: Technical Approach (TASK) - 3 sections
INSERT INTO templates (id, name, description, target_entity_type, is_built_in, is_protected, is_enabled, created_by, tags, created_at, modified_at)
VALUES (
    randomblob(16),
    'Technical Approach',
    'Template for documenting technical design and implementation approach. Helps teams align on architecture and design decisions.',
    'TASK',
    1, 1, 1,
    'system',
    'technical,architecture,design,approach',
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
);

INSERT INTO template_sections (id, template_id, title, usage_description, content_sample, content_format, ordinal, is_required, tags)
SELECT
    randomblob(16), t.id,
    'Architecture Overview',
    'High-level architectural approach and design patterns',
    '### Design Pattern
[Which design pattern(s) will be used and why]

### Component Structure
[How the code will be organized into components/modules]

### Data Flow
[How data moves through the system]

### Integration Points
[How this integrates with existing systems]',
    'MARKDOWN', 0, 1,
    'architecture,design,patterns'
FROM templates t WHERE t.name = 'Technical Approach';

INSERT INTO template_sections (id, template_id, title, usage_description, content_sample, content_format, ordinal, is_required, tags)
SELECT
    randomblob(16), t.id,
    'Key Dependencies',
    'Technical dependencies and external integrations',
    '### Libraries & Frameworks
- **[Library 1]**: [Purpose and version]
- **[Library 2]**: [Purpose and version]

### External APIs
- **[API 1]**: [Purpose and usage]
- **[API 2]**: [Purpose and usage]

### Database Changes
- **Schema Changes**: [Any table or column changes]
- **Migrations**: [How to handle existing data]

### Infrastructure
[Any infrastructure or configuration requirements]',
    'MARKDOWN', 1, 1,
    'dependencies,libraries,apis,database'
FROM templates t WHERE t.name = 'Technical Approach';

INSERT INTO template_sections (id, template_id, title, usage_description, content_sample, content_format, ordinal, is_required, tags)
SELECT
    randomblob(16), t.id,
    'Implementation Strategy',
    'Step-by-step approach to implementing the technical solution',
    '### Implementation Steps
1. **[Step 1]**: [What to implement first and why]
2. **[Step 2]**: [Next step with rationale]
3. **[Step 3]**: [Following step with rationale]

### Risk Mitigation
- **[Risk 1]**: [How to mitigate or handle]
- **[Risk 2]**: [How to mitigate or handle]

### Testing Strategy
[How to test the implementation]

### Rollout Plan
[How to safely deploy and roll out changes]',
    'MARKDOWN', 2, 1,
    'implementation,strategy,steps,rollout'
FROM templates t WHERE t.name = 'Technical Approach';

-- Template 8: Task Implementation Workflow (TASK) - 3 sections
INSERT INTO templates (id, name, description, target_entity_type, is_built_in, is_protected, is_enabled, created_by, tags, created_at, modified_at)
VALUES (
    randomblob(16),
    'Task Implementation Workflow',
    'Template for step-by-step task implementation workflow. Provides structure for implementing and validating task work.',
    'TASK',
    1, 1, 1,
    'system',
    'workflow,implementation,process,task',
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
);

INSERT INTO template_sections (id, template_id, title, usage_description, content_sample, content_format, ordinal, is_required, tags)
SELECT
    randomblob(16), t.id,
    'Implementation Analysis',
    'Understanding the task and planning the implementation',
    '### Task Understanding
- **Objective**: [What needs to be accomplished]
- **Scope**: [What is included and what is not]
- **Success Criteria**: [How we know it is done]

### Technical Analysis
- **Affected Components**: [What parts of the codebase will change]
- **Dependencies**: [What this depends on]
- **Complexity Assessment**: [Simple/Medium/Complex and why]

### Implementation Plan
1. [Step 1]
2. [Step 2]
3. [Step 3]',
    'MARKDOWN', 0, 1,
    'analysis,planning,scope'
FROM templates t WHERE t.name = 'Task Implementation Workflow';

INSERT INTO template_sections (id, template_id, title, usage_description, content_sample, content_format, ordinal, is_required, tags)
SELECT
    randomblob(16), t.id,
    'Step-by-Step Implementation',
    'Detailed implementation steps with progress tracking',
    '### Implementation Checklist
- [ ] **Step 1**: [Specific action item]
- [ ] **Step 2**: [Specific action item]
- [ ] **Step 3**: [Specific action item]

### Code Changes
[Document key code changes as you make them]

### Issues Encountered
[Track any problems or blockers discovered]

### Decisions Made
[Document any important decisions during implementation]',
    'MARKDOWN', 1, 1,
    'implementation,steps,progress'
FROM templates t WHERE t.name = 'Task Implementation Workflow';

INSERT INTO template_sections (id, template_id, title, usage_description, content_sample, content_format, ordinal, is_required, tags)
SELECT
    randomblob(16), t.id,
    'Testing & Validation',
    'Verification steps to ensure the implementation is correct',
    '### Unit Testing
- [ ] Unit tests written
- [ ] All unit tests passing
- [ ] Edge cases covered

### Integration Testing
- [ ] Integration tests written
- [ ] All integration tests passing
- [ ] End-to-end scenarios validated

### Manual Verification
- [ ] Tested in development environment
- [ ] Verified against acceptance criteria
- [ ] No regressions found

### Final Checks
- [ ] Code review completed
- [ ] Documentation updated
- [ ] Ready for deployment',
    'MARKDOWN', 2, 1,
    'testing,validation,verification'
FROM templates t WHERE t.name = 'Task Implementation Workflow';

-- Template 9: Bug Investigation Workflow (TASK) - 3 sections
INSERT INTO templates (id, name, description, target_entity_type, is_built_in, is_protected, is_enabled, created_by, tags, created_at, modified_at)
VALUES (
    randomblob(16),
    'Bug Investigation Workflow',
    'Template for systematic bug investigation and fixing. Guides through reproduction, root cause analysis, and fix verification.',
    'TASK',
    1, 1, 1,
    'system',
    'bug,investigation,debugging,troubleshooting',
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
);

INSERT INTO template_sections (id, template_id, title, usage_description, content_sample, content_format, ordinal, is_required, tags)
SELECT
    randomblob(16), t.id,
    'Investigation Process',
    'Steps to reproduce and understand the bug',
    '### Bug Description
- **Symptom**: [What is the observable problem]
- **Expected Behavior**: [What should happen]
- **Actual Behavior**: [What actually happens]
- **Impact**: [Who is affected and how severely]

### Reproduction Steps
1. [Step 1]
2. [Step 2]
3. [Step 3]
- **Result**: [What happens]

### Environment
- **Version**: [Software version]
- **Platform**: [OS, browser, etc.]
- **Configuration**: [Relevant settings]

### Investigation Notes
[Document findings during investigation]',
    'MARKDOWN', 0, 1,
    'reproduction,investigation,analysis'
FROM templates t WHERE t.name = 'Bug Investigation Workflow';

INSERT INTO template_sections (id, template_id, title, usage_description, content_sample, content_format, ordinal, is_required, tags)
SELECT
    randomblob(16), t.id,
    'Root Cause Analysis',
    'Identifying the underlying cause of the bug',
    '### Root Cause
[What is the fundamental cause of the bug]

### Code Location
- **File(s)**: [Which files contain the bug]
- **Function/Method**: [Specific code location]
- **Line(s)**: [Line numbers if applicable]

### Why It Happened
[Explanation of how the bug was introduced]

### Related Issues
[Any related bugs or technical debt]

### Fix Strategy
[High-level approach to fixing the bug]',
    'MARKDOWN', 1, 1,
    'root-cause,analysis,diagnosis'
FROM templates t WHERE t.name = 'Bug Investigation Workflow';

INSERT INTO template_sections (id, template_id, title, usage_description, content_sample, content_format, ordinal, is_required, tags)
SELECT
    randomblob(16), t.id,
    'Fix Implementation & Verification',
    'Implementing and validating the bug fix',
    '### Fix Implementation
- **Changes Made**: [Description of code changes]
- **Files Modified**: [List of files changed]
- **Approach**: [Why this fix approach was chosen]

### Testing
- [ ] Bug reproduction test added
- [ ] Fix verified in reproduction scenario
- [ ] All existing tests still pass
- [ ] No regressions introduced

### Validation
- [ ] Manual testing in affected scenarios
- [ ] Edge cases tested
- [ ] Performance impact assessed (if relevant)

### Prevention
[How to prevent similar bugs in the future]

### Documentation
[Any documentation updates needed]',
    'MARKDOWN', 2, 1,
    'fix,testing,verification,prevention'
FROM templates t WHERE t.name = 'Bug Investigation Workflow';

-- =============================================================================
-- Migration Complete
-- =============================================================================
-- v2.0.0 schema fully applied
-- - 31 total status values across all entity types
-- - Optimistic locking on all entities
-- - Description fields for projects and features
-- - 73+ performance indexes
-- - 9 built-in templates with 26 sections

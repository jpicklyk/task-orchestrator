-- Migration to update template sections with simplified content
-- This updates 6 templates that were simplified to remove enterprise bloat

-- =============================================================================
-- PART 1: Create the 6 templates if they don't exist
-- =============================================================================
-- This ensures templates exist before we try to add sections to them

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

-- =============================================================================
-- PART 2: Delete existing sections for templates that will be updated
-- =============================================================================
DELETE FROM template_sections
WHERE template_id IN (
    SELECT id FROM templates WHERE name IN (
        'Definition of Done',
        'GitHub PR Workflow',
        'Context & Background',
        'Testing Strategy',
        'Requirements Specification',
        'Local Git Branching Workflow'
    )
);

-- =============================================================================
-- Definition of Done Template - 2 simplified sections
-- =============================================================================

INSERT INTO template_sections (template_id, title, usage_description, content_sample, content_format, ordinal, is_required, tags)
SELECT
    t.id,
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
- [ ] **No Regressions**: Existing functionality still works correctly

### Documentation
- [ ] **Code Comments**: Complex logic is explained with appropriate comments
- [ ] **API Documentation**: API changes are documented with examples
- [ ] **User Documentation**: User-facing documentation is updated if needed
- [ ] **Technical Notes**: Architecture and design decisions are documented',
    'MARKDOWN',
    0,
    1,
    'implementation,quality,testing,documentation'
FROM templates t
WHERE t.name = 'Definition of Done';

INSERT INTO template_sections (template_id, title, usage_description, content_sample, content_format, ordinal, is_required, tags)
SELECT
    t.id,
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
- [ ] **Alerts**: Critical alerts are configured if needed

### Performance & Security
- [ ] **Performance**: Meets defined performance requirements
- [ ] **Security**: Security implications have been reviewed
- [ ] **Input Validation**: User input is properly validated and sanitized
- [ ] **Data Protection**: Sensitive data is handled appropriately

### Sign-off
- [ ] **Testing Complete**: All testing activities are complete and documented
- [ ] **Documentation Complete**: All required documentation is in place
- [ ] **Stakeholder Acceptance**: Key stakeholders have reviewed and approved
- [ ] **Ready for Release**: Team confirms ready for production release',
    'MARKDOWN',
    1,
    1,
    'deployment,monitoring,performance,security,sign-off'
FROM templates t
WHERE t.name = 'Definition of Done';

-- =============================================================================
-- Local Git Branching Workflow Template - 3 simplified sections
-- =============================================================================

INSERT INTO template_sections (template_id, title, usage_description, content_sample, content_format, ordinal, is_required, tags)
SELECT
    t.id,
    'Create Branch',
    'Creating and setting up a new git branch for local development',
    '### Branch Naming
Choose appropriate branch prefix:
- `feature/[description]` - New features or enhancements
- `bugfix/[description]` - Bug fixes
- `hotfix/[description]` - Critical production fixes
- `docs/[description]` - Documentation changes

Examples: `feature/user-auth`, `bugfix/search-crash`

### Create Branch
```bash
# Update main
git checkout main
git pull origin main

# Create and switch to new branch
git checkout -b [branch-name]

# Verify
git branch --show-current
```',
    'MARKDOWN',
    0,
    1,
    'git,branch-creation'
FROM templates t
WHERE t.name = 'Local Git Branching Workflow';

INSERT INTO template_sections (template_id, title, usage_description, content_sample, content_format, ordinal, is_required, tags)
SELECT
    t.id,
    'Implement & Commit',
    'Making changes and committing with good git hygiene',
    '### Development Workflow
1. **Make focused changes**: One logical change at a time
2. **Check status frequently**: `git status` and `git diff`
3. **Commit incrementally**: Logical chunks, not everything at once

### Before Committing
```bash
# Build and test
./gradlew build test

# Review changes
git status
git diff
```

### Commit Changes
```bash
# Stage changes
git add [files]  # or git add . for all

# Commit with clear message
git commit -m "[type]: [description]"
```

**Commit types**: `feat`, `fix`, `docs`, `refactor`, `test`, `chore`

**Examples**:
- `feat: add user authentication endpoint`
- `fix: resolve search returning empty results`
- `refactor: extract validation logic`

### Commit Message Best Practices
- Subject: 50 chars or less, imperative mood ("add" not "added")
- Body: Explain what and why (optional for small changes)
- Reference: Include task IDs or issue numbers if applicable',
    'MARKDOWN',
    1,
    1,
    'implementation,commits,workflow'
FROM templates t
WHERE t.name = 'Local Git Branching Workflow';

INSERT INTO template_sections (template_id, title, usage_description, content_sample, content_format, ordinal, is_required, tags)
SELECT
    t.id,
    'Verify & Finalize',
    'Final verification before pushing branch',
    '### Pre-Push Checklist
- [ ] All commits have clear messages
- [ ] Build succeeds: `./gradlew build`
- [ ] All tests pass: `./gradlew test`
- [ ] Code follows project style
- [ ] Documentation updated if needed
- [ ] Only intended changes included

### Review Commits
```bash
# View commit history
git log --oneline -n 5

# Review latest commit
git show HEAD

# Check branch status
git status
git branch -v
```

### Ready for Push
When branch is ready:
- All planned work is committed
- Tests pass
- Code is ready for review
- Ready to create PR (see GitHub PR Workflow template)',
    'MARKDOWN',
    2,
    1,
    'verification,finalization'
FROM templates t
WHERE t.name = 'Local Git Branching Workflow';

-- =============================================================================
-- GitHub PR Workflow Template - 3 simplified sections
-- =============================================================================

INSERT INTO template_sections (template_id, title, usage_description, content_sample, content_format, ordinal, is_required, tags)
SELECT
    t.id,
    'Pre-Push Validation',
    'Essential validation steps before pushing branch and creating PR',
    '### Sync with Latest Main
Always sync with latest main before creating PR:

```bash
# Fetch and rebase onto main
git fetch origin
git rebase origin/main

# Run tests after sync
./gradlew build test
```

### Pre-Push Checklist
- [ ] All tests pass locally
- [ ] Code compiles successfully
- [ ] Branch synced with latest origin/main
- [ ] No merge conflicts
- [ ] Commit messages are clear and descriptive
- [ ] No sensitive information in commits',
    'MARKDOWN',
    0,
    1,
    'github,sync,validation'
FROM templates t
WHERE t.name = 'GitHub PR Workflow';

INSERT INTO template_sections (template_id, title, usage_description, content_sample, content_format, ordinal, is_required, tags)
SELECT
    t.id,
    'Create Pull Request',
    'Push branch and create PR using GitHub MCP tools',
    '### Push Branch
```bash
git push -u origin [branch-name]
```

### Create PR via GitHub MCP
Use GitHub MCP tools to create pull request with:

**PR Title**: Follow conventional commits (`feat:`, `fix:`, `refactor:`, `docs:`, `test:`)

**PR Description**:
```markdown
## Description
[What changed and why]

## Changes Made
- [Change 1]
- [Change 2]

## Testing
- [ ] Unit tests passing
- [ ] Integration tests passing
- [ ] Manual testing completed

## Checklist
- [ ] Code follows style guidelines
- [ ] Self-review completed
- [ ] Documentation updated if needed
- [ ] Related issues linked

Closes #[issue-number]
```

### Configure PR
- Add reviewers using GitHub MCP tools
- Apply appropriate labels (feature, bugfix, documentation)
- Link related issues
- Set as draft if work-in-progress',
    'MARKDOWN',
    1,
    1,
    'git,pull-request,mcp-tools'
FROM templates t
WHERE t.name = 'GitHub PR Workflow';

INSERT INTO template_sections (template_id, title, usage_description, content_sample, content_format, ordinal, is_required, tags)
SELECT
    t.id,
    'Review & Merge',
    'Handle reviews, address feedback, and merge PR using GitHub MCP tools',
    '### Monitor PR Status
Use GitHub MCP tools to:
- Check review status and approvals
- Monitor CI/CD pipeline results
- Track requested changes

### Address Review Feedback
```bash
# Make requested changes
git add [modified-files]
git commit -m "address review: [description]"
git push origin [branch-name]
```

Respond to comments via GitHub MCP tools:
- Acknowledge feedback and explain changes
- Ask for clarification when needed
- Mark conversations resolved

### Handle Merge Conflicts
```bash
# Sync and resolve conflicts
git fetch origin
git rebase origin/main
# Resolve conflicts in IDE
git rebase --continue
git push --force-with-lease origin [branch-name]
```

### Merge PR
Use GitHub MCP tools to merge when ready:

**Pre-merge checklist**:
- [ ] All required reviews approved
- [ ] All CI/CD checks passing
- [ ] No merge conflicts
- [ ] Branch up-to-date with main

**Merge strategy** (use GitHub MCP tools):
- **Squash and Merge**: Recommended for most features (clean history)
- **Merge Commit**: When preserving commit history is important
- **Rebase and Merge**: For linear history with clean commits

### Post-Merge Cleanup
```bash
# Update local main
git checkout main
git pull origin main
git branch -d [branch-name]
```

Use GitHub MCP tools to:
- Delete remote branch
- Close related issues
- Update task status
- Notify team if needed',
    'MARKDOWN',
    2,
    1,
    'review,merge,mcp-tools,cleanup'
FROM templates t
WHERE t.name = 'GitHub PR Workflow';

-- =============================================================================
-- Context & Background Template - 3 simplified sections
-- =============================================================================

INSERT INTO template_sections (template_id, title, usage_description, content_sample, content_format, ordinal, is_required, tags)
SELECT
    t.id,
    'Why This Matters',
    'Business rationale and value proposition for the work',
    '### Business Objective
- **Primary Goal**: [What business problem this solves or opportunity it captures]
- **Expected Impact**: [How this affects users, revenue, efficiency, or competitive position]
- **Success Metrics**: [How we''ll measure success]

### Business Driver
- Revenue growth
- Cost reduction
- Risk mitigation
- Competitive differentiation
- Customer satisfaction
- Regulatory requirement

### Timeline Context
- **Key Deadline**: [Important date or milestone if applicable]
- **Why Now**: [Reason for current timing]',
    'MARKDOWN',
    0,
    1,
    'business,value,objectives'
FROM templates t
WHERE t.name = 'Context & Background';

INSERT INTO template_sections (template_id, title, usage_description, content_sample, content_format, ordinal, is_required, tags)
SELECT
    t.id,
    'User Context',
    'Who will use this and what problems it solves for them',
    '### Target Users
- **Primary Users**: [Who will use this most]
  - Role and responsibilities
  - Current tools and workflows
  - Technical proficiency level

### User Pain Points
**Current Problems**:
1. [Pain point 1 and its impact]
2. [Pain point 2 and its impact]

**Current Workarounds**: [How users currently handle these issues]

### User Goals
- **What users need**: [Core functionality or capability needed]
- **Why it matters**: [Impact on user productivity, satisfaction, or success]
- **Success looks like**: [How users will know this solves their problem]',
    'MARKDOWN',
    1,
    1,
    'users,needs,pain-points'
FROM templates t
WHERE t.name = 'Context & Background';

INSERT INTO template_sections (template_id, title, usage_description, content_sample, content_format, ordinal, is_required, tags)
SELECT
    t.id,
    'Dependencies & Coordination',
    'Related work, dependencies, and coordination needs',
    '### Related Work
- **[Related Project/Feature]**: [How it relates to this work]
  - Status and timeline
  - Coordination required

### Dependencies
**Upstream** (must be completed first):
- [Dependency]: Why it''s needed and when expected

**Downstream** (depends on this work):
- [Dependent work]: What''s blocked until this is done

### Technical Context
- **Existing Systems**: [Systems affected by this work]
- **Architecture Fit**: [How this fits into current architecture]
- **Technical Constraints**: [Limitations or requirements to consider]

### Team Coordination
- **Teams Involved**: [Other teams to coordinate with]
- **Shared Resources**: [Infrastructure, tools, or services used by multiple teams]',
    'MARKDOWN',
    2,
    1,
    'dependencies,coordination,technical-context'
FROM templates t
WHERE t.name = 'Context & Background';

-- =============================================================================
-- Testing Strategy Template - 3 simplified sections
-- =============================================================================

INSERT INTO template_sections (template_id, title, usage_description, content_sample, content_format, ordinal, is_required, tags)
SELECT
    t.id,
    'Test Coverage',
    'Test cases and coverage areas for thorough validation',
    '### Unit Tests
**Test Class**: `[ComponentName]Test`

Test format: `should [expected behavior] when [specific condition]`
- Given: [Initial state]
- When: [Action]
- Then: [Expected result]

**Coverage Areas**:
- Happy path scenarios
- Edge cases (empty input, boundary values, invalid data)
- Error conditions and exception handling

### Integration Tests
**Component Integration**:
- [Component A] ↔ [Component B]: [What''s being tested]
- Data flow and transformations
- Failure handling

**Database Integration** (if applicable):
- CRUD operations
- Transaction handling
- Data integrity and constraints

### API Tests
**Endpoint**: `[METHOD] /api/[endpoint]`
- **Success**: Valid request → Expected response + status code
- **Errors**: Invalid input, auth failures, permission issues

### End-to-End Tests
- Key user workflows that must work completely
- Critical business processes
- Integration between multiple components',
    'MARKDOWN',
    0,
    1,
    'testing,coverage,unit-tests,integration-tests'
FROM templates t
WHERE t.name = 'Testing Strategy';

INSERT INTO template_sections (template_id, title, usage_description, content_sample, content_format, ordinal, is_required, tags)
SELECT
    t.id,
    'Acceptance Criteria',
    'Measurable criteria that define completion',
    '### Functional Criteria
- [ ] Core functionality works as specified
- [ ] All acceptance criteria from requirements are met
- [ ] User workflows complete successfully
- [ ] Data validation and error handling work correctly
- [ ] Integration points function as expected

### Quality Criteria
- [ ] Unit test coverage meets project standard ([X]%)
- [ ] All tests pass consistently
- [ ] Code passes static analysis without critical issues
- [ ] Performance meets requirements (response time, throughput)
- [ ] Security best practices followed

### Deployment Criteria
- [ ] Builds successfully in CI/CD pipeline
- [ ] Can be deployed without errors
- [ ] Monitoring and logging in place
- [ ] Rollback procedure tested and documented',
    'MARKDOWN',
    1,
    1,
    'acceptance-criteria,completion,quality'
FROM templates t
WHERE t.name = 'Testing Strategy';

INSERT INTO template_sections (template_id, title, usage_description, content_sample, content_format, ordinal, is_required, tags)
SELECT
    t.id,
    'Testing Checkpoints',
    'Key validation checkpoints during development and deployment',
    '### During Development
**Before PR**:
- [ ] All new/modified code has tests
- [ ] Unit tests pass locally
- [ ] Code follows style guidelines
- [ ] No obvious bugs or issues

**Before Merge**:
- [ ] Code review approved
- [ ] All CI/CD checks passing
- [ ] Integration tests pass
- [ ] Documentation updated

### Before Deployment
**Pre-deployment Validation**:
- [ ] All tests pass in staging environment
- [ ] Performance tested under realistic load
- [ ] Security scan shows no critical issues
- [ ] Monitoring and alerts configured
- [ ] Rollback plan ready

### After Deployment
**Production Validation** (within 24-48 hours):
- [ ] Critical functionality working
- [ ] No error rate increase
- [ ] Performance metrics healthy
- [ ] Monitoring shows expected behavior

**If Issues Detected**:
- Critical issues → Immediate rollback
- Minor issues → Hot fix if needed
- Monitor closely for first 24-48 hours',
    'MARKDOWN',
    2,
    1,
    'checkpoints,validation,deployment'
FROM templates t
WHERE t.name = 'Testing Strategy';

-- =============================================================================
-- Requirements Specification Template - 3 simplified sections
-- =============================================================================

INSERT INTO template_sections (template_id, title, usage_description, content_sample, content_format, ordinal, is_required, tags)
SELECT
    t.id,
    'Must-Have Requirements',
    'Critical functional requirements that must be implemented for successful delivery',
    '### Must-Have Requirements

### Core Functionality
1. **[Requirement ID]**: [Requirement Name]
   - **Description**: [Detailed description of what must be implemented]
   - **User Story**: As a [user type], I want [functionality] so that [benefit]
   - **Acceptance Criteria**:
     - Given [context], when [action], then [expected result]
     - Given [context], when [action], then [expected result]
   - **Priority**: Critical

2. **[Requirement ID]**: [Requirement Name]
   - **Description**: [Detailed description of what must be implemented]
   - **User Story**: As a [user type], I want [functionality] so that [benefit]
   - **Acceptance Criteria**:
     - Given [context], when [action], then [expected result]
     - Given [context], when [action], then [expected result]
   - **Priority**: Critical

### Data Requirements
- **Data Input**: [What data must be accepted, format, validation rules]
- **Data Output**: [What data must be produced, format, structure]
- **Data Storage**: [What data must be persisted, retention requirements]
- **Data Migration**: [Any existing data that must be migrated or transformed]

### Integration Requirements
- **API Requirements**: [Required API endpoints, request/response formats]
- **External System Integration**: [Systems that must be integrated with]
- **Authentication/Authorization**: [Security requirements for access control]
- **Data Synchronization**: [How data stays consistent across systems]

### User Interface Requirements
- **User Experience**: [Key UX requirements and user flows]
- **Accessibility**: [WCAG compliance level, specific accessibility features]
- **Browser/Platform Support**: [Supported browsers, mobile requirements]
- **Responsive Design**: [Requirements for different screen sizes]

### Business Logic Requirements
- **Validation Rules**: [Business rules that must be enforced]
- **Calculation Logic**: [Any calculations or algorithms required]
- **Workflow Requirements**: [Business process flows that must be supported]
- **Audit Requirements**: [What actions must be logged or tracked]',
    'MARKDOWN',
    0,
    1,
    'functional,core,critical,acceptance-criteria'
FROM templates t
WHERE t.name = 'Requirements Specification';

INSERT INTO template_sections (template_id, title, usage_description, content_sample, content_format, ordinal, is_required, tags)
SELECT
    t.id,
    'Nice-to-Have Features',
    'Optional features and enhancements that would improve the solution but aren''t critical',
    '### Nice-to-Have Features

### Enhanced Functionality
1. **[Feature Name]**: [Description of optional feature]
   - **Value**: [How this would improve the user experience or business value]
   - **Effort Estimate**: [Rough estimate of implementation effort]
   - **Dependencies**: [What would need to be in place first]

2. **[Feature Name]**: [Description of optional feature]
   - **Value**: [How this would improve the user experience or business value]
   - **Effort Estimate**: [Rough estimate of implementation effort]
   - **Dependencies**: [What would need to be in place first]

### User Experience Enhancements
- **Advanced Search**: [Enhanced search capabilities beyond basic requirements]
- **Bulk Operations**: [Ability to perform operations on multiple items]
- **Customization Options**: [User preferences, themes, personalization]
- **Advanced Filtering**: [Additional filtering and sorting options]

### Performance Enhancements
- **Caching Improvements**: [Advanced caching for better performance]
- **Real-time Updates**: [Live updates without page refresh]
- **Offline Capabilities**: [Ability to work without internet connection]
- **Progressive Loading**: [Improved loading experience for large datasets]

### Administrative Features
- **Advanced Analytics**: [Detailed reporting and analytics capabilities]
- **Export/Import**: [Additional data export/import formats]
- **Advanced User Management**: [Role management, user provisioning]
- **System Monitoring**: [Enhanced monitoring and alerting capabilities]

### Integration Enhancements
- **Additional API Endpoints**: [Extra APIs for future integrations]
- **Webhook Support**: [Event-driven integrations with external systems]
- **Third-party Integrations**: [Integrations with popular tools/services]
- **Advanced Security**: [Additional security features like 2FA, SSO]

### Future Considerations
- **Mobile Application**: [Native mobile app considerations]
- **Internationalization**: [Multi-language support]
- **Multi-tenancy**: [Support for multiple organizations/tenants]
- **Advanced Workflow**: [Complex business process automation]',
    'MARKDOWN',
    1,
    0,
    'optional,enhancements,future,nice-to-have'
FROM templates t
WHERE t.name = 'Requirements Specification';

INSERT INTO template_sections (template_id, title, usage_description, content_sample, content_format, ordinal, is_required, tags)
SELECT
    t.id,
    'Constraints & Non-Functional Requirements',
    'Technical constraints and non-functional requirements that must be considered',
    '### Technical Constraints
- **Technology Stack**: [Required technologies, frameworks, or platforms]
- **Legacy Compatibility**: [Existing systems that must be supported]
- **Infrastructure**: [Hardware, network, or cloud limitations]
- **Third-party Dependencies**: [External services or APIs that must be used]

### Performance Requirements
- **Response Time**: [Maximum acceptable response times]
- **Throughput**: [Required transactions per second or concurrent users]
- **Scalability**: [Expected growth in users, data, or transactions]

### Security Requirements
- **Authentication**: [Required authentication methods]
- **Authorization**: [Access control and permission model]
- **Data Protection**: [Encryption and data handling requirements]
- **Audit Logging**: [What must be logged for security]

### User Experience Requirements
- **Browser/Platform Support**: [Supported browsers and devices]
- **Accessibility**: [WCAG level or specific accessibility needs]
- **Offline Capability**: [Any offline functionality requirements]

### Data Requirements
- **Data Volume**: [Expected data sizes and growth rates]
- **Data Retention**: [How long data must be kept]
- **Backup/Recovery**: [Data backup and recovery requirements]

### Integration Constraints
- **API Limitations**: [Rate limits, data format restrictions]
- **Network Requirements**: [Bandwidth, latency requirements]
- **Error Handling**: [How integration failures must be handled]',
    'MARKDOWN',
    2,
    1,
    'constraints,non-functional,performance,security'
FROM templates t
WHERE t.name = 'Requirements Specification';

-- Migration complete
-- Updated all 6 simplified templates:
-- 1. Definition of Done (2 sections)
-- 2. Local Git Branching Workflow (3 sections)
-- 3. GitHub PR Workflow (3 sections)
-- 4. Context & Background (3 sections)
-- 5. Testing Strategy (3 sections)
-- 6. Requirements Specification (3 sections)
-- Total: 17 sections created

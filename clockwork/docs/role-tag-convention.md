# Role Tag Convention

## Overview

Role tags are a naming convention for template sections that associate content with specific workflow lifecycle phases. They enable AI agents to query phase-appropriate guidance and optimize token consumption by filtering sections based on current workflow context.

## Tag Format

Role tags follow the pattern: `role:{rolename}`

Where `{rolename}` is one of:
- **queue** - Content for work waiting to be started (planning, requirements)
- **work** - Content for active implementation (technical approach, code changes)
- **review** - Content for validation and quality gates (test results, review checklists)
- **terminal** - Content for completion phase (summary, retrospective, final documentation)

## Role Mapping to Workflow Statuses

Role tags align with the status progression system's semantic role annotations:

| Role | Associated Statuses | When to Read |
|------|---------------------|--------------|
| **queue** | backlog, pending, draft, planning | Task first created or backlogged |
| **work** | in-progress, in-development, investigating, changes-requested | Active implementation phase |
| **review** | in-review, testing, validating, pending-review, ready-for-qa | Review and validation phase |
| **terminal** | completed, cancelled, deferred, archived, deployed | Completion and retrospective |

See [Status Progression - Role Annotations](status-progression.md#role-annotations) for complete status-to-role mappings.

## Usage with Templates

Template authors tag sections during template creation to indicate the workflow phase where content is most relevant.

### Example: Implementation Task Template

```javascript
// Technical Approach section - relevant during active work
manage_sections(
  operation="add",
  entityType="TEMPLATE",
  entityId="template-uuid",
  title="Technical Approach",
  content="Document implementation strategy...",
  tags="role:work,technical,guidance"
)

// Test Results section - relevant during review
manage_sections(
  operation="add",
  entityType="TEMPLATE",
  entityId="template-uuid",
  title="Test Results",
  content="Record test execution results...",
  tags="role:review,testing,validation"
)

// Summary section - relevant at completion
manage_sections(
  operation="add",
  entityType="TEMPLATE",
  entityId="template-uuid",
  title="Task Summary",
  content="Summarize what was accomplished...",
  tags="role:terminal,documentation"
)
```

### Template Section Role Guidelines

**Queue Phase** (`role:queue`):
- Requirements gathering notes
- Planning considerations
- Acceptance criteria checklists
- Prerequisites and dependencies
- Initial context and background

**Work Phase** (`role:work`):
- Technical approach documents
- Implementation instructions
- Code change guidelines
- Work-in-progress notes
- API design specifications

**Review Phase** (`role:review`):
- Test execution checklists
- Review criteria
- Verification procedures
- QA validation steps
- Performance testing results

**Terminal Phase** (`role:terminal`):
- Completion summaries
- Retrospective notes
- Final documentation
- Deployment records
- Lessons learned

## Querying by Role

AI agents can filter sections by role tag to fetch only phase-appropriate content.

### Query Examples

**Planning Phase** - Get queue-phase sections when breaking down feature:
```javascript
query_sections(
  entityType="FEATURE",
  entityId="feature-uuid",
  tags="role:queue",
  includeContent=true
)
// Returns: requirements, planning notes, acceptance criteria
// Token cost: ~2,000-3,000 (vs ~7,000+ all sections)
```

**Implementation Phase** - Get work-phase sections when implementing task:
```javascript
query_sections(
  entityType="TASK",
  entityId="task-uuid",
  tags="role:work",
  includeContent=true
)
// Returns: technical approach, implementation guidance, code specs
// Token cost: ~800-1,500 (vs ~3,000-5,000 all sections)
```

**Review Phase** - Get review-phase sections when validating work:
```javascript
query_sections(
  entityType="TASK",
  entityId="task-uuid",
  tags="role:review",
  includeContent=true
)
// Returns: test checklists, review criteria, validation procedures
```

**Completion Phase** - Get terminal-phase sections for retrospective:
```javascript
query_sections(
  entityType="TASK",
  entityId="task-uuid",
  tags="role:terminal",
  includeContent=true
)
// Returns: summary, retrospective, final documentation
```

## Multiple Tags

Sections can have role tags AND other descriptive tags. Tag queries match ANY tag (OR logic).

**Example** - Section with multiple tags:
```javascript
manage_sections(
  operation="add",
  title="Backend API Implementation Guide",
  tags="role:work,backend,api,implementation"
)
```

**Query by role**:
```javascript
query_sections(tags="role:work")
// Returns this section (matches role:work)
```

**Query by domain**:
```javascript
query_sections(tags="backend")
// Also returns this section (matches backend)
```

**Query multiple tags**:
```javascript
query_sections(tags="role:work,backend")
// Returns sections matching role:work OR backend
```

## Backward Compatibility

Existing templates without role tags continue to work normally. Role tags are additive - they enhance filtering but don't break existing functionality.

**Without role tags**:
- `query_sections(entityType="TASK", entityId="uuid")` - Returns all sections
- No filtering by workflow phase

**With role tags**:
- `query_sections(entityType="TASK", entityId="uuid")` - Still returns all sections
- `query_sections(entityType="TASK", entityId="uuid", tags="role:work")` - Filtered to work phase

## Guidelines for Template Authors

When adding role tags to custom templates:

1. **Consider the primary use case** - When will users need this section content?
   - During planning? → `role:queue`
   - During implementation? → `role:work`
   - During validation? → `role:review`
   - After completion? → `role:terminal`

2. **Use role tags alongside descriptive tags** - Combine workflow phase with domain/technology tags
   ```
   tags="role:work,backend,api,kotlin"
   ```

3. **Be consistent across templates** - If "Technical Approach" is `role:work` in one template, keep it consistent in others

4. **Don't over-tag** - Not every section needs a role tag. Use them where workflow phase filtering adds value.

5. **Test filtering** - Verify `query_sections(tags="role:work")` returns expected sections

## Built-in Template Role Tagging

The Task Orchestrator's built-in templates use role tags following these conventions:

| Template | Section | Role Tag | Rationale |
|----------|---------|----------|-----------|
| Technical Approach | Technical Approach | `role:work` | Read during implementation |
| Requirements | Requirements | `role:queue` | Read during planning |
| Testing Strategy | Test Strategy | `role:review` | Read during validation |
| Definition of Done | Completion Criteria | `role:terminal` | Read at completion |
| Bug Investigation | Investigation Results | `role:work` | Read during debugging |
| Context & Background | Business Context | `role:queue` | Read during planning |

See built-in template definitions in `src/main/kotlin/io/github/jpicklyk/mcptask/application/service/templates/` for complete tagging examples.

## Token Optimization Best Practice

Combining role tag filtering with selective content loading achieves maximum token efficiency:

**Standard approach** (loads all sections):
```javascript
query_container(operation="get", containerType="task", id="uuid", includeSections=true)
// Token cost: ~5,000-15,000 tokens
```

**Optimized approach** (role-filtered sections):
```javascript
// Step 1: Get task metadata (no sections)
task = query_container(operation="get", containerType="task", id="uuid", includeSections=false)
// Token cost: ~300-500 tokens

// Step 2: Get only work-phase sections
sections = query_sections(entityType="TASK", entityId="uuid", tags="role:work", includeContent=true)
// Token cost: ~800-1,500 tokens

// Total: ~1,100-2,000 tokens (vs 5,000-15,000)
// Savings: 60-80% token reduction
```

## Integration with Status Progression

Role tags complement the status progression system's role annotations. When a task transitions between workflow phases, agents can query appropriate sections:

**Status Transition Workflow**:
```javascript
// 1. Task moves to in-progress status
request_transition(containerId="uuid", containerType="task", trigger="start")
// Response includes: previousRole="queue", newRole="work"

// 2. Agent detects role transition to "work"
// 3. Query work-phase sections
sections = query_sections(entityType="TASK", entityId="uuid", tags="role:work")

// 4. Agent reads implementation guidance specific to work phase
```

See [Status Progression Guide](status-progression.md) for complete workflow examples.

## Related Documentation

- [Status Progression - Role Annotations](status-progression.md#role-annotations) - Status-to-role semantic mappings
- [AI Guidelines - Section Tag Filtering](ai-guidelines.md#section-tag-filtering-token-optimization) - AI agent token optimization patterns
- [Templates Guide](templates.md) - Template system architecture and usage
- [API Reference - query_sections](api-reference.md) - Complete tool documentation

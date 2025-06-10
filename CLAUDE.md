# Claude Code Project Memory

## Task-Orchestrator MCP Tools Usage Guidelines

### Task Management Protocol

**Always Use MCP Task Tools for Substantial Work:**
1. **Use `create_task`** instead of TodoWrite for any non-trivial work items:
   - Multi-step implementations (3+ steps)
   - Complexity rating > 2
   - Testing suites for components
   - Bug fixes requiring investigation
   - Feature enhancements
   - Integration work
   - Any work benefiting from tracking and documentation

2. **Task Workflow Process:**
   - Start with `get_overview` to check current work and priorities
   - If no suitable task exists, use `create_task` with proper metadata
   - Use `update_task` to set status to "in_progress" when starting work
   - Work incrementally with regular git commits following conventional commit format
   - Use `update_task` to set status to "completed" when finished
   - Use TodoWrite only for simple tracking within larger tasks

3. **Task Quality Standards:**
   - Write descriptive titles and summaries
   - Use appropriate complexity ratings (1-10 scale)
   - Add relevant tags for categorization and searchability
   - Include acceptance criteria in summaries when helpful
   - Reference related tasks, features, or projects when applicable

### Template Usage Protocol

**Always Check and Use Available Templates:**
1. **Before creating tasks or features**, run `list_templates` to check available templates
2. **Filter templates by target entity type** (TASK or FEATURE) and enabled status
3. **Apply appropriate templates** using the `templateIds` parameter in `create_task` or `create_feature`
4. **Use templates for consistency** in documentation structure and content organization

**Template Selection Guidelines:**
- **For Tasks:** Look for templates matching the work type (implementation, testing, bug-fix, etc.)
- **For Features:** Use feature-level templates that provide comprehensive documentation structure
- **Match template tags** to the work being done (e.g., "testing", "implementation", "documentation")
- **Prefer built-in templates** for standard workflows when available

**Template Application Examples:**
```bash
# Check available templates first
list_templates --targetEntityType TASK --isEnabled true

# Create task with appropriate template
create_task --title "Implement API endpoint" --summary "..." --templateIds ["template-uuid"]

# Check available feature templates
list_templates --targetEntityType FEATURE --isEnabled true

# Create feature with template
create_feature --name "User Authentication" --summary "..." --templateIds ["feature-template-uuid"]
```

### Git Workflow Integration

**Commit Standards:**
- Follow conventional commit format: `type: description`
- Include template application in commit messages when templates are used
- Commit incrementally as tasks progress
- Always include co-authorship attribution:
  ```
  🤖 Generated with [Claude Code](https://claude.ai/code)
  
  Co-Authored-By: Claude <noreply@anthropic.com>
  ```

### Testing Protocol

**When Creating Test Tasks:**
1. Create separate tasks for different test suites (unit, integration, component)
2. Use appropriate complexity ratings based on test scope
3. Include coverage requirements in task summaries
4. Tag tests with relevant categories ("unit-tests", "integration", "mocking", etc.)
5. Update mock repositories as needed to support new functionality

### Priority and Dependency Management

**Task Prioritization:**
- Use "high" priority for critical functionality and blocking issues
- Use "medium" priority for enhancements and non-blocking features  
- Use "low" priority for optimization and nice-to-have features
- Consider dependency relationships when setting priorities

**Dependency Integration:**
- Always test that new dependency features work with existing tools
- Update mock repositories when adding new repository interfaces
- Ensure backward compatibility in API enhancements
- Run full test suite after significant changes

### Error Handling and Quality

**Code Quality Standards:**
- Implement comprehensive error handling for all tools
- Provide clear, actionable error messages
- Use appropriate error codes from ErrorCodes utility
- Include proper logging for debugging support

**Testing Requirements:**
- Achieve comprehensive test coverage for new functionality
- Test both success and failure scenarios
- Include edge case testing
- Validate error responses and status codes

### Project Structure Awareness

This is a **Kotlin-based MCP (Model Context Protocol) server** that provides task orchestration tools. Key architectural components:
- **Domain layer:** Core business models and repository interfaces
- **Infrastructure layer:** Database implementations and utilities
- **Application layer:** MCP tools and business logic
- **Interface layer:** MCP server and API definitions

When working on this project, maintain the architectural boundaries and follow the established patterns for consistency.
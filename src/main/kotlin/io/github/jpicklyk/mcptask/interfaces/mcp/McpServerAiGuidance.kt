package io.github.jpicklyk.mcptask.interfaces.mcp

import io.modelcontextprotocol.kotlin.sdk.GetPromptResult
import io.modelcontextprotocol.kotlin.sdk.PromptMessage
import io.modelcontextprotocol.kotlin.sdk.Role
import io.modelcontextprotocol.kotlin.sdk.TextContent
import io.modelcontextprotocol.kotlin.sdk.server.Server
import kotlinx.serialization.json.JsonObject

/**
 * Extension functions to provide AI guidance for the MCP server.
 */
object McpServerAiGuidance {

    /**
     * Critical efficiency guidance for update operations.
     * AI agents must follow these patterns to avoid wasting 90%+ tokens.
     */
    private const val UPDATE_EFFICIENCY_GUIDE = """
## UPDATE OPERATIONS - CRITICAL EFFICIENCY RULES

⚠️ **NEVER fetch an entity just to update it!**
✅ **ALWAYS use partial updates** - only send fields you're changing

### The Problem
AI agents often waste 90%+ tokens by sending entire entities with unchanged fields:
- Task summaries alone can be 500+ characters
- Sending 6 unchanged fields wastes ~600 characters per update
- Multiplied across many operations = massive token waste

### The Solution: Partial Updates

**All update tools support partial updates. Only 'id' is required.**

Tools supporting partial updates:
- `update_task` - Only 'id' required, all other fields optional
- `update_feature` - Only 'id' required, all other fields optional
- `update_project` - Only 'id' required, all other fields optional
- `update_section` - Only 'id' required, all other fields optional

### Examples

❌ **INEFFICIENT** (wastes ~500+ characters):
```json
{
  "id": "task-uuid",
  "title": "Existing Title",              // ← Unchanged (unnecessary)
  "summary": "Long existing summary...",  // ← Unchanged (500+ chars wasted!)
  "status": "completed",                  // ← Only this changed
  "priority": "medium",                   // ← Unchanged (unnecessary)
  "complexity": 5,                        // ← Unchanged (unnecessary)
  "tags": "tag1,tag2,tag3"               // ← Unchanged (unnecessary)
}
```

✅ **EFFICIENT** (uses ~30 characters):
```json
{
  "id": "task-uuid",
  "status": "completed"  // Only send what changed!
}
```

**Token Savings: 94% reduction!**

### Common Scenarios

**Changing Status**:
```json
{"id": "uuid", "status": "in-progress"}  // ✓ 30 chars
```

**Changing Priority**:
```json
{"id": "uuid", "priority": "high"}  // ✓ 25 chars
```

**Updating Multiple Fields**:
```json
{"id": "uuid", "status": "completed", "complexity": 8}  // ✓ Still efficient
```

**DON'T DO THIS**:
```
1. Call get_task to fetch entity
2. Modify one field
3. Send entire entity back to update_task
Result: Wasted 90%+ tokens!
```

### For Section Updates

Use specialized tools for maximum efficiency:
- `update_section_text` - For content changes (send only text snippets)
- `update_section_metadata` - For metadata only (excludes content)
- `update_section` - For full updates (still supports partial updates)

### Remember
- Partial updates = 90-95% token savings
- Every character counts in API efficiency
- Only send what you're actually changing
"""

    /**
     * Configures AI guidance for the MCP server.
     * 
     * Note: This previously contained instruction "prompts" that were actually documentation.
     * Those have been removed as they don't align with MCP prompt concepts.
     * MCP prompts should be user-invokable templates for specific interactions.
     * 
     * Usage guidance is now provided through:
     * - Enhanced tool descriptions and parameter documentation
     * - Server-level metadata and descriptions
     * - User-invokable prompts for specific workflows
     */
    fun Server.configureAiGuidance() {
        // Add server overview prompt for users
        addServerOverviewPrompt(this)

        // Configure MCP Resources for AI guidelines
        TaskOrchestratorResources.configure(this)

        // Template management guidance now integrated into tool descriptions

        // Add workflow prompts for common task orchestrator workflows
        WorkflowPromptsGuidance.configureWorkflowPrompts(this)
    }
    
    /**
     * Adds a user-invokable prompt for getting comprehensive server overview and capabilities.
     */
    private fun addServerOverviewPrompt(server: Server) {
        server.addPrompt(
            name = "getting_started",
            description = "Essential workflow patterns and getting started guide for Task Orchestrator"
        ) { _ ->
            GetPromptResult(
                description = "Essential workflow patterns for Task Orchestrator",
                messages = listOf(
                    PromptMessage(
                        role = Role.assistant,
                        content = TextContent(
                            text = """
                            # Task Orchestrator - Essential Workflow Patterns
                            
                            ## Core Organization
                            **Projects** → **Features** → **Tasks** → **Sections**
                            
                            ## Essential Workflow
                            
                            1. **Start with Overview**: `get_overview` to see current state
                            2. **Template-Driven Creation**: `list_templates` → `create_task`/`create_feature` with templateIds
                            3. **Status Management**: pending → in-progress → completed
                            4. **Progressive Enhancement**: Add sections, dependencies as needed
                            
                            ## Quick Start
                            
                            ```
                            # See current work
                            get_overview
                            
                            # Find templates
                            list_templates --targetEntityType TASK
                            
                            # Create with template
                            create_task --title "..." --summary "..." --templateIds ["uuid"]
                            
                            # Update status when starting work
                            update_task --id "uuid" --status "in_progress"
                            ```
                            
                            ## Key Patterns
                            
                            - **Always start sessions** with `get_overview`
                            - **Use templates** for consistent documentation
                            - **Tag consistently**: task-type-feature, task-type-bug, etc.
                            - **Set complexity** (1-10) for estimation
                            - **Use bulk operations** for multiple sections
                            - **Check template guidance** with `get_sections` before marking complete
                            
                            ## Template Categories
                            - **Workflow**: Git branching, PR workflows, implementation
                            - **Documentation**: Technical approach, requirements
                            - **Quality**: Testing strategy, definition of done
                            
                            ## Workflow Automation

                            **Universal workflows** (all MCP clients):
                            - `initialize_task_orchestrator` - One-time AI setup (writes patterns to permanent memory)
                            - `project_setup_workflow` - Initialize new projects with proper structure
                            - `update_project_config` - Update project configuration
                            - `getting_started` - Quick reference guide (read-only, shows this content)

                            **Claude Code workflows** (requires Skills):
                            - `coordinate_feature_development` - End-to-end feature orchestration (Phases 1-4)

                            **Usage**: `task-orchestrator:workflow_name` then provide details or let AI guide you

                            **Note**: v1.0 workflows removed in v2.0. Use `coordinate_feature_development` or direct tool calls.

                            $UPDATE_EFFICIENCY_GUIDE
                            """.trimIndent()
                        )
                    )
                ),
                _meta = JsonObject(emptyMap())
            )
        }
    }

}
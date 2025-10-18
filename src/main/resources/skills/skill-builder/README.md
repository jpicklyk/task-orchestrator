# Skill Builder

**Help users create custom Claude Code Skills**

## Overview

The Skill Builder is a meta-Skill that helps you create new Skills for your workflows. It interviews you about your needs, generates properly-formatted SKILL.md files, creates supporting documentation, and provides testing guidance.

## When to Use

Use the Skill Builder when you want to:
- Create a new Skill for a repetitive workflow
- Package expertise into a reusable capability
- Automate coordination tasks (2-5 tool calls)
- Extend Task Orchestrator with custom functionality

**Activation phrases:**
- "Help me create a Skill"
- "I want to create a custom Skill"
- "How do I build a Skill for [purpose]?"
- "Create a Skill that does [action]"

## What It Creates

The Skill Builder generates:

1. **SKILL.md** - Main Skill definition with:
   - Proper YAML frontmatter (name, description, allowed-tools)
   - Clear workflow instructions
   - Usage guidelines and examples
   - Error handling patterns

2. **examples.md** - Concrete usage scenarios showing:
   - Realistic user requests
   - Step-by-step Skill actions
   - Expected outputs

3. **reference.md** (if using MCP tools) - Tool documentation with:
   - Tool purposes and parameters
   - Example tool calls
   - Integration patterns

4. **Testing guidance** - How to verify the Skill works:
   - Activation testing
   - Tool permission validation
   - Iteration instructions

## Example Usage

```
User: "Help me create a Skill that generates API documentation from Kotlin code"

Skill Builder asks:
- Will this read Kotlin source files? → Yes
- Should it write markdown documentation? → Yes
- Does it need to update Task Orchestrator tasks? → Yes
- What keywords should trigger activation? → "document API", "generate API docs"

Skill Builder creates:
✓ .claude/skills/api-doc-generator/SKILL.md
✓ .claude/skills/api-doc-generator/examples.md
✓ .claude/skills/api-doc-generator/reference.md

Test by saying: "Document the authentication API"
```

## Skill vs Subagent Decision

The Skill Builder will help you decide whether to create a Skill or Subagent:

### Create a Skill when:
✅ Task requires 2-5 tool calls
✅ Coordination or simple logic
✅ Frequent, repetitive workflow
✅ Fast response needed (no subagent overhead)

### Create a Subagent when:
✅ Complex reasoning required
✅ Code generation or architectural decisions
✅ Needs full context and conversation history
✅ Multi-step workflow with backtracking

The Skill Builder will recognize complexity indicators and recommend creating a subagent instead if appropriate.

## Files in This Directory

- **SKILL.md** - Main Skill definition (the Skill Builder itself)
- **examples.md** - Concrete examples of using the Skill Builder
- **skill-templates.md** - Reusable templates for common Skill patterns
- **README.md** - This file

## Skill Templates Available

The Skill Builder uses these templates for common patterns:

1. **Coordination Skill** - Coordinate multiple MCP tool calls
2. **File Generation Skill** - Generate files from templates
3. **Analysis Skill** - Analyze data and provide insights
4. **Integration Skill** - Bridge Task Orchestrator with external systems
5. **Validation Skill** - Check quality criteria before proceeding
6. **Reporting Skill** - Generate reports from project data
7. **Migration Skill** - Move or transform data between structures

See `skill-templates.md` for complete templates and customization guidance.

## Best Practices

When creating Skills with the Skill Builder:

1. **Start simple** - Focus on one clear purpose
2. **Restrict tools** - Only include tools the Skill truly needs
3. **Clear descriptions** - Include activation keywords in description
4. **Concrete examples** - Show realistic usage scenarios
5. **Test thoroughly** - Verify activation triggers work naturally

## Common Skill Patterns

**Pattern 1: Coordination**
- Purpose: Coordinate multiple MCP tool calls
- Tools: Task Orchestrator MCP tools
- Example: Feature Management, Task Management

**Pattern 2: File Generation**
- Purpose: Generate files from templates
- Tools: Read, Write, potentially Bash
- Example: Documentation Generator, Config Creator

**Pattern 3: Integration**
- Purpose: Bridge Task Orchestrator with external tools
- Tools: Bash, Read, Write, Task Orchestrator tools
- Example: Jira Sync, GitHub Issue Creator

## Troubleshooting

**Skill doesn't activate:**
- Check that description includes the keywords you're using
- Description should be under 200 characters for optimal activation
- Try more specific keywords

**Missing tools error:**
- Verify tool is in allowed-tools list
- Check tool name spelling (especially MCP tool prefix)
- Use `mcp__task-orchestrator__list_templates` to verify available tools

**Skill activates too broadly:**
- Make description more specific
- Add context about when NOT to use the Skill
- Consider splitting into multiple focused Skills

## Examples

See `examples.md` for detailed examples including:
- Creating a documentation generator Skill
- Creating a test coverage analyzer Skill
- Creating a Jira integration Skill
- Creating a git workflow automation Skill
- When to create a subagent instead

## Related Skills

- **Hook Builder** - Create Claude Code hooks for automation
- **Feature Management** - Coordinate feature workflows
- **Task Management** - Coordinate task workflows

## Getting Help

For help creating a Skill:
1. Say "Help me create a Skill"
2. The Skill Builder will interview you about your needs
3. Follow the interactive workflow
4. Test your new Skill with the provided test scenarios

## Contributing

To improve the Skill Builder:
1. Add new templates to `skill-templates.md`
2. Add examples to `examples.md`
3. Update SKILL.md with new patterns
4. Test with various user scenarios

## License

Part of the MCP Task Orchestrator project.

## Skills and Hooks Integration

### Skills Invoking MCP Tools

Skills are lightweight AI behaviors (300-600 tokens) that coordinate 2-5 MCP tool calls. They provide an efficiency layer between direct tool calls and subagents.

**Available Skills** (5 included):

| Skill | Purpose | Allowed MCP Tools | Token Savings |
|-------|---------|-------------------|---------------|
| **Feature Management** | Coordinate feature lifecycle | `get_feature`, `get_next_task`, `update_feature` | 77% vs subagent |
| **Task Management** | Route tasks, update status | `get_task`, `recommend_agent`, `set_status`, `add_section` | 77% vs subagent |
| **Dependency Analysis** | Analyze blockers, chains | `get_blocked_tasks`, `get_task_dependencies` | 75% vs subagent |
| **Hook Builder** | Create automation hooks | Write (generates bash scripts) | N/A (interactive) |
| **Skill Builder** | Create custom Skills | Write (generates SKILL.md files) | N/A (interactive) |

**How Skills Work**:
1. Claude Code scans `.claude/skills/` directory
2. Reads YAML frontmatter from `SKILL.md` files
3. When user request matches description, Claude automatically invokes Skill
4. Skill executes predefined workflow using allowed MCP tools
5. Returns result directly to user

**Example: Feature Management Skill**

User says: "What should I work on next?"

```markdown
Skill automatically executes:
1. get_feature(includeTasks=true, includeTaskDependencies=true)
2. get_next_task(featureId="...", limit=1)
3. Returns: "Recommended Task: T4 - Implement login endpoint (high priority, unblocked)"

Token Cost: ~300 tokens
vs Subagent: ~1400 tokens (78% savings)
```

**Skills Catalog**: See [`.claude/skills/README.md`](../.claude/skills/README.md) for complete documentation.

**Complete Skills Documentation**: [Skills Guide](skills-guide.md)

---

### Hooks Triggered by MCP Tools

Hooks are bash scripts (0 tokens) that execute automatically when MCP tool events occur.

**Available Hooks** (3 included):

| Hook | Trigger Event | Tool Matcher | Purpose |
|------|---------------|--------------|---------|
| **task-complete-commit.sh** | PostToolUse | `set_status` (completed) | Auto-commit on completion |
| **feature-complete-gate.sh** | PostToolUse | `update_feature` (completed) | Block if tests fail |
| **subagent-stop-logger.sh** | SubagentStop | All subagents | Log metrics |

**MCP Tools That Can Trigger Hooks**:
- `set_status` - Auto-commit, notifications, metrics
- `update_feature` - Quality gates, testing
- `update_task` - External system sync
- `create_task` - Template reminders

**Hooks Catalog**: See [`.claude/hooks/README.md`](../.claude/hooks/README.md) for complete documentation.

**Complete Hooks Documentation**: [Hooks Guide](hooks-guide.md)

---

### Integration Pattern: Skills + Hooks + MCP Tools

**Complete Workflow Example**:

```
1. "What's next?" → Feature Management Skill (300 tokens)
   ├─ get_feature, get_next_task
   └─ "Task T4: Implement login endpoint"

2. "Work on that" → Task Management Skill (300 tokens)
   ├─ get_task, recommend_agent
   └─ Routes to Backend Engineer

3. Backend Engineer (2000 tokens)
   ├─ get_task, add_section
   └─ set_status(completed)
      └─ Hook: task-complete-commit.sh (0 tokens)

Total: 2600 tokens (vs 5000+ without Skills/Hooks = 48% savings)
```

**See Also**: [Hybrid Architecture Guide](hybrid-architecture.md)

---

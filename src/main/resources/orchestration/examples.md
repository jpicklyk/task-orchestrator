---
name: examples
description: Real-world example scenarios demonstrating Task Orchestration workflows, user choices, and preference handling
version: "2.0.0"
---

# Task Orchestration Examples

## User Choice Scenarios

### Always Ask When:

1. **Blocker Reported by Subagent**
   - Simple blocker (missing file, config) → Suggest direct
   - Complex blocker (architecture issue) → Suggest specialist

2. **Small Edit Requests**
   - Single file, < 10 lines → Suggest direct
   - Multiple files, > 10 lines → Suggest specialist

3. **Bug Fixes**
   - Obvious typo/simple fix → Suggest direct
   - Requires investigation → Suggest specialist

4. **Documentation Updates**
   - Add/update 1-2 paragraphs → Suggest direct
   - Comprehensive docs → Suggest Implementation Specialist (documentation-implementation Skill)

5. **Configuration Changes**
   - Single config value → Suggest direct
   - Multi-file config → Suggest specialist

### Never Ask (Always Use Skill):

1. **Status Updates**
   - "Mark task complete" → Status Progression Skill (mandatory)
   - "Move to testing" → Status Progression Skill (mandatory)

2. **Task Routing**
   - "Execute tasks" → Task Orchestration Skill (mandatory)
   - "What's next" → Task Orchestration Skill (mandatory)

3. **Feature Lifecycle**
   - "Create feature" → Feature Orchestration Skill (mandatory)
   - "Complete feature" → Feature Orchestration Skill (mandatory)

4. **Dependency Checks**
   - "Check blockers" → Dependency Analysis Skill (mandatory)
   - "What can run in parallel" → Task Orchestration Skill (mandatory)

### User Preference Memory

**If user expresses preference:**
- "Just fix it directly" → Remember for session
- "Always use specialists" → Remember for session
- "Ask me each time" → Default behavior

**Respect stated preferences but confirm for major work:**
```
"You've preferred direct work for simple fixes. This change affects 15 files -
would you still like me to handle it directly, or should I route to Implementation Specialist?"
```

## Concrete Examples

### Example 1: Blocker Resolution
```
Subagent: "⚠️ BLOCKED - Missing resource file at /output-styles/task-orchestrator.md"
User: "Fix this"

You: "I can fix this blocker in two ways:

1. **Quick direct fix**: I'll run `git mv` to rename the folder right now
2. **Specialist fix**: Launch Senior Engineer to investigate and handle filesystem changes formally

The blocker is simple (one command). Which would you prefer?"

User: "1"
You: [Execute git mv, populate task summary, then use Status Progression Skill to mark complete]
```

### Example 2: Complex Implementation
```
User: "Implement user authentication API"

You: "This is substantial backend work. I'll use Task Orchestration Skill to route this:

1. Check if task exists, create if needed
2. Launch Implementation Specialist (Haiku) with backend-implementation Skill
3. Specialist will: implement API, write tests, update task, use Status Progression Skill to mark complete

Proceeding with specialist routing via Task Orchestration Skill..."
[Launch Task Orchestration Skill]
```

### Example 3: Documentation Update
```
User: "Update README to mention /output-styles command"

You: "I can handle this README update in two ways:

1. **Direct edit**: I'll add the documentation now (quick, ~3 lines)
2. **Implementation Specialist**: Launch specialist with documentation-implementation Skill for comprehensive review

This is a small addition. Which would you prefer?"

User: "1"
You: [Edit README, populate task summary, then use Status Progression Skill to mark complete]
```

### Example 4: Status Update Request
```
User: "Mark task T1 as complete"

You: [NO asking - this is coordination]
[Use Status Progression Skill to validate and update task status]
"✅ Task T1 marked complete via Status Progression Skill. Checking if any dependent tasks are now unblocked..."
```

## Real-World Integration Patterns

### Pattern 1: Coordination Work Flow
```
1. User requests coordination (feature creation, task execution, status updates)
2. You immediately use appropriate Skill (Feature, Task, Status Progression, or Dependency)
3. Skill performs work or launches subagent
4. Verify completion
5. Report to user
```

### Pattern 2: Implementation with User Confirmation
```
1. User requests implementation (code, docs, config)
2. You assess work scope
3. If complex or unclear: ASK USER - "Direct or Specialist?"
4. User confirms approach
5. Execute (direct or route via Skill to subagent)
6. Manage task lifecycle if working directly
```

### Pattern 3: Blocker Resolution
```
1. Subagent reports blocker
2. Assess blocker complexity and effort
3. If simple (< 5 mins): Suggest direct fix
4. If complex: Suggest specialist investigation
5. Wait for user confirmation
6. Execute chosen approach
```

### Pattern 4: Batch Work Management
```
1. Planning Specialist provides execution graph
2. You trust and use the graph (don't re-analyze)
3. Launch first batch of tasks via Task Orchestration Skill
4. Monitor progress
5. Cascade to next batch as tasks complete
6. Report progress after each batch
```

### Pattern 5: Feature Lifecycle
```
1. User: "Create feature X"
2. You: Use Feature Orchestration Skill
3. Skill creates feature and breaks down tasks (or launches Feature Architect for complex)
4. After tasks created: Watch for first task start
5. First task starts → Move feature to "in-development"
6. All tasks complete → Move feature to "testing"
7. Tests pass → Move feature to "completed"
```

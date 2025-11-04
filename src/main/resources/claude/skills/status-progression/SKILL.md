---
skill: status-progression
description: Navigate status workflows with user's config. Use when asked "What's next?", "Can I move to X?", or "Why did this fail?". Delegates to get_next_status tool, interprets StatusValidator errors, explains forward/backward/emergency patterns. Lightweight coordinator - validates nothing, explains everything.
---

# Status Progression Skill

**Role:** Workflow interpreter and guide. Helps users navigate their custom status workflows by delegating to tools and explaining results.

**Complementary Architecture:**
- **get_next_status tool** - Reads config, analyzes state, recommends next status (read-only)
- **StatusValidator** - Validates transitions at write-time, enforces rules, checks prerequisites (write-time)
- **This Skill** - Interprets recommendations and errors, explains user's config rules (guidance)

## When to Use This Skill

**Activate for:**
- "What status comes next?"
- "Can I move to testing/completed/etc?"
- "Why did my status change fail?"
- "Am I ready to complete this?"
- "What's my workflow?"
- "How do backward transitions work?"

## Core Responsibilities

1. **Delegate to get_next_status** - Let the tool analyze state and recommend next status
2. **Read user's config** - Load `status_validation` rules to explain what's possible
3. **Interpret errors** - Explain StatusValidator error messages in context of user's config
4. **Guide transitions** - Explain forward, backward, and emergency movements

## Tools Available

- `Read` - Load `.taskorchestrator/config.yaml` (status_validation section only)
- `get_next_status` - Intelligent status recommendations (delegates to MCP tool)
- `query_workflow_state` - Complete workflow state with cascade events (**NEW**)
- `query_container` - Check entity state (status, tags) when interpreting errors

**Critical:** This skill does NOT validate transitions. StatusValidator handles that automatically at write-time. This skill interprets results and explains user's workflow rules.

## Workflow Patterns: Forward, Backward, and Emergency

### Pattern 1: Forward Progression (Happy Path)

**When:** Moving through normal workflow sequence

**Example:**
```javascript
// User asks: "What's next?"
recommendation = get_next_status(containerId="task-uuid", containerType="task")

// Guide user:
"You're at: in-progress
Next step: testing

Your bug_fix_flow: pending → in-progress → testing → completed
StatusValidator will verify prerequisites when you transition."
```

**Why forward?** Normal progression toward completion.

### Pattern 2: Backward Movement (Rework/Iteration)

**When:** Need to fix issues, iterate on work, or respond to changes

**Example:**
```javascript
// User asks: "Can I move back to in-progress from in-review?"
config = Read(".taskorchestrator/config.yaml")

if (config.status_validation.allow_backward == true) {
  "✅ Yes! Your config allows backward movement.

  in-review → in-progress (backward to implement changes)

  Why: Code review requested changes, found a bug, requirements changed
  Then re-submit: in-progress → in-review"
}
```

**Why backward?** Iterative development - fix issues and retry without restarting from scratch.

**For detailed review iteration patterns:**
Read `./config-reference.md`

### Pattern 3: Emergency Transitions (Blockers/Cancellations)

**When:** Unexpected blockers, priority changes, or cancellations

**Example:**
```javascript
// User asks: "Task is blocked by external API, what do I do?"
config = Read(".taskorchestrator/config.yaml")

emergencyStatuses = config.status_progression.tasks.emergency_transitions
// Returns: [blocked, on-hold, cancelled, deferred]

"✅ Use emergency transition to: blocked
in-progress → blocked (emergency - can happen from any state)

Why emergency transitions exist:
- blocked: External dependency or technical issue
- on-hold: Priority shift, paused temporarily
- cancelled: Task no longer needed
- deferred: Postponed indefinitely"
```

**Why emergency?** Real-world interruptions don't follow linear workflows.

## Quick Action Patterns

### Action 1: What's Next?

**User:** "What status comes next?"

**Workflow:**
```javascript
// Delegate to tool
recommendation = get_next_status(containerId="task-uuid", containerType="task")
```

**Response:**
```
Next: testing

Your position: in-progress [YOU ARE HERE]
Flow: pending → in-progress → testing → completed
Using: bug_fix_flow (matched your tags: [bug, backend])

To transition:
manage_container(operation="setStatus", containerType="task", id="...", status="testing")
```

### Action 2: Try Transition (Let StatusValidator Check)

**User:** "Can I mark this complete?"

**Workflow:**
```javascript
// Don't manually validate - delegate to get_next_status
recommendation = get_next_status(containerId="task-uuid", containerType="task")

// Guide user to try it
"get_next_status recommends: completed

Try it! StatusValidator will check prerequisites automatically:
manage_container(operation=\"setStatus\", containerType=\"task\", id=\"...\", status=\"completed\")

If prerequisites aren't met, StatusValidator will explain what's missing.
I'll help interpret any errors."
```

**Key:** Let StatusValidator do validation. This skill interprets results.

### Action 3: Interpret StatusValidator Error

**User gets error:** "Cannot skip statuses. Must transition through: in-progress"

**Workflow:**
```javascript
// Read user's config to explain the rule
config = Read(".taskorchestrator/config.yaml")

"StatusValidator blocked this because YOUR config has:
  enforce_sequential: true

You tried: pending → testing (skipped in-progress)

Your options:
1. Follow sequential flow: pending → in-progress → testing
2. Change your config: Set enforce_sequential: false in config.yaml

Recommended next step:
manage_container(operation=\"setStatus\", containerType=\"task\", id=\"...\", status=\"in-progress\")"
```

**For detailed error interpretation patterns:**
Read `./validation-errors.md`

### Action 4: Check Complete Workflow State (NEW)

**User asks:** "Show me everything about this task's workflow"

**Workflow:**
```javascript
// Get comprehensive workflow state
workflowState = query_workflow_state(
  containerType="task",
  id="task-uuid"
)

// Explain complete state
"📊 Workflow State:

Current: ${workflowState.currentStatus}
Active Flow: ${workflowState.activeFlow}

✅ Allowed Next Steps:
${workflowState.allowedTransitions.map(s => `  → ${s}`).join('\n')}

⚠️ Cascade Events Detected:
${workflowState.detectedEvents.map(e => `  • ${e.event}: ${e.reason}`).join('\n')}

📋 Prerequisites for Each Transition:
${Object.entries(workflowState.prerequisites).map(([status, prereq]) =>
  `  ${status}: ${prereq.met ? '✅' : '❌'} ${prereq.blockingReasons.join(', ')}`
).join('\n')}"
```

**Benefits:**
- Single call gets complete workflow context
- Shows all allowed transitions from config
- Detects cascade events automatically
- Validates prerequisites for each option
- Works with user's custom workflows

## Understanding User-Defined Flows

**Critical:** Flows are defined by the USER in their config.yaml. Don't assume any specific flows exist.

**Check user's actual flows:**
```javascript
config = Read(".taskorchestrator/config.yaml")
taskFlows = Object.keys(config.status_progression.tasks).filter(key => key.endsWith('_flow'))
// Tell user: "Your config has these task flows: [list]"
```

**Default config has** (IF user hasn't customized):
- Tasks: `default_flow`, `bug_fix_flow`, `documentation_flow`, `hotfix_flow`, `with_review`
- Features: `default_flow`, `rapid_prototype_flow`, `with_review_flow`
- Projects: `default_flow`

**For custom flow examples (research, compliance, experiments, etc.):**
Read `./examples.md`

**For tag-based flow selection details:**
Read `./examples.md`

## What to Read from Config

**Read selectively** - only load sections needed to explain user's rules:

```javascript
config = Read(".taskorchestrator/config.yaml")

// Section 1: Validation rules (always useful)
validationRules = config.status_validation
// - enforce_sequential: Can skip statuses?
// - allow_backward: Can move backwards?
// - allow_emergency: Can jump to blocked/cancelled?
// - validate_prerequisites: Does StatusValidator check requirements?

// Section 2: Entity-specific flows (when explaining workflows)
taskFlows = config.status_progression.tasks
emergencyStatuses = config.status_progression.tasks.emergency_transitions
terminalStatuses = config.status_progression.tasks.terminal_statuses
```

**For detailed config structure reference:**
Read `./config-reference.md`

## Best Practices

1. **Always delegate to get_next_status** - Let the tool analyze state and recommend
2. **Read config selectively** - Load only status_validation and relevant flows
3. **Never duplicate validation** - StatusValidator checks prerequisites, not this skill
4. **Explain user's specific config** - Don't assume default flows exist
5. **Interpret, don't predict** - Explain errors after they happen, don't try to predict them
6. **Use progressive loading** - Load supporting files (examples.md, validation-errors.md, config-reference.md) only when needed
7. **Emphasize user control** - Flows are defined by user, not hardcoded by system

## Supporting Files

**Load on demand for specific scenarios:**

- **./examples.md** - Custom flow examples, tag-based selection patterns
  - Load when: User asks about custom flows, workflow possibilities, tag routing

- **./validation-errors.md** - Common errors, prerequisite failures, fix patterns
  - Load when: StatusValidator error occurs, user asks about validation rules

- **./config-reference.md** - Detailed config structure, backward movement, integration
  - Load when: User asks about configuration, how to customize workflows

## Key Reminders

**Your Role (Lightweight Coordination):**
- ✅ Delegate to get_next_status for all recommendations
- ✅ Read config.status_validation to explain user's rules
- ✅ Interpret StatusValidator errors with context
- ✅ Show forward/backward/emergency patterns with WHY explanations
- ✅ Reference user's ACTUAL config, not assumed defaults
- ✅ Load supporting files only when needed (progressive loading)

**What You DON'T Do:**
- ❌ Don't manually validate transitions (StatusValidator's job)
- ❌ Don't check prerequisites before transitions (StatusValidator's job)
- ❌ Don't duplicate logic from get_next_status (delegate to tool)
- ❌ Don't assume specific flows exist (user-defined, could be customized)
- ❌ Don't load all supporting files upfront (use progressive loading)

**Complementary Roles:**
- **get_next_status** reads config, analyzes tags, recommends (read-only)
- **StatusValidator** enforces rules, checks prerequisites (write-time)
- **You** interpret results, explain possibilities (guidance)

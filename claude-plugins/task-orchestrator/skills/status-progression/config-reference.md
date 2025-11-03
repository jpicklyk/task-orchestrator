# Status Progression - Configuration Reference

This file contains detailed config structure and backward movement patterns. Load this when users ask about configuration or how to customize workflows.

## Config Structure

**File location:** `.taskorchestrator/config.yaml`

### Full Structure Overview

```yaml
version: "2.0.0"

status_progression:
  features:
    default_flow: [...]           # Always exists
    custom_flow: [...]            # User-defined (optional)
    flow_mappings: [...]          # Tag → flow routing
    emergency_transitions: [...]  # Statuses from any state
    terminal_statuses: [...]      # End states

  tasks:
    default_flow: [...]
    custom_flow: [...]
    flow_mappings: [...]
    emergency_transitions: [...]
    terminal_statuses: [...]

  projects:
    default_flow: [...]
    emergency_transitions: [...]
    terminal_statuses: [...]

status_validation:
  enforce_sequential: true/false  # Sequential progression required?
  allow_backward: true/false      # Backward movement allowed?
  allow_emergency: true/false     # Emergency transitions allowed?
  validate_prerequisites: true/false # Prerequisite checking enabled?
```

### What Each Section Controls

**status_progression.{entityType}.default_flow:**
- **Purpose:** Fallback flow when no tags match flow_mappings
- **Required:** Yes (at least one flow per entity type)
- **Format:** Array of status names in kebab-case
- **Example:** `[pending, in-progress, testing, completed]`

**status_progression.{entityType}.flow_mappings:**
- **Purpose:** Route entities to flows based on tags
- **Required:** No (can be empty array)
- **Format:** Array of {tags, flow} objects
- **Example:**
  ```yaml
  - tags: [bug, bugfix, fix]
    flow: bug_fix_flow
  ```
- **Matching:** First match wins (order matters)

**status_progression.{entityType}.emergency_transitions:**
- **Purpose:** Statuses accessible from any state (bypass flow rules)
- **Required:** No (can be empty array)
- **Format:** Array of status names
- **Example:** `[blocked, on-hold, cancelled, deferred]`
- **When used:** Unexpected blockers, priority shifts, cancellations

**status_progression.{entityType}.terminal_statuses:**
- **Purpose:** End states with no further progression
- **Required:** Yes (can be empty but must exist)
- **Format:** Array of status names
- **Example:** `[completed, cancelled, archived]`
- **Effect:** Cannot transition FROM these statuses

**status_validation.enforce_sequential:**
- **Purpose:** Require step-by-step progression through flow
- **When true:** Cannot skip statuses (pending → testing blocked, must go through in-progress)
- **When false:** Can jump to any status in flow
- **Recommendation:** true for teams needing process compliance

**status_validation.allow_backward:**
- **Purpose:** Allow moving backwards in flow for rework
- **When true:** Can move testing → in-progress for bug fixes
- **When false:** Can only move forward
- **Recommendation:** true for iterative development

**status_validation.allow_emergency:**
- **Purpose:** Allow emergency_transitions from any state
- **When true:** Can jump to blocked/on-hold/cancelled from anywhere
- **When false:** Emergency statuses follow normal flow rules
- **Recommendation:** true to handle unexpected issues

**status_validation.validate_prerequisites:**
- **Purpose:** Check business rules before transitions
- **When true:** Feature needs tasks before in-development, tasks need summary before completed
- **When false:** Skip prerequisite checks
- **Recommendation:** true to prevent incomplete work from being marked complete

---

## Backward Movement for Review Iterations

**Pattern:** With `allow_backward: true`, you can move backwards in the flow for unlimited review iterations without duplicate statuses.

### Example: with_review Flow

**Flow definition:**
```yaml
with_review:
  - backlog
  - pending
  - in-progress
  - in-review          # Awaiting code review
  - testing            # Review approved, testing
  - completed
```

**Config settings:**
```yaml
status_validation:
  enforce_sequential: true   # Follow flow order
  allow_backward: true       # Allow backward movement
```

### Iteration Workflow

**First submission:**
```javascript
// Developer finishes work
in-progress → in-review (forward)
```

**Changes requested:**
```javascript
// Reviewer requests changes
in-review → in-progress (backward to implement changes)
```

**Re-submission after changes:**
```javascript
// Developer re-submits after fixes
in-progress → in-review (forward for re-review)
```

**Multiple rounds:**
```javascript
// If more changes needed, repeat:
in-review → in-progress (backward again)
in-progress → in-review (forward again)

// Unlimited iterations supported
```

**Approval:**
```javascript
// When approved, move forward
in-review → testing (forward when review passes)
testing → completed (forward to complete)
```

### Why Backward Movement Works Better

**Instead of this (duplicate statuses in flow - DOESN'T WORK with enforce_sequential):**
```yaml
# ❌ BROKEN with enforce_sequential: true
with_review_iterations:
  - pending
  - in-progress
  - in-review
  - in-progress    # Duplicate status
  - in-review      # Duplicate status
  - in-progress    # Duplicate status
  - testing
```

**Use this (clean flow + backward movement):**
```yaml
# ✅ WORKS with enforce_sequential: true + allow_backward: true
with_review:
  - pending
  - in-progress
  - in-review
  - testing
  - completed

# No duplicates needed - backward movement handles iterations
```

### Key Benefits

- ✅ **Unlimited iterations** - Move backward as many times as needed
- ✅ **Clean flow definition** - No duplicate statuses required
- ✅ **Works with enforce_sequential** - Maintains process compliance
- ✅ **Clear history** - Status changes show iteration count
- ✅ **Flexible** - Works for any iterative workflow (reviews, testing, validation)

### changes-requested Status

**Note:** The `changes-requested` status exists in the TASK enum but is NOT used in default flows.

**Why it exists:**
- Some teams want explicit "changes requested" state
- Can create custom flows that include it

**Why it's not in default flows:**
- Backward movement pattern is more flexible
- No need for separate status - just move backward

**If you want to use it:**
```yaml
# Custom flow with changes-requested
custom_review_flow:
  - pending
  - in-progress
  - in-review
  - changes-requested  # Explicit state for requested changes
  - testing
  - completed

# Workflow:
# in-review → changes-requested (reviewer requests changes)
# changes-requested → in-progress (developer starts fixing)
# in-progress → in-review (developer re-submits)
```

---

## What to Read from Config

**Read selectively** - only load sections needed to explain user's rules:

### Always Useful: Validation Rules

```javascript
config = Read(".taskorchestrator/config.yaml")

// Section 1: Validation rules (always useful)
validationRules = config.status_validation
// - enforce_sequential: Can skip statuses?
// - allow_backward: Can move backwards?
// - allow_emergency: Can jump to blocked/cancelled?
// - validate_prerequisites: Does StatusValidator check requirements?
```

### When Explaining Workflows: Entity-Specific Flows

```javascript
// Section 2: Entity-specific flows (when explaining workflows)
taskFlows = config.status_progression.tasks
featureFlows = config.status_progression.features
projectFlows = config.status_progression.projects

// Get specific flow
defaultTaskFlow = taskFlows.default_flow
bugFixFlow = taskFlows.bug_fix_flow  // May not exist - check first

// Get emergency and terminal statuses
emergencyStatuses = taskFlows.emergency_transitions
terminalStatuses = taskFlows.terminal_statuses
```

### When Explaining Tag Routing: Flow Mappings

```javascript
// Section 3: Flow mappings (when explaining tag-based routing)
taskMappings = config.status_progression.tasks.flow_mappings
featureMappings = config.status_progression.features.flow_mappings

// Show user THEIR mappings, not assumed defaults
"Your task flow mappings:
- Tags [bug, bugfix, fix] → bug_fix_flow
- Tags [documentation, docs] → documentation_flow
- Default: default_flow"
```

### What NOT to Read

**Don't read these sections** - not implemented or not used by this skill:
- `parallelism` - Not implemented yet
- `automation` - Not implemented yet
- `complexity` - Not loaded by backend (referenced in docs only)
- `feature_creation` - Not loaded by backend (referenced in docs only)
- `dependencies` - Not implemented yet

**Only read status_progression and status_validation sections.**

---

## Checking What Exists in User's Config

**Never assume flows exist** - always check user's actual config:

### Check Available Flows

```javascript
config = Read(".taskorchestrator/config.yaml")

// Get all task flows
taskFlows = Object.keys(config.status_progression.tasks)
  .filter(key => key.endsWith('_flow'))

// Tell user what's configured
"Your config has these task flows:
- default_flow (always exists)
- bug_fix_flow
- documentation_flow
- hotfix_flow
- with_review

You can create custom flows by adding them to your config.yaml"
```

### Check Flow Mappings

```javascript
// Get user's actual mappings
taskMappings = config.status_progression.tasks.flow_mappings

// Check if specific mapping exists
hasBugMapping = taskMappings.some(m => m.tags.includes("bug"))

if (!hasBugMapping) {
  "No bug flow mapping in your config.
  Tasks with 'bug' tag will use default_flow.

  To add bug mapping, edit config.yaml:
  flow_mappings:
    - tags: [bug, bugfix, fix]
      flow: bug_fix_flow"
}
```

---

## When Config Missing

If `.taskorchestrator/config.yaml` doesn't exist:

```javascript
// Attempted to read config
config = Read(".taskorchestrator/config.yaml")

// File not found
if (error) {
  "No config found. StatusValidator using fallback mode:
  - All enum statuses allowed
  - No transition rules enforced
  - No prerequisite validation

  To enable config-driven validation:
  1. Run setup_claude_orchestration tool
  2. Creates .taskorchestrator/config.yaml
  3. Customize rules as needed

  Without config, status transitions rely only on enum validation."
}
```

**Fallback behavior:**
- StatusValidator allows any enum status
- No flow sequences enforced
- No prerequisite checking
- Basic enum validation only

---

## Integration with Other Skills

**Status Progression Skill works alongside:**

### Feature Management Skill
- Feature Management uses config for quality gates
- Status Progression explains what those gates are
- Complementary: Feature Management coordinates, Status Progression guides

### Task Management Skill
- Task Management uses config for execution planning
- Status Progression explains workflow possibilities
- Complementary: Task Management executes, Status Progression guides

### Dependency Analysis Skill
- Dependency Analysis complements prerequisite checking
- Shows what's blocking tasks
- Status Progression explains how blockers affect status transitions

**Clear separation:**
- Feature/Task Management Skills: Coordination and execution
- Status Progression Skill: Workflow guidance and error interpretation
- Dependency Analysis Skill: Blocker identification

---

## Customization Examples

### Adding a Custom Flow

**User wants research task flow (no testing needed):**

```yaml
# Edit .taskorchestrator/config.yaml
status_progression:
  tasks:
    # Add new flow
    research_task_flow:
      - pending
      - in-progress
      - completed

    # Add mapping
    flow_mappings:
      - tags: [research, spike, investigation]
        flow: research_task_flow
```

**Result:** Tasks tagged with "research" skip testing and use simplified flow.

### Changing Validation Rules

**User wants flexible workflow (skip statuses allowed):**

```yaml
# Edit .taskorchestrator/config.yaml
status_validation:
  enforce_sequential: false  # Now can skip statuses
  allow_backward: true       # Keep backward movement
  allow_emergency: true
  validate_prerequisites: true
```

**Result:** Can jump directly from pending → testing without going through in-progress.

### Adding Emergency Transition

**User wants "deferred" status accessible anytime:**

```yaml
# Edit .taskorchestrator/config.yaml
status_progression:
  tasks:
    emergency_transitions:
      - blocked
      - on-hold
      - cancelled
      - deferred  # Add this
```

**Result:** Can move to deferred from any state (low-priority task postponed indefinitely).

---

## Summary

**Progressive Loading Pattern:**
1. User asks about configuration
2. You load this file
3. Show relevant section based on question
4. Reference user's actual config for examples

**Remember:**
- Always read user's config.yaml before explaining rules
- Config sections are user-defined and customizable
- Only read status_progression and status_validation sections
- Check what exists before referencing flows
- Explain rules in terms of user's specific settings

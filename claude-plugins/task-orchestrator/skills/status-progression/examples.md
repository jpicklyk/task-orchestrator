# Status Progression - Flow Examples

This file contains custom flow examples and tag-based selection patterns. Load this when users ask about workflow possibilities or custom flow creation.

## Flows in Default Config (Starter Examples)

**IF the user hasn't customized their config**, these flows exist:

### Task Flows

**default_flow:**
```yaml
- backlog            # Task in backlog, needs prioritization
- pending            # Task ready, waiting to start
- in-progress        # Actively being worked on
- testing            # Implementation complete, running tests
- completed          # Task complete
```

**bug_fix_flow:**
```yaml
- pending            # Bug reported, ready to fix (skip backlog for urgency)
- in-progress        # Fixing bug
- testing            # Testing fix
- completed          # Bug fixed
```

**documentation_flow:**
```yaml
- pending
- in-progress
- in-review          # Documentation review (no code testing needed)
- completed
```

**hotfix_flow:**
```yaml
- pending
- in-progress
- completed          # Emergency fixes, skip backlog and review
```

**with_review:**
```yaml
- backlog
- pending
- in-progress
- in-review          # Awaiting code review
- testing            # Review approved, testing
- completed
```

### Feature Flows

**default_flow:**
```yaml
- draft              # Initial draft, rough ideas
- planning           # Define requirements, break into tasks
- in-development     # Active implementation
- testing            # All tasks complete, running tests
- validating         # Tests passed, final validation
- completed          # Feature complete and validated
```

**rapid_prototype_flow:**
```yaml
- draft
- in-development     # Skip planning and testing for quick experiments
- completed
```

**with_review_flow:**
```yaml
- draft
- planning
- in-development
- testing
- validating
- pending-review     # Awaiting human approval
- completed
```

### Project Flows

**default_flow:**
```yaml
- planning           # Define scope and features
- in-development     # Active development
- completed          # Project finished
- archived           # Archive for history
```

---

## Example Custom Flows (What Users COULD Create)

**These DON'T exist by default** - they show what's possible when users customize their config.

### 1. Research Task Flow

**Use case:** Research tasks, spikes, investigations that don't need testing

```yaml
research_task_flow:
  - pending
  - in-progress
  - completed         # No testing needed for research

flow_mappings:
  - tags: [research, spike, investigation]
    flow: research_task_flow
```

**Why this works:**
- Research is exploratory, doesn't produce testable code
- Skips testing gate entirely
- Faster completion for non-code work

**Example tasks:**
- "Research authentication libraries"
- "Investigate performance bottleneck"
- "Spike: Feasibility of real-time sync"

### 2. Compliance Review Flow

**Use case:** Regulated features requiring legal/compliance approval

```yaml
compliance_review_flow:
  - draft
  - planning
  - in-development
  - testing
  - validating
  - pending-review    # Legal/compliance review gate
  - completed

flow_mappings:
  - tags: [compliance, regulated, audit, hipaa, gdpr]
    flow: compliance_review_flow
```

**Why this works:**
- Adds formal approval gate before completion
- Required for regulated industries (healthcare, finance)
- Ensures compliance sign-off is documented

**Example features:**
- "HIPAA-compliant patient data storage"
- "GDPR data export API"
- "SOC 2 audit logging"

### 3. Experiment Flow

**Use case:** Experiments designed to fail fast without formal process

```yaml
experiment_flow:
  - pending
  - in-progress
  - cancelled         # Most experiments fail - that's OK!
  # OR
  - completed         # Some succeed

# No testing or review required - experiments are exploratory

flow_mappings:
  - tags: [experiment, try, explore]
    flow: experiment_flow
```

**Why this works:**
- Experiments are meant to fail fast
- No formal testing/review overhead
- Terminal states: cancelled (expected) or completed (rare success)

**Example tasks:**
- "Experiment: Can we use WebAssembly for this?"
- "Try implementing with GraphQL instead"
- "Explore alternative caching strategy"

### 4. Staged Rollout Flow

**Use case:** Gradual deployment with validation gates

```yaml
staged_rollout_flow:
  - draft
  - planning
  - in-development
  - testing
  - validating
  - deployed          # Custom intermediate status (if added to enum)
  - completed

flow_mappings:
  - tags: [gradual-rollout, canary, phased-deploy]
    flow: staged_rollout_flow
```

**Why this works:**
- Adds explicit "deployed" state between validating and completed
- Allows tracking deployment progress separately
- Useful for canary deployments, feature flags

**Note:** Requires adding "deployed" status to FEATURE enum (not in default)

**Example features:**
- "New recommendation algorithm (canary rollout)"
- "Payment provider migration (phased)"

### 5. Multi-Stage Review Flow

**Use case:** Features requiring multiple approval stages (design, security, business)

```yaml
multi_review_flow:
  - draft
  - planning
  - in-review          # Design review
  - in-development
  - testing
  - validating
  - pending-review     # Security/business review
  - completed

flow_mappings:
  - tags: [high-risk, security-critical, business-critical]
    flow: multi_review_flow
```

**Why this works:**
- Two review gates: after planning (design) and before completion (security/business)
- Catches issues early with design review
- Final validation before production

**Example features:**
- "New authentication method"
- "Payment processing changes"
- "Data retention policy implementation"

### 6. Simple Task Flow (Minimal Overhead)

**Use case:** Trivial tasks that don't need backlog or testing

```yaml
simple_task_flow:
  - pending
  - in-progress
  - completed

flow_mappings:
  - tags: [trivial, simple, quick-fix]
    flow: simple_task_flow
```

**Why this works:**
- Minimal workflow for tiny changes
- Skips backlog (not worth prioritizing)
- Skips testing (too small to warrant formal testing)

**Example tasks:**
- "Update copyright year"
- "Fix typo in error message"
- "Add tooltip to button"

---

## Tag-Based Flow Selection

The `get_next_status` tool automatically selects workflows based on tags **IF** the user has flow_mappings configured.

### How Tag Matching Works

```javascript
// get_next_status reads user's config and matches tags
recommendation = get_next_status(
  containerId="task-uuid",
  containerType="task"
)

// Tool checks task tags → matches flow_mappings → returns appropriate flow
// If tags: [bug, backend] → uses bug_fix_flow (IF that mapping exists in config)
// If tags: [research] → uses research_task_flow (IF user created that flow)
// If no match → uses default_flow
```

### Default Tag Mappings (IF User Hasn't Changed Config)

**Task mappings:**
```yaml
flow_mappings:
  - tags: [bug, bugfix, fix]
    flow: bug_fix_flow
  - tags: [documentation, docs]
    flow: documentation_flow
  - tags: [hotfix, emergency, critical]
    flow: hotfix_flow
  - tags: [needs-review, code-review]
    flow: with_review
```

**Feature mappings:**
```yaml
flow_mappings:
  - tags: [prototype, poc, spike]
    flow: rapid_prototype_flow
  - tags: [needs-review, stakeholder-approval]
    flow: with_review_flow
```

### Custom Tag Mapping Examples

**User's custom config could have:**
```yaml
# Tasks
flow_mappings:
  - tags: [research, spike]
    flow: research_task_flow        # Custom flow user created
  - tags: [compliance]
    flow: compliance_review_flow    # Another custom flow
  - tags: [experiment]
    flow: experiment_flow
  - tags: [trivial]
    flow: simple_task_flow

# Features
flow_mappings:
  - tags: [gradual-rollout, canary]
    flow: staged_rollout_flow
  - tags: [high-risk, security-critical]
    flow: multi_review_flow
```

### Checking User's Actual Mappings

**Always verify what the user has configured:**

```javascript
config = Read(".taskorchestrator/config.yaml")

// Get task flow mappings
taskMappings = config.status_progression.tasks.flow_mappings
// Show user THEIR mappings, not assumed defaults

// Get feature flow mappings
featureMappings = config.status_progression.features.flow_mappings

// Tell user what's configured:
"Your task flow mappings:
- Tags [bug, bugfix, fix] → bug_fix_flow
- Tags [research] → research_task_flow  (custom!)
- Tags [experiment] → experiment_flow   (custom!)
- Default: default_flow"
```

### Tag Matching Priority

When a task/feature has multiple tags, the **first matching mapping** wins:

```yaml
flow_mappings:
  - tags: [hotfix, emergency, critical]
    flow: hotfix_flow               # Priority 1
  - tags: [bug, bugfix, fix]
    flow: bug_fix_flow              # Priority 2
  - tags: [needs-review]
    flow: with_review               # Priority 3
```

**Example:** Task with tags `[hotfix, bug]`
- Matches mapping 1 (hotfix) → uses `hotfix_flow`
- Does NOT use `bug_fix_flow` (even though "bug" tag is present)

**Best practice:** Order mappings from most specific to least specific.

---

## Creating Custom Flows - Best Practices

### 1. Start with a Use Case

**Ask:**
- What makes this workflow different from default?
- What gates can I skip? (backlog, testing, review)
- What gates do I need to add? (compliance, multi-stage review)

### 2. Keep Flows Linear

**Do:**
```yaml
research_task_flow:
  - pending
  - in-progress
  - completed
```

**Don't:**
```yaml
research_task_flow:
  - pending
  - in-progress
  - in-progress    # ❌ Duplicates don't work with enforce_sequential
  - completed
```

**Why:** With `enforce_sequential: true`, duplicate statuses break validation. Use backward movement instead.

### 3. Use Backward Movement for Iterations

**Instead of duplicating statuses, enable backward movement:**

```yaml
status_validation:
  allow_backward: true  # Enable this
```

**Then flow becomes:**
```yaml
with_review:
  - pending
  - in-progress
  - in-review
  - testing
  - completed

# Iteration: in-review → in-progress (backward) → in-review (forward)
# Unlimited iterations without duplicate statuses
```

### 4. Tag Conventions

**Use consistent tag patterns:**
- Workflow type: `research`, `experiment`, `compliance`
- Urgency: `hotfix`, `emergency`, `critical`
- Process: `needs-review`, `stakeholder-approval`
- Domain: `backend`, `frontend`, `database`

**Combine tags for specificity:**
- Task with `[bug, backend]` → bug fix flow, backend specialist
- Task with `[research, frontend]` → research flow, frontend context

### 5. Document Your Custom Flows

**Add comments to config.yaml:**
```yaml
# Research task flow - for spikes and investigations that don't produce testable code
# Created: 2024-01-15
# Use for: Technical research, library evaluations, architecture spikes
research_task_flow:
  - pending
  - in-progress
  - completed
```

---

## When to Create a Custom Flow

**Create custom flow when:**
- ✅ Default flow has unnecessary gates for specific work types (research doesn't need testing)
- ✅ Need additional approval gates (compliance review, multi-stage approval)
- ✅ Want faster path for specific scenarios (hotfix skips backlog and review)
- ✅ Pattern repeats frequently (if you keep skipping same gates, formalize it)

**Don't create custom flow when:**
- ❌ One-off exception (use emergency transitions instead)
- ❌ Only difference is number of iterations (use backward movement)
- ❌ Can be handled by tags alone (tags don't require new flow)

---

## Summary

**Progressive Loading Pattern:**
1. User asks: "What custom flows can I create?"
2. You load this file
3. Show relevant examples based on their context
4. Guide them to update their config.yaml

**Remember:**
- All examples here are POSSIBILITIES, not defaults
- Users must add flows to their config.yaml
- Always check user's actual config before referencing flows
- Use tag mappings to automatically route to appropriate flows

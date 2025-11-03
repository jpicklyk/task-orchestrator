# Feature Orchestration - Configuration Reference

Complete guide to configuring feature status workflows in `.taskorchestrator/config.yaml`.

## Status Flow Configuration

Feature workflows are configured in `.taskorchestrator/config.yaml` using the `status_flows` section.

### Built-in Flows

#### default_flow (Most Common)
```yaml
feature_status_flows:
  default_flow:
    - draft
    - planning
    - in-development
    - testing
    - validating
    - completed
```

**Use for:** Standard feature development with full QA process

**Phases:**
- **draft** → **planning**: Initial concept, gathering requirements
- **planning** → **in-development**: Requirements defined, tasks created, work begins
- **in-development** → **testing**: All tasks complete, ready for QA
- **testing** → **validating**: Tests pass, final checks
- **validating** → **completed**: All validation complete, feature shipped

**Prerequisites:**
- planning → in-development: ≥1 task created
- in-development → testing: All tasks completed/cancelled
- testing → validating: Tests passed
- validating → completed: All tasks completed/cancelled

---

#### rapid_prototype_flow
```yaml
feature_status_flows:
  rapid_prototype_flow:
    - draft
    - in-development
    - completed
```

**Use for:** Quick prototypes, spikes, proof-of-concepts

**Phases:**
- **draft** → **in-development**: Start coding immediately
- **in-development** → **completed**: Work done, skip testing

**Prerequisites:**
- draft → in-development: None (can start immediately)
- in-development → completed: All tasks completed/cancelled

**Warning:** No testing phase - use only for throwaway prototypes

---

#### with_review_flow
```yaml
feature_status_flows:
  with_review_flow:
    - draft
    - planning
    - in-development
    - testing
    - validating
    - pending-review
    - completed
```

**Use for:** Features requiring human approval (security, architecture, UX)

**Phases:**
- Same as default_flow until **validating**
- **validating** → **pending-review**: Automated checks pass, waiting for human review
- **pending-review** → **completed**: Review approved, ship it
- **pending-review** → **in-development**: Changes requested, back to work (if allow_backward: true)

**Prerequisites:**
- All default_flow prerequisites plus:
- validating → pending-review: All validation complete
- pending-review → completed: Review approved (external signal)

---

## Flow Mapping (Tag-Based Routing)

Control which features use which flow via tags.

### Configuration Pattern

```yaml
status_flows:
  feature_flows:
    flow_mappings:
      - tags: ["prototype", "spike", "poc"]
        flow_name: rapid_prototype_flow

      - tags: ["security", "architecture", "breaking-change"]
        flow_name: with_review_flow

      - tags: []  # Default - matches any feature without specific tags
        flow_name: default_flow
```

### How Matching Works

1. Feature has tags: `["backend", "security", "api"]`
2. get_next_status checks mappings in order:
   - First mapping: Does feature have ANY of `["prototype", "spike", "poc"]`? → No
   - Second mapping: Does feature have ANY of `["security", "architecture", "breaking-change"]`? → Yes (security)
   - **Match found** → Use `with_review_flow`

3. Feature has tags: `["frontend", "ui"]`
   - First mapping: No match
   - Second mapping: No match
   - Third mapping: Empty tags `[]` → **Always matches** → Use `default_flow`

### Tag Strategy Best Practices

**Domain Tags** (don't affect flow):
- backend, frontend, database, testing, documentation

**Flow Routing Tags** (affect status flow):
- prototype, spike, poc → rapid_prototype_flow
- security, architecture, breaking-change → with_review_flow

**Example Feature Tagging:**
```javascript
// Standard feature - uses default_flow
manage_container(
  operation="create",
  containerType="feature",
  tags="backend,api,user-management"
)

// Prototype - uses rapid_prototype_flow
manage_container(
  operation="create",
  containerType="feature",
  tags="frontend,prototype,ui-experiment"
)

// Security feature - uses with_review_flow
manage_container(
  operation="create",
  containerType="feature",
  tags="backend,security,oauth-integration"
)
```

---

## Prerequisite Validation

Configured in `status_validation` section.

### Validation Matrix

| Transition | Prerequisite | Validation Rule | Error if Failed |
|------------|--------------|-----------------|-----------------|
| planning → in-development | Task count ≥ 1 | `taskCounts.total >= 1` | "Feature must have at least 1 task before transitioning to IN_DEVELOPMENT" |
| in-development → testing | All tasks complete | `taskCounts.byStatus.pending == 0 && taskCounts.byStatus["in-progress"] == 0` | "Cannot transition to TESTING: X task(s) not completed" |
| testing → validating | Tests passed | External signal | "Cannot transition to VALIDATING: Tests not run or failing" |
| validating → completed | All tasks complete | `taskCounts.byStatus.pending == 0 && taskCounts.byStatus["in-progress"] == 0` | "Cannot transition to COMPLETED: X task(s) not completed" |

### Configuration Options

```yaml
status_validation:
  validate_prerequisites: true  # Enable validation (default: true)
```

**Production:** Always keep `validate_prerequisites: true`
**Development/Testing:** Set to `false` to bypass validation temporarily

---

## Backward Movement

Allow moving features backward in workflow (e.g., testing → in-development for rework).

### Configuration

```yaml
status_flows:
  feature_flows:
    allow_backward: true  # Enable backward movement (default: false)
```

### When to Use

**Enable backward movement** (`allow_backward: true`):
- Code review finds issues → pending-review → in-development
- Tests fail → testing → in-development
- Requirements change during development

**Disable backward movement** (`allow_backward: false`):
- Strict waterfall process
- Audit/compliance requirements
- Prevent accidental status regression

### Example Backward Transition

```javascript
// Feature in testing, tests fail
feature.status = "testing"

// With allow_backward: true
"Use Status Progression Skill to move feature back to in-development"
// Allowed: testing → in-development

// With allow_backward: false
// Error: "Backward movement not allowed"
// Must cancel feature or fix in testing phase
```

---

## Emergency Transitions

Special transitions allowed from ANY status, regardless of flow configuration.

### Available Emergency Statuses

**blocked** - Cannot proceed due to external dependency
**on-hold** - Paused, not cancelled
**cancelled** - Work abandoned
**archived** - Completed work, no longer relevant

### Configuration

```yaml
status_flows:
  feature_flows:
    allow_emergency_transitions: true  # Default: true
```

### Example Usage

```javascript
// Feature blocked by external API dependency
"Use Status Progression Skill to mark feature as blocked.
Context: Waiting for external API access from partner team"

// Feature on-hold due to business priority shift
"Use Status Progression Skill to put feature on-hold.
Context: Deprioritized for Q2, will resume in Q3"
```

---

## Quality Gates

Hook-based validation before status transitions.

### Configuration

```yaml
quality_gates:
  enabled: true

  feature_gates:
    testing:
      hook: "test_runner"
      required: true

    validating:
      hook: "code_coverage"
      required: false  # Warning only
```

### Gate Behavior

**required: true** - Blocks status transition if gate fails
**required: false** - Shows warning but allows progression

### Example Gate Failure

```
Error: Cannot complete feature - testing gate failed
Hook: test_runner
Status: FAILED
Output: 3 test failures in authentication module

Actions:
1. Fix test failures
2. Re-run hook
3. Retry status transition
```

---

## Complete Configuration Example

```yaml
# .taskorchestrator/config.yaml

status_flows:
  feature_flows:
    # Feature status flows
    default_flow:
      - draft
      - planning
      - in-development
      - testing
      - validating
      - completed

    rapid_prototype_flow:
      - draft
      - in-development
      - completed

    with_review_flow:
      - draft
      - planning
      - in-development
      - testing
      - validating
      - pending-review
      - completed

    # Tag-based flow routing
    flow_mappings:
      - tags: ["prototype", "spike", "poc"]
        flow_name: rapid_prototype_flow

      - tags: ["security", "architecture", "breaking-change"]
        flow_name: with_review_flow

      - tags: []  # Default
        flow_name: default_flow

    # Behavior settings
    allow_backward: true
    allow_emergency_transitions: true

# Prerequisite validation
status_validation:
  validate_prerequisites: true  # Enforce prerequisite checks

# Quality gates (optional)
quality_gates:
  enabled: false  # Disable hooks for now
```

---

## Migration from Hardcoded Flows

**Before (v1.x - Hardcoded):**
```kotlin
// Code assumed specific status names
if (allTasksComplete) {
  feature.status = "testing"  // Breaks with custom configs
}
```

**After (v2.0 - Config-Driven):**
```javascript
// Skills detect events, delegate to Status Progression Skill
if (allTasksComplete) {
  "Use Status Progression Skill to progress feature status.
  Context: All tasks complete"

  // Status Progression Skill:
  // 1. Calls get_next_status tool
  // 2. Tool reads user's config.yaml
  // 3. Matches feature tags to flow_mappings
  // 4. Recommends next status from matched flow
  // Result: Could be "testing", "completed", "validating", etc.
}
```

---

## Custom Flow Creation

### Step 1: Define Flow Sequence

```yaml
status_flows:
  feature_flows:
    my_custom_flow:
      - draft
      - design
      - implementation
      - qa
      - production
```

### Step 2: Add Flow Mapping

```yaml
status_flows:
  feature_flows:
    flow_mappings:
      - tags: ["custom-process"]
        flow_name: my_custom_flow
```

### Step 3: Tag Features

```javascript
manage_container(
  operation="create",
  containerType="feature",
  tags="backend,custom-process",
  name="New Feature"
)
```

### Step 4: Use Status Progression Skill

```javascript
"Use Status Progression Skill to progress feature to next status"
// Skill calls get_next_status
// Tool finds flow via tags → my_custom_flow
// Recommends next status from custom flow
```

---

## Troubleshooting Configuration

### Flow Not Activating

**Symptom:** Feature uses default_flow instead of custom flow

**Check:**
1. Feature has correct tags: `query_container(operation="get", containerType="feature", id="...")`
2. Tags match flow_mappings: Compare feature.tags to config.yaml mappings
3. Mapping order: Mappings checked top-to-bottom, first match wins
4. Empty tags mapping: Should always be last (catches all)

**Fix:**
```javascript
// Add missing tag
manage_container(
  operation="update",
  containerType="feature",
  id="...",
  tags="existing-tag,prototype"  // Add flow routing tag
)
```

### Validation Blocking Progression

**Symptom:** Status change rejected with prerequisite error

**Check:**
1. Task counts: `query_container(operation="overview", containerType="feature", id="...")`
2. Review taskCounts.byStatus for incomplete tasks
3. Check config: Is `validate_prerequisites: true`?

**Fix Option A - Resolve Prerequisites:**
```javascript
// Complete or cancel remaining tasks
"Use Status Progression Skill to complete task X"
```

**Fix Option B - Bypass Validation (Development Only):**
```yaml
# .taskorchestrator/config.yaml
status_validation:
  validate_prerequisites: false  # Temporary bypass
```

### Backward Movement Blocked

**Symptom:** Cannot move feature from testing → in-development

**Check:**
```yaml
status_flows:
  feature_flows:
    allow_backward: false  # ← This blocks backward movement
```

**Fix:**
```yaml
status_flows:
  feature_flows:
    allow_backward: true
```

---

## Best Practices

### 1. Start with default_flow
Most features should use the standard flow. Only add custom flows when needed.

### 2. Use flow routing tags sparingly
Too many flow routing tags creates confusion. Stick to:
- prototype/spike/poc → rapid_prototype_flow
- security/architecture/breaking-change → with_review_flow
- Everything else → default_flow

### 3. Keep validation enabled in production
```yaml
status_validation:
  validate_prerequisites: true  # Always in production
```

Only disable for local development/testing.

### 4. Document custom flows
If you create custom flows, document them in your project's README:
```markdown
## Custom Workflows

### design-first-flow
Used for features requiring upfront UX design approval.
Tags: ["ux-design", "design-first"]
Flow: draft → design → design-review → implementation → testing → completed
```

### 5. Test flow transitions
Before deploying custom config:
1. Create test feature with appropriate tags
2. Progress through each status
3. Verify prerequisites enforced correctly
4. Test backward movement (if enabled)
5. Test emergency transitions

---

## Related Documentation

- **Event-Driven Pattern**: See `docs/event-driven-status-progression-pattern.md`
- **Status Progression Skill**: See `.claude/skills/status-progression/SKILL.md`
- **Troubleshooting**: See `troubleshooting.md`
- **Examples**: See `examples.md`

# Routing Validation

**Purpose**: Detect violations of mandatory Skill usage patterns (Skills vs Direct tools vs Subagents).

**When**: After ANY workflow completes

**Applies To**: All Skills and Subagents

**Token Cost**: ~300-500 tokens

## Critical Routing Rules

### Rule 1: Status Changes MUST Use Status Progression Skill

**Violation**: Calling `manage_container(operation="setStatus")` directly

**Expected**: Use Status Progression Skill for ALL status changes

**Why Critical**: Prerequisite validation (summary length, dependencies, task completion) required

**Detection**:
```javascript
if (statusChanged && !usedStatusProgressionSkill) {
  return {
    severity: "CRITICAL",
    violation: "Status change bypassed mandatory Status Progression Skill",
    impact: "Prerequisites may not have been validated",
    expected: "Use Status Progression Skill for status changes"
  }
}
```

### Rule 2: Feature Creation MUST Use Feature Orchestration Skill

**Violation**: Calling `manage_container(operation="create", containerType="feature")` directly

**Expected**: Use Feature Orchestration Skill for feature creation

**Why Critical**: Complexity assessment and template discovery required

**Detection**:
```javascript
if (featureCreated && !usedFeatureOrchestrationSkill) {
  return {
    severity: "CRITICAL",
    violation: "Feature creation bypassed mandatory Feature Orchestration Skill",
    impact: "Complexity not assessed, templates may be missed",
    expected: "Use Feature Orchestration Skill for feature creation"
  }
}
```

### Rule 3: Task Execution SHOULD Use Task Orchestration Skill

**Violation**: Launching specialists directly without checking dependencies/parallel opportunities

**Expected**: Use Task Orchestration Skill for batch execution

**Why Important**: Dependency analysis and parallelization optimization

**Detection**:
```javascript
if (multipleTasksLaunched && !usedTaskOrchestrationSkill) {
  return {
    severity: "WARN",
    violation: "Multiple tasks launched without Task Orchestration Skill",
    impact: "May miss parallel opportunities or dependency conflicts",
    expected: "Use Task Orchestration Skill for batch execution"
  }
}
```

### Rule 4: Implementation Specialists MUST Use Status Progression for Completion

**Violation**: Specialist calls `manage_container(operation="setStatus")` directly

**Expected**: Specialist uses Status Progression Skill to mark complete

**Why Critical**: Prerequisite validation (summary, Files Changed section, tests)

**Detection**:
```javascript
if (implementationSpecialist && taskCompleted && !usedStatusProgressionSkill) {
  return {
    severity: "CRITICAL",
    violation: `${specialistName} marked task complete without Status Progression Skill`,
    impact: "Summary/Files Changed/test validation may have been skipped",
    expected: "Use Status Progression Skill in Step 8 of specialist lifecycle"
  }
}
```

## Validation Workflow

### Step 1: Identify Workflow Type

```javascript
workflowType = identifyWorkflow(entityType, userInput, output)
// Returns: "status-change", "feature-creation", "task-execution", "implementation"
```

### Step 2: Check Mandatory Skill Usage

```javascript
mandatorySkills = {
  "status-change": "status-progression",
  "feature-creation": "feature-orchestration",
  "task-execution": "task-orchestration",  // WARN level
  "feature-completion": "feature-orchestration"
}

requiredSkill = mandatorySkills[workflowType]
```

### Step 3: Detect Skill Bypass

```javascript
// Check if required Skill was used
skillUsed = checkSkillUsage(output, requiredSkill)

if (!skillUsed && requiredSkill) {
  severity = (workflowType == "task-execution") ? "WARN" : "CRITICAL"

  violation = {
    workflowType: workflowType,
    requiredSkill: requiredSkill,
    actualApproach: detectActualApproach(output),
    severity: severity,
    impact: describeImpact(requiredSkill)
  }
}
```

### Step 4: Verify Specialist Lifecycle Adherence

For Implementation Specialists (Backend, Frontend, Database, Test, Technical Writer):

```javascript
if (category == "SUBAGENT" && isImplementationSpecialist(entityType)) {
  lifecycle = {
    step8Expected: "Use Status Progression Skill to mark complete",
    step8Actual: detectStep8Approach(output),
    compliant: false
  }

  // Check if Status Progression Skill was mentioned
  if (mentions(output, "Status Progression") || mentions(output, "status-progression")) {
    lifecycle.compliant = true
  } else if (taskStatusChanged) {
    violation = {
      severity: "CRITICAL",
      specialist: entityType,
      step: "Step 8",
      issue: "Marked task complete without Status Progression Skill",
      impact: "Prerequisite validation (summary, Files Changed, tests) may be incomplete",
      expected: "Use Status Progression Skill for completion"
    }
  }
}
```

## Violation Severity Levels

### CRITICAL (Immediate Alert)
- Status change without Status Progression Skill
- Feature creation without Feature Orchestration Skill
- Implementation specialist completion without Status Progression Skill
- **Action**: Report immediately, add to TodoWrite, suggest correction

### WARN (Log for Review)
- Task execution without Task Orchestration Skill (multiple tasks)
- Efficiency opportunities missed (parallelization)
- **Action**: Log to TodoWrite, mention in end-of-session summary

### INFO (Observation)
- Workflow variations that are acceptable
- Optimization suggestions
- **Action**: Track for pattern analysis only

## Report Template

```markdown
## 🚨 Routing Violation Detected

**Severity**: CRITICAL

**Workflow Type**: [status-change / feature-creation / etc.]

**Violation**: [Description]

**Impact**: [What this affects]

**Expected Approach**: Use [Skill Name] Skill

**Actual Approach**: Direct tool call / Subagent / etc.

**Recommendation**: [How to correct]

---

**Added to TodoWrite**:
- Review [Workflow]: [Issue description]

**Decision Required**: Should orchestrator retry using correct Skill?
```

## Common Violations

### Violation 1: Direct Status Change

```javascript
User: "Mark task T1 complete"
Orchestrator: manage_container(operation="setStatus", status="completed")  // ❌

Expected: Use Status Progression Skill
Reason: Summary validation, dependency checks required
```

### Violation 2: Direct Feature Creation

```javascript
User: "Create user authentication feature"
Orchestrator: manage_container(operation="create", containerType="feature")  // ❌

Expected: Use Feature Orchestration Skill
Reason: Complexity assessment, template discovery required
```

### Violation 3: Specialist Bypass

```javascript
Backend Engineer: manage_container(operation="setStatus", status="completed")  // ❌

Expected: Use Status Progression Skill in Step 8
Reason: Summary, Files Changed, test validation required
```

## Integration with Post-Execution Review

```javascript
// ALWAYS run routing validation in post-execution
Read "routing-validation.md"

violations = detectRoutingViolations(
  workflowType,
  entityType,
  entityOutput,
  context
)

if (violations.length > 0) {
  for violation in violations:
    if (violation.severity == "CRITICAL") {
      // Report immediately
      alertUser(violation)
      addToTodoWrite(violation)
    } else {
      // Log for summary
      logViolation(violation)
    }
}
```

## Continuous Improvement

### Pattern Tracking
If same violation occurs 2+ times in session:
- Update orchestrator instructions
- Add validation checkpoint in pre-execution
- Suggest systemic improvement

### Definition Updates
Recurring violations indicate documentation gaps:
- Update Skill definitions with clearer trigger patterns
- Add examples of correct vs incorrect usage
- Update CLAUDE.md Decision Gates section

## When to Report

- **CRITICAL violations**: Report immediately (don't wait for post-execution)
- **WARN violations**: Include in post-execution summary
- **INFO observations**: Track for pattern analysis only
